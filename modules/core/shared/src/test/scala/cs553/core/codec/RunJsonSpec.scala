package cs553.core.codec

import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.rng.Seed
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Graphs
import cs553.core.world.Simulator
import cs553.core.world.Toys.ChatAction
import cs553.core.world.Toys.ChatMsg
import cs553.core.world.Toys.ChatState
import cs553.core.world.Toys.FloodAction
import cs553.core.world.Toys.FloodMsg
import cs553.core.world.Toys.FloodOutput
import cs553.core.world.Toys.FloodState
import cs553.core.world.Toys.chatter
import cs553.core.world.Toys.flood
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

final class RunJsonSpec extends AnyFunSuite with ScalaCheckPropertyChecks with EitherValues {

  test("a seeded flood run round-trips through JSON") {
    forAll(Graphs.connected, Gen.long) { (topology: Topology, seed: Long) =>
      val spec = RunSpec(
        flood.id,
        topology,
        ChannelOrder.AnyOrder,
        Policy.Seeded(Seed(seed)),
        StopRule.AtQuiescence,
        1000
      )
      val run = Simulator.run(flood, spec).value.run
      val text = RunJson.write(run)
      assert(RunJson.read[FloodState, FloodMsg, FloodAction, FloodOutput](text) == Right(run))
    }
  }

  test("a run with Rejected, Dropped and Decided events round-trips through JSON") {
    val n0 = NodeId(0)
    val n1 = NodeId(1)
    val topology =
      Graphs.fixed(Topology.directed(List(n0, n1), List(n0 -> n1), Graphs.initiatorAttrs))
    val script = List[Choice[ChatAction]](
      Choice.Fire(n0, ChatAction.Talk),
      Choice.Drop(EnvelopeId(n0, n1, 0)),
      Choice.Deliver(EnvelopeId(n0, n1, 1))
    )
    val spec = RunSpec(
      chatter.id,
      topology,
      ChannelOrder.Fifo,
      Policy.Script(script),
      StopRule.AtQuiescence,
      10
    )
    val run = Simulator.run(chatter, spec).value.run
    assert(run.trace.exists {
      case TraceEvent.Dropped(_, _) => true
      case _                        => false
    })
    assert(run.decisions == Vector(n1 -> 2))
    assert(RunJson.read[ChatState, ChatMsg, ChatAction, Int](RunJson.write(run)) == Right(run))
  }

  test("a seed at the 64-bit limit survives the round trip") {
    val topology = Graphs.fixed(Topology.ring(2, Graphs.initiatorAttrs))
    val spec = RunSpec(
      flood.id,
      topology,
      ChannelOrder.Fifo,
      Policy.Seeded(Seed(Long.MaxValue)),
      StopRule.AtQuiescence,
      100
    )
    val run = Simulator.run(flood, spec).value.run
    val back = RunJson.read[FloodState, FloodMsg, FloodAction, FloodOutput](RunJson.write(run))
    assert(back.map(_.trace.headOption) == Right(run.trace.headOption))
  }

  test("malformed JSON gives Left") {
    assert(
      RunJson
        .read[FloodState, FloodMsg, FloodAction, FloodOutput]("""{"trace": [{"Nope": {}}]}""")
        .isLeft
    )
  }
}
