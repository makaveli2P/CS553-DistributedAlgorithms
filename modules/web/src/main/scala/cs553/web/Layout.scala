package cs553.web

import cs553.core.ids.NodeId
import cs553.core.topology.Topology
import cs553.core.trace.Chip

import scala.annotation.tailrec

/** Deterministic positions for the canvas. No physics: the same topology always gives the same
  * picture.
  */
object Layout {

  /** The radius of a node circle. */
  val nodeRadius: Double = 22.0

  /** The height of an envelope. */
  val envelopeHeight: Double = 20.0

  /** The gap between two envelopes in one queue. */
  val envelopeGap: Double = 5.0

  /** The distance between two lines of a node label. */
  val lineHeight: Double = 16.0

  /** A canvas narrower than this puts each label under its node. */
  val narrowWidth: Double = 560.0

  /** The size of the canvas before the first measurement. */
  val defaultSize: Size = Size(900, 480)

  /** The room around the ring for the labels and the actions: at each side, above the top node and
    * under the bottom node.
    */
  private final case class Margins(side: Double, top: Double, bottom: Double)

  private val wideMargins = Margins(side = 150.0, top = 100.0, bottom = 104.0)
  private val narrowMargins = Margins(side = 56.0, top = 100.0, bottom = 104.0)

  /** The positions of the nodes and the view box for a canvas of a measured size. `rx` and `ry` are
    * the radii of the ring.
    */
  final case class Plan(
      box: Box,
      positions: Map[NodeId, Point],
      rx: Double,
      ry: Double,
      narrow: Boolean
  ) {

    def side(at: Point): LabelSide = Layout.side(at, ry, narrow)
  }

  /** The nodes on an ellipse, clockwise from the top. The order is a depth-first walk that follows
    * the out-edges first, so a directed ring keeps its ring order and a tree keeps its branches
    * together. The walk starts at the first initiator, or at the smallest node. The view box is the
    * measured size, so one unit is one pixel and the text keeps its CSS size.
    */
  def plan(topology: Topology, size: Size): Plan = {
    val width = math.max(size.width, 200.0)
    val height = math.max(size.height, 200.0)
    val narrow = width < narrowWidth
    val m = if narrow then { narrowMargins }
    else { wideMargins }
    val fitX = math.max(40.0, (width - 2 * m.side) / 2)
    val fitY = math.max(40.0, (height - m.top - m.bottom) / 2)
    // A wide canvas gets a flat ellipse and a tall phone canvas a tall one, but neither is thin.
    val rx = math.min(fitX, 1.7 * fitY)
    val ry = math.min(fitY, 1.7 * rx)
    val positions = ring(topology, rx, ry)
    // Centre the drawing on the nodes that exist: a pentagon has no node at the bottom of the
    // ellipse, so its drawing is shorter than the ellipse.
    val ys = positions.values.map(_.y)
    val top = ys.minOption.getOrElse(-ry) - m.top
    val bottom = ys.maxOption.getOrElse(ry) + m.bottom
    val boxY = (top + bottom) / 2 - height / 2
    Plan(Box(-width / 2, boxY, width, height), positions, rx, ry, narrow)
  }

  /** The nodes on an ellipse with the radii `rx` and `ry` around `(0, 0)`, clockwise from the top,
    * in the order of `walk`. The canvas and the home thumbnails use the same positions.
    */
  def ring(topology: Topology, rx: Double, ry: Double): Map[NodeId, Point] = {
    val order = walk(topology)
    val n = order.size
    order.zipWithIndex.map { case (node, i) =>
      val angle = -math.Pi / 2 + 2 * math.Pi * i / math.max(n, 1)
      node -> Point(round(rx * math.cos(angle)), round(ry * math.sin(angle)))
    }.toMap
  }

  /** The side of the label of a node at `at`, for a ring with the vertical radius `ry`. The top
    * node gets its label above and the bottom node below. On a narrow canvas each other node gets
    * its label below. On a wide canvas the label goes outside the ring.
    */
  def side(at: Point, ry: Double, narrow: Boolean): LabelSide = {
    val vertical = ry > 0 && math.abs(at.y) / ry > 0.9
    if vertical && at.y < 0 then { LabelSide.Above }
    else if vertical || narrow then { LabelSide.Below }
    else if at.x > 0 then { LabelSide.Right }
    else { LabelSide.Left }
  }

  /** Where the label lines of a node start, their anchor, and the centre of the first action. The
    * action goes on the far side of the label, away from the circle.
    */
  def labelSpot(at: Point, side: LabelSide, lines: Int): LabelSpot = {
    val r = nodeRadius
    val extra = lineHeight * (lines - 1)
    side match {
      case LabelSide.Above =>
        val first = at.y - r - 12 - extra
        LabelSpot(at.x, first, side, first - 24)
      case LabelSide.Below =>
        val first = at.y + r + 18
        LabelSpot(at.x, first, side, first + extra + 20)
      case LabelSide.Right =>
        val first = at.y + 4 - extra / 2
        LabelSpot(at.x + r + 10, first, side, first + extra + 20)
      case LabelSide.Left =>
        val first = at.y + 4 - extra / 2
        LabelSpot(at.x - r - 10, first, side, first + extra + 20)
    }
  }

