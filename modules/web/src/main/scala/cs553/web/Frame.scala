package cs553.web

import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.trace.EdgeStyle
import cs553.core.trace.EnvelopeId
import cs553.core.trace.NodeFill

/** A point in the coordinates of the canvas. One unit is one CSS pixel. */
final case class Point(x: Double, y: Double)

/** The measured size of the canvas in CSS pixels. */
final case class Size(width: Double, height: Double)

/** The view box of the canvas. */
final case class Box(x: Double, y: Double, width: Double, height: Double) {
  def attr: String = s"$x $y $width $height"
}

/** Where the label of a node goes, relative to its circle. */
enum LabelSide {
  case Above, Below, Left, Right
}

/** Where the label lines of a node start, their anchor, and the centre of the first action. */
final case class LabelSpot(x: Double, firstY: Double, side: LabelSide, actionY: Double) {

  def anchor: String = side match {
    case LabelSide.Above | LabelSide.Below => "middle"
    case LabelSide.Right                   => "start"
    case LabelSide.Left                    => "end"
  }
}

/** One spontaneous action that a node offers now. `index` is its place in `Protocol.actions`.
  * `next` is true when "Deliver next" does this action.
  */
final case class ActionView(index: Int, label: String, next: Boolean)

/** One node at the shown step. `lines` are the parts of the `Inspect` label, one for each line, and
  * then the `Badge` marks of the overlay. `fill` is the last `Fill` mark of the overlay. `cutAt` is
  * the step of the first world with a `Cut` mark for the node, when it is not later than the shown
  * step.
  */
final case class NodeView(
    id: NodeId,
    at: Point,
    side: LabelSide,
    lines: List[String],
    current: Boolean,
    decided: Boolean,
    fill: Option[NodeFill],
    cutAt: Option[Int],
    actions: List[ActionView]
)

/** One drawn edge. An undirected pair of channels is one edge with `directed = false`. `style` is
  * the last `OnEdge` mark of the overlay for the edge, in either direction.
  */
final case class EdgeView(
    from: NodeId,
    to: NodeId,
    a: Point,
    b: Point,
    directed: Boolean,
    style: Option[EdgeStyle]
)

/** The text of one `Channel` mark, for example "recording: 5, 3". It sits next to the middle of the
  * channel `from -> to`, on the right side of the direction, so that the two directions of an
  * undirected edge do not overlap.
  */
final case class NoteView(from: NodeId, to: NodeId, at: Point, text: String)

/** Why the learner cannot deliver an envelope now. */
enum Blocked {

  /** A FIFO channel delivers its oldest envelope first. `head` is the label of that envelope. */
  case Behind(head: String)

  /** The timeline shows a past step. */
  case Past
}

/** One envelope in a mailbox. `at` is its centre and `toward` is the centre of the receiver.
  * `place` is its place in the queue of its channel, from 0. `fresh` is true when the shown step
  * sent it.
  */
final case class EnvelopeView(
    id: EnvelopeId,
    label: String,
    fields: List[(String, String)],
    sentAt: Option[Int],
    place: Int,
    blocked: Option[Blocked],
    next: Boolean,
    fresh: Boolean,
    at: Point,
    toward: Point
) {

  /** The text for the hover tip, the screen reader and the panel. */
  def describe: String = {
    val where = if place == 0 then { "first in the queue" }
    else { s"waiting ${Frame.ordinal(place + 1)} in the queue" }
    s"$label, from ${id.from.value} to ${id.to.value}, $where"
  }
}

/** The envelopes of one queue that do not fit on the edge: a "+n" mark at `at`. */
final case class MoreView(from: NodeId, to: NodeId, at: Point, count: Int)

/** The canvas and the text lines of one step. */
final case class Frame(
    box: Box,
    nodes: List[NodeView],
    edges: List[EdgeView],
    envelopes: List[EnvelopeView],
    more: List[MoreView],
    notes: List[NoteView],
    step: Int,
    last: Int,
    status: String,
    decidedAt: Option[Int],
    quiescentAt: Option[Int],
    limitAt: Option[Int],
    invariant: Option[String]
) {
  def live: Boolean = step == last
}

object Frame {

  /** "1st", "2nd", "3rd", "4th" and so on. */
  def ordinal(n: Int): String = {
    val suffix = (n % 100, n % 10) match {
      case (11 | 12 | 13, _) => "th"
      case (_, 1)            => "st"
      case (_, 2)            => "nd"
      case (_, 3)            => "rd"
      case (_, _)            => "th"
    }
    s"$n$suffix"
  }

  /** "3", "3 and 4", "3, 4 and 5". */
  def list(items: List[String]): String = items.reverse match {
    case Nil          => ""
    case one :: Nil   => one
    case last :: rest => s"${rest.reverse.mkString(", ")} and $last"
  }
}

/** The content of the inspector. */
enum PanelView {
  case NodeInfo(
      id: NodeId,
      fields: List[(String, String)],
      waiting: List[String],
      lastMessage: Option[String],
      lastRule: Option[String],
      decision: Option[String]
  )
  case EnvelopeInfo(envelope: EnvelopeView, canDeliver: Boolean)
}

/** One topology choice: the name in the link and the title that the learner reads. */
final case class PresetChoice(name: String, title: String)

/** The choices of the settings, which are always visible. */
final case class SettingsView(
    presets: List[PresetChoice],
    preset: Option[String],
    seed: Long,
    order: ChannelOrder,
    orderLock: Option[String]
)

/** The one primary action of the sandbox, with its label and an optional hover hint. */
final case class Primary(label: String, event: UiEvent, hint: Option[String])

/** The result of the last "Copy link". */
enum CopyState {
  case Idle, Copied, Failed
}

/** All that the sandbox screen shows. `Sandbox` renders it and computes nothing. */
final case class Screen(
    title: String,
    frame: Frame,
    primary: Option[Primary],
    panel: Option[PanelView],
    settings: SettingsView,
    branchLine: Option[String],
    refused: Option[String],
    playing: Boolean,
    speed: Speed,
    copy: CopyState
)
