package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.ids.NodeId
import cs553.core.trace.Outcome
import cs553.core.trace.Run
import cs553.core.trace.Stamp
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Simulator

import scala.concurrent.duration.FiniteDuration

/** The collector of the free-run engine. It is not a node: it only gets reports and builds the
  * trace.
  *
  * The nodes report in the order that the dispatcher gives, so the arrival order is not the order
  * of the trace. The Recorder sorts the steps by Lamport time, then by node, then by the local
  * counter of the node. A send always has a lower Lamport time than its receive, so each `Sent`
  * comes before its delivery, and the steps of one node keep their local order.
  *
  * Nobody sees the whole system. So the Recorder ends the run when it sees the first decision (rule
  * `AtFirstDecision`) or when it has `stepLimit` reports. Then it keeps the steps up to the cut.
  * The steps that it has are closed under "happened before", so the kept steps are too. The
  * `silence` timeout is only a failure guard.
  */
object Recorder {

  /** All messages of the Recorder. */
  sealed trait Command[S, M, A, O]

  /** The report of one node step: the events of `Effects.of`, with the local stamp. */
  final case class Handled[S, M, A, O](node: NodeId, events: List[TraceEvent[S, M, A, O]])
      extends Command[S, M, A, O]

  private final case class Silent[S, M, A, O]() extends Command[S, M, A, O]

  /** The events of one node step, with the data that sorts them. */
  private[actors] final case class Group[S, M, A, O](
      node: NodeId,
      local: Int,
      lamport: Int,
      decided: Boolean,
      events: List[TraceEvent[S, M, A, O]]
  )

  private def stampOf(e: TraceEvent[Any, Any, Any, Any]): Option[Stamp] = e match {
    case TraceEvent.Started(_, _, _)           => None
    case TraceEvent.Sent(at, _)                => Some(at)
    case TraceEvent.Stepped(at, _, _, _, _, _) => Some(at)
    case TraceEvent.Rejected(at, _, _, _)      => Some(at)
    case TraceEvent.Dropped(at, _)             => Some(at)
    case TraceEvent.Decided(at, _, _)          => Some(at)
    case TraceEvent.Finished(at, _)            => Some(at)
  }

  private def withStep[S, M, A, O](
      e: TraceEvent[S, M, A, O],
      step: Int
  ): TraceEvent[S, M, A, O] = e match {
    case TraceEvent.Started(_, _, _) => e
    case TraceEvent.Sent(at, env)    => TraceEvent.Sent(at.copy(step = step), env)
    case TraceEvent.Stepped(at, node, cause, rule, after, sent) =>
      TraceEvent.Stepped(at.copy(step = step), node, cause, rule, after, sent)
    case TraceEvent.Rejected(at, node, cause, error) =>
      TraceEvent.Rejected(at.copy(step = step), node, cause, error)
    case TraceEvent.Dropped(at, id)        => TraceEvent.Dropped(at.copy(step = step), id)
    case TraceEvent.Decided(at, node, out) => TraceEvent.Decided(at.copy(step = step), node, out)
    case TraceEvent.Finished(at, outcome)  => TraceEvent.Finished(at.copy(step = step), outcome)
  }

  private def isDecided(e: TraceEvent[Any, Any, Any, Any]): Boolean = e match {
    case TraceEvent.Decided(_, _, _) => true
    case TraceEvent.Started(_, _, _) | TraceEvent.Sent(_, _) |
        TraceEvent.Stepped(_, _, _, _, _, _) | TraceEvent.Rejected(_, _, _, _) |
        TraceEvent.Dropped(_, _) | TraceEvent.Finished(_, _) =>
      false
  }

  def apply[S, M, A, O](
      started: TraceEvent[S, M, A, O],
      stop: StopRule,
      stepLimit: Int,
      silence: FiniteDuration,
      replyTo: ActorRef[RunGuardian.Result[S, M, A, O]]
  ): Behavior[Handled[S, M, A, O]] = {
    Behaviors
      .setup[Command[S, M, A, O]] { context =>
        context.setReceiveTimeout(silence, Silent())
        collecting(started, stop, stepLimit, replyTo, Vector.empty)
      }
      .narrow[Handled[S, M, A, O]]
  }

  private def collecting[S, M, A, O](
      started: TraceEvent[S, M, A, O],
      stop: StopRule,
      stepLimit: Int,
      replyTo: ActorRef[RunGuardian.Result[S, M, A, O]],
      groups: Vector[Group[S, M, A, O]]
  ): Behavior[Command[S, M, A, O]] = {
    Behaviors.receiveMessage {
      case Silent() =>
        replyTo ! RunGuardian.Failed(
          RunGuardian.EngineError.UnexpectedReport("no node reported and no node decided")
        )
        Behaviors.stopped
      case Handled(node, events) =>
        val stamp = events.headOption.flatMap(stampOf)
        val group = Group(
          node,
          stamp.fold(0)(_.local),
          stamp.fold(0)(_.lamport),
          events.exists(isDecided),
          events
        )
        val all = groups :+ group
        val decides = stop match {
          case StopRule.AtFirstDecision => group.decided
          case StopRule.AtQuiescence    => false
        }
        if decides || all.size >= stepLimit then {
          replyTo ! RunGuardian.Finished(build(started, stop, stepLimit, all))
          Behaviors.stopped
        } else { collecting(started, stop, stepLimit, replyTo, all) }
    }
  }

  /** The trace of `groups`: sorted by time, cut at the first decision or at the step limit, and
    * numbered from 1 in that order.
    */
  private[actors] def build[S, M, A, O](
      started: TraceEvent[S, M, A, O],
      stop: StopRule,
      stepLimit: Int,
      groups: Vector[Group[S, M, A, O]]
  ): Run[S, M, A, O] = {
    val sorted = groups.sortBy(g => (g.lamport, g.node, g.local))
    val decidedAt = stop match {
      case StopRule.AtFirstDecision => sorted.indexWhere(_.decided)
      case StopRule.AtQuiescence    => -1
    }
    val (kept, outcome) =
      if decidedAt >= 0 && decidedAt < stepLimit then {
        (sorted.take(decidedAt + 1), Outcome.AlgorithmDecided)
      } else if sorted.size >= stepLimit then { (sorted.take(stepLimit), Outcome.StepLimit) }
      else { (sorted, Outcome.Quiescent) }
    val steps = kept.zipWithIndex.flatMap { case (g, i) => g.events.map(withStep(_, i + 1)) }
    val finished = Simulator.finishedAt(kept.size, outcome)
    Run((started +: steps) :+ finished)
  }
}
