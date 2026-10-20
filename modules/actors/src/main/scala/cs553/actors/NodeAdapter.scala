package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.ids.NodeId
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.Protocol
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Step
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.TraceEvent
import cs553.core.world.Buffered
import cs553.core.world.Clock
import cs553.core.world.Effects

/** One process of the mediated engine, as an actor.
  *
  * The adapter has no algorithm logic (coding rule 20). For each `Deliver` or `Act`, it calls the
  * pure `Protocol`, gives the result to `Effects.of` (the same function that the Simulator uses),
  * puts each envelope into its outgoing channel with `!` and reports `Handled` to the Controller.
  * The node holds only the refs of its outgoing channels, never the ref of a peer node (coding rule
  * 21). The node owns its Lamport clock and the next sequence number of each outgoing channel.
  */
object NodeAdapter {

  /** All messages of a node. */
  sealed trait Command[M, +A]

  /** A channel gives the node this envelope for the global step `step`. */
  final case class Deliver[M](item: Buffered[M], step: Int) extends Command[M, Nothing]

  /** The Controller tells the node to do `action` in the global step `step`. */
  final case class Act[M, A](action: A, step: Int) extends Command[M, A]

  /** The guardian gives the node the refs of its outgoing channels, after it spawned them. */
  private[actors] final case class Wire[M](out: Map[NodeId, ActorRef[Channel.In[M]]])
      extends Command[M, Nothing]

  /** What a node tells the Controller. */
  enum Report[+S, +M, +A, +O] {

    /** The node has its channel refs. `actions` are its spontaneous actions now. */
    case Ready(node: NodeId, actions: List[A])

    /** The node handled one step.
      *
      * `events` are the trace events of the step from `Effects.of`: `Stepped` (with the cause, the
      * new state and the sent ids), the `Sent` events and `Decided`, or one `Rejected`. `sent` are
      * the ids that the node put into its channels, and `actions` are its actions after the step.
      */
    case Handled(
        node: NodeId,
        events: List[TraceEvent[S, M, A, O]],
        sent: List[EnvelopeId],
        actions: List[A]
    )
  }

  /** A node with the initial state `initial`. It waits for `Wire`, and then reports `Ready`. */
  def apply[S, M, A, O](
      p: Protocol[S, M, A, O],
      ctx: NodeCtx,
      initial: S,
      reportTo: ActorRef[Report[S, M, A, O]]
  ): Behavior[Command[M, A]] = {
    Behaviors.receiveMessage {
      case Wire(out) =>
        reportTo ! Report.Ready(ctx.self, p.actions(ctx, initial))
        running(Setup(p, ctx, out, reportTo), initial, Clock.zero, Map.empty)
      case Deliver(_, _) | Act(_, _) => Behaviors.unhandled
    }
  }

  /** The values that do not change during a run. */
  private final case class Setup[S, M, A, O](
      p: Protocol[S, M, A, O],
      ctx: NodeCtx,
      out: Map[NodeId, ActorRef[Channel.In[M]]],
      reportTo: ActorRef[Report[S, M, A, O]]
  )

  private def running[S, M, A, O](
      setup: Setup[S, M, A, O],
      state: S,
      clock: Clock,
      nextSeq: Map[Edge, Int]
  ): Behavior[Command[M, A]] = {
    val ctx = setup.ctx
    Behaviors.receiveMessage {
      case Deliver(item, step) =>
        val id = item.env.id
        val result = setup.p.onMessage(ctx, state, id.from, item.env.msg)
        handle(setup, state, nextSeq, step, Choice.Deliver(id), clock.receive(item.lamport), result)
      case Act(action, step) =>
        val result = setup.p.onAction(ctx, state, action)
        handle(setup, state, nextSeq, step, Choice.Fire(ctx.self, action), clock.tick, result)
      case Wire(_) => Behaviors.unhandled
    }
  }

  /** Performs the effects of one handler result and reports them. A protocol error becomes a
    * `Rejected` event in `Effects.of`: the state stays, and the actor does not crash.
    */
  private def handle[S, M, A, O](
      setup: Setup[S, M, A, O],
      state: S,
      nextSeq: Map[Edge, Int],
      step: Int,
      cause: Choice[A],
      clock: Clock,
      result: Either[ProtocolError, Step[S, M, O]]
  ): Behavior[Command[M, A]] = {
    val ctx = setup.ctx
    val effects = Effects.of(step, ctx, cause, clock, e => nextSeq.getOrElse(e, 0), result)
    effects.sent.foreach(item => setup.out.get(item.env.id.to).foreach(_ ! Channel.Put(item)))
    val seqs = effects.sent.foldLeft(nextSeq) { (acc, item) =>
      acc.updated(item.env.id.channel, item.env.id.seq + 1)
    }
    val after = effects.state.getOrElse(state)
    setup.reportTo ! Report.Handled(
      ctx.self,
      effects.events,
      effects.sent.map(_.env.id),
      setup.p.actions(ctx, after)
    )
    running(setup, after, clock, seqs)
  }
}
