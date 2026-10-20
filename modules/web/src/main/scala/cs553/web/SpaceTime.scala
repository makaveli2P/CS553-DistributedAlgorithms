package cs553.web

import cs553.core.ids.NodeId
import cs553.core.trace.EnvelopeId

/** Which canvas the sandbox shows. One view at a time. */
enum ViewMode {
  case Graph, SpaceTime
}

/** The axis of time in the space-time view: along x on a wide screen, along y on a narrow one. */
enum Orientation {
  case Horizontal, Vertical
}

/** What one event dot shows. */
enum DotKind {
  case Step, Rejected, Decided
}

/** One event of a node at a step. `tip` is the text for the hover tip. */
final case class SpaceDot(node: NodeId, step: Int, kind: DotKind, tip: String)

/** One message: sent at `sentStep`, delivered at `deliveredStep`, or still in flight. */
final case class SpaceMessage(
    id: EnvelopeId,
    label: String,
    sentStep: Int,
    deliveredStep: Option[Int]
)

/** The facts of a run up to the shown step, in the terms of the space-time view. All steps are at
  * most `cursor`. `last` is the newest step of the whole run, so the view keeps one width.
  */
final case class SpaceModel(
    nodes: List[NodeId],
    last: Int,
    cursor: Int,
    dots: List[SpaceDot],
    messages: List[SpaceMessage],
    cuts: Map[NodeId, Int],
    decidedAt: Option[Int],
    quiescentAt: Option[Int]
)

/** The line of one node: its label and the two ends. */
final case class SpaceLane(node: NodeId, from: Point, to: Point, label: Point)

/** One placed event dot. */
final case class PlacedDot(at: Point, dot: SpaceDot, current: Boolean)

/** One message arrow. `open` is true for a message in flight: it has no head and stops short of the
  * receiver line.
  */
final case class SpaceArrow(id: EnvelopeId, label: String, from: Point, to: Point, open: Boolean)

/** A marker across all lines at one step: the decision or the quiescence. */
enum MarkerKind {
  case Decided, Quiescent
}

final case class SpaceMarker(kind: MarkerKind, step: Int, from: Point, to: Point, label: Point)

/** The placed space-time view in pixels. `width` and `height` are the size of the whole drawing. */
final case class SpaceLayout(
    orientation: Orientation,
    width: Double,
    height: Double,
    lanes: List[SpaceLane],
    dots: List[PlacedDot],
    arrows: List[SpaceArrow],
    cut: List[Point],
    now: (Point, Point),
    markers: List[SpaceMarker]
)

/** The pure layout of the space-time view. One line for each node, and one dot for each event,
  * placed by step. A message goes from the dot of its send to the dot of its delivery.
  */
object SpaceTime {

  /** A canvas with fewer pixels than this across is a phone: time goes down. */
  val narrow: Double = 560.0

  def orientation(size: Size): Orientation =
    if size.width < narrow then { Orientation.Vertical }
    else { Orientation.Horizontal }

  private val margin = 20.0
  private val labelSpace = 44.0
  private val minStepGap = 26.0
  private val maxStepGap = 52.0
  private val minLaneGap = 46.0
  private val maxLaneGap = 70.0

  def layout(model: SpaceModel, size: Size): SpaceLayout = {
    val o = orientation(size)
    val n = math.max(1, model.nodes.size)
    val span = math.max(1, model.last)
    // The room along the time axis, and across the lanes, that the container gives.
    val (timeRoom, laneRoom) = o match {
      case Orientation.Horizontal =>
        (size.width - labelSpace - 2 * margin, size.height - 2 * margin)
      case Orientation.Vertical => (size.height - labelSpace - 2 * margin, size.width - 2 * margin)
    }
    val stepGap = clamp(timeRoom / span, minStepGap, maxStepGap)
    val laneGap = clamp(laneRoom / n, minLaneGap, maxLaneGap)
    val timeLength = labelSpace + margin + span * stepGap + margin
    val laneLength = 2 * margin + n * laneGap

    val laneIndex = model.nodes.zipWithIndex.toMap
    def point(lane: Int, time: Double): Point = {
      val across = margin + laneGap * (lane + 0.5)
      val along = labelSpace + margin + time * stepGap
      o match {
        case Orientation.Horizontal => Point(along, across)
        case Orientation.Vertical   => Point(across, along)
      }
    }
    def at(node: NodeId, time: Double): Point = point(laneIndex.getOrElse(node, 0), time)
    def labelPoint(lane: Int): Point = o match {
      case Orientation.Horizontal => Point(margin, point(lane, 0).y)
      case Orientation.Vertical   => Point(point(lane, 0).x, margin + 10)
    }

    val lanes = model.nodes.zipWithIndex.map { case (node, i) =>
      SpaceLane(node, point(i, 0), point(i, span.toDouble), labelPoint(i))
    }
    val dots = model.dots.map { d =>
      PlacedDot(at(d.node, d.step.toDouble), d, d.step == model.cursor)
    }
    val arrows = model.messages.map { m =>
      val start = at(m.id.from, m.sentStep.toDouble)
      m.deliveredStep match {
        case Some(step) =>
          SpaceArrow(m.id, m.label, start, at(m.id.to, step.toDouble), open = false)
        case None =>
          // An open arrow goes part of the way toward the receiver line, to the shown step.
          val target = at(m.id.to, (math.max(model.cursor, m.sentStep) + 1).toDouble)
          val end = Point(
            start.x + (target.x - start.x) * 0.6,
            start.y + (target.y - start.y) * 0.6
          )
          SpaceArrow(m.id, m.label, start, end, open = true)
      }
    }
    val cut = model.cuts.toList.sortBy(c => laneIndex.getOrElse(c._1, 0)).map { case (node, step) =>
      at(node, step.toDouble)
    }
    val bottom = model.nodes.size - 1
    def across(step: Int): (Point, Point) = (point(0, step.toDouble), point(bottom, step.toDouble))
    def marker(kind: MarkerKind, step: Int): SpaceMarker = {
      val (from, to) = across(step)
      // The decided label goes above or beside the line, the quiescent label below or beside it.
      val label = (o, kind) match {
        case (Orientation.Horizontal, MarkerKind.Decided)   => Point(from.x, margin - 8)
        case (Orientation.Horizontal, MarkerKind.Quiescent) => Point(from.x, laneLength + 8)
        case (Orientation.Vertical, MarkerKind.Decided)     => Point(laneLength - 4, from.y - 5)
        case (Orientation.Vertical, MarkerKind.Quiescent)   => Point(laneLength - 4, from.y + 13)
      }
      SpaceMarker(kind, step, from, to, label)
    }
    val markers =
      model.decidedAt.map(marker(MarkerKind.Decided, _)).toList ++
        model.quiescentAt.map(marker(MarkerKind.Quiescent, _)).toList
    val (width, height) = o match {
      case Orientation.Horizontal => (timeLength, laneLength + 14)
      case Orientation.Vertical   => (laneLength, timeLength + 14)
    }
    SpaceLayout(o, width, height, lanes, dots, arrows, cut, across(model.cursor), markers)
  }

  private def clamp(value: Double, low: Double, high: Double): Double =
    math.max(low, math.min(high, value))
}
