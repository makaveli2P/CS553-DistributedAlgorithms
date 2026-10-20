package cs553.core.world

import cs553.core.ids.NodeId
import cs553.core.ids.Uid
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import org.scalacheck.Gen

/** Generators of test topologies. In `connected`, node 0 is the initiator. */
object Graphs {

  val initiatorAttrs: Map[NodeId, NodeAttrs] =
    Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map.empty))

  /** A connected undirected graph: a random spanning tree plus random extra edges. */
  val connected: Gen[Topology] = for {
    size <- Gen.choose(2, 10)
    parents <- Gen.sequence[List[Int], Int]((1 until size).map(i => Gen.choose(0, i - 1)))
    extra <- Gen.listOf(Gen.zip(Gen.choose(0, size - 1), Gen.choose(0, size - 1)))
  } yield {
    val tree = parents.zipWithIndex.map { case (parent, i) => (parent, i + 1) }
    val edges = (tree ++ extra.filter { case (a, b) => a != b }).map { case (a, b) =>
      (NodeId(a), NodeId(b))
    }
    Topology
      .undirected((0 until size).map(NodeId(_)), edges, initiatorAttrs)
      .getOrElse(fallback)
  }

  /** A connected undirected graph with one initiator at a random node. */
  val connectedAnyInitiator: Gen[Topology] = for {
    topology <- connected
    initiator <- Gen.oneOf(topology.nodes.toList)
  } yield topology.withInitiators(Set(initiator)).getOrElse(topology)

  /** A directed ring of 2 to 12 nodes. Each node has a distinct `Uid`, and a random non-empty
    * subset of the nodes are initiators.
    */
  val ringWithUids: Gen[Topology] = for {
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

  private lazy val fallback: Topology = {
    Topology.undirected(
      List(NodeId(0), NodeId(1)),
      List(NodeId(0) -> NodeId(1)),
      initiatorAttrs
    ) match {
      case Right(t)     => t
      case Left(errors) => sys.error(errors.toList.mkString(", "))
    }
  }

  /** Unwraps a topology that a test builds from fixed, valid input. */
  def fixed(result: Either[?, Topology]): Topology = result match {
    case Right(t)    => t
    case Left(error) => sys.error(s"the test topology is not valid: $error")
  }
}