  /** The approximate card behind the label lines. Queues keep clear of it where they can. */
  def labelCard(spot: LabelSpot, lines: List[String]): Box = {
    val w = lines.map(_.length).maxOption.getOrElse(0) * 7.0 + 8
    val h = lineHeight * lines.size + 4
    val left = spot.side match {
      case LabelSide.Above | LabelSide.Below => spot.x - w / 2
      case LabelSide.Right                   => spot.x - 4
      case LabelSide.Left                    => spot.x - w + 4
    }
    Box(left, spot.firstY - 13, w, h)
  }

  /** The approximate width of an envelope with this label. */
  def envelopeWidth(label: String): Double = math.max(34.0, label.length * 6.6 + 14.0)

  /** The size of the diamond of a control message. */
  val controlSize: Double = 14.0

  /** The approximate width of a message chip. */
  def chipWidth(chip: Chip): Double = chip match {
    case Chip.Text(text) => math.max(24.0, text.length * 6.6 + 14.0)
    case Chip.Red(text)  => math.max(24.0, text.length * 6.6 + 14.0)
    case Chip.Control    => controlSize
  }

  /** The box of a chip of width `w` centred at `p`. */
  def chipBox(p: Point, w: Double): Box =
    Box(p.x - w / 2, p.y - envelopeHeight / 2, w, envelopeHeight)

  /** The box of a node circle with a small margin. A queue keeps clear of the other nodes. */
  def nodeBox(p: Point): Box = {
    val r = nodeRadius + 4
    Box(p.x - r, p.y - r, 2 * r, 2 * r)
  }

  /** The approximate box of a channel note whose baseline centre is `at`. */
  def noteBox(at: Point, text: String): Box = {
    val w = text.length * 6.2 + 8
    Box(at.x - w / 2, at.y - 11, w, 15)
  }

  /** The spot of a note for the channel from `from` to `to`: near the receiver, beside the edge, at
    * the first place that covers the least of `avoid`.
    */
  def noteSpot(from: Point, to: Point, text: String, avoid: List[Box]): Point = {
    val dx = from.x - to.x
    val dy = from.y - to.y
    val length = math.max(1.0, math.sqrt(dx * dx + dy * dy))
    val (ux, uy) = (dx / length, dy / length)
    val spots = for {
      t <- List(0.3, 0.22, 0.4, 0.5, 0.15)
      side <- List(14.0, -14.0, 24.0, -24.0)
    } yield Point(
      round(to.x + ux * length * t - uy * side),
      round(to.y + uy * length * t + ux * side + 4)
    )
    spots.minBy(p => avoid.map(overlap(noteBox(p, text), _)).sum)
  }

