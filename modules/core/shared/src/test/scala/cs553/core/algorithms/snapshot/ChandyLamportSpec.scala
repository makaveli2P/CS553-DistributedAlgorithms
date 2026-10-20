package cs553.core.algorithms.snapshot

import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.registry.ChandyLamportEntry
import cs553.core.rng.Seed
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError
import cs553.core.trace.ChannelRecord
import cs553.core.trace.Choice
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.RunError
import cs553.core.world.Simulation
import cs553.core.world.Simulator
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedSet

final class ChandyLamportSpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private type ClSim = Simulation[ClState, ClMsg, ClAction, ClOutput]

  private def run(topology: Topology, seed: Long): ClSim = {
    val spec = RunSpec(
      ChandyLamport.id,
      topology,
      ChannelOrder.Fifo,
      Policy.Seeded(Seed(seed)),
      StopRule.AtQuiescence,
      100000
    )
    Simulator.run(ChandyLamport.protocol, spec).value
  }

  private def attrs(role: Role, balance: Int, budget: Int): NodeAttrs = {
    NodeAttrs(role, None, Map.empty)
      .withExtra(NodeAttrs.Keys.balance, balance.toString)
      .withExtra(NodeAttrs.Keys.budget, budget.toString)
  }

  /** A strongly connected directed graph of 2 to 8 nodes: a directed cycle through all nodes in a
    * random order, plus 0 to n random extra edges. Each node has a random balance and budget, and a
    * random non-empty set of nodes are initiators.
    */
  private val bankPlain: Gen[Topology] = for {
    size <- Gen.choose(2, 8)
    keys <- Gen.listOfN(size, Gen.long)
    extraCount <- Gen.choose(0, size)
    extra <- Gen.listOfN(extraCount, Gen.zip(Gen.choose(0, size - 1), Gen.choose(0, size - 1)))
    balances <- Gen.listOfN(size, Gen.choose(0, 15))
    budgets <- Gen.listOfN(size, Gen.choose(0, 4))
    first <- Gen.choose(0, size - 1)
    flags <- Gen.listOfN(size, Gen.prob(0.3))
  } yield {
    val cycle = (0 until size).toList.zip(keys).sortBy(_._2).map(_._1)
    val cycleEdges = cycle.zip(cycle.drop(1) ++ cycle.take(1))
    val edges = (cycleEdges ++ extra.filter { case (a, b) => a != b }).map { case (a, b) =>
      (NodeId(a), NodeId(b))
    }
    val nodeAttrs = (0 until size).map { i =>
      val role = if i == first || flags.lift(i).contains(true) then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> attrs(role, balances.lift(i).getOrElse(0), budgets.lift(i).getOrElse(0))
    }.toMap
    Graphs.fixed(Topology.directed((0 until size).map(NodeId(_)), edges, nodeAttrs))
  }

  private val banks: Gen[Topology] = for {
    plain <- bankPlain
    moved <- Gen.oneOf(Gen.const(plain), Graphs.relabel(plain))
  } yield moved

  /** The step of the local snapshot of each node. */
  private def cutSteps(sim: ClSim): Map[NodeId, Int] = {
    sim.run.trace.foldLeft(Map.empty[NodeId, Int]) {
      case (acc, TraceEvent.Stepped(at, node, _, _, after, _))
          if after.phase != ClPhase.Waiting && !acc.contains(node) =>
        acc.updated(node, at.step)
      case (acc, _) => acc
    }
  }

  /** Each transfer: its channel, seq, amount, send step and delivery step. */
  private final case class Transfer(
      from: NodeId,
      to: NodeId,
      seq: Int,
      amount: Int,
      sent: Int,
      received: Option[Int]
  )

  private def transfers(sim: ClSim): List[Transfer] = {
    val delivered = sim.run.trace.collect {
      case TraceEvent.Stepped(at, _, Choice.Deliver(id), _, _, _) => id -> at.step
    }.toMap
    sim.run.trace.toList.collect {
      case TraceEvent.Sent(at, env) if env.msg != ClMsg.Marker =>
        val amount = env.msg match {
          case ClMsg.Transfer(a) => a
          case ClMsg.Marker      => 0
        }
        Transfer(env.id.from, env.id.to, env.id.seq, amount, at.step, delivered.get(env.id))
    }
  }

  private def finalPhases(sim: ClSim): Map[NodeId, ClPhase] = {
    sim.last.map(_.states.map { case (n, s) => n -> s.phase }.toMap).getOrElse(Map.empty)
  }

  private def records(phase: ClPhase): List[ChannelLog] = phase match {
    case ClPhase.Waiting                => Nil
    case ClPhase.Recording(_, channels) => channels
    case ClPhase.Recorded(_, channels)  => channels
  }

  // -----------------------------------------------------------------------------------------------
  // Properties over random strongly connected graphs and seeds. The order is locked to Fifo.

  test("property: each run ends quiescent, each node decides once, and no step is Rejected") {
    forAll(banks, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = run(topology, seed)
      assert(sim.run.outcome.contains(Outcome.Quiescent))
      assert(sim.run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
      assert(sim.run.decisions.map(_._1).sorted.toList == topology.nodes.toList)
    }
  }

  test("property: the saved balances plus the recorded channels equal the initial total") {
    forAll(banks, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = run(topology, seed)
      val total = sim.run.decisions.map { case (_, ClOutput.Snapshot(balance, channels)) =>
        balance + channels.flatMap(_.amounts).sum
      }.sum
      assert(total == ChandyLamport.initialTotal(topology))
    }
  }

  test("property: the cut is consistent, and each channel records exactly the transfers in it") {
    forAll(banks, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = run(topology, seed)
      val cut = cutSteps(sim)
      val all = transfers(sim)
      all.foreach { t =>
        val afterCut = cut.get(t.from).forall(t.sent > _)
        val beforeCut = t.received.exists(r => cut.get(t.to).exists(r < _))
        assert(!(afterCut && beforeCut), s"$t was sent after the cut and received before it")
      }
      val phases = finalPhases(sim)
      topology.edges.foreach { e =>
        val expected = all
          .filter(t => t.from == e.from && t.to == e.to)
          .filter(t => cut.get(e.from).exists(t.sent < _))
          .filter(t => t.received.exists(r => cut.get(e.to).exists(r > _)))
          .sortBy(_.seq)
          .map(_.amount)
        val got = phases.get(e.to).toList.flatMap(records).find(_.sender == e.from)
        assert(got == Some(ChannelLog.Closed(e.from, expected)), s"channel $e")
      }
    }
  }

  test("property: the channel of the first marker records empty") {
    forAll(banks, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = run(topology, seed)
      val phases = finalPhases(sim)
      sim.run.trace.foreach {
        case TraceEvent.Stepped(
              _,
              node,
              Choice.Deliver(id),
              ChandyLamport.Rules.firstMarker,
              _,
              _
            ) =>
          val got = phases.get(node).toList.flatMap(records).find(_.sender == id.from)
          assert(got == Some(ChannelLog.Closed(id.from, Nil)), s"node $node")
        case _ => ()
      }
    }
  }

  test("property: a node records only its incoming channels, and it takes its snapshot once") {
    forAll(banks, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = run(topology, seed)
      finalPhases(sim).foreach { case (node, phase) =>
        assert(records(phase).map(_.sender) == topology.inNeighbours(node).toList, s"node $node")
      }
      val starts = sim.run.trace.toList.collect {
        case TraceEvent.Stepped(_, node, _, rule, _, _)
            if rule == ChandyLamport.Rules.initiate || rule == ChandyLamport.Rules.firstMarker =>
          node
      }
      assert(starts.sorted == topology.nodes.toList)
    }
  }

  test("property: the invariant holds in each world of each run") {
    forAll(banks, Gen.long) { (topology: Topology, seed: Long) =>
      run(topology, seed).worlds.foreach(w => assert(ChandyLamport.invariant(w) == None))
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Regression tests for the verified bugs of the college code.

  private val node1 =
    NodeCtx(NodeId(1), SortedSet(NodeId(0), NodeId(5)), SortedSet(NodeId(0), NodeId(5)))

  private val waiting1 = ClState(Role.NonInitiator, 10, 3, ClPhase.Waiting)

  test("regression: a non-initiator never offers Initiate, and onAction refuses it") {
    val p = ChandyLamport.protocol
    assert(!p.actions(node1, waiting1).contains(ClAction.Initiate))
    assert(p.onAction(node1, waiting1, ClAction.Initiate).isLeft)
    val initiator = waiting1.copy(role = Role.Initiator)
    assert(p.actions(node1, initiator).contains(ClAction.Initiate))
  }

  test("regression: a transfer always changes the balance (the college \"Increment\" bug)") {
    val p = ChandyLamport.protocol
    val got = p.onMessage(node1, waiting1, NodeId(0), ClMsg.Transfer(4)).value
    assert(got.state.balance == 14)
    val spent = p.onAction(node1, waiting1, ClAction.Spend(NodeId(5), 3)).value
    assert(spent.state.balance == 7 && spent.state.budget == 2)
    assert(spent.sends.map(_.msg) == List(ClMsg.Transfer(3)))
  }

  test("regression: the channel of the first marker records empty, also after a later transfer") {
    // The college topology 0 - 1 - 5: node 0 takes its snapshot, sends the marker to 1, and then
    // sends a transfer to 1. The transfer is a post-snapshot message, so 1 must not record it.
    val p = ChandyLamport.protocol
    val marked = p.onMessage(node1, waiting1, NodeId(0), ClMsg.Marker).value
    assert(marked.sends.map(_.msg) == List(ClMsg.Marker, ClMsg.Marker))
    val late = p.onMessage(node1, marked.state, NodeId(0), ClMsg.Transfer(2)).value
    val inTransit = p.onMessage(node1, late.state, NodeId(5), ClMsg.Transfer(4)).value
    assert(late.state.balance == 12)
    assert(
      inTransit.state.phase == ClPhase.Recording(
        10,
        List(ChannelLog.Closed(NodeId(0), Nil), ChannelLog.Open(NodeId(5), List(4)))
      )
    )
    val done = p.onMessage(node1, inTransit.state, NodeId(5), ClMsg.Marker).value
    assert(
      done.decision == Some(
        ClOutput.Snapshot(
          10,
          List(RecordedChannel(NodeId(0), Nil), RecordedChannel(NodeId(5), List(4)))
        )
      )
    )
  }

  test("regression: a node never takes a second snapshot") {
    val p = ChandyLamport.protocol
    val start = ClState(Role.Initiator, 10, 0, ClPhase.Waiting)
    assert(p.actions(node1, start) == List(ClAction.Initiate))
    val initiated = p.onAction(node1, start, ClAction.Initiate).value
    assert(p.actions(node1, initiated.state).isEmpty)
    assert(p.onAction(node1, initiated.state, ClAction.Initiate).isLeft)
    val one = p.onMessage(node1, initiated.state, NodeId(0), ClMsg.Marker).value
    assert(one.sends.isEmpty)
    assert(p.onMessage(node1, one.state, NodeId(0), ClMsg.Marker).left.value match {
      case ProtocolError.UnexpectedMessage(from, _) => from == NodeId(0)
      case _                                        => false
    })
    val two = p.onMessage(node1, one.state, NodeId(5), ClMsg.Marker).value
    assert(two.sends.isEmpty && two.decision.nonEmpty)
    assert(p.onMessage(node1, two.state, NodeId(5), ClMsg.Marker).isLeft)
  }

  // -----------------------------------------------------------------------------------------------
  // Input checks, presets, overlay and invariant.

  test("the order is locked to Fifo") {
    val preset = ChandyLamportEntry.algorithm.presets.headOption.map(_.topology)
    preset.foreach { topology =>
      val spec = RunSpec(
        ChandyLamport.id,
        topology,
        ChannelOrder.AnyOrder,
        Policy.Seeded(Seed(1)),
        StopRule.AtQuiescence,
        1000
      )
      val result = Simulator.run(ChandyLamport.protocol, spec)
      assert(
        result.left.toOption.contains(
          RunError.OrderLocked(ChannelOrder.Fifo, ChannelOrder.AnyOrder)
        )
      )
    }
    assert(preset.nonEmpty)
  }

  test("the protocol refuses a graph that is not strongly connected, or a bad balance") {
    val line = Graphs.fixed(
      Topology.directed(
        List(NodeId(0), NodeId(1)),
        List(NodeId(0) -> NodeId(1)),
        Graphs.initiatorAttrs
      )
    )
    assert(ChandyLamport.protocol.check(line).isLeft)
    val ctx = NodeCtx(NodeId(0), SortedSet(NodeId(1)), SortedSet(NodeId(1)))
    val bad = NodeAttrs.default.withExtra(NodeAttrs.Keys.balance, "ten")
    assert(
      ChandyLamport.protocol.init(ctx, bad) ==
        Left(TopologyError.BadAttribute(NodeId(0), "balance", "ten"))
    )
    val negative = NodeAttrs.default.withExtra(NodeAttrs.Keys.budget, "-1")
    assert(ChandyLamport.protocol.init(ctx, negative).isLeft)
    assert(
      ChandyLamport.protocol.init(ctx, NodeAttrs.default) ==
        Right(ClState(Role.NonInitiator, 10, 3, ClPhase.Waiting))
    )
  }

  test("the presets: bank-4 first, ring-5 with two initiators, and the college network") {
    val presets = ChandyLamportEntry.algorithm.presets
    assert(presets.map(_.name) == List("bank-4", "ring-5", "netgraph-13"))
    presets.foreach(p => assert(p.topology.isStronglyConnected, p.name))
    assert(ChandyLamportEntry.algorithm.preset("ring-5").map(_.topology.initiators.size) == Some(2))
    assert(
      ChandyLamportEntry.algorithm
        .preset("bank-4")
        .map(p => ChandyLamport.initialTotal(p.topology)) == Some(50)
    )
  }

  test("bank-4: some seeds record money in transit") {
    val topology = ChandyLamportEntry.algorithm.preset("bank-4").map(_.topology)
    val inTransit = (1L to 20L).flatMap { seed =>
      topology.toList.flatMap(t => run(t, seed).run.decisions.toList).collect {
        case (_, ClOutput.Snapshot(_, channels)) => channels.flatMap(_.amounts).sum
      }
    }
    assert(inTransit.exists(_ > 0))
  }

  test("the overlay at the end of a bank-4 run: a cut, a badge and closed channels everywhere") {
    val alg = ChandyLamportEntry.algorithm
    alg.preset("bank-4").foreach { preset =>
      val sim = run(preset.topology, 3)
      val states = sim.last.map(_.states).getOrElse(Map.empty)
      val marks = alg.overlay.marks(scala.collection.immutable.SortedMap.from(states))
      assert(marks.collect { case Mark.Cut(n) => n }.sorted == preset.topology.nodes.toList)
      // The initiator decided, so no "initiator" badge is left. The label shows the saved balance.
      assert(marks.collect { case Mark.Badge(n, _) => n }.isEmpty)
      assert(marks.collect { case Mark.Fact(t) => t }.exists(_.startsWith("snapshot total ")))
      val channels = marks.collect { case Mark.Channel(from, to, record, _) => (from, to, record) }
      assert(channels.size == preset.topology.edges.size)
      assert(channels.forall(_._3 == ChannelRecord.Closed))
      assert(
        marks.collect { case Mark.Fill(n, NodeFill.Chosen) =>
          n
        } == preset.topology.initiators.toList
      )
    }
  }

  test("the invariant holds on a run, and it gives a line on forged worlds") {
    val alg = ChandyLamportEntry.algorithm
    alg.preset("bank-4").foreach { preset =>
      val sim = run(preset.topology, 5)
      assert(sim.worlds.forall(w => ChandyLamport.invariant(w) == None))
      sim.last.foreach { w =>
        val node = NodeId(0)
        val forged = w.states.get(node).map { s =>
          w.copy(states = w.states.updated(node, s.copy(balance = s.balance + 5)))
        }
        assert(forged.flatMap(ChandyLamport.invariant).exists(_.startsWith("Total 55 is not 50")))
        val wrongCut = w.states.get(node).map { s =>
          val phase = s.phase match {
            case ClPhase.Recorded(saved, channels) => ClPhase.Recorded(saved + 1, channels)
            case other                             => other
          }
          w.copy(states = w.states.updated(node, s.copy(phase = phase)))
        }
        assert(wrongCut.flatMap(ChandyLamport.invariant).exists(_.contains("not consistent")))
        val foreign = w.states.get(node).map { s =>
          val phase = ClPhase.Recorded(0, List(ChannelLog.Closed(NodeId(1), Nil)))
          w.copy(states = w.states.updated(node, s.copy(phase = phase)))
        }
        assert(foreign.flatMap(ChandyLamport.invariant).exists(_.contains("not incoming")))
      }
    }
  }
}
