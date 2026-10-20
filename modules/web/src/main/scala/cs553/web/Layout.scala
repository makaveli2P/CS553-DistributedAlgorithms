package cs553.web

import cs553.core.ids.NodeId
import cs553.core.topology.Topology

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

  private val labelMarginX: Double = 130.0
  private val labelMarginY: Double = 76.0

  /** The nodes on a circle, clockwise from the top. The order is a depth-first walk that follows
    * the out-edges first, so a directed ring keeps its ring order and a tree keeps its branches
    * together. The walk starts at the first initiator, or at the smallest node.
    */
  def positions(topology: Topology): Map[NodeId, Point] = {
    val order = walk(topology)
    val n = order.size
    val radius = math.max(150.0, n * 32.0)
    order.zipWithIndex.map { case (node, i) =>
      val angle = -math.Pi / 2 + 2 * math.Pi * i / math.max(n, 1)
      node -> Point(round(radius * math.cos(angle)), round(radius * math.sin(angle)))
    }.toMap
  }

  /** The view box that holds each node, with room for the phase labels. */
  def box(points: Iterable[Point]): Box = {
    val xs = points.map(_.x)
    val ys = points.map(_.y)
    val minX = xs.minOption.getOrElse(0.0) - labelMarginX
    val maxX = xs.maxOption.getOrElse(0.0) + labelMarginX
    val minY = ys.minOption.getOrElse(0.0) - labelMarginY
    val maxY = ys.maxOption.getOrElse(0.0) + labelMarginY
    Box(minX, minY, maxX - minX, maxY - minY)
  }

  /** The approximate width of an envelope with this label. */
  def envelopeWidth(label: String): Double = math.max(34.0, label.length * 6.6 + 14.0)

  /** The centres of the envelopes of the channel from `from` to `to`, oldest first, on the edge and
    * near the receiver. When the reverse channel `shared` the edge, each queue gets only the half
    * of the edge at its receiver, so the two queues never overlap. Only the envelopes that fit get
    * a centre.
    */
  def queue(from: Point, to: Point, widths: Vector[Double], shared: Boolean): Vector[Point] = {
    val dx = from.x - to.x
    val dy = from.y - to.y
    val length = math.sqrt(dx * dx + dy * dy)
    if length <= 0 then { Vector.empty }
    else {
      val ux = dx / length
      val uy = dy / length
      // An axis-aligned envelope covers more of a slanted edge than of a level one.
      val extents = widths.map(extent(ux, uy, _))
      // The queue starts where its first envelope clears the receiver circle, and it ends at the
      // middle of a shared edge or where the sender circle starts.
      val start =
        extents.headOption.fold(0.0)(_ => clearance(ux, uy, widths.headOption.getOrElse(0.0)))
      val end = if shared then { length / 2 - 4.0 }
      else { length - nodeRadius - 10.0 }
      extents
        .scanLeft(start)((at, e) => at + 2 * e + envelopeGap)
        .zip(extents)
        .takeWhile { case (at, e) => at + 2 * e <= end }
        .map { case (at, e) =>
          val along = at + e
          Point(round(to.x + ux * along), round(to.y + uy * along))
        }
    }
  }

  /** The distance along the direction `(ux, uy)` from a node centre at which an envelope of width
    * `w`, centred on the line, first leaves a gap of 12 to the circle (room for an arrowhead). The
    * result is the near end of the envelope, so the centre is this value plus the extent.
    */
  private def clearance(ux: Double, uy: Double, w: Double): Double = {
    val e = extent(ux, uy, w)
    val gap = nodeRadius + 12.0
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
