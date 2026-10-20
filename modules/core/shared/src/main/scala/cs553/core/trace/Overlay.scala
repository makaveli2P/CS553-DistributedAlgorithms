package cs553.core.trace

import cs553.core.ids.NodeId

import scala.collection.immutable.SortedMap

/** How the canvas draws an edge that a mark names. */
enum EdgeStyle {

  /** The edge is in the tree that the algorithm builds: a parent edge of Echo or Tarry. */
  case Tree

  /** The edge carried a message: a used channel of Tarry. */
  case Used

  /** The algorithm removed the edge: a granted request of Bracha-Toueg. */
  case Removed

  /** The node `from` waits for the node `to`: a wait-for edge of Bracha-Toueg. The canvas draws an
    * arrow from `from` to `to`. Another style on the same edge sets the stroke, and the arrow
    * stays.
    */
  case WaitsFor
}

/** The fill of a node. The canvas gives each fill one CSS class. */
enum NodeFill {

  /** The one node that the run is about: the leader, or the initiator that decided. */
  case Chosen

  /** The node decided. Use it only when no `Decided` event shows the decision. */
  case Decided

  /** The node only relays messages: a defeated candidate of an election. */
  case Passive

  /** The node took its local snapshot (Lai-Yang). */
  case Red

  /** The node did not take its local snapshot yet (Lai-Yang). */
  case White

  /** The node is free: it is not deadlocked (Bracha-Toueg). */
  case Free

  /** The node is deadlocked (Bracha-Toueg). */
  case Deadlocked
}

/** The record state of one channel in a snapshot. */
enum ChannelRecord {

  /** The receiver records the messages that arrive on the channel. */
  case Recording

  /** The receiver stopped the record of the channel. */
  case Closed
}

/** One mark that an overlay draws on the canvas. The text in a mark comes from the overlay, which
  * is at the render edge, like `Inspect`.
  */
enum Mark {

  /** The canvas draws the edge between `from` and `to` in `style`. An undirected edge matches in
    * both directions.
    */
  case OnEdge(from: NodeId, to: NodeId, style: EdgeStyle)

  /** The canvas fills the node. When two fills name one node, the last one wins. */
  case Fill(node: NodeId, fill: NodeFill)

  /** One short line of text on the node, for example "2 of 3" or "round 2". */
  case Badge(node: NodeId, text: String)

  /** The record state of the channel `from -> to` and the recorded messages, oldest first. */
  case Channel(from: NodeId, to: NodeId, record: ChannelRecord, recorded: List[String])

  /** The node took its local snapshot. The canvas shows the step of the first world with this mark:
    * see `Overlay.cutSteps`.
    */
  case Cut(node: NodeId)

  /** One short line of text about the whole world, for example "round 2: 3 active". The UI shows it
    * in the facts row under the canvas.
    */
  case Fact(text: String)
}

object Mark {

  /** The edge from `child` to `parent` is in the tree that the algorithm builds. */
  def treeEdge(child: NodeId, parent: NodeId): Mark = Mark.OnEdge(child, parent, EdgeStyle.Tree)
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

  /** For each node with a `Cut` mark: the index of the first world in `worlds` that has the mark.
    */
  def cutSteps[S](overlay: Overlay[S], worlds: Seq[SortedMap[NodeId, S]]): Map[NodeId, Int] = {
    worlds.zipWithIndex.foldLeft(Map.empty[NodeId, Int]) { case (acc, (states, step)) =>
      val cut = overlay.marks(states).collect { case Mark.Cut(node) => node }
      acc ++ cut.filterNot(acc.contains).map(_ -> step)
    }
  }
}
