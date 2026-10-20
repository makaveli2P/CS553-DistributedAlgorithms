package cs553.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.registry.Algorithm
import cs553.core.registry.AnyAlgorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.Engine
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import cs553.core.world.RunError
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration.*

final class RunGuardianSpec
    extends ScalaTestWithActorTestKit
    with AnyFunSuiteLike
    with EitherValues
    with OptionValues {

  /** A failure guard. A run replies well before it. */
  private val guard: FiniteDuration = 20.seconds

  private def specOf[S, M, A, O](a: Algorithm[S, M, A, O], seed: Long): RunSpec[A] = {
    a.spec(a.presets.headOption.value, Seed(seed))
  }

  /** A started run: the guardian ref and the probe that gets its reply. */
  private def start[S, M, A, O](
      a: Algorithm[S, M, A, O],
      spec: RunSpec[A],
      name: String
  ): (ActorRef[Nothing], TestProbe[RunGuardian.Result[S, M, A, O]]) = {
    val probe = createTestProbe[RunGuardian.Result[S, M, A, O]]()
    (spawn(RunGuardian(a.protocol, spec, probe.ref), name), probe)
  }

  private def finished[S, M, A, O](probe: TestProbe[RunGuardian.Result[S, M, A, O]]) = {
    probe.expectMessageType[RunGuardian.Finished[S, M, A, O]](guard).run
  }

  private def failed[S, M, A, O](probe: TestProbe[RunGuardian.Result[S, M, A, O]]) = {
    probe.expectMessageType[RunGuardian.Failed[S, M, A, O]](guard).error
  }

  test("the actors of a run have readable names") {
    assert(RunGuardian.nodeName(NodeId(3)) == "node-3")
    assert(RunGuardian.channelName(Edge(NodeId(3), NodeId(5))) == "channel-3-5")
    assert(RunGuardian.controllerName == "controller")
  }

  test("the guardian, and so each actor of the run, stops after Finished") {
    val a = Registry.echo
    val (guardian, probe) = start(a, specOf(a, 1), "run-stop")
    assert(finished(probe).decisions.nonEmpty)
    // Akka stops a parent only after all of its children stopped. So when the guardian is
    // terminated, the Controller, the nodes and the channels are terminated too.
    probe.expectTerminated(guardian, guard)
  }

  test("two runs in one actor system do not share state") {
    val echo = Registry.echo
    val cr = Registry.changRoberts
    // Both runs start before either reply, so their actors are active at the same time.
    val (g1, p1) = start(echo, specOf(echo, 1), "run-a")
    val (g2, p2) = start(echo, specOf(echo, 7), "run-b")
    val (g3, p3) = start(cr, specOf(cr, 7), "run-c")
    val r1 = finished(p1)
    val r2 = finished(p2)
    val r3 = finished(p3)
    assert(r1.trace.drop(1) == echo.simulate(specOf(echo, 1)).value.run.trace.drop(1))
    assert(r2.trace.drop(1) == echo.simulate(specOf(echo, 7)).value.run.trace.drop(1))
    assert(r3.trace.drop(1) == cr.simulate(specOf(cr, 7)).value.run.trace.drop(1))
    assert(r1.trace != r2.trace)
    List(g1, g2, g3).foreach(g => p1.expectTerminated(g, guard))
  }

  test("a spec for another algorithm gives Failed with BadSpec, and the guardian stops") {
    val a = Registry.echo
    val spec = specOf(a, 1).copy(algorithm = AlgorithmId("tarry"))
    val (guardian, probe) = start(a, spec, "run-bad-spec")
    assert(
      failed(probe) == RunGuardian.EngineError.BadSpec(
        RunError.AlgorithmMismatch(a.id, AlgorithmId("tarry"))
      )
    )
    probe.expectTerminated(guardian, guard)
  }

  test("a Script choice that is not legal gives Failed with BadChoice at its step") {
    val a = Registry.echo
    val missing = EnvelopeId(NodeId(1), NodeId(2), 5)
    val spec = specOf(a, 1).copy(policy = Policy.Script(List(Choice.Deliver(missing))))
    val (guardian, probe) = start(a, spec, "run-bad-choice")
    assert(failed(probe) == RunGuardian.EngineError.BadChoice(1, Choice.Deliver(missing)))
    probe.expectTerminated(guardian, guard)
  }

  test("MediatedEngine.record runs a registry algorithm under a SpawnProtocol system") {
    val system = ActorSystem(SpawnProtocol(), "record-test")
    given Timeout = Timeout(guard)
    val alg = AnyAlgorithm(Registry.changRoberts)
    val spec = alg.algorithm.defaultSpec.value
    // The test thread is outside the actor system, so it can wait for the Future (a guard only).
    val result = Await.result(MediatedEngine.record(system, "run-1", alg)(spec), guard)
    result match {
      case RunGuardian.Finished(run) =>
        val engines = run.trace.collect { case TraceEvent.Started(_, e, _) => e }
        assert(engines == Vector(Engine.AkkaMediated))
        assert(run.trace.drop(1) == alg.algorithm.simulate(spec).value.run.trace.drop(1))
      case RunGuardian.Failed(error) => fail(s"the run failed: $error")
    }
    system.terminate()
  }
}
