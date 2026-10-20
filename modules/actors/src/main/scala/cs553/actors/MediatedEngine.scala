package cs553.actors

import akka.actor.typed.ActorSystem
import akka.actor.typed.Props
import akka.actor.typed.SpawnProtocol
import akka.actor.typed.scaladsl.AskPattern.*
import akka.util.Timeout
import cs553.core.protocol.Protocol
import cs553.core.registry.AnyAlgorithm
import cs553.core.trace.RunSpec

import scala.concurrent.Future

/** The entry point of the mediated engine for code outside the actor system, for example the CLI.
  *
  * This object is the edge: it is the only place that uses `ask` and returns a `Future`. The actors
  * of the run use only `!` (coding rule 22). The `timeout` is a failure guard. A run that ends
  * sends its reply before the timeout.
  */
object MediatedEngine {

  /** Spawns a `RunGuardian` with the name `name` under the `SpawnProtocol` guardian of `system`,
    * and completes with its reply. `name` must be unique in `system` while the run is active.
    */
  def run[S, M, A, O](
      system: ActorSystem[SpawnProtocol.Command],
      name: String,
      p: Protocol[S, M, A, O],
      spec: RunSpec[A]
  )(using timeout: Timeout): Future[RunGuardian.Result[S, M, A, O]] = {
    given akka.actor.typed.Scheduler = system.scheduler
    system.ask[RunGuardian.Result[S, M, A, O]] { replyTo =>
      SpawnProtocol.Spawn(RunGuardian(p, spec, replyTo), name, Props.empty, system.ignoreRef)
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
