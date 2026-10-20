package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.ids.NodeId
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.Protocol
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Step
import cs553.core.rng.Rng
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.Envelope
import cs553.core.world.Clock
import cs553.core.world.Effects

/** One process of the free-run engine, as an actor.
  *
  * The node holds the refs of its peers and sends to them directly with `!`. No channel actor and
  * no Controller sit between two nodes. The dispatcher of Akka picks the order of the messages, and
  * only the order of each pair of nodes holds (coding rule 21 applies to the mediated engine only).
  *
  * The node has no algorithm logic. It calls the pure `Protocol` and gives the result to
  * `Effects.of`, as the mediated engine does. It owns its clock (a node-local counter and a Lamport
  * clock) and the next sequence number of each outgoing channel. It fires its own spontaneous
  * actions: after `Start`, and again after each step, it sends itself one `Next` while it has an
  * action. No global scheduler fires an action.
  */
object FreeNode {

  /** All messages of a node. */
  sealed trait Command[M]

  /** A peer gives the node an envelope and the Lamport time of the send. */
  final case class Deliver[M](env: Envelope[M], lamport: Int) extends Command[M]

  /** The guardian gives the node the refs of all its out-neighbours. */
  private[actors] final case class Wire[M](peers: Map[NodeId, ActorRef[Command[M]]])
      extends Command[M]

  /** The guardian tells the node that all nodes have their refs. The node can send now. */
  private[actors] final case class Start[M]() extends Command[M]

  /** The node tells itself to fire one spontaneous action. */
  private final case class Next[M]() extends Command[M]

  private final case class Setup[S, M, A, O](
      p: Protocol[S, M, A, O],
      ctx: NodeCtx,
      peers: Map[NodeId, ActorRef[Command[M]]],
      self: ActorRef[Command[M]],
      reportTo: ActorRef[Recorder.Handled[S, M, A, O]]
  )

  private final case class Local[S](
      state: S,
      clock: Clock,
      nextSeq: Map[Edge, Int],
      rng: Rng,
      queued: Boolean
  )

  /** A node with the initial state `initial`. `seed` makes the choice among its own actions. */
  def apply[S, M, A, O](
      p: Protocol[S, M, A, O],
      ctx: NodeCtx,
      initial: S,
      seed: Seed,
      reportTo: ActorRef[Recorder.Handled[S, M, A, O]]
  ): Behavior[Command[M]] = {
    Behaviors.setup[Command[M]] { context =>
      Behaviors.receiveMessage[Command[M]] {
        case Wire(peers) =>
          val setup = Setup(p, ctx, peers, context.self, reportTo)
          wired(setup, Local(initial, Clock.zero, Map.empty, Rng(seed), queued = false))
        case Start() | Next() | Deliver(_, _) => Behaviors.unhandled
      }
    }
  }

  private def wired[S, M, A, O](
      setup: Setup[S, M, A, O],
      local: Local[S]
  ): Behavior[Command[M]] = {
    val ctx = setup.ctx
    Behaviors.receiveMessage[Command[M]] {
      case Start() => wired(setup, schedule(setup, local))
      case Next()  =>
        val idle = local.copy(queued = false)
        idle.rng.pick(setup.p.actions(ctx, idle.state)) match {
          case None                => wired(setup, idle)
          case Some((rng, action)) =>
            val result = setup.p.onAction(ctx, idle.state, action)
            val cause = Choice.Fire(ctx.self, action)
            handle(setup, idle.copy(rng = rng), cause, idle.clock.tick, result)
        }
      case Deliver(env, lamport) =>
        val result = setup.p.onMessage(ctx, local.state, env.id.from, env.msg)
        handle(setup, local, Choice.Deliver(env.id), local.clock.receive(lamport), result)
      case Wire(_) => Behaviors.unhandled
    }
  }

  /** Reports the step to the Recorder, and then sends the envelopes. The report goes first, so the
    * Recorder gets the `Sent` of an envelope before the receiver can report its delivery.
    */
  private def handle[S, M, A, O](
      setup: Setup[S, M, A, O],
      local: Local[S],
      cause: Choice[A],
      clock: Clock,
      result: Either[ProtocolError, Step[S, M, O]]
  ): Behavior[Command[M]] = {
    val ctx = setup.ctx
    val effects = Effects.of(0, ctx, cause, clock, e => local.nextSeq.getOrElse(e, 0), result)
    setup.reportTo ! Recorder.Handled(ctx.self, effects.events)
    effects.sent.foreach { item =>
      setup.peers.get(item.env.id.to).foreach(peer => peer ! Deliver(item.env, item.lamport))
    }
    val seqs = effects.sent.foldLeft(local.nextSeq) { (acc, item) =>
      acc.updated(item.env.id.channel, item.env.id.seq + 1)
    }
    val after =
      local.copy(state = effects.state.getOrElse(local.state), clock = clock, nextSeq = seqs)
    wired(setup, schedule(setup, after))
  }

  /** Sends `Next` to the node itself when it has an action and no `Next` waits. */
  private def schedule[S, M, A, O](setup: Setup[S, M, A, O], local: Local[S]): Local[S] = {
    if !local.queued && setup.p.actions(setup.ctx, local.state).nonEmpty then {
      setup.self ! Next()
      local.copy(queued = true)
    } else { local }
  }
}
