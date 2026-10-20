package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.ids.NodeId
import cs553.core.protocol.Protocol
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.Engine
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import cs553.core.world.RunError
import cs553.core.world.Simulator
import cs553.core.world.World

/** The root of one mediated run. Each run is one actor subtree with no shared state (coding rule
  * 24).
  *
  * The guardian checks the spec with `Simulator.start`. It then spawns one `Controller`, one
  * `NodeAdapter` for each process and one `Channel` for each directed edge, as its children:
  *
  * {{{
  * <guardian>/controller
  * <guardian>/node-3
  * <guardian>/channel-3-5
  * }}}
  *
  * When the Controller stops after its reply, the guardian stops, and Akka stops all children. So
  * no actor of the run stays after `Finished`.
  */
object RunGuardian {

  /** The reply of a run to `replyTo`. */
  sealed trait Result[S, M, A, O]

  /** The run ended. `run` is the full trace, from `Started` (engine `AkkaMediated`) to `Finished`.
    */
  final case class Finished[S, M, A, O](run: Run[S, M, A, O]) extends Result[S, M, A, O]

  /** The engine could not do the run. */
  final case class Failed[S, M, A, O](error: EngineError[A]) extends Result[S, M, A, O]

  /** Why a mediated run failed. A protocol error is not an engine error: it is a `Rejected` event.
    */
  enum EngineError[+A] {

    /** `Simulator.start` refused the spec. */
    case BadSpec(error: RunError[A])

    /** A `Script` choice at this step is not legal. */
    case BadChoice(step: Int, choice: Choice[A])

    /** A channel refused a `Release` or a `Drop`. */
    case Refused(id: EnvelopeId, reason: Channel.Refusal)

    /** The Controller got a report that the protocol of the engine does not allow now. */
    case UnexpectedReport(detail: String)

    /** An actor of the run stopped before the end of the run. */
    case ActorStopped(name: String)
  }

  /** The name of the actor of `node`, for example `node-3`. */
  def nodeName(node: NodeId): String = s"node-${node.value}"

  /** The name of the actor of `edge`, for example `channel-3-5`. */
  def channelName(edge: Edge): String = s"channel-${edge.from.value}-${edge.to.value}"

  val controllerName: String = "controller"

  /** A guardian that runs `spec` with `p` and sends the result to `replyTo`. */
  def apply[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      replyTo: ActorRef[Result[S, M, A, O]]
  ): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { context =>
      Simulator.start(p, spec).map(_.last) match {
        case Left(error) =>
          replyTo ! Failed(EngineError.BadSpec(error))
          Behaviors.stopped
        case Right(None) =>
          replyTo ! Failed(EngineError.UnexpectedReport("the Simulator gave no initial world"))
          Behaviors.stopped
        case Right(Some(world)) => spawnRun(context, p, spec, world, replyTo)
      }
    }
  }

  private def spawnRun[S, M, A, O](
      context: ActorContext[Nothing],
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      world: World[S, M],
      replyTo: ActorRef[Result[S, M, A, O]]
  ): Behavior[Nothing] = {
    val edges = world.topology.edges.toList
    val started = TraceEvent.Started(spec, Engine.AkkaMediated, world.states)
    val controller = context.spawn(
      Controller[S, M, A, O](spec, world.order, world.states.keySet, edges.toSet, started, replyTo),
      controllerName
    )
    val nodes = world.states.toList.flatMap { case (node, state) =>
      world.ctxs.get(node).toList.map { ctx =>
        node -> context.spawn(NodeAdapter(p, ctx, state, controller), nodeName(node))
      }
    }.toMap
    val channels = edges.flatMap { edge =>
      nodes.get(edge.to).toList.map { receiver =>
        edge -> context.spawn(
          Channel[M](world.order, receiver.narrow[NodeAdapter.Deliver[M]], controller),
          channelName(edge)
        )
      }
    }.toMap
    nodes.foreach { case (node, ref) =>
      val out = channels.collect {
        case (edge, channel) if edge.from == node => edge.to -> channel.narrow[Channel.In[M]]
      }
      ref ! NodeAdapter.Wire(out)
    }
    controller ! Controller.Wired(nodes, channels)
    context.watch(controller)
    nodes.values.foreach(context.watch)
    channels.values.foreach(context.watch)
    Behaviors.receiveSignal[Nothing] { case (_, Terminated(ref)) =>
      if ref != controller then {
        replyTo ! Failed(EngineError.ActorStopped(ref.path.name))
      }
      Behaviors.stopped
    }
  }
}
