package cs553.core.codec

import cats.data.NonEmptyList
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.wave.EchoAction
import cs553.core.golden.GoldenFiles
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

  // Version 1 links, as the web wrote them. Do not change these strings: each shared URL holds one.
  // A failure here means that old links stop working or replay another run (coding rule 30).
  private val echoGoldenLink: String =
    "1.eyJhIjoiZWNobyIsInQiOiJpbnB1dEVjaG8iLCJvIjoiZiIsInAiOiIxIiwicyI6InEiLCJsIjoxMDAwLCJjIjpbWzIsMSx7Ildha2UiOnt9fV0sWzAsMSwyLDBdLFswLDEsNCwwXSxbMCw0LDUsMF0sWzAsMiw0LDBdLFswLDUsMSwwXSxbMCwyLDUsMF0sWzAsNCwyLDBdLFswLDEsNSwwXSxbMCw1LDQsMF0sWzAsNCwxLDBdLFswLDUsMiwwXSxbMCwyLDMsMF0sWzAsMywyLDBdLFswLDIsMSwwXV19"

  private val crGoldenLink: String =
    "1.eyJhIjoiY2hhbmctcm9iZXJ0cyIsInQiOiJyaW5nLTYiLCJvIjoiZiIsInAiOiIxIiwicyI6InEiLCJsIjoxMDAwLCJjIjpbWzIsMyx7Ildha2UiOnt9fV0sWzAsMyw0LDBdLFswLDQsNSwwXSxbMiwyLHsiV2FrZSI6e319XSxbMCw1LDAsMF0sWzAsMiwzLDBdLFswLDMsNCwxXSxbMCw0LDUsMV0sWzAsNSwwLDFdLFswLDAsMSwwXSxbMCwxLDIsMF0sWzAsMiwzLDFdLFswLDMsNCwyXSxbMCw0LDUsMl0sWzAsNSwwLDJdLFswLDAsMSwxXSxbMCwxLDIsMV1dfQ"

  private val inlineGoldenLink: String =
    "1.eyJhIjoiZWNobyIsInQiOnsibm9kZXMiOlsxLDIsM10sImVkZ2VzIjpbeyJmcm9tIjoxLCJ0byI6Mn0seyJmcm9tIjoxLCJ0byI6M30seyJmcm9tIjoyLCJ0byI6MX0seyJmcm9tIjoyLCJ0byI6M30seyJmcm9tIjozLCJ0byI6MX0seyJmcm9tIjozLCJ0byI6Mn1dLCJhdHRycyI6eyIxIjp7InJvbGUiOnsiSW5pdGlhdG9yIjp7fX0sInVpZCI6bnVsbCwiZXh0cmEiOnt9fSwiMiI6eyJyb2xlIjp7Ik5vbkluaXRpYXRvciI6e319LCJ1aWQiOm51bGwsImV4dHJhIjp7fX0sIjMiOnsicm9sZSI6eyJOb25Jbml0aWF0b3IiOnt9fSwidWlkIjpudWxsLCJleHRyYSI6e319fX0sIm8iOiJmIiwicCI6IjMiLCJzIjoicSIsImwiOjEwMDAsImMiOltbMiwxLHsiV2FrZSI6e319XSxbMCwxLDIsMF0sWzAsMSwzLDBdXX0"

  test("a committed version 1 preset link replays the committed golden trace") {
    val echo = Registry.echo.decodeLink(echoGoldenLink).value
    assert(Some(echo.spec) == Registry.echo.preset("inputEcho").map(Registry.echo.spec(_, Seed(1))))
    assert(echo.choices.headOption == Some(Choice.Fire(NodeId(1), EchoAction.Wake)))
    assert(echo.choices.size == 15)
    val cr = Registry.changRoberts.decodeLink(crGoldenLink).value
    assert(
      Some(cr.spec) ==
        Registry.changRoberts.preset("ring-6").map(Registry.changRoberts.spec(_, Seed(1)))
    )
    assert(cr.choices.lift(3) == Some(Choice.Fire(NodeId(2), CrAction.Wake)))
    assert(cr.choices.lift(1) == Some(Choice.Deliver(EnvelopeId(NodeId(3), NodeId(4), 0))))
    val committed = GoldenFiles.all.toMap
    val echoTrace =
      Registry.echo.replay(echo).map(s => Registry.echo.writeTracePretty(s.run) + "\n")
    val crTrace =
      Registry.changRoberts
        .replay(cr)
        .map(s => Registry.changRoberts.writeTracePretty(s.run) + "\n")
    assert(echoTrace.toOption == committed.get("echo/inputEcho-simulator-1.json"))
    assert(crTrace.toOption == committed.get("chang-roberts/ring-6-simulator-1.json"))
  }

  test("a committed version 1 link with an inline topology decodes to the same run") {
    val shared = Registry.echo.decodeLink(inlineGoldenLink).value
    assert(shared.spec.topology.nodes.toList == List(NodeId(1), NodeId(2), NodeId(3)))
    assert(shared.spec.topology.initiators.toList == List(NodeId(1)))
    assert(shared.spec.topology.edges.size == 6)
    assert(shared.spec.policy == Policy.Seeded(Seed(3)))
    assert(shared.spec.order == ChannelOrder.Fifo)
    assert(shared.spec.stop == StopRule.AtQuiescence)
    assert(shared.spec.stepLimit == 1000)
    assert(
      shared.choices == List(
        Choice.Fire(NodeId(1), EchoAction.Wake),
        Choice.Deliver(EnvelopeId(NodeId(1), NodeId(2), 0)),
        Choice.Deliver(EnvelopeId(NodeId(1), NodeId(3), 0))
      )
    )
    val replayed = Registry.echo.replay(shared).value
    assert(replayed.worlds.size == 4)
    assert(Registry.echo.encodeLink(shared) == inlineGoldenLink)
  }
}
