package cs553.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import cs553.core.registry.Algorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.trace.Choice
import cs553.core.trace.Engine
import cs553.core.trace.Outcome
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.*

/** Free runs are not deterministic, so these tests check properties of each trace, not the exact
  * events. The strongest property: the free-run trace is a legal schedule of the pure model. So
  * `Simulator.replay` of its choices gives the same events, and the invariant of the algorithm
  * holds in each world of the replay.
  */
final class FreeRunSpec
    extends ScalaTestWithActorTestKit
    with AnyFunSuiteLike
    with EitherValues
    with OptionValues {

  private val guard: FiniteDuration = 20.seconds

  private val runs: Int = 10

  private def freeRun[S, M, A, O](
      a: Algorithm[S, M, A, O],
      spec: RunSpec[A],
      name: String
  ): Run[S, M, A, O] = {
    val probe = createTestProbe[RunGuardian.Result[S, M, A, O]]()
    val _ = spawn(FreeRun(a.protocol, spec, probe.ref), name)
    probe.expectMessageType[RunGuardian.Finished[S, M, A, O]](guard).run
  }

  /** Each delivery has an earlier `Sent` with the same id, and a higher Lamport time. */
  private def checkCausality[S, M, A, O](run: Run[S, M, A, O], name: String): Unit = {
    val sentAt = run.trace.zipWithIndex.collect { case (TraceEvent.Sent(at, env), i) =>
      env.id -> (i, at.lamport)
    }
    assert(sentAt.map(_._1).distinct.size == sentAt.size, s"$name: an envelope id occurs twice")
    val sent = sentAt.toMap
    val deliveries = run.trace.zipWithIndex.collect {
      case (TraceEvent.Stepped(at, _, Choice.Deliver(id), _, _, _), i) => (id, at.lamport, i)
      case (TraceEvent.Rejected(at, _, Choice.Deliver(id), _), i)      => (id, at.lamport, i)
    }
    deliveries.foreach { case (id, lamport, i) =>
      val (sentIndex, sentLamport) = sent.get(id).value
      assert(sentIndex < i, s"$name: $id arrives before its send")
      assert(lamport > sentLamport, s"$name: the Lamport time of $id does not grow")
    }
    val _ = assert(deliveries.map(_._1).distinct.size == deliveries.size, s"$name: double delivery")
  }

  /** On each node, the local counter grows by one and the Lamport time grows. */
  private def checkNodeClocks[S, M, A, O](run: Run[S, M, A, O], name: String): Unit = {
    val steps = run.trace.collect {
      case TraceEvent.Stepped(at, node, _, _, _, _) => (node, at)
      case TraceEvent.Rejected(at, node, _, _)      => (node, at)
    }
    steps.groupBy(_._1).foreach { case (node, own) =>
      val stamps = own.map(_._2)
      assert(stamps.map(_.local) == (1 to stamps.size).toList, s"$name: counter of $node")
      assert(
        stamps.zip(stamps.drop(1)).forall { case (x, y) => x.lamport < y.lamport },
        s"$name: Lamport time of $node"
      )
    }
  }

  private def check[S, M, A, O](a: Algorithm[S, M, A, O], run: Run[S, M, A, O], name: String) = {
    val started = run.trace.collectFirst { case s @ TraceEvent.Started(_, _, _) => s }.value
    assert(started.engine == Engine.AkkaFreeRun, name)
    assert(started.spec.stop == StopRule.AtFirstDecision, name)
    assert(run.outcome.contains(Outcome.AlgorithmDecided), s"$name: ${run.outcome}")
    assert(run.decisions.nonEmpty, name)
    checkCausality(run, name)
    checkNodeClocks(run, name)
    val choices = run.trace.toList.collect {
      case TraceEvent.Stepped(_, _, cause, _, _, _) => cause
      case TraceEvent.Rejected(_, _, cause, _)      => cause
    }
    val replay = a.replay(cs553.core.codec.SharedRun(started.spec, choices)).value
    val normalized = run.trace.map {
      case TraceEvent.Started(spec, _, initial) =>
        TraceEvent.Started(spec, Engine.Simulator, initial)
      case other => other
    }
    assert(replay.run.trace == normalized, s"$name: the replay differs from the free run")
    replay.worlds.zipWithIndex.foreach { case (world, k) =>
      assert(a.invariant(world).isEmpty, s"$name: invariant at step $k: ${a.invariant(world)}")
    }
  }

  Registry.all.foreach { alg =>
    test(s"${alg.name}: $runs free runs are legal schedules that decide") {
      val a = alg.algorithm
      val preset = a.presets.headOption.value
      (1 to runs).foreach { n =>
        val name = s"free-${a.id.value}-$n"
        val run = freeRun(a, a.spec(preset, Seed(n.toLong)), name)
        check(a, run, name)
      }
    }
  }

  test("a step limit ends a free run with the outcome StepLimit") {
    val a = Registry.changRoberts
    val preset = a.presets.headOption.value
    val run = freeRun(a, a.spec(preset, Seed(3)).copy(stepLimit = 4), "free-limit")
    assert(run.outcome.contains(Outcome.StepLimit))
    val steps = run.trace.collect { case TraceEvent.Stepped(_, _, _, _, _, _) => 1 }
    assert(steps.size == 4)
  }
}
