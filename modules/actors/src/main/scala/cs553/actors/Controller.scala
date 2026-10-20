package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import cs553.core.world.Simulator
import cs553.core.world.World

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

/** The observer of one mediated run. It picks each step and records the trace.
  *
  * The Controller knows the envelope ids in each channel (from `Enqueued`) and the actions of each
  * node (from `Ready` and `Handled`). It does not know the node states. For each step, it:
  *
  *   1. gets the legal choices from `World.enabledOf` and the stop decision from
  *      `Simulator.stopWhen`, the same rules that the Simulator uses;
  *   1. picks one choice with the policy of the spec (`Simulator.seededChoice` or the next `Script`
  *      choice);
  *   1. sends `Release` to a channel or `Act` to a node;
  *   1. waits for the `Handled` report and one `Enqueued` for each sent id.
  *
  * Akka does not order messages from two different senders, so an `Enqueued` can come before or
  * after `Handled`. Only one step runs at a time, so the choices alone define the trace. The
  * Controller is internal to the engine: only `RunGuardian` spawns it.
  */
private[actors] object Controller {

  /** All messages that the Controller gets: the reports of the channels and the nodes, and `Wired`
    * from the guardian.
    */
  type Command[S, M, A, O] = Channel.Report | NodeAdapter.Report[S, M, A, O] | Wired[M, A]

  /** The guardian gives the Controller the refs of the run, after it spawned them. */
  private[actors] final case class Wired[M, A](
      nodes: Map[NodeId, ActorRef[NodeAdapter.Command[M, A]]],
      channels: Map[Edge, ActorRef[Channel.Command[M]]]
  )

  /** A Controller for `spec` that waits for `Wired` and for `Ready` from each of `nodes`.
    *
    * `order` is the resolved channel order, and `started` is the first trace event. At the end, the
    * Controller sends `Finished` or `Failed` to `replyTo` and stops.
    */
  def apply[S, M, A, O](
      spec: RunSpec[A],
      order: ChannelOrder,
      nodes: Set[NodeId],
      edges: Set[Edge],
      started: TraceEvent[S, M, A, O],
      replyTo: ActorRef[RunGuardian.Result[S, M, A, O]]
  ): Behavior[Command[S, M, A, O]] = {
    val script = spec.policy match {
      case Policy.Seeded(_)       => Nil
      case Policy.Script(choices) => choices
    }
    val state = State[S, M, A, O](
      spec,
      order,
      Map.empty,
      Map.empty,
      SortedMap.from(edges.toList.map(_ -> Vector.empty[EnvelopeId])),
      SortedMap.empty,
      script,
      0,
      Vector(started),
      replyTo
    )
    wiring(state, nodes, wired = false)
  }

  /** The state of a run between two steps. */
  private final case class State[S, M, A, O](
      spec: RunSpec[A],
      order: ChannelOrder,
      nodes: Map[NodeId, ActorRef[NodeAdapter.Command[M, A]]],
      channels: Map[Edge, ActorRef[Channel.Command[M]]],
      queues: SortedMap[Edge, Vector[EnvelopeId]],
      actions: SortedMap[NodeId, List[A]],
      script: List[Choice[A]],
      step: Int,
      trace: Vector[TraceEvent[S, M, A, O]],
      replyTo: ActorRef[RunGuardian.Result[S, M, A, O]]
  ) {
    def enabled: List[Choice[A]] = World.enabledOf(order, queues.values.toList, actions.toList)

    def enqueue(id: EnvelopeId): State[S, M, A, O] = {
      val queue = queues.getOrElse(id.channel, Vector.empty)
      copy(queues = queues.updated(id.channel, (queue :+ id).sortBy(_.seq)))
    }

    def dequeue(id: EnvelopeId): State[S, M, A, O] = {
      val queue = queues.getOrElse(id.channel, Vector.empty)
      copy(queues = queues.updated(id.channel, queue.filterNot(_ == id)))
    }

    def buffers(id: EnvelopeId): Boolean = queues.get(id.channel).exists(_.contains(id))
  }

  /** The reports of the step that runs now. `node` is the node that must report `Handled`: the
    * target of the `Act`, or the receiver of the released envelope.
    */
  private final case class Pending[S, M, A, O](
      node: NodeId,
      handled: Option[(List[TraceEvent[S, M, A, O]], List[EnvelopeId])],
      enqueued: Set[EnvelopeId]
  ) {
    def complete: Boolean = handled.exists { case (_, sent) => sent.forall(enqueued.contains) }

    /** The enqueued ids that the node did not send in this step. */
    def unexpected: Set[EnvelopeId] = {
      handled.fold(Set.empty[EnvelopeId]) { case (_, sent) => enqueued -- sent }
    }
  }

  /** Waits for `Wired` and for `Ready` from each node in `waiting`. */
  private def wiring[S, M, A, O](
      state: State[S, M, A, O],
      waiting: Set[NodeId],
      wired: Boolean
  ): Behavior[Command[S, M, A, O]] = {
    if wired && waiting.isEmpty then { proceed(state) }
    else {
      Behaviors.receiveMessage {
        case Wired(nodes, channels) =>
          wiring(state.copy(nodes = nodes, channels = channels), waiting, wired = true)
        case NodeAdapter.Report.Ready(node, actions) =>
          wiring(state.copy(actions = state.actions.updated(node, actions)), waiting - node, wired)
        case NodeAdapter.Report.Handled(node, _, _, _) =>
          fail(state, RunGuardian.EngineError.UnexpectedReport(s"Handled from node $node"))
        case NodeAdapter.Report.Unwired(node, ids) => unwired(state, node, ids)
        case Channel.Report.Enqueued(id)           =>
          fail(state, RunGuardian.EngineError.UnexpectedReport(s"Enqueued($id)"))
        case Channel.Report.Refused(id, reason) =>
          fail(state, RunGuardian.EngineError.Refused(id, reason))
      }
    }
  }

  /** Stops the run, or starts the next step. A `Drop` needs no report, so the loop continues. */
  @tailrec
  private def proceed[S, M, A, O](state: State[S, M, A, O]): Behavior[Command[S, M, A, O]] = {
    val enabled = state.enabled
    Simulator.stopWhen(state.spec, state.step, enabled, state.trace.lastOption) match {
      case Some(outcome) => finish(state, outcome)
      case None          =>
        pick(state, enabled) match {
          case None                    => finish(state, Outcome.ScriptExhausted)
          case Some((choice, current)) =>
            val step = state.step + 1
            val bad = RunGuardian.EngineError.BadChoice(step, choice)
            choice match {
              case Choice.Deliver(id) =>
                if !enabled.contains(choice) then { fail(current, bad) }
                else {
                  send(current, current.channels.get(id.channel), Channel.Release[M](id, step)) {
                    stepping(current.dequeue(id), Pending(id.to, None, Set.empty))
                  }
                }
              case Choice.Fire(node, action) =>
                if !enabled.contains(choice) then { fail(current, bad) }
                else {
                  send(current, current.nodes.get(node), NodeAdapter.Act[M, A](action, step)) {
                    stepping(current, Pending(node, None, Set.empty))
                  }
                }
              case Choice.Drop(id) =>
                current.channels.get(id.channel).filter(_ => current.buffers(id)) match {
                  case None          => fail(current, bad)
                  case Some(channel) =>
                    // A Drop needs no report. Akka keeps the order of the messages from one
                    // sender to one receiver, so the channel removes the envelope before it gets
                    // the next Release from the Controller. If the channel refuses the Drop, its
                    // Refused report stops the run in the next step.
                    channel ! Channel.Drop[M](id)
                    val dropped = World.droppedAt(step, id)
                    proceed(current.dequeue(id).copy(step = step, trace = current.trace :+ dropped))
                }
            }
        }
    }
  }

  /** The next choice of the policy, and the state with the rest of the script. */
  private def pick[S, M, A, O](
      state: State[S, M, A, O],
      enabled: List[Choice[A]]
  ): Option[(Choice[A], State[S, M, A, O])] = {
    state.spec.policy match {
      case Policy.Seeded(seed) =>
        Simulator.seededChoice(seed, state.step, enabled).map(c => (c, state))
      case Policy.Script(_) =>
        state.script match {
          case head :: tail => Some((head, state.copy(script = tail)))
          case Nil          => None
        }
    }
  }

  /** Sends `msg` to `target` and continues with `next`. A missing ref stops the run with an error.
    */
  private def send[S, M, A, O, T](
      state: State[S, M, A, O],
      target: Option[ActorRef[T]],
      msg: T
  )(next: => Behavior[Command[S, M, A, O]]): Behavior[Command[S, M, A, O]] = {
    target match {
      case None      => fail(state, RunGuardian.EngineError.UnexpectedReport(s"no actor for $msg"))
      case Some(ref) =>
        ref ! msg
        next
    }
  }

  /** Waits for the reports of one step: `Handled` and one `Enqueued` for each sent id. */
  private def stepping[S, M, A, O](
      state: State[S, M, A, O],
      pending: Pending[S, M, A, O]
  ): Behavior[Command[S, M, A, O]] = {
    if pending.complete && pending.unexpected.nonEmpty then {
      val ids = pending.unexpected.toList.sorted.mkString(", ")
      fail(state, RunGuardian.EngineError.UnexpectedReport(s"Enqueued of ids not sent: $ids"))
    } else if pending.complete then {
      val events = pending.handled.fold(List.empty[TraceEvent[S, M, A, O]])(_._1)
      proceed(state.copy(step = state.step + 1, trace = state.trace ++ events))
    } else {
      Behaviors.receiveMessage {
        case Channel.Report.Enqueued(id) =>
          stepping(state.enqueue(id), pending.copy(enqueued = pending.enqueued + id))
        case NodeAdapter.Report.Handled(node, _, _, _) if node != pending.node =>
          val detail = s"Handled from node $node, not from node ${pending.node}"
          fail(state, RunGuardian.EngineError.UnexpectedReport(detail))
        case NodeAdapter.Report.Handled(node, events, sent, actions) =>
          pending.handled match {
            case Some(_) =>
              fail(state, RunGuardian.EngineError.UnexpectedReport(s"a second Handled from $node"))
            case None =>
              stepping(
                state.copy(actions = state.actions.updated(node, actions)),
                pending.copy(handled = Some((events, sent)))
              )
          }
        case Channel.Report.Refused(id, reason) =>
          fail(state, RunGuardian.EngineError.Refused(id, reason))
        case NodeAdapter.Report.Unwired(node, ids) => unwired(state, node, ids)
        case NodeAdapter.Report.Ready(node, _)     =>
          fail(state, RunGuardian.EngineError.UnexpectedReport(s"Ready from node $node"))
        case Wired(_, _) => fail(state, RunGuardian.EngineError.UnexpectedReport("a second Wired"))
      }
    }
  }

  private def unwired[S, M, A, O](
      state: State[S, M, A, O],
      node: NodeId,
      ids: List[EnvelopeId]
  ): Behavior[Command[S, M, A, O]] = {
    fail(
      state,
      RunGuardian.EngineError.Unwired(s"node $node has no channel for ${ids.mkString(", ")}")
    )
  }

  private def finish[S, M, A, O](
      state: State[S, M, A, O],
      outcome: Outcome
  ): Behavior[Command[S, M, A, O]] = {
    val trace = state.trace :+ Simulator.finishedAt(state.step, outcome)
    state.replyTo ! RunGuardian.Finished(Run(trace))
    Behaviors.stopped
  }

  private def fail[S, M, A, O](
      state: State[S, M, A, O],
      error: RunGuardian.EngineError[A]
  ): Behavior[Command[S, M, A, O]] = {
    state.replyTo ! RunGuardian.Failed(error)
    Behaviors.stopped
  }
}
