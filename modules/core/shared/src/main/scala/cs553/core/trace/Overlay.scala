package cs553.core.trace

import cs553.core.ids.NodeId

import scala.collection.immutable.SortedMap

/** One mark that an overlay draws on the canvas. */
enum Mark {

  /** The edge from `child` to `parent` is in the tree that the algorithm builds. */
  case TreeEdge(child: NodeId, parent: NodeId)
}

/** Computes the marks of a world from the node states, at the render edge. Give one instance for
  * each `S`. The UI draws the marks and does not know the rule that made them.
  */
trait Overlay[S] {
  def marks(states: SortedMap[NodeId, S]): List[Mark]
}

object Overlay {
  def apply[S](using overlay: Overlay[S]): Overlay[S] = overlay

  /** The overlay of an algorithm that has no marks. */
  def none[S]: Overlay[S] = new Overlay[S] {
    def marks(states: SortedMap[NodeId, S]): List[Mark] = Nil
  }
}
