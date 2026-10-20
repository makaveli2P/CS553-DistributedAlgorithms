package cs553.core.world

import cs553.core.ids.NodeId
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import org.scalacheck.Gen

/** Generators of test topologies. Node 0 is the initiator. */
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
