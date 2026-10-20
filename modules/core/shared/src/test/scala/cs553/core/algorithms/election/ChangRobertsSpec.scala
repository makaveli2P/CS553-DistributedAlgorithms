package cs553.core.algorithms.election

import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.ids.Uid
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Step
import cs553.core.rng.Seed
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Inspect
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.ChoiceError
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.RunError
import cs553.core.world.Simulator
import cs553.core.world.World
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

final class ChangRobertsSpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private type CrRun = Run[CrState, CrMsg, CrAction, CrOutput]

  private val p = ChangRoberts.protocol

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.Fifo, ChannelOrder.AnyOrder)

  private def spec(
      topology: Topology,
      policy: Policy[CrAction],
      order: ChannelOrder = ChannelOrder.Fifo
  ): RunSpec[CrAction] = {
    RunSpec(ChangRoberts.id, topology, order, policy, StopRule.AtQuiescence, 100000)
  }

  private def runCr(
      topology: Topology,
      seed: Long,
      order: ChannelOrder = ChannelOrder.Fifo
  ): CrRun = {
    Simulator.run(p, spec(topology, Policy.Seeded(Seed(seed)), order)).value.run
  }

  private def finalStates(run: CrRun): Map[NodeId, CrState] = {
    val initial = run.trace.collectFirst { case TraceEvent.Started(_, _, states) => states }
    run.trace.foldLeft(initial.getOrElse(Map.empty)) {
      case (states, TraceEvent.Stepped(_, node, _, _, after, _)) => states.updated(node, after)
      case (states, _)                                           => states
    }
  }

  private def sentMessages(run: CrRun): List[CrMsg] = run.trace.toList.collect {
    case TraceEvent.Sent(_, env) => env.msg
  }

  private def electCount(run: CrRun): Int = sentMessages(run).count {
    case CrMsg.Elect(_)  => true
    case CrMsg.Leader(_) => false
  }

  private def leaderCount(run: CrRun): Int = sentMessages(run).count {
    case CrMsg.Elect(_)  => false
    case CrMsg.Leader(_) => true
  }

  private def maxInitiatorUid(topology: Topology): Option[Uid] = {
    topology.initiators.toList.flatMap(n => topology.attrsOf(n).uid).maxOption
  }

  /** A ring `0 -> 1 -> ... -> n-1 -> 0` with these uids and initiators. */
  private def ring(uids: List[Int], initiators: Set[Int]): Topology = {
    val attrs = uids.zipWithIndex.map { case (uid, i) =>
      val role = if initiators.contains(i) then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> NodeAttrs(role, Some(Uid(uid)), Map.empty)
    }.toMap
    Graphs.fixed(Topology.ring(uids.size, attrs))
  }

  /** The ring of the college config: 0(10) -> 1(20) -> 2(5) -> 3(15) -> 0. */
  private def collegeRing(initiators: Set[Int]): Topology = ring(List(10, 20, 5, 15), initiators)

  // -----------------------------------------------------------------------------------------------
  // Properties over random rings, random initiator sets, seeds and both channel orders.

  test("property: each run ends quiescent, with no Rejected event") {
    forAll(Graphs.ringWithUids, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        assert(ChangRoberts.check(topology).isRight)
        val run = runCr(topology, seed, order)
        assert(run.outcome.contains(Outcome.Quiescent))
        assert(run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
    }
  }

  test("property: exactly one leader, and it is the initiator with the maximum uid") {
    forAll(Graphs.ringWithUids, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runCr(topology, seed, order)
        val elected = run.decisions.collect { case (node, CrOutput.Elected(uid)) => (node, uid) }
        val expected = maxInitiatorUid(topology)
        assert(elected.size == 1)
        assert(elected.map(_._2).headOption == expected)
        elected.foreach { case (node, uid) =>
          assert(topology.attrsOf(node).role == Role.Initiator)
          assert(topology.attrsOf(node).uid.contains(uid))
        }
    }
  }

  test("property: each node decides exactly once and ends with the uid of the leader") {
    forAll(Graphs.ringWithUids, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runCr(topology, seed, order)
        val expected = maxInitiatorUid(topology)
        assert(run.decisions.map(_._1).sorted.toList == topology.nodes.toList)
        assert(run.decisions.forall {
          case (_, CrOutput.Elected(uid))    => expected.contains(uid)
          case (_, CrOutput.Follows(leader)) => expected.contains(leader)
        })
        finalStates(run).values.foreach {
          case CrState.Leader(uid)         => assert(expected.contains(uid))
          case CrState.Follower(_, leader) => assert(expected.contains(leader))
          case other                       => fail(s"a node ends in the state $other")
        }
    }
  }

  test("property: at most n(n+1)/2 Elect messages and exactly n Leader messages") {
    forAll(Graphs.ringWithUids, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runCr(topology, seed, order)
        val n = topology.nodes.size
        assert(electCount(run) <= n * (n + 1) / 2)
        assert(leaderCount(run) == n)
    }
  }

  test("the worst case: all initiators, uids that decrease along the ring, n(n+1)/2 Elects") {
    val n = 6
    val worst = ring((n to 1 by -1).toList, (0 until n).toSet)
    // All Wakes fire first, so that each bid travels as far as it can.
    val wakes = (0 until n).toList.map(i => Choice.Fire(NodeId(i), CrAction.Wake))
    val world = World.init(p, worst, ChannelOrder.Fifo).value
    val afterWakes = wakes.foldLeft(world)((w, c) => World.next(p, w, c).value._1)
    assert(World.enabled(p, afterWakes).forall {
      case Choice.Deliver(_) => true
      case Choice.Fire(_, _) => false
      case Choice.Drop(_)    => false
    })
    val script = wakes ++ deliverAll(afterWakes)
    val run = Simulator.run(p, spec(worst, Policy.Script(script))).value.run
    assert(electCount(run) == n * (n + 1) / 2)
    assert(run.decisions.collect { case (node, CrOutput.Elected(_)) =>
      node
    }.toList == List(NodeId(0)))
  }

  /** The choices that deliver the lowest enabled envelope until the run is quiescent. */
  private def deliverAll(start: World[CrState, CrMsg]): List[Choice[CrAction]] = {
    LazyList
      .unfold(start) { w =>
        World.enabled(p, w).headOption.map { c =>
          (c, World.next(p, w, c).value._1)
        }
      }
      .toList
  }

  // -----------------------------------------------------------------------------------------------
  // Examples on the college ring.

  test("college ring with initiators 0 and 1: node 1 (uid 20) wins, and each node learns uid 20") {
    val run = runCr(collegeRing(Set(0, 1)), 11L)
    assert(run.decisions.collect { case (n, CrOutput.Elected(u)) =>
      (n, u)
    }.toList == List(NodeId(1) -> Uid(20)))
    assert(leaderCount(run) == 4)
    assert(finalStates(run).get(NodeId(0)).contains(CrState.Follower(Some(Uid(10)), Uid(20))))
  }

  test("regression chang-roberts-3: only node 2 (uid 5) is an initiator, so uid 5 wins") {
    // The college code let non-initiators join with their own uid, so uid 20 won.
    (0L until 10L).foreach { seed =>
      val run = runCr(collegeRing(Set(2)), seed)
      assert(run.decisions.collect { case (n, CrOutput.Elected(u)) =>
        (n, u)
      }.toList == List(NodeId(2) -> Uid(5)))
      assert(electCount(run) == 4)
    }
  }

  test(
    "regression chang-roberts-1: an initiator that a larger bid defeats before Wake never bids"
  ) {
    // Ring 0(20) -> 1(10). Node 0 wakes, and its bid reaches node 1 before node 1 wakes.
    val topology = ring(List(20, 10), Set(0, 1))
    val script = List(
      Choice.Fire(NodeId(0), CrAction.Wake),
      Choice.Deliver(EnvelopeId(NodeId(0), NodeId(1), 0))
    )
    val world = World.init(p, topology, ChannelOrder.Fifo).value
    val after = script.foldLeft(world)((w, c) => World.next(p, w, c).value._1)
    assert(after.states.get(NodeId(1)).contains(CrState.Passive(Some(Uid(10)))))
    assert(!World.enabled(p, after).contains(Choice.Fire(NodeId(1), CrAction.Wake)))
    assert(
      World.next(p, after, Choice.Fire(NodeId(1), CrAction.Wake)).left.value ==
        ChoiceError.ActionNotOffered(NodeId(1))
    )
    val run = Simulator.run(p, spec(topology, Policy.Script(script ++ deliverAll(after)))).value.run
    assert(run.decisions.count {
      case (_, CrOutput.Elected(_)) => true
      case (_, CrOutput.Follows(_)) => false
    } == 1)
    assert(electCount(run) == 2)
  }

  test("regression chang-roberts-4: initiator selection only picks real ring nodes") {
    // The college code put the Terminator (key -1) in the pool of initiators. Here an initiator is
    // an attribute of a topology node, and the World offers Wake only at nodes of the topology.
    forAll(Graphs.ringWithUids, Gen.long) { (topology: Topology, seed: Long) =>
      val fired = runCr(topology, seed).trace.toList.collect {
        case TraceEvent.Stepped(_, node, Choice.Fire(target, CrAction.Wake), _, _, _) =>
          assert(node == target)
          target
      }
      assert(fired.nonEmpty)
      assert(fired.toSet.subsetOf(topology.initiators))
    }
    forAll(Gen.choose(2, 30), Gen.long) { (size: Int, seed: Long) =>
      val generated = ChangRoberts.randomRing(size, Seed(seed)).value
      assert(generated.initiators.nonEmpty)
      assert(generated.initiators.subsetOf(generated.nodes))
      assert(ChangRoberts.check(generated).isRight)
    }
    val topology = collegeRing(Set(0))
    assert(topology.withInitiators(Set(NodeId(-1))).isLeft)
    val stray = Choice.Fire(NodeId(-1), CrAction.Wake)
    assert(
      Simulator.run(p, spec(topology, Policy.Script(List(stray)))).left.value ==
        RunError.BadChoice(1, stray, ChoiceError.UnknownNode(NodeId(-1)))
    )
  }

  test("check rejects a duplicate uid, no initiator, an initiator with no uid and a non-ring") {
    assert(ChangRoberts.check(ring(List(3, 3, 1), Set(0))).isLeft)
    assert(ChangRoberts.check(ring(List(3, 2, 1), Set.empty)).isLeft)
    val noUid = Graphs.fixed(
      Topology.ring(3, Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map.empty)))
    )
    assert(ChangRoberts.check(noUid).isLeft)
    assert(p.init(NodeCtx.of(noUid, NodeId(0)), noUid.attrsOf(NodeId(0))).isLeft)
    val undirected = Graphs.fixed(
      Topology.undirected(
        List(NodeId(0), NodeId(1), NodeId(2)),
        List(NodeId(0) -> NodeId(1), NodeId(1) -> NodeId(2), NodeId(2) -> NodeId(0)),
        collegeRing(Set(0)).attrs.filter { case (n, _) => n.value < 3 }
      )
    )
    assert(ChangRoberts.check(undirected).isLeft)
    assert(ChangRoberts.check(collegeRing(Set(0, 2))).isRight)
  }

  test("randomRing is a pure function of its seed") {
    forAll(Gen.choose(2, 20), Gen.long) { (size: Int, seed: Long) =>
      val a = ChangRoberts.randomRing(size, Seed(seed))
      assert(a == ChangRoberts.randomRing(size, Seed(seed)))
      assert(
        a.map(t => t.nodes.toList.flatMap(t.attrsOf(_).uid).map(_.value).sorted) ==
          Right((1 to size).toList)
      )
    }
  }

  test("randomRing depends on its seed: 20 seeds give different uid orders and initiator sets") {
    val rings = (1L to 20L).map(seed => ChangRoberts.randomRing(8, Seed(seed)).value)
    val uidOrders = rings.map(t => t.nodes.toList.map(t.attrsOf(_).uid)).toSet
    val initiatorSets = rings.map(_.initiators).toSet
    assert(uidOrders.size > 1)
    assert(initiatorSets.size > 1)
  }

  test("randomRing(6, seed 7) gives one fixed ring") {
    // A golden value: a change to the shuffle or to SplitMix64 changes the CLI demo ring.
    val t = ChangRoberts.randomRing(6, Seed(7L)).value
    assert(t.nodes.toList.flatMap(t.attrsOf(_).uid).map(_.value) == List(1, 5, 4, 6, 3, 2))
    assert(t.initiators.toList.map(_.value) == List(1, 2, 5))
  }

  // -----------------------------------------------------------------------------------------------
  // Direct examples of the leader branches, which a correct run does not reach.

  private val ctx = NodeCtx.of(collegeRing(Set(0)), NodeId(0))
  private val from = NodeId(3)

  private def ruleOf(result: Either[ProtocolError, Step[CrState, CrMsg, CrOutput]]): RuleId = {
    result.value.rule
  }

  test("the leader purges a smaller late Elect and rejects a larger one, in both leader states") {
    List(CrState.Announcing(Uid(5)), CrState.Leader(Uid(5))).foreach { leader =>
      assert(
        ruleOf(p.onMessage(ctx, leader, from, CrMsg.Elect(Uid(3)))) == ChangRoberts.Rules.purge
      )
      assert(p.onMessage(ctx, leader, from, CrMsg.Elect(Uid(3))).value.state == leader)
      assert(p.onMessage(ctx, leader, from, CrMsg.Elect(Uid(9))).left.value match {
        case ProtocolError.UnexpectedMessage(sender, _) => sender == from
        case _                                          => false
      })
    }
  }

  test("an announcing leader accepts only its own Leader message") {
    val announcing = CrState.Announcing(Uid(5))
    val back = p.onMessage(ctx, announcing, from, CrMsg.Leader(Uid(5))).value
    assert(back.state == CrState.Leader(Uid(5)))
    assert(back.rule == ChangRoberts.Rules.announceReturned)
    assert(back.sends.isEmpty)
    assert(p.onMessage(ctx, announcing, from, CrMsg.Leader(Uid(9))).isLeft)
    assert(p.onMessage(ctx, CrState.Leader(Uid(5)), from, CrMsg.Leader(Uid(5))).isLeft)
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    assert(Inspect[CrMsg].label(CrMsg.Elect(Uid(7))) == "Elect(7)")
    assert(Inspect[CrMsg].fields(CrMsg.Leader(Uid(9))) == List("uid" -> "9"))
    assert(Inspect[CrAction].label(CrAction.Wake) == "Wake")
    assert(Inspect[CrOutput].label(CrOutput.Follows(Uid(9))) == "Follows(9)")
    assert(Inspect[CrState].label(CrState.Active(Uid(4))) == "active 4")
    assert(
      Inspect[CrState].fields(CrState.Follower(None, Uid(9))) ==
        List("phase" -> "Follower", "uid" -> "none", "leader" -> "9")
    )
  }
}
