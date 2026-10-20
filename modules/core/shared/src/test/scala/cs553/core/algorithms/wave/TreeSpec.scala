package cs553.core.algorithms.wave

import cs553.core.Fixtures
import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.registry.TreeEntry
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EdgeStyle
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.Simulator
import cs553.core.world.World
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet

final class TreeSpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private type TreeRun = Run[TreeState, TreeMsg, TreeAction, TreeOutput]

  private val p = Tree.protocol

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.Fifo, ChannelOrder.AnyOrder)

  private def ids(values: Int*): List[NodeId] = values.toList.map(NodeId(_))

  /** A random undirected tree of 2 to 12 nodes: node i > 0 has a random parent below i. Half of the
    * cases have sparse ids in a random order.
    */
  private val treesPlain: Gen[Topology] = for {
    size <- Gen.choose(2, 12)
    parents <- Gen.sequence[List[Int], Int]((1 until size).map(i => Gen.choose(0, i - 1)))
  } yield Graphs.fixed(
    Topology.tree(
      (0 until size).map(NodeId(_)),
      parents.zipWithIndex.map { case (parent, i) => NodeId(parent) -> NodeId(i + 1) }
    )
  )

  private val trees: Gen[Topology] = for {
    plain <- treesPlain
    moved <- Gen.oneOf(Gen.const(plain), Graphs.relabel(plain))
  } yield moved

  private def spec(
      topology: Topology,
      policy: Policy[TreeAction],
      order: ChannelOrder = ChannelOrder.Fifo
  ): RunSpec[TreeAction] = RunSpec(Tree.id, topology, order, policy, StopRule.AtQuiescence, 10000)

  private def runTree(
      topology: Topology,
      seed: Long,
      order: ChannelOrder = ChannelOrder.Fifo
  ): TreeRun = Simulator.run(p, spec(topology, Policy.Seeded(Seed(seed)), order)).value.run

  /** A schedule that delivers each enabled token before it lets a node start. `picks` chooses the
    * delivery or the leaf. So the leaves often get a token before Start.
    */
  private def deliveriesFirst(
      topology: Topology,
      order: ChannelOrder,
      picks: List[Int]
  ): List[Choice[TreeAction]] = {
    @tailrec
    def loop(
        w: World[TreeState, TreeMsg],
        left: List[Int],
        acc: List[Choice[TreeAction]]
    ): List[Choice[TreeAction]] = {
      val enabled = World.enabled(p, w)
      val deliveries = enabled.collect { case c @ Choice.Deliver(_) => c }
      val pool = if deliveries.nonEmpty then { deliveries }
      else { enabled }
      pool match {
        case Nil    => acc.reverse
        case _ :: _ =>
          val pick = left.headOption.getOrElse(0)
          val choice = pool(Math.floorMod(pick, pool.size))
          loop(World.next(p, w, choice).value._1, left.drop(1), choice :: acc)
      }
    }
    loop(World.init(p, topology, order).value, picks, Nil)
  }

  private def finalStates(run: TreeRun): Map[NodeId, TreeState] = {
    val initial = run.trace.collectFirst { case TraceEvent.Started(_, _, states) => states }
    run.trace.foldLeft(initial.getOrElse(Map.empty)) {
      case (states, TraceEvent.Stepped(_, node, _, _, after, _)) => states.updated(node, after)
      case (states, _)                                           => states
    }
  }

  private def senders(run: TreeRun): List[NodeId] = run.trace.toList.collect {
    case TraceEvent.Sent(_, env) => env.id.from
  }

  private def rules(run: TreeRun): List[RuleId] = run.trace.toList.collect {
    case TraceEvent.Stepped(_, _, _, rule, _, _) => rule
  }

  private def rejected(run: TreeRun): List[ProtocolError] = run.trace.toList.collect {
    case TraceEvent.Rejected(_, _, _, error) => error
  }

  /** The node at the end of the parent path from `node`: a decider, or `None` on a cycle. */
  @tailrec
  private def deciderOf(
      states: Map[NodeId, TreeState],
      node: NodeId,
      seen: Set[NodeId]
  ): Option[NodeId] = {
    states.get(node) match {
      case Some(s) if s.phase == TreePhase.Decided => Some(node)
      case Some(s) if !seen.contains(node)         =>
        s.parent match {
          case Some(parent) => deciderOf(states, parent, seen + node)
          case None         => None
        }
      case Some(_) | None => None
    }
  }

  /** The checks of design section 3 on one finished run. */
  private def assertTreeRun(topology: Topology, run: TreeRun): Unit = {
    assert(run.outcome.contains(Outcome.Quiescent))
    assert(rejected(run).isEmpty)
    // Each node sends exactly one message: |V| in total.
    assert(senders(run).sorted == topology.nodes.toList)
    // Exactly two nodes decide, once each. They are neighbours, and each names the other.
    val decisions = run.decisions.toList
    assert(decisions.size == 2)
    decisions match {
      case List((a, TreeOutput.Decided(pa)), (b, TreeOutput.Decided(pb))) =>
        assert(pa == b && pb == a)
        assert(topology.outNeighbours(a).contains(b))
      case other => fail(s"not two decisions: $other")
    }
    // The parent path of each node ends at a decider, so the parents form the tree towards the
    // decider edge.
    val states = finalStates(run)
    val deciders = decisions.map(_._1).toSet
    topology.nodes.foreach { node =>
      val s = states(node)
      assert(s.received.size == s.degree || !deciders.contains(node))
      assert(deciderOf(states, node, Set.empty).exists(deciders.contains), s"node $node")
      s.parent.foreach(parent => assert(topology.outNeighbours(node).contains(parent)))
      assert(deciders.contains(node) || s.phase == TreePhase.Sent)
    }
  }

  private def line(size: Int): Topology = Graphs.fixed(
    Topology.tree(
      (0 until size).map(NodeId(_)),
      (1 until size).map(i => NodeId(i - 1) -> NodeId(i))
    )
  )

  // -----------------------------------------------------------------------------------------------
  // Properties over random trees, seeds, schedules and both channel orders.

  test("property: seeded runs send |V| messages and two neighbours decide, each once") {
    forAll(trees, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      assertTreeRun(topology, runTree(topology, seed, order))
    }
  }

  test("property: the same holds when each delivery comes before each Start") {
    forAll(trees, Gen.listOf(Gen.choose(0, 1000)), orders) {
      (topology: Topology, picks: List[Int], order: ChannelOrder) =>
        val script = deliveriesFirst(topology, order, picks)
        val run = Simulator.run(p, spec(topology, Policy.Script(script), order)).value.run
        assertTreeRun(topology, run)
    }
  }

  test("property: the invariant holds in each world of a seeded run") {
    forAll(trees, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val simulation = Simulator.run(p, spec(topology, Policy.Seeded(Seed(seed)), order)).value
      simulation.worlds.foreach(w => assert(Tree.invariant(w).isEmpty))
    }
  }

  test("property: at the end, the overlay has |V| - 1 tree edges and fills the two deciders") {
    forAll(trees, Gen.long) { (topology: Topology, seed: Long) =>
      val simulation = Simulator.run(p, spec(topology, Policy.Seeded(Seed(seed)))).value
      val states = simulation.last.map(_.states).getOrElse(SortedMap.empty[NodeId, TreeState])
      val marks = TreeEntry.algorithm.overlay.marks(states)
      val edges = marks.collect { case Mark.OnEdge(a, b, EdgeStyle.Tree) => Set(a, b) }
      assert(edges.size == topology.nodes.size - 1)
      assert(edges.distinct.size == edges.size)
      val chosen = marks.collect { case Mark.Fill(node, NodeFill.Chosen) => node }
      assert(chosen.toSet == simulation.run.decisions.map(_._1).toSet)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Examples.

  test("on the line 0-1-2-3 each of the two leaves sends by Start or by token-before-start") {
    val run = runTree(line(4), 1L)
    assertTreeRun(line(4), run)
    assert(
      rules(run).count(_ == Tree.Rules.start) + rules(run).count(
        _ == Tree.Rules.tokenBeforeStart
      ) == 2
    )
  }

  test("on two nodes both start, and both decide") {
    val run = runTree(line(2), 3L)
    assertTreeRun(line(2), run)
    assert(run.decisions.map(_._1).toList.sorted == ids(0, 1))
  }

  test("the preset netgraph-13 is the college tree: 13 nodes, 12 edges, 13 messages") {
    val preset = TreeEntry.algorithm.presets.find(_.name == "netgraph-13")
    assert(preset.isDefined)
    preset.foreach { pr =>
      assert(pr.topology.nodes.size == 13)
      assert(pr.topology.edges.size == 24)
      (1L to 5L).foreach(seed => assertTreeRun(pr.topology, runTree(pr.topology, seed)))
    }
    assert(TreeEntry.algorithm.presets.map(_.name) == List("tree-7", "netgraph-13", "line-5"))
  }

  test("a second token from one sender, a token from a non-neighbour and a late Start fail") {
    val ctx = NodeCtx(NodeId(1), SortedSet(NodeId(0), NodeId(2)), SortedSet(NodeId(0), NodeId(2)))
    val collecting = TreeState(TreePhase.Collecting, ids(0), None, 2)
    assert(p.onMessage(ctx, collecting, NodeId(0), TreeMsg.Token).isLeft)
    assert(p.onMessage(ctx, collecting, NodeId(9), TreeMsg.Token).isLeft)
    val sent = TreeState(TreePhase.Sent, ids(0), Some(NodeId(2)), 2)
    assert(p.onMessage(ctx, sent, NodeId(0), TreeMsg.Token).isLeft)
    assert(p.onAction(ctx, sent, TreeAction.Start).isLeft)
    assert(p.actions(ctx, sent).isEmpty)
    val decided = TreeState(TreePhase.Decided, ids(0, 2), Some(NodeId(2)), 2)
    assert(p.onMessage(ctx, decided, NodeId(2), TreeMsg.Token).isLeft)
  }

  test("init rejects a node whose in-neighbours and out-neighbours differ") {
    val ctx = NodeCtx(NodeId(0), SortedSet.empty, SortedSet(NodeId(1)))
    assert(p.init(ctx, NodeAttrs.default).isLeft)
  }

  test("the invariant breaks on a forged world with three deciders or two non-neighbours") {
    val world = World.init(p, line(4), ChannelOrder.Fifo).value
    def decided(parent: Int) = TreeState(TreePhase.Decided, Nil, Some(NodeId(parent)), 1)
    val three = world.copy(states =
      world.states ++ List(
        NodeId(0) -> decided(1),
        NodeId(1) -> decided(0),
        NodeId(2) -> decided(1)
      )
    )
    assert(Tree.invariant(three).exists(_.contains("3 nodes decided")))
    val apart =
      world.copy(states = world.states ++ List(NodeId(0) -> decided(1), NodeId(3) -> decided(2)))
    assert(Tree.invariant(apart).exists(_.contains("not each other's parent")))
    // Node 0 names node 1, but node 1 names node 2: only one half of the mutual-parent rule holds.
    val oneSided =
      world.copy(states = world.states ++ List(NodeId(0) -> decided(1), NodeId(1) -> decided(2)))
    assert(Tree.invariant(oneSided).exists(_.contains("not each other's parent")))
    assert(Tree.invariant(world).isEmpty)
  }

  test("the invariant breaks on a forged world with two tokens on one channel") {
    val world = World.init(p, line(2), ChannelOrder.Fifo).value
    val start = Choice.Fire(NodeId(0), TreeAction.Start)
    val once = World.next(p, world, start).value._1
    val queue = once.channel(Edge(NodeId(0), NodeId(1)))
    assert(queue.size == 1)
    val twice =
      once.copy(channels = once.channels.updated(queue.head.env.id.channel, queue ++ queue))
    assert(Tree.invariant(twice).exists(_.contains("holds 2 tokens")))
  }

  // -----------------------------------------------------------------------------------------------
  // Regression tests for the verified bugs of the college code (review section "tree").

  test("regression: a leaf that gets its neighbour's token before Start decides once") {
    // Path 0-1-2. Node 0 starts. Node 1 sends to 2. Node 2 gets the token before its Start.
    val script = List(
      Choice.Fire(NodeId(0), TreeAction.Start),
      Choice.Deliver(EnvelopeId(NodeId(0), NodeId(1), 0)),
      Choice.Deliver(EnvelopeId(NodeId(1), NodeId(2), 0)),
      Choice.Deliver(EnvelopeId(NodeId(2), NodeId(1), 0))
    )
    val run = Simulator.run(p, spec(line(3), Policy.Script(script))).value.run
    assert(
      rules(run) == List(
        Tree.Rules.start,
        Tree.Rules.sendToParent,
        Tree.Rules.tokenBeforeStart,
        Tree.Rules.decide
      )
    )
    assert(
      run.decisions.toList == List(
        NodeId(2) -> TreeOutput.Decided(NodeId(1)),
        NodeId(1) -> TreeOutput.Decided(NodeId(2))
      )
    )
    assertTreeRun(line(3), run)
    // Node 2 cannot start after it decided.
    val states = finalStates(run)
    assert(p.actions(NodeCtx.of(line(3), NodeId(2)), states(NodeId(2))).isEmpty)
  }

  test("regression: the run does not stop at the first decision; both decisions are in the trace") {
    (1L to 20L).foreach { seed =>
      val run = runTree(line(5), seed)
      assert(run.decisions.size == 2)
      assert(run.outcome.contains(Outcome.Quiescent))
    }
  }

  test("regression: check rejects a forest, a single node, a cycle and a directed graph") {
    val forest = Graphs.fixed(
      Topology.undirected(ids(0, 1, 2, 3), List(NodeId(0) -> NodeId(1), NodeId(2) -> NodeId(3)))
    )
    val single = Graphs.fixed(Topology.undirected(ids(0), Nil))
    val cycle = Graphs.fixed(Topology.bidirectionalRing(3))
    val directed = Graphs.fixed(Topology.ring(3))
    assert(p.check(forest).left.value.head.render.contains("not connected"))
    assert(p.check(single).left.value.toList.exists(_.render.contains("2 or more nodes")))
    assert(p.check(cycle).left.value.head.render.contains("needs a tree"))
    assert(p.check(directed).left.value.head.render.contains("undirected"))
    assert(p.check(line(3)) == Right(line(3)))
  }

  test("regression: a DOT file that lists one edge in both directions gives one tree edge") {
    val dot = """digraph { "0" -> "1"; "1" -> "0"; "1" -> "2" }"""
    val topology = Graphs.fixed(Topology.fromDot(dot).flatMap(Tree.prepare))
    assert(topology.edges.size == 4)
    assertTreeRun(topology, runTree(topology, 2L))
  }

  test("regression: the preset comes from the fixture file, not from a hand copy") {
    val text = Fixtures.topologies.collectFirst {
      case ("NetGraph_21-04-24-18-24-58.ngs.perturbed.dot", t) => t
    }
    val loaded = text.map(t => Graphs.fixed(Topology.fromDot(t).flatMap(Tree.prepare)))
    val preset = TreeEntry.algorithm.presets.find(_.name == "netgraph-13").map(_.topology)
    assert(loaded.isDefined && loaded == preset)
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    val collecting = TreeState(TreePhase.Collecting, ids(4), None, 3)
    assert(Inspect[TreeState].label(collecting) == "waits for 1 more")
    assert(Inspect[TreeState].label(TreeState(TreePhase.Collecting, Nil, None, 1)) == "leaf")
    assert(
      Inspect[TreeState].fields(collecting) == List(
        "phase" -> "collecting",
        "parent" -> "none yet",
        "received from" -> "4",
        "degree" -> "3"
      )
    )
    val sent = TreeState(TreePhase.Sent, ids(4, 5), Some(NodeId(6)), 3)
    assert(Inspect[TreeState].label(sent) == "sent to 6")
    val decided = sent.copy(phase = TreePhase.Decided)
    assert(Inspect[TreeState].label(decided) == "decider")
    assert(Inspect[TreeMsg].label(TreeMsg.Token) == "Token")
    assert(Inspect[TreeAction].label(TreeAction.Start) == "Start")
    assert(Inspect[TreeOutput].label(TreeOutput.Decided(NodeId(6))) == "the partner is node 6")
  }
}
