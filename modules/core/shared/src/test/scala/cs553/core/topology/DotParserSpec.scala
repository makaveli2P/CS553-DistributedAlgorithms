package cs553.core.topology

import cs553.core.Fixtures
import cs553.core.ids.NodeId
import org.scalatest.funsuite.AnyFunSuite

final class DotParserSpec extends AnyFunSuite {

  test("the build embeds every fixture topology") {
    assert(Fixtures.topologies.map(_._1).count(_.endsWith(".dot")) == 5)
  }

  Fixtures.topologies.foreach { case (name, text) =>
    test(s"the parser and Topology.fromDot accept the fixture $name") {
      val graph = DotParser.parse(text)
      assert(graph.isRight, graph.left.map(_.toList.map(_.render)))
      val topology = Topology.fromDot(text)
      assert(topology.isRight, topology.left.map(_.toList.map(_.render)))
      topology.foreach { t =>
        assert(t.initiators.toList == List(NodeId(0)))
        assert(t.edges.nonEmpty)
      }
    }
  }

  test("inputEcho.dot gives 6 nodes and 7 directed edges with the edge defaults") {
    val text = Fixtures.topologies.collectFirst { case ("inputEcho.dot", t) => t }.getOrElse("")
    val result = DotParser.parse(text)
    assert(result.map(_.kind) == Right(GraphKind.Directed))
    assert(result.map(_.name) == Right(Some("Net Graph with 6 nodes")))
    assert(result.map(_.nodes.map(_.id)) == Right(List("0", "1", "2", "4", "5", "3")))
    assert(result.map(_.edges.size) == Right(7))
    assert(result.map(_.edges.forall(_.attrs.get("class").contains("link-class"))) == Right(true))
    assert(
      result.map(_.nodes.headOption.flatMap(_.attrs.get("label"))) == Right(Some("<b>Init</b>"))
    )
  }

  test("an undirected graph with comments, chains and semicolons gives a symmetric topology") {
    val text =
      """|// a line comment
         |/* a block
         |   comment */
         |graph g {
         |  # a hash comment
         |  0 -- 1 -- 2; 2 -- 0 [weight=3, color = "red"]
         |  3 [uid=7, initiator=true]
         |  rankdir = LR
         |}
         |""".stripMargin
    val topology = Topology.fromDot(text)
    assert(topology.map(_.isSymmetric) == Right(true))
    assert(topology.map(_.edges.size) == Right(6))
    assert(topology.map(_.nodes.size) == Right(4))
    assert(topology.map(_.initiators.toList) == Right(List(NodeId(3))))
    assert(topology.map(_.attrsOf(NodeId(3)).uid.map(_.value)) == Right(Some(7)))
    assert(DotParser.parse(text).map(_.attrs) == Right(Map("rankdir" -> "LR")))
  }

  private def firstError(text: String): Option[DotError] = {
    DotParser.parse(text).left.toOption.map(_.head)
  }

  test("a missing closing brace gives an error at the end of the input") {
    val error = firstError("digraph {\n  0 -> 1\n")
    assert(error.map(_.line) == Some(3))
    assert(error.exists(_.message.contains("'}'")))
  }

  test("an unterminated quoted string gives an error at its start") {
    assert(
      firstError("digraph {\n  \"0 -> 1\n}") == Some(
        DotError(2, 3, "the quoted string has no closing '\"'")
      )
    )
  }

  test("an unterminated block comment gives an error") {
    assert(firstError("/* no end\ndigraph { }").exists(_.message.contains("'*/'")))
  }

  test("an edge operator that does not match the graph kind gives an error") {
    val error = firstError("graph {\n  0 -> 1\n}")
    assert(error.map(e => (e.line, e.column)) == Some((2, 5)))
    assert(error.exists(_.message.contains("does not match")))
  }

  test("a subgraph gives an error") {
    assert(firstError("digraph { subgraph s { 0 } }").exists(_.message.contains("subgraphs")))
  }

  test("a port gives an error") {
    assert(firstError("digraph { 0:n -> 1 }").exists(_.message.contains("ports")))
  }

  test("an input without a graph keyword gives an error") {
    assert(firstError("{ 0 -> 1 }").exists(_.message.contains("'graph' or 'digraph'")))
  }

  test("text after the closing brace gives an error") {
    assert(firstError("digraph { } extra").exists(_.message.contains("end of the input")))
  }

  test("an attribute without a value gives an error") {
    assert(firstError("digraph { 0 [color] }").exists(_.message.contains("'='")))
  }

  test("an empty input gives an error") {
    assert(firstError("").isDefined)
  }

  test("Topology.fromDot rejects a node id that is not a number") {
    assert(
      Topology.fromDot("digraph { a -> 1 }").left.map(_.toList) == Left(
        List(TopologyError.BadNodeId("a"))
      )
    )
  }

  test("Topology.fromDot reports each bad attribute, and rejects a self-loop") {
    val result = Topology.fromDot("digraph { 1 -> 1\n 2 [uid=x]\n 3 [uid=y] }").left.map(_.toList)
    assert(
      result == Left(
        List(
          TopologyError.BadAttribute(NodeId(2), "uid", "x"),
          TopologyError.BadAttribute(NodeId(3), "uid", "y")
        )
      )
    )
    assert(
      Topology.fromDot("digraph { 1 -> 1 }").left.map(_.toList) == Left(
        List(TopologyError.SelfLoop(NodeId(1)))
      )
    )
  }

  test("Topology.fromDot wraps a parse error with its line") {
    val result = Topology.fromDot("digraph {\n\n  0 -> \n}")
    assert(
      result.left.map(_.toList.map(_.render)) == Left(
        List("line 4, column 1: expected a node id after the edge operator but found '}'")
      )
    )
  }
}
