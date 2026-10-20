package cs553.core.world

import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.rng.Seed
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Toys.ChatAction
import cs553.core.world.Toys.FloodAction
import cs553.core.world.Toys.FloodMsg
import cs553.core.world.Toys.FloodOutput
import cs553.core.world.Toys.FloodState
import cs553.core.world.Toys.chatter
import cs553.core.world.Toys.flood
import cs553.core.world.Toys.lockedChatter
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

final class SimulatorSpec extends AnyFunSuite with ScalaCheckPropertyChecks with EitherValues {

  private type FloodRun = Run[FloodState, FloodMsg, FloodAction, FloodOutput]

  private def floodSpec(
      topology: Topology,
      seed: Long,
      order: ChannelOrder = ChannelOrder.Fifo
  ): RunSpec[FloodAction] = {
    RunSpec(flood.id, topology, order, Policy.Seeded(Seed(seed)), StopRule.AtQuiescence, 10000)
  }

  private def runFlood(spec: RunSpec[FloodAction]): FloodRun = Simulator.run(flood, spec).value.run

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.Fifo, ChannelOrder.AnyOrder)

  /** The choice of each step, in order. */
  private def choices[S, M, A, O](run: Run[S, M, A, O]): List[Choice[A]] = {
    run.trace.toList.collect {
      case TraceEvent.Stepped(_, _, cause, _, _, _) => cause
      case TraceEvent.Rejected(_, _, cause, _)      => cause
      case TraceEvent.Dropped(_, id)                => Choice.Drop(id)
    }
  }

  test("the same seed gives an identical trace") {
    forAll(Graphs.connected, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        assert(
          runFlood(floodSpec(topology, seed, order)) == runFlood(floodSpec(topology, seed, order))
        )
    }
  }

  test("each delivered envelope has an earlier Sent event, and each envelope id is sent once") {
    forAll(Graphs.connected, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val trace = runFlood(floodSpec(topology, seed, order)).trace
        val sentAt = trace.zipWithIndex.collect { case (TraceEvent.Sent(_, env), i) => env.id -> i }
        assert(sentAt.map(_._1).distinct.size == sentAt.size)
        val sentIndex = sentAt.toMap
        trace.zipWithIndex.foreach {
          case (TraceEvent.Stepped(_, _, Choice.Deliver(id), _, _, _), i) =>
            assert(sentIndex.get(id).exists(_ < i), s"$id is delivered at $i with no earlier Sent")
          case _ => ()
        }
    }
  }

  test("in Fifo order each channel delivers its envelopes in sequence order") {
    forAll(Graphs.connected, Gen.long) { (topology: Topology, seed: Long) =>
      val delivered = choices(runFlood(floodSpec(topology, seed))).collect {
        case Choice.Deliver(id) => id
      }
      delivered.groupBy(_.channel).values.foreach { ids =>
        assert(ids.map(_.seq) == ids.indices.toList)
      }
    }
  }

  test("flood reaches each node, sends 2|E| - (n - 1) messages and ends quiescent") {
    forAll(Graphs.connected, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val simulation = Simulator.run(flood, floodSpec(topology, seed, order)).value
        val run = simulation.run
        val sent = run.trace.count {
          case TraceEvent.Sent(_, _) => true
          case _                     => false
        }
        assert(run.outcome == Some(Outcome.Quiescent))
        assert(run.decisions.map(_._1).sorted == topology.nodes.toVector)
        assert(sent == topology.edges.size - (topology.nodes.size - 1))
        assert(simulation.last.exists(_.buffered.isEmpty))
        assert(simulation.worlds.size == simulation.last.map(_.step).getOrElse(-1) + 1)
    }
  }

  test("a Script of the choices of a seeded run replays the same events") {
    forAll(Graphs.connected, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val seeded = runFlood(floodSpec(topology, seed, order))
        val scripted =
          runFlood(floodSpec(topology, seed, order).copy(policy = Policy.Script(choices(seeded))))
        assert(scripted.trace.drop(1) == seeded.trace.drop(1))
    }
  }

  private val line: Topology = Graphs.fixed(Topology.ring(3, Graphs.initiatorAttrs)).symmetric

  test("the run stops at the step limit") {
    val simulation = Simulator.run(flood, floodSpec(line, 1L).copy(stepLimit = 1)).value
    assert(simulation.run.outcome == Some(Outcome.StepLimit))
    assert(simulation.worlds.size == 2)
  }

  test("AtFirstDecision stops after the first step with a decision") {
    val run = runFlood(floodSpec(line, 1L).copy(stop = StopRule.AtFirstDecision))
    assert(run.outcome == Some(Outcome.AlgorithmDecided))
    assert(run.decisions == Vector(NodeId(0) -> FloodOutput.Covered))
  }

  test("an empty Script ends with ScriptExhausted while choices are enabled") {
    val run = runFlood(floodSpec(line, 1L).copy(policy = Policy.Script(Nil)))
    assert(run.outcome == Some(Outcome.ScriptExhausted))
    assert(run.trace.size == 2)
  }

  test("an illegal Script choice gives BadChoice with its step") {
    val bad = Choice.Deliver(EnvelopeId(NodeId(0), NodeId(1), 0))
    val result = Simulator.run(flood, floodSpec(line, 1L).copy(policy = Policy.Script(List(bad))))
    assert(
      result.left.value == RunError.BadChoice(
        1,
        bad,
        ChoiceError.NotBuffered(EnvelopeId(NodeId(0), NodeId(1), 0))
      )
    )
  }

  test("a Script can drop an envelope") {
    val script = List(
      Choice.Fire(NodeId(0), FloodAction.Start),
      Choice.Drop(EnvelopeId(NodeId(0), NodeId(1), 0))
    )
    val run = runFlood(floodSpec(line, 1L).copy(policy = Policy.Script(script)))
    assert(
      run.trace.contains(
        TraceEvent.Dropped(cs553.core.trace.Stamp(2, 0, 0), EnvelopeId(NodeId(0), NodeId(1), 0))
      )
    )
  }

  test("a locked channel order refuses another order") {
    val spec = RunSpec[ChatAction](
      chatter.id,
      line,
      ChannelOrder.Fifo,
      Policy.Seeded(Seed(1L)),
      StopRule.AtQuiescence,
      10
    )
    assert(
      Simulator.run(lockedChatter, spec).left.value == RunError.OrderLocked(
        ChannelOrder.AnyOrder,
        ChannelOrder.Fifo
      )
    )
    assert(Simulator.run(lockedChatter, spec.copy(order = ChannelOrder.AnyOrder)).isRight)
  }

  test("a spec for another algorithm is refused") {
    val spec = floodSpec(line, 1L).copy(algorithm = AlgorithmId("other"))
    assert(
      Simulator.run(flood, spec).left.value == RunError.AlgorithmMismatch(
        flood.id,
        AlgorithmId("other")
      )
    )
  }

  test("a negative step limit is refused") {
    assert(
      Simulator.run(flood, floodSpec(line, 1L).copy(stepLimit = -1)).left.value == RunError
        .BadStepLimit(-1)
    )
  }
}
