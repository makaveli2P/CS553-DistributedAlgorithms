package cs553.core.topology

import cs553.core.ids.NodeId
import io.circe.syntax.*
import org.scalatest.funsuite.AnyFunSuite

final class TopologySpec extends AnyFunSuite {

  private def ids(values: Int*): List[NodeId] = values.toList.map(NodeId(_))

  test("ring gives a directed ring with one in-neighbour and one out-neighbour for each node") {
    val ring = Topology.ring(4)
    assert(ring.map(_.edges.size) == Right(4))
    assert(ring.map(_.outNeighbours(NodeId(3)).toList) == Right(ids(0)))
    assert(ring.map(_.inNeighbours(NodeId(0)).toList) == Right(ids(3)))
    assert(ring.map(_.isSymmetric) == Right(false))
    assert(ring.map(_.symmetric.isSymmetric) == Right(true))
  }

  test("ring rejects a size below 2") {
    assert(Topology.ring(1).left.map(_.toList) == Left(List(TopologyError.RingTooSmall(1))))
  }

  test("undirected adds both directions") {
    val t = Topology.undirected(ids(0, 1, 2), List(NodeId(0) -> NodeId(1), NodeId(1) -> NodeId(2)))
    assert(t.map(_.edges.size) == Right(4))
    assert(t.map(_.outNeighbours(NodeId(1)).toList) == Right(ids(0, 2)))
    assert(t.map(_.inNeighbours(NodeId(1)).toList) == Right(ids(0, 2)))
  }

  test("directed reports every error, not only the first") {
    val result = Topology.directed(
      ids(0, 1, 1),
      List(NodeId(0) -> NodeId(9), NodeId(1) -> NodeId(1)),
      Map(NodeId(8) -> NodeAttrs.default)
    )
    assert(
      result.left.map(_.toList) == Left(
        List(
          TopologyError.DuplicateNode(NodeId(1)),
          TopologyError.UnknownNode(NodeId(9)),
          TopologyError.SelfLoop(NodeId(1)),
          TopologyError.UnknownNode(NodeId(8))
        )
      )
    )
  }

  test("directed rejects an empty node set") {
    assert(Topology.directed(Nil, Nil).left.map(_.toList) == Left(List(TopologyError.Empty)))
  }

  test("a topology round-trips through JSON, and the decoder runs the checks") {
    val t = Topology.undirected(
      ids(0, 1, 2),
      List(NodeId(0) -> NodeId(1)),
      Map(NodeId(0) -> NodeAttrs(Role.Initiator, None, Map("x" -> "y")))
    )
    t.foreach { topology =>
      assert(topology.asJson.as[Topology] == Right(topology))
    }
    assert(t.isRight)
    val bad = io.circe.parser.parse("""{"nodes":[0],"edges":[{"from":0,"to":0}],"attrs":{}}""")
    assert(bad.flatMap(_.as[Topology]).isLeft)
  }
}
