package cs553.cli

import akka.actor.typed.ActorSystem
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout
import cs553.actors.FreeRun
import cs553.actors.MediatedEngine
import cs553.actors.RunGuardian
import cs553.core.registry.Algorithm
import cs553.core.registry.AnyAlgorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.topology.Preset

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The `record` command: one run of a registry algorithm on one of its presets in the mediated or
  * the free-run Akka engine. The result is the same trace format as `simulate`, with the engine
  * `AkkaMediated` or `AkkaFreeRun`.
  *
  * `plan` is pure. `run` starts the actors and returns a `Future`. Only `Main` waits for it.
  */
object Record {

  /** The algorithm and the preset of `args`, or a message that names the valid values. */
  def plan(args: RecordArgs): Either[String, (AnyAlgorithm, Preset)] = {
    val ids = Registry.all.map(_.id.value).mkString(", ")
    for {
      alg <- Registry.find(args.alg).toRight(s"unknown algorithm '${args.alg.value}' (use $ids)")
      preset <- alg.algorithm.preset(args.preset).toRight {
        val names = alg.algorithm.presets.map(_.name).mkString(", ")
        s"${alg.name} has no preset '${args.preset}' (use $names)"
      }
    } yield (alg, preset)
  }

  /** The name of the run guardian, for example `run-echo-inputEcho-7`. */
  def runName(args: RecordArgs): String = s"run-${args.alg.value}-${args.preset}-${args.seed}"

  /** Records the run of `args` in `system`. The JSON is pretty and ends with a newline, as in the
    * golden traces.
    */
  def run(system: ActorSystem[SpawnProtocol.Command], args: RecordArgs)(using
      Timeout,
      ExecutionContext
  ): Future[Either[String, Report]] = {
    plan(args) match {
      case Left(error)          => Future.successful(Left(error))
      case Right((alg, preset)) =>
        args.engine match {
          case RecordEngine.Mediated => mediated(system, alg.algorithm, preset, args)
          case RecordEngine.Free     => free(system, alg.algorithm, preset, args)
        }
    }
  }

  private def mediated[S, M, A, O](
      system: ActorSystem[SpawnProtocol.Command],
      a: Algorithm[S, M, A, O],
      preset: Preset,
      args: RecordArgs
  )(using Timeout, ExecutionContext): Future[Either[String, Report]] = {
    val spec = a.spec(preset, Seed(args.seed))
    MediatedEngine.run(system, runName(args), a.protocol, spec).map {
      case RunGuardian.Finished(run) =>
        val header = s"${a.name} on ${preset.name} in the mediated Akka engine, " +
          s"order ${spec.order}, seed ${args.seed}"
        val lines = Render.lines(run)(using
          a.inspectState,
          a.inspectMsg,
          a.inspectAction,
          a.inspectOutput
        )
        Right(Report(header :: lines, a.writeTracePretty(run) + "\n"))
      case RunGuardian.Failed(error) => Left(s"the mediated engine failed: $error")
    }
  }

  private def free[S, M, A, O](
      system: ActorSystem[SpawnProtocol.Command],
      a: Algorithm[S, M, A, O],
      preset: Preset,
      args: RecordArgs
  )(using Timeout, ExecutionContext): Future[Either[String, Report]] = {
    val spec = a.spec(preset, Seed(args.seed))
    FreeRun.run(system, runName(args), a.protocol, spec).map {
      case RunGuardian.Finished(run) =>
        val header = s"${a.name} on ${preset.name} in the free-run Akka engine, " +
          s"seed ${args.seed}. Each run can give a different order."
        val lines = Render.lines(run)(using
          a.inspectState,
          a.inspectMsg,
          a.inspectAction,
          a.inspectOutput
        )
        Right(Report(header :: lines, a.writeTracePretty(run) + "\n"))
      case RunGuardian.Failed(error) => Left(s"the free-run engine failed: $error")
    }
  }
}
