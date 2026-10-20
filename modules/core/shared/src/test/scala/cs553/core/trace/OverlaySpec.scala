package cs553.core.trace

import cs553.core.ids.NodeId
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedMap

final class OverlaySpec extends AnyFunSuite {

  /** A toy overlay: a node with a state of 1 or more took its snapshot. */
  private val recorded: Overlay[Int] = new Overlay[Int] {
    def marks(states: SortedMap[NodeId, Int]): List[Mark] = {
      states.toList.collect { case (node, s) if s >= 1 => Mark.Cut(node) }
    }
  }

  test("cutSteps gives the index of the first world where each node has a Cut mark") {
    val worlds = List(
      SortedMap(NodeId(0) -> 0, NodeId(1) -> 0),
      SortedMap(NodeId(0) -> 1, NodeId(1) -> 0),
      SortedMap(NodeId(0) -> 2, NodeId(1) -> 1)
    )
    assert(Overlay.cutSteps(recorded, worlds) == Map(NodeId(0) -> 1, NodeId(1) -> 2))
    assert(Overlay.cutSteps(Overlay.none[Int], worlds) == Map.empty)
  }

  test("treeEdge is an OnEdge mark in the Tree style") {
    assert(Mark.treeEdge(NodeId(2), NodeId(1)) == Mark.OnEdge(NodeId(2), NodeId(1), EdgeStyle.Tree))
  }
}
