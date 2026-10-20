package cs553.core.world

import cs553.core.ids.NodeId
import cs553.core.ids.Uid
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import org.scalacheck.Gen

/** Generators of test topologies.
  *
  * Each generator gives the plain form (ids `0..n-1`, ring edges `i -> i+1`) or a relabelled form
  * (sparse ids in a random order). In `connected`, the node that was node 0 is the initiator.
  */
object Graphs {

  val initiatorAttrs: Map[NodeId, NodeAttrs] =
    Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map.empty))

  /** A connected undirected graph with ids `0..n-1`: a random spanning tree plus 0 to n random
    * extra edges.
    */
  val connectedPlain: Gen[Topology] = for {
    size <- Gen.choose(2, 10)
    parents <- Gen.sequence[List[Int], Int]((1 until size).map(i => Gen.choose(0, i - 1)))
    extraCount <- Gen.choose(0, size)
    extra <- Gen.listOfN(extraCount, Gen.zip(Gen.choose(0, size - 1), Gen.choose(0, size - 1)))
  } yield {
    val tree = parents.zipWithIndex.map { case (parent, i) => (parent, i + 1) }
    val edges = (tree ++ extra.filter { case (a, b) => a != b }).map { case (a, b) =>
      (NodeId(a), NodeId(b))
    }
    fixed(Topology.undirected((0 until size).map(NodeId(_)), edges, initiatorAttrs))
  }

  /** A connected undirected graph, in the plain or in a relabelled form. */
  val connected: Gen[Topology] = plainOrRelabelled(connectedPlain)

  /** A connected undirected graph with one initiator at a random node. */
  val connectedAnyInitiator: Gen[Topology] = for {
    topology <- connected
    initiator <- Gen.oneOf(topology.nodes.toList)
  } yield fixed(topology.withInitiators(Set(initiator)))

  /** A directed ring of 2 to 12 nodes `i -> i+1`. Each node has a distinct `Uid`, and a random
    * non-empty subset of the nodes are initiators.
    */
  val ringWithUidsPlain: Gen[Topology] = for {
    size <- Gen.choose(2, 12)
    uids <- Gen.pick(size, 1 to 99)
    keys <- Gen.listOfN(size, Gen.long)
    first <- Gen.choose(0, size - 1)
    flags <- Gen.listOfN(size, Gen.prob(0.5))
  } yield {
    val shuffled = uids.toList.zip(keys).sortBy(_._2).map(_._1)
    val attrs = shuffled.zipWithIndex.map { case (uid, i) =>
      val role = if i == first || flags.lift(i).contains(true) then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> NodeAttrs(role, Some(Uid(uid)), Map.empty)
    }.toMap
    fixed(Topology.ring(size, attrs))
  }

  /** A ring with uids, in the plain form or with sparse ids in a random order along the ring. */
  val ringWithUids: Gen[Topology] = plainOrRelabelled(ringWithUidsPlain)

  /** The same topology with each node id replaced by a distinct id in `[0, 100)`, in a random
    * order. The edges and the attributes move with the nodes.
    */
  def relabel(topology: Topology): Gen[Topology] = {
    val size = topology.nodes.size
    for {
      picked <- Gen.pick(size, 0 until 100)
      keys <- Gen.listOfN(size, Gen.long)
    } yield {
      val targets = picked.toList.zip(keys).sortBy(_._2).map { case (id, _) => NodeId(id) }
      val mapping = topology.nodes.toList.zip(targets).toMap
      def move(node: NodeId): NodeId = mapping.getOrElse(node, node)
      fixed(
        Topology.directed(
          topology.nodes.toList.map(move),
          topology.edges.toList.map(e => (move(e.from), move(e.to))),
          topology.attrs.map { case (node, attrs) => move(node) -> attrs }
        )
      )
    }
  }

  private def plainOrRelabelled(plain: Gen[Topology]): Gen[Topology] = for {
    topology <- plain
    moved <- Gen.oneOf(Gen.const(topology), relabel(topology))
  } yield moved

  /** Unwraps a topology that a test builds from valid input. A generator bug fails the test. */
  def fixed(result: Either[?, Topology]): Topology = result match {
    case Right(t)    => t
    case Left(error) => sys.error(s"the test topology is not valid: $error")
  }
}
