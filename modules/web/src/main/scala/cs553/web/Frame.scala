package cs553.web

import cs553.core.ids.NodeId
import cs553.core.trace.EnvelopeId

/** A point in the coordinates of the canvas. */
final case class Point(x: Double, y: Double)

/** The view box of the canvas. */
final case class Box(x: Double, y: Double, width: Double, height: Double) {
  def attr: String = s"$x $y $width $height"
}

/** One spontaneous action that a node offers now. `index` is its place in `Protocol.actions`. */
final case class ActionView(index: Int, label: String)

/** One node at the shown step. Strings come from `Inspect` only. */
final case class NodeView(
    id: NodeId,
    at: Point,
    phase: String,
    current: Boolean,
    decided: Boolean,
    actions: List[ActionView]
)

/** One drawn edge. An undirected pair of channels is one edge with `directed = false`. */
final case class EdgeView(from: NodeId, to: NodeId, directed: Boolean, tree: Boolean)

/** Why the learner cannot deliver an envelope now. */
enum Blocked {

  /** A FIFO channel delivers its oldest envelope first. */
  case Behind(head: String)

  /** The timeline shows a past step. */
  case Past
}

/** One envelope in a mailbox. */
final case class EnvelopeView(
    id: EnvelopeId,
    label: String,
    fields: List[(String, String)],
    sentAt: Option[Int],
    blocked: Option[Blocked],
    next: Boolean
)

/** One directed channel and its queue, oldest first. `shared` is true when the reverse channel uses
  * the same drawn edge.
  */
final case class ChannelView(
    from: NodeId,
    to: NodeId,
    shared: Boolean,
    queue: Vector[EnvelopeView]
)

/** The canvas and the text lines of one step. */
final case class Frame(
    box: Box,
    nodes: List[NodeView],
    edges: List[EdgeView],
    channels: List[ChannelView],
    step: Int,
    last: Int,
    status: String,
    decidedAt: Option[Int],
    quiescentAt: Option[Int],
    limitAt: Option[Int]
) {
  def live: Boolean = step == last
}

/** The content of the side panel. */
enum PanelView {
  case NodeInfo(
      id: NodeId,
      phase: String,
      fields: List[(String, String)],
      lastMessage: Option[String],
      lastRule: Option[String],
      decision: Option[String]
  )
  case EnvelopeInfo(
      envelope: EnvelopeView,
      from: NodeId,
      to: NodeId,
      canDeliver: Boolean
  )
  case SettingsInfo(settings: SettingsView)
}

/** The choices of the settings panel. */
final case class SettingsView(
    presets: List[String],
    preset: Option[String],
    seed: Long,
    fifo: Boolean,
    orderLock: Option[String]
)

/** The one primary action of the sandbox, with its label. */
final case class Primary(label: String, event: UiEvent)

/** All that the sandbox screen shows. `Sandbox` renders it and computes nothing. */
final case class Screen(
    title: String,
    frame: Frame,
    primary: Option[Primary],
    panel: Option[PanelView],
    branchLine: Option[String],
    refused: Option[String],
    playing: Boolean,
    speed: Speed,
    copied: Boolean
)
