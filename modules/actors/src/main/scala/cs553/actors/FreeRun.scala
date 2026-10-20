package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.SpawnProtocol
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.AskPattern.*
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import cs553.core.ids.NodeId
import cs553.core.protocol.Protocol
import cs553.core.registry.AnyAlgorithm
import cs553.core.rng.Rng
import cs553.core.rng.Seed
import cs553.core.trace.Engine
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Simulator

import scala.concurrent.Future
import scala.concurrent.duration.*

/** The root of one free run. Each run is one actor subtree with no shared state.
  *
  * The guardian spawns one `Recorder` and one `FreeNode` for each process. The nodes send to each
  * other directly. No Controller and no channel actor stands between them, so the dispatcher of
  * Akka picks the order of the messages. Only the order of each pair of nodes holds.
  *
  * Nobody sees the whole run. So the run ends when a node decides, or when the number of steps
  * reaches the limit of the spec. The guardian forces the stop rule `AtFirstDecision`. A real
  * system has no global observer, and it needs termination detection.
  */
object FreeRun {

  /** How long the Recorder waits for a report. Only a failure guard: a run that has no decision and
    * no step budget left stops sending, and then the run fails.
    */
  private val silence: FiniteDuration = 10.seconds

  /** The name of the actor of `node`, for example `free-node-3`. */
  def nodeName(node: NodeId): String = s"free-node-${node.value}"

  val recorderName: String = "recorder"

  /** A guardian that runs `spec` with `p` and sends the result to `replyTo`. */
  def apply[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      replyTo: ActorRef[RunGuardian.Result[S, M, A, O]]
  ): Behavior[Nothing] = {
    Behaviors
      .setup[RunGuardian.Result[S, M, A, O]] { context =>
        val freeSpec = spec.copy(stop = StopRule.AtFirstDecision)
        val seed = freeSpec.policy match {
          case Policy.Seeded(s) => s
          case Policy.Script(_) => Seed(0)
        }
        val started = Simulator.start(p, freeSpec).left.map(RunGuardian.EngineError.BadSpec(_))
        val world = started.flatMap(
          _.last.toRight(RunGuardian.EngineError.UnexpectedReport("the Simulator gave no world"))
        )
        world match {
          case Left(error) =>
            replyTo ! RunGuardian.Failed(error)
            Behaviors.stopped
          case Right(w) =>
            val first = TraceEvent.Started(freeSpec, Engine.AkkaFreeRun, w.states)
            val recorder = context.spawn(
              Recorder[S, M, A, O](
                first,
                freeSpec.stop,
                freeSpec.stepLimit,
                silence,
                context.self
              ),
              recorderName
            )
            val nodes = w.ctxs.toList.flatMap { case (node, ctx) =>
              w.states.get(node).toList.map { state =>
                val nodeSeed = Rng.derive(Seed(seed.value + node.value))
                node -> context.spawn(FreeNode(p, ctx, state, nodeSeed, recorder), nodeName(node))
              }
            }.toMap
            nodes.foreach { case (node, ref) =>
              val peers = w.ctxs.get(node).toList.flatMap(_.out.toList).flatMap { to =>
                nodes.get(to).toList.map(to -> _)
              }
              ref ! FreeNode.Wire(peers.toMap)
            }
            // All wires come before the first start, so a node never gets an envelope before its
            // own wire.
            nodes.values.foreach(_ ! FreeNode.Start())
            context.watch(recorder)
            nodes.values.foreach(context.watch)
            awaiting(replyTo)
        }
      }
      .narrow[Nothing]
  }

  private def awaiting[S, M, A, O](
      replyTo: ActorRef[RunGuardian.Result[S, M, A, O]]
  ): Behavior[RunGuardian.Result[S, M, A, O]] = {
    Behaviors
      .receiveMessage[RunGuardian.Result[S, M, A, O]] { result =>
        replyTo ! result
        Behaviors.stopped
      }
      .receiveSignal { case (_, Terminated(ref)) =>
        // The Recorder sends its result before it stops, so a Terminated that comes first means
        // that an actor stopped with no result.
        replyTo ! RunGuardian.Failed(RunGuardian.EngineError.ActorStopped(ref.path.name))
        Behaviors.stopped
      }
  }

  /** The edge of the free-run engine, as `MediatedEngine.run`. Only this place uses `ask`. */
  def run[S, M, A, O](
      system: ActorSystem[SpawnProtocol.Command],
      name: String,
      p: Protocol[S, M, A, O],
      spec: RunSpec[A]
  )(using timeout: Timeout): Future[RunGuardian.Result[S, M, A, O]] = {
    given akka.actor.typed.Scheduler = system.scheduler
    system.ask[RunGuardian.Result[S, M, A, O]] { replyTo =>
      SpawnProtocol.Spawn(FreeRun(p, spec, replyTo), name, Props.empty, system.ignoreRef)
    }
  }

  /** `run` for one algorithm of the registry. */
  def record(
      system: ActorSystem[SpawnProtocol.Command],
      name: String,
      alg: AnyAlgorithm
  )(spec: RunSpec[alg.A])(using
      Timeout
  ): Future[RunGuardian.Result[alg.S, alg.M, alg.A, alg.O]] = {
    run(system, name, alg.algorithm.protocol, spec)
  }
}
