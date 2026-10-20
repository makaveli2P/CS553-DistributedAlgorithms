package cs553.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.OrderPolicy
import cs553.core.registry.Algorithm
import cs553.core.registry.AnyAlgorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.topology.Preset
import cs553.core.trace.Engine
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.*

/** The mediated Akka engine gives the same trace as the Simulator for the same `RunSpec`, event by
  * event. Only the engine field of `Started` differs.
  */
final class ConformanceSpec
    extends ScalaTestWithActorTestKit
    with AnyFunSuiteLike
    with EitherValues {

  /** A failure guard. A run replies well before it. */
  private val guard: FiniteDuration = 20.seconds

  private val seeds: List[Long] = (1L to 20L).toList

  /** The trace with the engine of `Started` set to `Simulator`. */
  private def normalized[S, M, A, O](run: Run[S, M, A, O]): Vector[TraceEvent[S, M, A, O]] = {
    run.trace.map {
      case TraceEvent.Started(spec, _, initial) =>
        TraceEvent.Started(spec, Engine.Simulator, initial)
      case other => other
    }
  }

  /** The first event where the traces differ, for a short failure message. */
  private def difference[E](expected: Vector[E], actual: Vector[E]): String = {
    val i = expected.zip(actual).indexWhere { case (e, a) => e != a }
    if i < 0 then { s"the lengths differ: ${expected.size} and ${actual.size}" }
    else { s"event $i:\n  simulator: ${expected.lift(i)}\n  akka:      ${actual.lift(i)}" }
  }

  /** Runs `spec` in the mediated engine, and checks the trace against the Simulator. */
  private def conform[S, M, A, O](
      a: Algorithm[S, M, A, O],
      spec: RunSpec[A],
      name: String
  ): Run[S, M, A, O] = {
    val expected = a.simulate(spec).value.run
    val probe = createTestProbe[RunGuardian.Result[S, M, A, O]]()
    val _ = spawn(RunGuardian(a.protocol, spec, probe.ref), name)
    val finished = probe.expectMessageType[RunGuardian.Finished[S, M, A, O]](guard)
    val engines = finished.run.trace.collect { case TraceEvent.Started(_, engine, _) => engine }
    assert(engines == Vector(Engine.AkkaMediated), name)
    val actual = normalized(finished.run)
    assert(actual == expected.trace, s"$name: ${difference(expected.trace, actual)}")
    assert(finished.run.outcome == expected.outcome, name)
    finished.run
  }

  private def orders(alg: AnyAlgorithm): List[ChannelOrder] = alg.algorithm.protocol.order match {
    case OrderPolicy.Locked(order)  => List(order)
    case OrderPolicy.Default(order) => (order :: ChannelOrder.values.toList).distinct
  }

  private def seeded[S, M, A, O](a: Algorithm[S, M, A, O], preset: Preset, order: ChannelOrder) = {
    seeds.foreach { seed =>
      val spec = a.spec(preset, Seed(seed)).copy(order = order)
      val run = conform(a, spec, s"seeded-${a.id.value}-${preset.name}-$order-$seed")
      assert(run.decisions.nonEmpty, s"seed $seed")
    }
  }

  private def scripted[S, M, A, O](a: Algorithm[S, M, A, O], preset: Preset): Unit = {
    seeds.take(3).foreach { seed =>
      val seededSpec = a.spec(preset, Seed(seed))
      val choices = a.simulate(seededSpec).value.choices
      val spec = seededSpec.copy(policy = Policy.Script(choices))
      val run = conform(a, spec, s"script-${a.id.value}-${preset.name}-$seed")
      assert(run.decisions.nonEmpty, s"seed $seed")
    }
  }

  Registry.all.foreach { alg =>
    alg.algorithm.presets.foreach { preset =>
      orders(alg).foreach { order =>
        test(
          s"${alg.name} on ${preset.name} in $order: the Akka trace equals the Simulator trace"
        ) {
          seeded(alg.algorithm, preset, order)
        }
      }
      test(s"${alg.name} on ${preset.name}: a Script run equals the Simulator trace") {
        scripted(alg.algorithm, preset)
      }
    }
  }

  test("a step limit stops the Akka run at the same step as the Simulator") {
    val a = Registry.changRoberts
    a.presets.headOption.foreach { preset =>
      val run = conform(a, a.spec(preset, Seed(3)).copy(stepLimit = 5), "limit-chang-roberts")
      assert(run.outcome.contains(Outcome.StepLimit))
    }
  }
}
