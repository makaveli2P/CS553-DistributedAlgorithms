package cs553.core.codec

import cats.data.NonEmptyList
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.wave.EchoAction
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.topology.Preset
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

final class RunLinkSpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private val nodeIds: Gen[NodeId] = Gen.choose(0, 120).map(NodeId(_))

  private val envelopeIds: Gen[EnvelopeId] = for {
    from <- nodeIds
    to <- nodeIds
    seq <- Gen.choose(0, 5000)
  } yield EnvelopeId(from, to, seq)

  private def choices[A](actions: Gen[A]): Gen[Choice[A]] = Gen.oneOf(
    envelopeIds.map(Choice.Deliver(_)),
    envelopeIds.map(Choice.Drop(_)),
    Gen.zip(nodeIds, actions).map { case (n, a) => Choice.Fire(n, a) }
  )

  private def sharedRuns[A](
      algorithm: AlgorithmId,
      topologies: Gen[Topology],
      actions: Gen[A]
  ): Gen[SharedRun[A]] = for {
    topology <- topologies
    order <- Gen.oneOf(ChannelOrder.values.toList)
    policy <- Gen.oneOf(
      Gen.long.map(n => Policy.Seeded(Seed(n))),
      Gen.listOf(choices(actions)).map(Policy.Script(_))
    )
    stop <- Gen.oneOf(StopRule.values.toList)
    limit <- Gen.choose(0, 100000)
    taken <- Gen.listOf(choices(actions))
  } yield SharedRun(RunSpec(algorithm, topology, order, policy, stop, limit), taken)

  private val echoPresets: List[Preset] = Registry.echo.presets
  private val crPresets: List[Preset] = Registry.changRoberts.presets

  private def presetOrAny(presets: List[Preset], other: Gen[Topology]): Gen[Topology] = {
    presets match {
      case Nil  => other
      case list => Gen.oneOf(Gen.oneOf(list.map(_.topology)), other)
    }
  }

  test("an Echo link round-trips, with a preset or an inline topology") {
    val runs = sharedRuns(
      Registry.echo.id,
      presetOrAny(echoPresets, Graphs.connected),
      Gen.const(EchoAction.Wake)
    )
    forAll(runs) { (shared: SharedRun[EchoAction]) =>
      val text = Registry.echo.encodeLink(shared)
      assert(Registry.echo.decodeLink(text) == Right(shared))
      assert(RunLink.algorithmOf(text) == Right(Registry.echo.id))
    }
  }

  test("a Chang-Roberts link round-trips, with a preset or an inline topology") {
    val runs = sharedRuns(
      Registry.changRoberts.id,
      presetOrAny(crPresets, Graphs.ringWithUids),
      Gen.const(CrAction.Wake)
    )
    forAll(runs) { (shared: SharedRun[CrAction]) =>
      assert(
        Registry.changRoberts.decodeLink(Registry.changRoberts.encodeLink(shared)) == Right(shared)
      )
    }
  }

  test("the link text has the version prefix and only URL-safe characters") {
    forAll(sharedRuns(Registry.echo.id, Graphs.connected, Gen.const(EchoAction.Wake))) {
      (shared: SharedRun[EchoAction]) =>
        val text = Registry.echo.encodeLink(shared)
        assert(text.startsWith("1."))
        assert(text.matches("1\\.[A-Za-z0-9_-]+"))
    }
  }

  test("a link on a preset holds the preset name, so it stays short") {
    Registry.echo.defaultSpec.foreach { spec =>
      val wake = Choice.Fire(NodeId(1), EchoAction.Wake)
      val text = Registry.echo.encodeLink(SharedRun(spec, List(wake)))
      assert(text.length < 120, text)
    }
  }

  test("a leading # is ignored") {
    Registry.echo.defaultSpec.foreach { spec =>
      val shared = SharedRun(spec, Nil)
      assert(Registry.echo.decodeLink("#" + Registry.echo.encodeLink(shared)) == Right(shared))
    }
  }

  test("text that is not a link gives NotALink") {
    assert(
      Registry.echo.decodeLink("hello").left.value ==
        NonEmptyList.one(LinkError.NotALink("expected <version>.<payload>"))
    )
  }

  test("an unknown version gives UnsupportedVersion") {
    assert(
      Registry.echo.decodeLink("9.e30").left.value ==
        NonEmptyList.one(LinkError.UnsupportedVersion("9"))
    )
  }

  test("a payload that is not base64url gives NotBase64") {
    val errors = Registry.echo.decodeLink("1.***").left.value.toList
    assert(errors.collectFirst { case LinkError.NotBase64(detail) => detail }.nonEmpty, errors)
  }

  test("a link for Echo does not open as Chang-Roberts") {
    Registry.echo.defaultSpec.foreach { spec =>
      val text = Registry.echo.encodeLink(SharedRun(spec, Nil))
      val errors = Registry.changRoberts.decodeLink(text).left.value.toList
      assert(errors.contains(LinkError.WrongAlgorithm(Registry.changRoberts.id, Registry.echo.id)))
      assert(errors.contains(LinkError.UnknownPreset("inputEcho")))
    }
  }

  test("each field error is reported, not only the first") {
    val json = """{"a":"echo","t":"nowhere","o":"x","p":"seed","s":"z","l":"many","c":[[7]]}"""
    val body = java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val errors = Registry.echo.decodeLink(s"1.$body").left.value
    assert(errors.size == 6, errors)
  }
}
