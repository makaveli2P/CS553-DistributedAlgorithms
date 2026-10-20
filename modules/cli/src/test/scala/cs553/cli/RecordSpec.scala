package cs553.cli

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.election.CrMsg
import cs553.core.algorithms.election.CrOutput
import cs553.core.algorithms.election.CrState
import cs553.core.codec.RunJson
import cs553.core.ids.AlgorithmId
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.trace.Engine
import cs553.core.trace.TraceEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

final class RecordSpec
    extends AnyFunSuite
    with EitherValues
    with OptionValues
    with BeforeAndAfterAll {

  private val guard: FiniteDuration = 30.seconds
  private val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "record")

  override def afterAll(): Unit = system.terminate()

  private def record(line: String): RecordArgs = {
    Args.parse(line.split(" ").toList).value match {
      case Command.Record(args)               => args
      case Command.Simulate(_) | Command.Help => fail(s"'$line' does not parse as record")
    }
  }

  test("Args parses a full record command") {
    val args = record(
      "record --engine mediated --alg chang-roberts --preset ring-6 --seed 7 --out a.json"
    )
    assert(
      args == RecordArgs(
        RecordEngine.Mediated,
        AlgorithmId("chang-roberts"),
        "ring-6",
        7L,
        Some("a.json")
      )
    )
  }

  test(
    "Args rejects a record command without an engine, with the free engine or with bad options"
  ) {
    val bad = List(
      "record --alg echo --preset inputEcho",
      "record --engine free --alg echo --preset inputEcho",
      "record --engine mediated --preset inputEcho",
      "record --engine mediated --alg echo",
      "record --engine mediated --alg echo --preset inputEcho --ring 3",
      "record --engine mediated --alg echo --preset inputEcho --seed x"
    )
    bad.foreach(line => assert(Args.parse(line.split(" ").toList).isLeft, line))
    val free = Args.parse("record --engine free --alg echo --preset inputEcho".split(" ").toList)
    assert(free.left.value.contains("M7"))
  }

  test("Record.plan names the valid algorithms and presets") {
    val unknownAlg = Record.plan(record("record --engine mediated --alg nope --preset x"))
    val known = Registry.all.map(_.id.value).mkString(", ")
    assert(unknownAlg.left.value == s"unknown algorithm 'nope' (use $known)")
    val unknownPreset = Record.plan(record("record --engine mediated --alg echo --preset ring-6"))
    assert(
      unknownPreset.left.value == "Echo has no preset 'ring-6' (use inputEcho, inputTarry, netgraph-6)"
    )
  }

  test(
    "record runs the mediated engine and gives the Simulator trace with the engine AkkaMediated"
  ) {
    given Timeout = Timeout(guard)
    given ExecutionContext = system.executionContext
    val args = record("record --engine mediated --alg chang-roberts --preset ring-6 --seed 7")
    // The probe gets the result of the Future. The guard is a failure guard only (coding rule 25).
    val probe = TestProbe[Either[String, Report]]()(using system)
    Record.run(system, args).foreach(probe.ref ! _)
    val report = probe.receiveMessage(guard).value
    assert(report.text.headOption.exists(_.contains("in the mediated Akka engine")))
    assert(report.text.count(_.contains("DECIDES: it is the leader")) == 1)
    val run = RunJson.read[CrState, CrMsg, CrAction, CrOutput](report.json).value
    assert(run.trace.collect { case TraceEvent.Started(_, e, _) =>
      e
    } == Vector(Engine.AkkaMediated))
    val alg = Registry.changRoberts
    val expected = alg.simulate(alg.spec(alg.preset("ring-6").value, Seed(7))).value.run
    assert(run.trace.drop(1) == expected.trace.drop(1))
    assert(report.json.endsWith("}\n"))
  }
}
