package cs553.core.algorithms.snapshot

import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.registry.LaiYangEntry
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.trace.ChannelRecord
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Outcome
import cs553.core.trace.Overlay
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Buffered
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.Simulation
import cs553.core.world.Simulator
import cs553.core.world.World
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedMap

final class LaiYangSpec
    extends AnyFunSuite
    with PropertyBudget
    with EitherValues
    with OptionValues {

  private type LySim = Simulation[LyState, LyMsg, LyAction, LyOutput]

  private val p = LaiYang.protocol

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.AnyOrder, ChannelOrder.Fifo)

  /** A strongly connected directed graph of 2 to 7 nodes: a random Hamiltonian cycle plus random
    * extra edges. Each node has a random balance and budget, and a random non-empty set of nodes
    * are initiators. The ids are plain or relabelled.
    */
  private val bankGraphPlain: Gen[Topology] = for {
    size <- Gen.choose(2, 7)
    keys <- Gen.listOfN(size, Gen.long)
    extraCount <- Gen.choose(0, 2 * size)
    extra <- Gen.listOfN(extraCount, Gen.zip(Gen.choose(0, size - 1), Gen.choose(0, size - 1)))
    balances <- Gen.listOfN(size, Gen.choose(0, 20))
    budgets <- Gen.listOfN(size, Gen.choose(0, 3))
    first <- Gen.choose(0, size - 1)
    flags <- Gen.listOfN(size, Gen.prob(0.3))
  } yield {
    val cycle = (0 until size).toList.zip(keys).sortBy(_._2).map(_._1)
    val ring = cycle.zip(cycle.drop(1) ++ cycle.take(1))
    val edges = (ring ++ extra.filter { case (a, b) => a != b }).map { case (a, b) =>
      (NodeId(a), NodeId(b))
    }
    val attrs = (0 until size).map { i =>
      val role = if i == first || flags.lift(i).contains(true) then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> NodeAttrs(role, None, Map.empty)
        .withExtra(NodeAttrs.Keys.balance, balances.lift(i).getOrElse(0).toString)
        .withExtra(NodeAttrs.Keys.budget, budgets.lift(i).getOrElse(0).toString)
    }.toMap
    Graphs.fixed(Topology.directed((0 until size).map(NodeId(_)), edges, attrs))
  }

  private val bankGraph: Gen[Topology] = for {
    plain <- bankGraphPlain
    moved <- Gen.oneOf(Gen.const(plain), Graphs.relabel(plain))
  } yield moved

  private def spec(
      topology: Topology,
      policy: Policy[LyAction],
      order: ChannelOrder = ChannelOrder.AnyOrder
  ): RunSpec[LyAction] = {
    RunSpec(LaiYang.id, topology, order, policy, StopRule.AtQuiescence, 100000)
  }

  private def simulate(topology: Topology, seed: Long, order: ChannelOrder): LySim = {
    Simulator.run(p, spec(topology, Policy.Seeded(Seed(seed)), order)).value
  }

  private def script(topology: Topology, choices: List[Choice[LyAction]]): LySim = {
    Simulator.replay(p, spec(topology, Policy.Script(choices)), choices).value
  }

  private def stateAt(sim: LySim, node: NodeId): Option[LyState] = {
    sim.last.flatMap(_.states.get(node))
  }

  /** The index of the first world in which the node is red. */
  private def cutStep(sim: LySim, node: NodeId): Option[Int] = {
    sim.worlds.indexWhere(_.states.get(node).exists(_.color == LyColor.Red)) match {
      case -1    => None
      case index => Some(index)
    }
  }

  private def sent(sim: LySim): Map[EnvelopeId, (Int, LyMsg)] = {
    sim.run.trace.collect { case TraceEvent.Sent(at, env) => env.id -> (at.step, env.msg) }.toMap
  }

  /** The delivered envelopes with the step of the delivery, in step order. */
  private def delivered(sim: LySim): List[(EnvelopeId, Int)] = {
    sim.run.trace.toList.collect { case TraceEvent.Stepped(at, _, Choice.Deliver(id), _, _, _) =>
      id -> at.step
    }
  }

  private def ring2: Topology = {
    Graphs.fixed(
      Topology.undirected(
        List(NodeId(0), NodeId(1)),
        List(NodeId(0) -> NodeId(1)),
        Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map.empty))
      )
    )
  }

  private def env(from: Int, to: Int, seq: Int): EnvelopeId = {
    EnvelopeId(NodeId(from), NodeId(to), seq)
  }

  private def deliver(from: Int, to: Int, seq: Int): Choice[LyAction] = {
    Choice.Deliver(env(from, to, seq))
  }

  private def spend(node: Int, to: Int, amount: Int): Choice[LyAction] = {
    Choice.Fire(NodeId(node), LyAction.Spend(NodeId(to), amount))
  }

  private val initiate0: Choice[LyAction] = Choice.Fire(NodeId(0), LyAction.Initiate)

  // -----------------------------------------------------------------------------------------------
  // Properties over random strongly connected graphs, seeds and both channel orders.

  test("property: each run ends quiescent with no Rejected event, and each node decides once") {
    forAll(bankGraph, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      assert(p.check(topology).isRight)
      val sim = simulate(topology, seed, order)
      assert(sim.run.outcome.contains(Outcome.Quiescent))
      assert(sim.run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
      assert(sim.run.decisions.map(_._1).sorted.toList == topology.nodes.toList)
      sim.last.foreach(_.states.values.foreach { s =>
        assert(s.phase match {
          case LyPhase.Done(_)                      => true
          case LyPhase.White | LyPhase.Recording(_) => false
        })
      })
    }
  }

  test("property: conservation in each world, and the snapshot total equals the initial total") {
    forAll(bankGraph, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      sim.worlds.foreach(w => assert(LaiYang.invariant(w) == None))
      val total = LaiYang.initialTotal(topology)
      assert(total.isDefined)
      assert(sim.last.flatMap(w => LaiYang.snapshotTotal(w.states.values)) == total)
      val decided = sim.run.decisions.map { case (_, LyOutput.Recorded(balance, channels)) =>
        balance + channels.values.flatten.sum
      }.sum
      assert(Some(decided) == total)
    }
  }

  test(
    "property: consistent cut: each node records its balance before it handles the step that " +
      "makes it red, and each channel records the white transfers that arrive after the cut"
  ) {
    forAll(bankGraph, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      sim.worlds.zipWithIndex.foreach { case (w, i) => assert(w.step == i) }
      val envelopes = sent(sim)
      val cuts = topology.nodes.toList.map(n => n -> cutStep(sim, n)).toMap
      topology.nodes.foreach { node =>
        val cut = cuts.getOrElse(node, None)
        assert(cut.exists(_ > 0))
        cut.foreach { k =>
          val before = sim.worlds.lift(k - 1).flatMap(_.states.get(node)).map(_.balance)
          assert(stateAt(sim, node).flatMap(_.record).map(_.balance) == before)
        }
      }
      // The color of a transfer tells if the sender sent it before or after its cut.
      envelopes.foreach {
        case (id, (step, LyMsg.Transfer(_, color))) =>
          val senderCut = cuts.getOrElse(id.from, None).getOrElse(Int.MaxValue)
          assert((color == LyColor.White) == (step < senderCut))
        case (_, (_, LyMsg.Control(_))) => ()
      }
      // A red transfer never arrives before the cut of the receiver: it makes the cut.
      delivered(sim).foreach { case (id, step) =>
        envelopes.get(id).map(_._2) match {
          case Some(LyMsg.Transfer(_, LyColor.Red)) =>
            assert(cuts.getOrElse(id.to, None).exists(_ <= step))
          case Some(LyMsg.Transfer(_, LyColor.White)) | Some(LyMsg.Control(_)) | None => ()
        }
      }
      // The recorded state of a channel is the white transfers that arrive after the cut.
      topology.edges.foreach { case Edge(from, to) =>
        val cut = cuts.getOrElse(to, None).getOrElse(Int.MaxValue)
        val expected = delivered(sim)
          .collect {
            case (id, step) if id.channel == Edge(from, to) && step > cut =>
              envelopes.get(id).map(_._2)
          }
          .flatten
          .collect { case LyMsg.Transfer(amount, LyColor.White) => amount }
        val recorded = stateAt(sim, to).flatMap(_.record).map(_.channels.getOrElse(from, Nil))
        assert(recorded.contains(expected))
      }
    }
  }

  test("property: each node records exactly once and sends one control message on each channel") {
    forAll(bankGraph, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      topology.nodes.foreach { node =>
        val colors = sim.worlds.flatMap(_.states.get(node)).map(_.color)
        val changes = colors.zip(colors.drop(1)).count { case (a, b) => a != b }
        assert(changes == 1)
        assert(colors.lastOption.contains(LyColor.Red))
      }
      val controls = sent(sim).toList.collect { case (id, (_, LyMsg.Control(_))) => id.channel }
      assert(controls.sorted == topology.edges.toList)
      val initiates = sim.run.trace.toList.collect {
        case TraceEvent.Stepped(_, node, Choice.Fire(_, LyAction.Initiate), _, _, _) => node
      }
      assert(initiates.distinct == initiates)
      assert(initiates.toSet.subsetOf(topology.initiators))
      assert(initiates.nonEmpty || topology.initiators.isEmpty)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Regression tests for the verified bugs of the college code.

  test("regression lai-yang-1: a red transfer makes a white node record before it handles it") {
    // Node 0 initiates and sends a red transfer. The transfer overtakes the control message.
    val sim = script(ring2, List(initiate0, spend(0, 1, 5), deliver(0, 1, 1)))
    val node1 = stateAt(sim, NodeId(1))
    assert(node1.map(_.balance).contains(15))
    assert(node1.flatMap(_.record).map(_.balance).contains(10))
    assert(node1.flatMap(_.record).map(_.trigger).contains(LyTrigger.RedTransfer))
    val last = sim.run.trace.collect { case TraceEvent.Stepped(_, _, _, rule, _, sends) =>
      (rule, sends)
    }.lastOption
    assert(last == Some(LaiYang.Rules.snapshotOnRed -> List(env(1, 0, 0))))
  }

  test("regression lai-yang-2: a node stays red after it is done, so its transfers stay red") {
    val done = List(initiate0, deliver(0, 1, 0), deliver(1, 0, 0))
    val sim = script(ring2, done ++ List(spend(1, 0, 5), deliver(1, 0, 1)))
    assert(sim.run.decisions.map(_._1).toList == List(NodeId(1), NodeId(0)))
    assert(sent(sim).get(env(1, 0, 1)).map(_._2).contains(LyMsg.Transfer(5, LyColor.Red)))
    sim.last.foreach { w =>
      assert(w.states.values.forall(_.color == LyColor.Red))
      assert(!World.enabled(p, w).contains(initiate0))
    }
    val rules = sim.run.trace.collect { case TraceEvent.Stepped(_, _, _, rule, _, _) => rule }
    assert(rules.takeRight(2) == Vector(LaiYang.Rules.spendRed, LaiYang.Rules.receiveRed))
  }

  test("regression lai-yang-3: a second control message or a second Initiate is rejected") {
    val ctx = NodeCtx.of(ring2, NodeId(0))
    val record = LyRecord(LyTrigger.Initiated, 10, SortedMap(NodeId(1) -> 0), SortedMap.empty)
    val base = p.init(ctx, ring2.attrsOf(NodeId(0))).value
    val recording = base.copy(phase = LyPhase.Recording(record))
    val done = base.copy(phase = LyPhase.Done(record))
    assert(p.onMessage(ctx, recording, NodeId(1), LyMsg.Control(0)).isLeft)
    assert(p.onMessage(ctx, done, NodeId(1), LyMsg.Control(0)).isLeft)
    assert(p.onAction(ctx, recording, LyAction.Initiate).isLeft)
    assert(p.onAction(ctx, done, LyAction.Initiate).isLeft)
    assert(!p.actions(ctx, done).contains(LyAction.Initiate))
  }

  test("regression lai-yang-4: a transfer takes from the sender and adds to the receiver") {
    val sim = script(ring2, List(spend(0, 1, 5), deliver(0, 1, 0)))
    assert(stateAt(sim, NodeId(0)).map(_.balance).contains(5))
    assert(stateAt(sim, NodeId(1)).map(_.balance).contains(15))
    assert(sent(sim).get(env(0, 1, 0)).map(_._2).contains(LyMsg.Transfer(5, LyColor.White)))
  }

  test(
    "regression lai-yang-7: the white transfer that closes the last channel is in the decision"
  ) {
    // The white transfer goes before the snapshot, and the control message overtakes it.
    val choices = List(
      spend(0, 1, 5),
      initiate0,
      deliver(0, 1, 1),
      deliver(1, 0, 0),
      deliver(0, 1, 0)
    )
    val sim = script(ring2, choices)
    assert(
      sim.run.decisions.toList == List(
        NodeId(0) -> LyOutput.Recorded(5, SortedMap.empty),
        NodeId(1) -> LyOutput.Recorded(10, SortedMap(NodeId(0) -> List(5)))
      )
    )
  }

  test("regression lai-yang-9: a spend to a node that is not an out-neighbour is rejected") {
    val bank = LaiYangEntry.algorithm.preset("bank-4").map(_.topology)
    bank.foreach { t =>
      assert(!t.isSymmetric && t.isStronglyConnected)
      val ctx = NodeCtx.of(t, NodeId(0))
      val state = p.init(ctx, t.attrsOf(NodeId(0))).value
      assert(
        p.onAction(ctx, state, LyAction.Spend(NodeId(3), 1)).left.value ==
          ProtocolError.NotANeighbour(NodeId(3))
      )
    }
    assert(bank.isDefined)
  }

  test("a white transfer after the control message that counted it is rejected") {
    val ctx = NodeCtx.of(ring2, NodeId(1))
    val base = p.init(ctx, ring2.attrsOf(NodeId(1))).value
    val record = LyRecord(LyTrigger.ControlMessage, 10, SortedMap(NodeId(0) -> 0), SortedMap.empty)
    val white = LyMsg.Transfer(1, LyColor.White)
    assert(p.onMessage(ctx, base.copy(phase = LyPhase.Done(record)), NodeId(0), white).isLeft)
    val recording = base.copy(phase = LyPhase.Recording(record))
    assert(p.onMessage(ctx, recording, NodeId(0), white).isLeft)
  }

  // -----------------------------------------------------------------------------------------------
  // Topology checks, the invariant, the overlay and the presets.

  test("check rejects a graph that is not strongly connected, no initiator and one node") {
    val oneWay = Graphs.fixed(
      Topology.directed(
        List(NodeId(0), NodeId(1)),
        List(NodeId(0) -> NodeId(1)),
        Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map.empty))
      )
    )
    assert(p.check(oneWay).isLeft)
    assert(p.check(Graphs.fixed(ring2.withInitiators(Set.empty))).isLeft)
    assert(p.check(Graphs.fixed(ring2.without(Set(NodeId(1))))).isLeft)
    assert(p.check(ring2).isRight)
  }

  test("init rejects a balance that is not a number and a negative budget") {
    val ctx = NodeCtx.of(ring2, NodeId(0))
    val text = NodeAttrs.default.withExtra(NodeAttrs.Keys.balance, "ten")
    assert(p.init(ctx, text).isLeft)
    assert(p.init(ctx, NodeAttrs.default.withExtra(NodeAttrs.Keys.budget, "-1")).isLeft)
    val parsed = p.init(ctx, NodeAttrs.default.withExtra(NodeAttrs.Keys.balance, "7")).value
    assert(parsed.balance == 7 && parsed.budget == LaiYang.defaultBudget)
  }

  test("the invariant holds on a run and gives one line on forged worlds") {
    val sim = script(ring2, List(spend(0, 1, 5), initiate0))
    val w = sim.last.value
    assert(LaiYang.invariant(w) == None)
    val lost: World[LyState, LyMsg] = w.copy(channels = w.channels.map { case (e, _) =>
      e -> Vector.empty[Buffered[LyMsg]]
    })
    assert(LaiYang.invariant(lost) == Some("Total 15 is not 20: a transfer was lost."))
    val wrong = LyRecord(LyTrigger.Initiated, 3, SortedMap.empty, SortedMap.empty)
    val forged: World[LyState, LyMsg] = w.copy(
      channels = lost.channels,
      states = w.states.map { case (n, s) =>
        n -> s.copy(balance = 10, phase = LyPhase.Done(wrong))
      }
    )
    assert(
      LaiYang.invariant(forged) ==
        Some("The snapshot total 6 is not 20: the cut is not consistent.")
    )
  }

  test("the overlay at the end of each preset run: red nodes, cuts, closed channels") {
    val alg = LaiYangEntry.algorithm
    assert(alg.presets.map(_.name) == List("bank-4", "netgraph-6", "ring-5"))
    alg.presets.foreach { preset =>
      val sim = alg.simulate(alg.spec(preset, Seed(3))).value
      val states = sim.last.map(_.states).getOrElse(SortedMap.empty[NodeId, LyState])
      val marks = Overlay[LyState].marks(states)
      val nodes = preset.topology.nodes.toList
      val cuts = marks.collect { case Mark.Cut(node) => node }
      assert(cuts == nodes, preset.name)
      val fills = marks.collect { case Mark.Fill(node, fill) => node -> fill }.toMap
      val initiated = states.collect {
        case (n, s) if s.record.exists(_.trigger == LyTrigger.Initiated) => n
      }.toSet
      assert(initiated.nonEmpty && initiated.subsetOf(preset.topology.initiators), preset.name)
      nodes.foreach { n =>
        val expected = if initiated.contains(n) then { NodeFill.Chosen }
        else { NodeFill.Red }
        assert(fills.get(n).contains(expected), preset.name)
      }
      val channels = marks.collect { case Mark.Channel(from, to, record, _) =>
        assert(record == ChannelRecord.Closed)
        Edge(from, to)
      }
      assert(channels.sorted == preset.topology.edges.toList, preset.name)
    }
  }

  test("the overlay of a white node and of a recording node") {
    val sim = script(ring2, List(initiate0))
    val marks = Overlay[LyState].marks(sim.last.value.states)
    assert(
      marks == List(
        Mark.Fill(NodeId(0), NodeFill.Red),
        Mark.Cut(NodeId(0)),
        Mark.Badge(NodeId(0), "0 of 1 closed"),
        Mark.Channel(NodeId(1), NodeId(0), ChannelRecord.Recording, Nil),
        Mark.Fill(NodeId(1), NodeFill.White)
      )
    )
  }

  test("the ring-5 preset has two initiators, and some seeds start two snapshots at once") {
    val alg = LaiYangEntry.algorithm
    val preset = alg.preset("ring-5").value
    assert(preset.topology.initiators.map(_.value).toList == List(0, 2))
    val counts = (1L to 20L).map { seed =>
      alg.simulate(alg.spec(preset, Seed(seed))).value.run.trace.count {
        case TraceEvent.Stepped(_, _, Choice.Fire(_, LyAction.Initiate), _, _, _) => true
        case _                                                                    => false
      }
    }
    assert(counts.contains(2))
    assert(counts.forall(c => c == 1 || c == 2))
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    assert(Inspect[LyMsg].label(LyMsg.Transfer(5, LyColor.Red)) == "Transfer(5, red)")
    assert(Inspect[LyMsg].label(LyMsg.Control(2)) == "Control(2)")
    assert(Inspect[LyAction].label(LyAction.Spend(NodeId(3), 5)) == "Spend(5 to 3)")
    assert(Inspect[LyAction].label(LyAction.Initiate) == "Initiate")
    assert(
      Inspect[LyOutput].label(LyOutput.Recorded(10, SortedMap(NodeId(0) -> List(5, 1)))) ==
        "recorded balance 10, channels 0: 5 + 1"
    )
    val ctx = NodeCtx.of(ring2, NodeId(0))
    val white = p.init(ctx, ring2.attrsOf(NodeId(0))).value
    assert(Inspect[LyState].label(white) == "white, balance 10")
    val record = LyRecord(LyTrigger.Initiated, 10, SortedMap.empty, SortedMap.empty)
    assert(
      Inspect[LyState].label(white.copy(phase = LyPhase.Recording(record))) ==
        "recording, recorded 10"
    )
    assert(Inspect[LyState].fields(white).take(2) == List("phase" -> "white", "color" -> "white"))
  }
}
