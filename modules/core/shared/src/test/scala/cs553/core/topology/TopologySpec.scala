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

  test("isolated, isConnected, without and withInitiators") {
    val t =
      Topology.undirected(ids(0, 1, 2, 3), List(NodeId(1) -> NodeId(2), NodeId(2) -> NodeId(3)))
    assert(t.map(_.isolated.toList) == Right(ids(0)))
    assert(t.map(_.isConnected) == Right(false))
    val trimmed = t.flatMap(_.without(Set(NodeId(0))))
    assert(trimmed.map(_.nodes.toList) == Right(ids(1, 2, 3)))
    assert(trimmed.map(_.isConnected) == Right(true))
    val started = trimmed.flatMap(_.withInitiators(Set(NodeId(2))))
    assert(started.map(_.initiators.toList) == Right(ids(2)))
    assert(
      trimmed.flatMap(_.withInitiators(Set(NodeId(9)))).left.map(_.toList) ==
        Left(List(TopologyError.UnknownNode(NodeId(9))))
    )
    assert(trimmed.flatMap(_.without(Set(NodeId(1), NodeId(2), NodeId(3)))).isLeft)
  }

  test("regression: only the label text Init makes an initiator, not a label that contains Init") {
    val dot =
      """digraph { "0" [label=<<b>Init</b>>]; "1" [label="Init"]; "2" [label="NonInitiator"];
        |"3" [label=<<b>Initial</b>>]; "4" [label=" Init "] }""".stripMargin
    assert(
      Topology.fromDot(dot).map(_.initiators.toList) == Right(List(NodeId(0), NodeId(1), NodeId(4)))
    )
  }

  test("a bidirectional ring has both directions of each edge and is strongly connected") {
    val ring = Topology.bidirectionalRing(4)
    assert(ring.map(_.edges.size) == Right(8))
    assert(ring.map(_.isSymmetric) == Right(true))
    assert(ring.map(_.isStronglyConnected) == Right(true))
    assert(Topology.ring(4).map(_.isStronglyConnected) == Right(true))
  }

  test("a directed path is not strongly connected") {
    val path = Topology.directed(
      List(0, 1, 2).map(NodeId(_)),
      List((0, 1), (1, 2)).map { case (a, b) =>
        (NodeId(a), NodeId(b))
      }
    )
    assert(path.map(_.isStronglyConnected) == Right(false))
  }

  test("tree accepts a tree and refuses a graph with a cycle") {
    def ids(edges: List[(Int, Int)]) = edges.map { case (a, b) => (NodeId(a), NodeId(b)) }
    val nodes = List(0, 1, 2, 3).map(NodeId(_))
    assert(Topology.tree(nodes, ids(List((0, 1), (1, 2), (1, 3)))).map(_.isTree) == Right(true))
    assert(Topology.tree(nodes, ids(List((0, 1), (1, 2), (2, 0), (2, 3)))).isLeft)
  }

  test("a DOT wait-for graph keeps the request, balance and budget attributes") {
    val dot = "digraph { 0 [request=2, balance=10, budget=x]; 1; 2; 0 -> 1; 0 -> 2 }"
    val t = Topology.fromDot(dot)
    val attrs = t.map(_.attrsOf(NodeId(0)))
    assert(attrs.flatMap(_.int(NodeId(0), NodeAttrs.Keys.request)) == Right(Some(2)))
    assert(attrs.flatMap(_.int(NodeId(0), NodeAttrs.Keys.balance)) == Right(Some(10)))
    assert(attrs.flatMap(_.int(NodeId(0), NodeAttrs.Keys.budget)).isLeft)
    assert(t.map(_.outDegree(NodeId(0))) == Right(2))
    assert(NodeAttrs.default.withExtra("request", "1").int(NodeId(5), "request") == Right(Some(1)))
  }
}
