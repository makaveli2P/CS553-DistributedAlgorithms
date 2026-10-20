package cs553.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.Protocol
import cs553.core.registry.Algorithm
import cs553.core.registry.AnyAlgorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.Engine
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import cs553.core.world.RunError
import cs553.core.world.Simulator
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuiteLike

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
    startWith(a.protocol, spec, name)
  }

  private def startWith[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      name: String
  ): (ActorRef[Nothing], TestProbe[RunGuardian.Result[S, M, A, O]]) = {
    val probe = createTestProbe[RunGuardian.Result[S, M, A, O]]()
    (spawn(RunGuardian(p, spec, probe.ref), name), probe)
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
    // The probe gets the result of the Future. The guard is a failure guard only (coding rule 25).
    val probe = TestProbe[RunGuardian.Result[alg.S, alg.M, alg.A, alg.O]]()(using system)
    MediatedEngine
      .record(system, "run-1", alg)(spec)
      .foreach(probe.ref ! _)(using
        system.executionContext
      )
    probe.receiveMessage(guard) match {
      case RunGuardian.Finished(run) =>
        val engines = run.trace.collect { case TraceEvent.Started(_, e, _) => e }
        assert(engines == Vector(Engine.AkkaMediated))
        assert(run.trace.drop(1) == alg.algorithm.simulate(spec).value.run.trace.drop(1))
      case RunGuardian.Failed(error) => fail(s"the run failed: $error")
    }
    system.terminate()
  }

  /** `p`, but each `onMessage` throws, as a protocol with a bug can. Akka then stops the node. */
  private def crashing[S, M, A, O](p: Protocol[S, M, A, O]): Protocol[S, M, A, O] = {
    new Protocol[S, M, A, O] {
      def id = p.id
      def order = p.order
      override def check(topology: Topology) = p.check(topology)
      def init(ctx: NodeCtx, attrs: NodeAttrs) = {
        p.init(ctx, attrs)
      }
      def actions(ctx: NodeCtx, state: S) = p.actions(ctx, state)
      def onAction(ctx: NodeCtx, state: S, action: A) = {
        p.onAction(ctx, state, action)
      }
      def onMessage(ctx: NodeCtx, state: S, from: NodeId, msg: M) = {
        sys.error(s"a test bug in node ${ctx.self.value}")
      }
    }
  }

  test("a node actor that stops during the run gives Failed with ActorStopped, and no hang") {
    val a = Registry.echo
    val spec = specOf(a, 1)
    // The first Deliver of the seeded run goes to this node, and its onMessage throws.
    val first = a.simulate(spec).value.choices.collectFirst { case Choice.Deliver(id) => id.to }
    val (guardian, probe) = startWith(crashing(a.protocol), spec, "run-crash")
    assert(failed(probe) == RunGuardian.EngineError.ActorStopped(RunGuardian.nodeName(first.value)))
    probe.expectTerminated(guardian, guard)
  }

  test("the guardian refuses a world that has no context for a node") {
    val a = Registry.echo
    val world = Simulator.start(a.protocol, specOf(a, 1)).value.last.value
    assert(RunGuardian.wiringOf(world) == Right(()))
    val node = world.ctxs.keySet.headOption.value
    val broken = world.copy(ctxs = world.ctxs - node)
    assert(
      RunGuardian.wiringOf(broken) == Left(
        RunGuardian.EngineError.Unwired(s"no context for the nodes ${node.value}")
      )
    )
  }
}
