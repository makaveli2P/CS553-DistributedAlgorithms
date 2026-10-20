package cs553.core.algorithms.election

import cs553.core.ids.NodeId
import cs553.core.ids.Uid
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Send
import cs553.core.registry.FranklinEntry
import cs553.core.registry.Presets
import cs553.core.rng.Seed
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.trace.Chip
import cs553.core.trace.Inspect
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
import cs553.core.world.World
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite

final class FranklinSpec
    extends AnyFunSuite
    with PropertyBudget
    with EitherValues
    with OptionValues {

  private type FrSim = Simulation[FrState, FrMsg, FrAction, FrOutput]

  private val p = Franklin.protocol

  /** A bidirectional ring of 3 to 12 nodes with distinct uids and a random non-empty set of
    * initiators, in the plain form or with sparse ids in a random order.
    */
  private val ringPlain: Gen[Topology] = for {
    size <- Gen.choose(3, 12)
    uids <- Gen.pick(size, 1 to 99)
    keys <- Gen.listOfN(size, Gen.long)
    first <- Gen.choose(0, size - 1)
    flags <- Gen.listOfN(size, Gen.prob(0.5))
  } yield {
    val shuffled = uids.toList.zip(keys).sortBy(_._2).map(_._1)
    val initiators = flags.zipWithIndex.collect { case (true, i) => i }.toSet + first
    Graphs.fixed(Topology.bidirectionalRing(size, Presets.ringAttrs(shuffled, initiators)))
  }

  private val rings: Gen[Topology] = for {
    plain <- ringPlain
    moved <- Gen.oneOf(Gen.const(plain), Graphs.relabel(plain))
  } yield moved

  private def spec(topology: Topology, policy: Policy[FrAction]): RunSpec[FrAction] = {
    RunSpec(Franklin.id, topology, ChannelOrder.Fifo, policy, StopRule.AtQuiescence, 100000)
  }

  private def simulate(topology: Topology, seed: Long): FrSim = {
    Simulator.run(p, spec(topology, Policy.Seeded(Seed(seed)))).value
  }

  private def ring(uids: List[Int], initiators: Set[Int]): Topology = {
    Graphs.fixed(Topology.bidirectionalRing(uids.size, Presets.ringAttrs(uids, initiators)))
  }

  private def maxInitiatorUid(topology: Topology): Option[Uid] = {
    topology.initiators.toList.flatMap(n => topology.attrsOf(n).uid).maxOption
  }

  private def sent(sim: FrSim): List[FrMsg] = sim.run.trace.toList.collect {
    case TraceEvent.Sent(_, env) => env.msg
  }

  private def idCount(sim: FrSim): Int = sent(sim).count {
    case FrMsg.Id(_, _)  => true
    case FrMsg.Leader(_) => false
  }

  private def leaderCount(sim: FrSim): Int = sent(sim).size - idCount(sim)

  /** For each round: the nodes that were active in that round in some world of the run. */
  private def activeByRound(sim: FrSim): Map[Int, Set[NodeId]] = {
    sim.worlds.toList
      .flatMap(_.states.toList.collect { case (node, FrState.Active(_, round, _)) =>
        (round, node)
      })
      .groupMap(_._1)(_._2)
      .map { case (round, nodes) => round -> nodes.toSet }
  }

  private def rounds(sim: FrSim): Int = activeByRound(sim).keys.maxOption.getOrElse(0)

  private def elected(sim: FrSim): List[(NodeId, Uid)] = sim.run.decisions.toList.collect {
    case (node, FrOutput.Elected(uid)) => (node, uid)
  }

  // -----------------------------------------------------------------------------------------------
  // Properties over random bidirectional rings, random initiator sets and seeds. The order is
  // locked to Fifo, so Fifo is the only order to test.

  test("property: each run ends quiescent, with no Rejected event") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      assert(p.check(topology).isRight)
      val sim = simulate(topology, seed)
      assert(sim.run.outcome.contains(Outcome.Quiescent))
      assert(sim.run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
    }
  }

  test("property: exactly one leader, and it is the initiator with the maximum uid") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = simulate(topology, seed)
      val leaders = elected(sim)
      assert(leaders.size == 1)
      assert(leaders.map(_._2).headOption == maxInitiatorUid(topology))
      leaders.foreach { case (node, uid) => assert(topology.attrsOf(node).uid.contains(uid)) }
    }
  }

  test("property: the run has at most floor(log2 n) + 1 rounds") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = simulate(topology, seed)
      val n = topology.nodes.size
      val bound = (math.log(n.toDouble) / math.log(2.0) + 1e-9).toInt + 1
      assert(Franklin.maxRounds(n) == bound)
      assert(rounds(sim) >= 1)
      assert(rounds(sim) <= bound)
      assert(rounds(sim) <= Franklin.maxRounds(topology.initiators.size))
    }
  }

  test("property: the active count at least halves in each round, and the last round has one") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = simulate(topology, seed)
      val byRound = activeByRound(sim)
      val last = rounds(sim)
      assert(byRound.get(1).contains(topology.initiators.toSet))
      (1 until last).foreach { r =>
        val now = byRound.getOrElse(r, Set.empty).size
        val next = byRound.getOrElse(r + 1, Set.empty).size
        assert(now >= 2)
        assert(next <= now / 2, s"round $r has $now, round ${r + 1} has $next")
        assert(byRound.getOrElse(r + 1, Set.empty).subsetOf(byRound.getOrElse(r, Set.empty)))
      }
      assert(byRound.get(last).map(_.toList) == Some(elected(sim).map(_._1)))
    }
  }

  test("property: all nodes learn the leader, and each node decides exactly once") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = simulate(topology, seed)
      val expected = maxInitiatorUid(topology)
      assert(sim.run.decisions.map(_._1).sorted.toList == topology.nodes.toList)
      assert(sim.run.decisions.forall {
        case (_, FrOutput.Elected(uid))    => expected.contains(uid)
        case (_, FrOutput.Follows(leader)) => expected.contains(leader)
      })
      sim.last.toList.flatMap(_.states.values).foreach {
        case FrState.Leader(uid)         => assert(expected.contains(uid))
        case FrState.Follower(_, leader) => assert(expected.contains(leader))
        case other                       => fail(s"a node ends in the state $other")
      }
    }
  }

  test("property: exactly 2n Id messages in each round and n Leader messages") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = simulate(topology, seed)
      val n = topology.nodes.size
      assert(idCount(sim) == 2 * n * rounds(sim))
      assert(leaderCount(sim) == n)
    }
  }

  test("property: the invariant holds in each world of each run") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      simulate(topology, seed).worlds.foreach(w => assert(Franklin.invariant(w) == None))
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Examples.

  test("ring-8: uid 8 wins in round 3, and the active counts are 8, 3 and 1") {
    val topology = ring(List(3, 7, 1, 8, 5, 2, 6, 4), (0 until 8).toSet)
    (1L to 10L).foreach { seed =>
      val sim = simulate(topology, seed)
      assert(elected(sim) == List(NodeId(3) -> Uid(8)))
      val counts = activeByRound(sim).toList.sortBy(_._1).map(_._2.size)
      assert(counts == List(8, 3, 1))
    }
  }

  test("the order is locked to Fifo: a run with AnyOrder is an error") {
    val topology = ring(List(3, 1, 2), Set(0, 1, 2))
    val anyOrder = spec(topology, Policy.Seeded(Seed(1L))).copy(order = ChannelOrder.AnyOrder)
    assert(
      Simulator.run(p, anyOrder).left.value ==
        RunError.OrderLocked(ChannelOrder.Fifo, ChannelOrder.AnyOrder)
    )
  }

  test("an initiator that a message wakes joins round 1 and sends its uid both ways") {
    val topology = ring(List(5, 9, 2), Set(0, 1, 2))
    val ctx = NodeCtx.of(topology, NodeId(1))
    val step = p.onMessage(ctx, FrState.Asleep(Uid(9)), NodeId(0), FrMsg.Id(Uid(5), 1)).value
    assert(step.state == FrState.Active(Uid(9), 1, List(Heard(NodeId(0), Uid(5), 1))))
    assert(
      step.sends.toSet == Set(
        Send(NodeId(0), FrMsg.Id(Uid(9), 1)),
        Send(NodeId(2), FrMsg.Id(Uid(9), 1))
      )
    )
    assert(step.rule == Franklin.Rules.wakeByMessage)
  }

  test("a survivor keeps an early message of the next round and uses it after it advances") {
    val topology = ring(List(5, 9, 2, 1, 3), Set(0, 1, 2, 3, 4))
    val ctx = NodeCtx.of(topology, NodeId(1))
    val start = FrState.Active(Uid(9), 1, Nil)
    val a = p.onMessage(ctx, start, NodeId(0), FrMsg.Id(Uid(5), 1)).value
    val b = p.onMessage(ctx, a.state, NodeId(0), FrMsg.Id(Uid(7), 2)).value
    assert(b.rule == Franklin.Rules.record)
    assert(b.sends.isEmpty)
    val c = p.onMessage(ctx, b.state, NodeId(2), FrMsg.Id(Uid(2), 1)).value
    assert(c.rule == Franklin.Rules.survive)
    assert(c.state == FrState.Active(Uid(9), 2, List(Heard(NodeId(0), Uid(7), 2))))
    assert(
      c.sends.toSet == Set(
        Send(NodeId(0), FrMsg.Id(Uid(9), 2)),
        Send(NodeId(2), FrMsg.Id(Uid(9), 2))
      )
    )
  }

  test("a defeated candidate relays the early message that it holds to the other side") {
    val topology = ring(List(5, 4, 2, 1, 3), Set(0, 1, 2, 3, 4))
    val ctx = NodeCtx.of(topology, NodeId(1))
    val held =
      FrState.Active(Uid(4), 1, List(Heard(NodeId(0), Uid(5), 1), Heard(NodeId(0), Uid(8), 2)))
    val step = p.onMessage(ctx, held, NodeId(2), FrMsg.Id(Uid(2), 1)).value
    assert(step.state == FrState.Passive(Some(Uid(4))))
    assert(step.rule == Franklin.Rules.defeat)
    assert(step.sends == List(Send(NodeId(2), FrMsg.Id(Uid(8), 2))))
  }

  test("a node that gets its own uid from both sides while it holds a later message breaks") {
    // Only one active node can get its own uid back, so no later-round message can exist then.
    val topology = ring(List(5, 9, 2), Set(0, 1, 2))
    val ctx = NodeCtx.of(topology, NodeId(1))
    val forged =
      FrState.Active(Uid(9), 1, List(Heard(NodeId(0), Uid(9), 1), Heard(NodeId(0), Uid(7), 2)))
    val got = p.onMessage(ctx, forged, NodeId(2), FrMsg.Id(Uid(9), 1))
    assert(got.left.exists {
      case ProtocolError.InvariantBroken(_) => true
      case _                                => false
    })
  }

  test("an active node rejects a message two rounds ahead and a second message from one side") {
    val topology = ring(List(5, 9, 2), Set(0, 1, 2))
    val ctx = NodeCtx.of(topology, NodeId(1))
    val start = FrState.Active(Uid(9), 1, List(Heard(NodeId(0), Uid(5), 1)))
    assert(p.onMessage(ctx, start, NodeId(2), FrMsg.Id(Uid(2), 3)).isLeft)
    assert(p.onMessage(ctx, start, NodeId(0), FrMsg.Id(Uid(5), 1)).isLeft)
    assert(p.onMessage(ctx, FrState.Leader(Uid(9)), NodeId(0), FrMsg.Id(Uid(5), 1)).isLeft)
    assert(p.onMessage(ctx, start, NodeId(0), FrMsg.Leader(Uid(5))).isLeft)
  }

  test("the invariant gives one line on forged worlds") {
    val topology = ring(List(3, 7, 1, 8, 5, 2, 6, 4), (0 until 8).toSet)
    val world = World.init(p, topology, ChannelOrder.Fifo).value
    assert(Franklin.invariant(world) == None)
    def forge(changes: (Int, FrState)*): World[FrState, FrMsg] = {
      world.copy(states = changes.foldLeft(world.states) { case (s, (n, st)) =>
        s.updated(NodeId(n), st)
      })
    }
    assert(
      Franklin.invariant(forge(3 -> FrState.Leader(Uid(8)), 1 -> FrState.Leader(Uid(7)))) ==
        Some("Nodes 1, 3 are all leaders.")
    )
    assert(
      Franklin.invariant(forge(1 -> FrState.Announcing(Uid(7)))) ==
        Some("The leader has uid 7, but the largest uid is 8.")
    )
    assert(
      Franklin.invariant(forge(0 -> FrState.Follower(Some(Uid(3)), Uid(6)))) ==
        Some("Node 0 follows uid 6, which is not the largest uid.")
    )
    assert(
      Franklin.invariant(forge(3 -> FrState.Active(Uid(8), 5, Nil))) ==
        Some("Node 3 is in round 5, but 4 rounds are enough for this ring.")
    )
  }

  test("the overlay at the end of the ring-8 preset fills the leader and marks the relays") {
    val alg = FranklinEntry.algorithm
    val preset = alg.presets.headOption.value
    val sim = alg.simulate(alg.spec(preset, Seed(1L))).value
    val states = sim.last.value.states
    val marks = alg.overlay.marks(states)
    assert(marks.collect { case Mark.Fill(n, NodeFill.Chosen) => n } == List(NodeId(3)))
    assert(marks.collect { case Mark.Fill(n, NodeFill.Passive) => n }.size == 7)
    assert(marks.collect { case b @ Mark.Badge(_, _) => b }.isEmpty)
    val middle = sim.worlds.find(_.states.values.exists {
      case FrState.Active(_, 2, _) => true
      case _                       => false
    })
    assert(
      middle.exists(w => alg.overlay.marks(w.states).contains(Mark.Badge(NodeId(3), "round 2")))
    )
  }

  test("check rejects a duplicate uid, no initiator, an initiator with no uid and a non-ring") {
    assert(p.check(ring(List(3, 3, 1), Set(0))).isLeft)
    assert(p.check(ring(List(3, 2, 1), Set.empty)).isLeft)
    val noUid = Graphs.fixed(
      Topology.bidirectionalRing(3, Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map.empty)))
    )
    assert(p.check(noUid).isLeft)
    assert(p.init(NodeCtx.of(noUid, NodeId(0)), noUid.attrsOf(NodeId(0))).isLeft)
    val directed = Graphs.fixed(Topology.ring(3, Presets.ringAttrs(List(1, 2, 3), Set(0))))
    assert(p.check(directed).isLeft)
    assert(p.check(ring(List(1, 2), Set(0))).isLeft)
    assert(p.check(ring(List(1, 2, 3), Set(0))).isRight)
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    assert(Inspect[FrMsg].label(FrMsg.Id(Uid(7), 2)) == "Id(7, round 2)")
    assert(Inspect[FrMsg].chip(FrMsg.Id(Uid(7), 2)) == Chip.Text("7\u00b7r2"))
    assert(Inspect[FrMsg].fields(FrMsg.Leader(Uid(9))) == List("uid" -> "9"))
    assert(Inspect[FrAction].label(FrAction.Wake) == "Wake")
    assert(Inspect[FrOutput].label(FrOutput.Follows(Uid(9))) == "the leader is uid 9")
    assert(Inspect[FrState].label(FrState.Active(Uid(4), 2, Nil)) == "active, uid 4")
    assert(
      Inspect[FrState].fields(FrState.Active(Uid(4), 2, List(Heard(NodeId(1), Uid(3), 2)))) ==
        List("phase" -> "active", "uid" -> "4", "round" -> "2", "from node 1" -> "uid 3, round 2")
    )
    assert(Inspect[FrState].label(FrState.Passive(None)) == "passive")
  }

  // -----------------------------------------------------------------------------------------------
  // Regression tests for the verified bugs of the college code.

  test("regression franklin-1: no orchestrator; the leader decides when its own uid comes back") {
    forAll(rings, Gen.long) { (topology: Topology, seed: Long) =>
      val sim = simulate(topology, seed)
      // Each message goes between ring neighbours. No process outside the ring takes part.
      sim.run.trace.toList.collect { case TraceEvent.Sent(_, env) => env.id }.foreach { id =>
        assert(topology.outNeighbours(id.from).contains(id.to))
      }
      // The leader decides in the step that delivers an Id with its own uid.
      val steps = sim.run.trace.toList.collect {
        case TraceEvent.Stepped(_, node, _, rule, FrState.Announcing(uid), _) => (node, uid, rule)
      }
      assert(steps.map(_._3) == List(Franklin.Rules.elected))
      // Each node stores the result in its own state.
      assert(sim.last.toList.flatMap(_.states.values).forall {
        case FrState.Leader(_) | FrState.Follower(_, _) => true
        case _                                          => false
      })
    }
  }

  test("regression franklin-2: the election picks the maximum uid, not the minimum") {
    // The college tests passed with `min` in place of `max`. This ring has a unique answer.
    val sim = simulate(ring(List(2, 9, 4, 1, 7), (0 until 5).toSet), 3L)
    assert(elected(sim) == List(NodeId(1) -> Uid(9)))
    assert(sim.run.decisions.count {
      case (_, FrOutput.Follows(u)) => u == Uid(9)
      case (_, FrOutput.Elected(_)) => false
    } == 4)
  }

  test(
    "regression franklin-3 and franklin-4: a malformed ring is an error, not a crash or a hang"
  ) {
    // A node with three neighbours, and a ring with a missing back edge.
    val extra = Graphs.fixed(
      Topology.undirected(
        (0 until 4).map(NodeId(_)),
        List(0 -> 1, 1 -> 2, 2 -> 3, 3 -> 0, 0 -> 2).map { case (a, b) => (NodeId(a), NodeId(b)) },
        Presets.ringAttrs(List(1, 2, 3, 4), Set(0))
      )
    )
    assert(p.check(extra).isLeft)
    val oneWay = Graphs.fixed(
      Topology.directed(
        (0 until 3).map(NodeId(_)),
        List(0 -> 1, 1 -> 0, 1 -> 2, 2 -> 1, 2 -> 0).map { case (a, b) => (NodeId(a), NodeId(b)) },
        Presets.ringAttrs(List(1, 2, 3), Set(0))
      )
    )
    assert(p.check(oneWay).isLeft)
    assert(Simulator.run(p, spec(oneWay, Policy.Seeded(Seed(1L)))).isLeft)
    // A message from a node that is not a neighbour is a rejected input.
    val ok = ring(List(1, 2, 3, 4), Set(0))
    val ctx = NodeCtx.of(ok, NodeId(0))
    assert(
      p.onMessage(ctx, FrState.Passive(None), NodeId(2), FrMsg.Id(Uid(3), 1)).left.value match {
        case ProtocolError.UnexpectedMessage(from, _) => from == NodeId(2)
        case _                                        => false
      }
    )
  }

  test("regression franklin-8: no repeated passive orders; each node goes passive at most once") {
    // The ring of the finding. The college orchestrator sent TurnPassive again in each round.
    val topology = ring(List(5, 1, 4, 2, 6, 3, 7, 0), (0 until 8).toSet)
    (1L to 10L).foreach { seed =>
      val sim = simulate(topology, seed)
      val defeats = sim.run.trace.toList.collect {
        case TraceEvent.Stepped(_, node, _, rule, _, _) if rule == Franklin.Rules.defeat => node
      }
      assert(defeats.distinct == defeats)
      assert(defeats.size == 7)
      assert(elected(sim) == List(NodeId(6) -> Uid(7)))
      assert(sent(sim).size == 2 * 8 * rounds(sim) + 8)
    }
  }

  test("the college ring of 5: uid 4 wins in round 2") {
    val sim = simulate(ring(List(0, 1, 2, 3, 4), (0 until 5).toSet), 1L)
    assert(elected(sim) == List(NodeId(4) -> Uid(4)))
    assert(rounds(sim) == 2)
  }
}