  /** The centres of the envelopes of the channel from `from` to `to`, oldest first, on the edge and
    * near the receiver. When the reverse channel `shared` the edge, each queue gets only the half
    * of the edge at its receiver, so the two queues never overlap. Only the envelopes that fit get
    * a centre.
    *
    * When that queue covers one of the boxes in `avoid`, or when no envelope fits, the queue looks
    * for a better place: it slides along the edge, it can move to a lane beside the edge, and on a
    * shared edge it can use the full edge. It takes the place that covers the least. A box in
    * `hard` (another node, envelope or note) costs much more than a box in `avoid` (a label card),
    * and so does the circle of the sender or the receiver.
    */
  def queue(
      from: Point,
      to: Point,
      widths: Vector[Double],
      shared: Boolean,
      avoid: List[Box] = Nil,
      hard: List[Box] = Nil
  ): Vector[Point] = {
    val dx = from.x - to.x
    val dy = from.y - to.y
    val length = math.sqrt(dx * dx + dy * dy)
    if length <= 0 then { Vector.empty }
    else {
      val ux = dx / length
      val uy = dy / length
      // An axis-aligned envelope covers more of a slanted edge than of a level one.
      val extents = widths.map(extent(ux, uy, _))
      val w0 = widths.headOption.getOrElse(0.0)
      // The queue starts where its first envelope clears the receiver circle with room for the
      // arrowhead, and it ends at the middle of a shared edge or where the sender circle starts.
      val near = clearance(ux, uy, w0, 12.0)
      val tight = clearance(ux, uy, w0, 3.0)
      val full = length - nodeRadius - 10.0
      val end = if shared then { length / 2 - 4.0 }
      else { full }
      def spot(along: Double, lane: Double): Point =
        Point(round(to.x + ux * along - uy * lane), round(to.y + uy * along + ux * lane))
      def place(start: Double, lane: Double, stop: Double): Vector[Point] = {
        extents
          .scanLeft(start)((at, e) => at + 2 * e + envelopeGap)
          .zip(extents)
          .takeWhile { case (at, e) => at + 2 * e <= stop }
          .map { case (at, e) => spot(at + e, lane) }
      }
      def boxes(spots: Vector[Point]): Vector[Box] = spots.zip(widths).map { case (p, w) =>
        Box(p.x - w / 2, p.y - envelopeHeight / 2, w, envelopeHeight)
      }
      // An envelope inside the circle of the sender or the receiver costs as much as a full cover.
      def inCircle(b: Box, c: Point): Boolean = {
        val px = math.max(b.x, math.min(c.x, b.x + b.width)) - c.x
        val py = math.max(b.y, math.min(c.y, b.y + b.height)) - c.y
        math.sqrt(px * px + py * py) < nodeRadius + 2
      }
      def covered(spots: Vector[Point]): Double = boxes(spots).map { box =>
        val ends = if inCircle(box, to) || inCircle(box, from) then { box.width * box.height }
        else { 0.0 }
        avoid.map(overlap(box, _)).sum + 20 * hard.map(overlap(box, _)).sum + 20 * ends
      }.sum
      val first = extents.headOption.getOrElse(0.0)
      val normal = place(near, 0.0, end)
      val spots =
        if normal.nonEmpty && covered(normal) == 0 then { normal }
        else {
          val stops = if shared then { List(end, full) }
          else { List(end) }
          val candidates = for {
            stop <- stops
            lane <- List(0.0, 14.0, -14.0, 26.0, -26.0)
            start <- LazyList
              .iterate(tight)(_ + 3.0)
              .takeWhile(_ <= math.max(tight, stop - 2 * first))
              .toList
            sp = place(start, lane, stop) if sp.nonEmpty
          } yield (sp, covered(sp), math.abs(lane), stop)
          candidates
            .minByOption { case (sp, cover, lane, stop) => (cover, -sp.size, lane, stop) }
            .map(_._1)
            .getOrElse(normal)
        }
      // A queue never disappears: on a very short edge its first envelope still gets one spot.
      if spots.nonEmpty then { spots }
      else { extents.headOption.toVector.map(e => spot(near + e, 0.0)) }
    }
  }

  private def overlap(a: Box, b: Box): Double = {
    val w = math.min(a.x + a.width, b.x + b.width) - math.max(a.x, b.x)
    val h = math.min(a.y + a.height, b.y + b.height) - math.max(a.y, b.y)
    if w > 0 && h > 0 then { w * h }
    else { 0.0 }
  }

  /** The distance along the direction `(ux, uy)` from a node centre at which an envelope of width
    * `w`, centred on the line, first leaves a gap of 12 to the circle (room for an arrowhead). The
    * result is the near end of the envelope, so the centre is this value plus the extent.
    */
  private def clearance(ux: Double, uy: Double, w: Double, room: Double): Double = {
    val e = extent(ux, uy, w)
    val gap = nodeRadius + room
    def clear(d: Double): Boolean = {
      val cx = ux * (d + e)
      val cy = uy * (d + e)
      val px = math.max(cx - w / 2, math.min(0.0, cx + w / 2))
      val py = math.max(cy - envelopeHeight / 2, math.min(0.0, cy + envelopeHeight / 2))
      math.sqrt(px * px + py * py) >= gap
    }
    LazyList.iterate(0.0)(_ + 1.0).take(400).find(clear).getOrElse(gap)
  }

  /** Half the length of the edge that an axis-aligned envelope covers. */
  private def extent(ux: Double, uy: Double, w: Double): Double = {
    math.min(
      (w / 2) / math.max(math.abs(ux), 0.01),
      (envelopeHeight / 2) / math.max(math.abs(uy), 0.01)
    )
  }

  private def round(v: Double): Double = math.round(v * 10) / 10.0

  private def walk(topology: Topology): List[NodeId] = {
    val start = topology.initiators.headOption.orElse(topology.nodes.headOption)
    val visited = start.fold(List.empty[NodeId])(s => dfs(topology, List(s), Vector.empty))
    visited ++ topology.nodes.toList.filterNot(visited.contains)
  }

  @tailrec
  private def dfs(topology: Topology, stack: List[NodeId], seen: Vector[NodeId]): List[NodeId] = {
    stack match {
      case Nil                                 => seen.toList
      case node :: rest if seen.contains(node) => dfs(topology, rest, seen)
      case node :: rest                        =>
        val out = topology.outNeighbours(node).toList
        val in = topology.inNeighbours(node).toList.filterNot(out.contains)
        val next = (out ++ in).filterNot(seen.contains)
        dfs(topology, next ++ rest, seen :+ node)
    }
  }
}
