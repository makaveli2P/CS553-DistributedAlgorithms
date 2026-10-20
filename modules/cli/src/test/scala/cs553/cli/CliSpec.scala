package cs553.cli

import cs553.core.algorithms.election.ChangRoberts
import cs553.core.algorithms.wave.EchoAction
import cs553.core.algorithms.wave.EchoMsg
import cs553.core.algorithms.wave.EchoOutput
import cs553.core.algorithms.wave.EchoState
import cs553.core.codec.RunJson
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.rng.Rng
import cs553.core.rng.Seed
import cs553.core.trace.Outcome
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

final class CliSpec extends AnyFunSuite with EitherValues {

  /** The text of fixtures/topologies/inputEcho.dot. */
  private val inputEcho: String =
    """digraph "Net Graph with 6 nodes" {
      |edge ["class"="link-class"]
      |"0" ["color"="red","label"=<<b>Init</b>>,"fontcolor"="#1020d0"]
      |"1" -> "2"
      |"1" -> "4"
      |"1" -> "5"
      |"2" -> "3"
      |"2" -> "5"
      |"4" -> "2"
      |"4" -> "5"
      |}""".stripMargin

  private def simulate(line: String): SimulateArgs = {
    Args.parse(line.split(" ").toList).value match {
      case Command.Simulate(args) => args
      case Command.Help           => fail(s"'$line' parses as help")
    }
  }

  test("Args parses a full simulate command") {
    val args = simulate(
      "simulate --alg echo --topology a.dot --seed 7 --order any --initiator 1 --out t.json --steps 50"
    )
    assert(
      args == SimulateArgs(
        Alg.Echo,
        Source.File("a.dot"),
        7L,
        Some(ChannelOrder.AnyOrder),
        Some(NodeId(1)),
        Some("t.json"),
        50
      )
    )
  }

  test("Args gives defaults and a ring source") {
    val args = simulate("simulate --alg chang-roberts --ring 6")
    assert(args == SimulateArgs(Alg.ChangRoberts, Source.Ring(6), 0L, None, None, None, 100000))
  }

  test("Args rejects bad input with a message") {
    val bad = List(
      "simulate --alg tarry --ring 3",
      "simulate --alg echo",
      "simulate --alg echo --ring 3 --topology a.dot",
      "simulate --alg echo --topology a.dot --seed x",
      "simulate --alg echo --topology a.dot --order lifo",
      "simulate --alg echo --topology a.dot --bogus 1",
      "simulate --alg echo --topology a.dot --seed 1 --seed 2",
      "simulate --alg echo --topology",
      "simulate --alg echo --ring -3",
      "record"
    )
    bad.foreach(line => assert(Args.parse(line.split(" ").toList).isLeft, line))
    assert(Args.parse(Nil) == Right(Command.Help))
  }

  test("simulate echo on inputEcho.dot as it is explains that the Init node 0 has no edges") {
    val args = simulate("simulate --alg echo --topology inputEcho.dot --seed 7")
    val error = Simulate.run(args, Some(inputEcho)).left.value
    assert(error.contains("these nodes have no edges: 0"))
    assert(error.contains("the initiator 0 has no edges; choose an initiator that has edges"))
    assert(error.contains("hint: use --initiator to choose the initiator."))
  }

  test("simulate echo on inputEcho.dot with --initiator 1 prints the trace and gives JSON") {
    val args = simulate("simulate --alg echo --topology inputEcho.dot --initiator 1 --seed 7")
    val report = Simulate.run(args, Some(inputEcho)).value
    assert(report.text.contains("note: node 0 has no edges, so the run omits it"))
    assert(report.text.exists(_.contains("[decide]")))
    assert(report.text.contains("finished: Quiescent after 15 steps, 14 messages"))
    assert(report.text.contains("decisions: node 1: the wave is complete"))
    val run = RunJson.read[EchoState, EchoMsg, EchoAction, EchoOutput](report.json).value
    assert(run.outcome.contains(Outcome.Quiescent))
    assert(run.decisions.map(_._1).toList == List(NodeId(1)))
  }

  test("simulate chang-roberts on a seeded ring elects one leader, and each node decides") {
    val args = simulate("simulate --alg chang-roberts --ring 6 --seed 7")
    val report = Simulate.run(args, None).value
    assert(report.text.exists(_.startsWith("nodes: 0(uid ")))
    assert(report.text.count(_.contains("DECIDES: it is the leader")) == 1)
    assert(report.text.count(_.contains("DECIDES: the leader is uid")) == 5)
    assert(report.text.exists(_.startsWith("finished: Quiescent")))
    assert(Simulate.run(args, None) == Simulate.run(args, None))
  }

  test("simulate chang-roberts takes the ring from a seed that differs from the schedule seed") {
    val report = Simulate.run(simulate("simulate --alg chang-roberts --ring 6 --seed 7"), None)
    val ring = ChangRoberts.randomRing(6, Rng.derive(Seed(7L))).value
    val expected = ring.nodes.toList
      .map { n =>
        val uid = ring.attrsOf(n).uid.fold("-")(_.value.toString)
        val mark = if ring.initiators.contains(n) then { "*" }
        else { "" }
        s"${n.value}(uid $uid$mark)"
      }
      .mkString(", ")
    assert(report.value.text.contains(s"nodes: $expected (* marks an initiator)"))
    assert(Rng.derive(Seed(7L)) != Seed(7L))
  }

  test("simulate chang-roberts on a DOT ring with a duplicate uid explains the requirement") {
    val dot = """digraph { "0" [uid=3, initiator=true]; "1" [uid=3]; "0" -> "1"; "1" -> "0" }"""
    val error =
      Simulate.run(simulate("simulate --alg chang-roberts --topology r.dot"), Some(dot)).left.value
    assert(error.startsWith("the topology does not meet the requirements of Chang-Roberts:"))
    assert(error.contains("the uid 3 is on more than one node"))
  }

  test("simulate rejects --ring for echo and a bad DOT file") {
    assert(Simulate.run(simulate("simulate --alg echo --ring 4"), None).isLeft)
    val bad = Simulate.run(simulate("simulate --alg echo --topology x.dot"), Some("graph {"))
    assert(bad.left.value.startsWith("x.dot is not a valid topology:"))
  }
}
