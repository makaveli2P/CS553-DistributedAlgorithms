package cs553.web

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg as s
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.scalajs.dom

/** The space-time view: one line for each node, one dot for each event, one arrow for each message.
  * It draws a `SpaceLayout` and computes nothing. The drawing keeps its own size and the box around
  * it scrolls.
  */
object SpaceTimeView {

  private type Svg = ReactiveSvgElement[dom.svg.Element]

  def render(
      layout: Signal[Option[SpaceLayout]],
      mode: Signal[ViewMode],
      dispatch: Observer[UiEvent]
  ): HtmlElement = {
    div(
      cls("canvas", "spacetime"),
      cls("canvas--hidden") <-- mode.map(_ != ViewMode.SpaceTime),
      inContext { box =>
        layout --> Observer[Option[SpaceLayout]] { shown =>
          // The drawing scrolls inside the box. Keep the shown step in view.
          val _ = dom.window.requestAnimationFrame { _ =>
            shown.foreach(l => keepInView(box.ref, l))
          }
        }
      },
      child.maybe <-- layout.map(_.map(l => drawing(l, dispatch)))
    )
  }

  private def keepInView(box: dom.html.Element, l: SpaceLayout): Unit = {
    val room = 48.0
    l.orientation match {
      case Orientation.Horizontal =>
        val x = l.now._1.x
        if x < box.scrollLeft + room || x > box.scrollLeft + box.clientWidth - room then {
          box.scrollLeft = math.max(0.0, x - box.clientWidth / 2)
        }
      case Orientation.Vertical =>
        val y = l.now._1.y
        if y < box.scrollTop + room || y > box.scrollTop + box.clientHeight - room then {
          box.scrollTop = math.max(0.0, y - box.clientHeight / 2)
        }
    }
  }

  private def drawing(l: SpaceLayout, dispatch: Observer[UiEvent]): Svg = {
    s.svg(
      s.cls("spacetime-svg"),
      s.width := l.width.toString,
      s.height := l.height.toString,
      s.defs(
        s.marker(
          s.idAttr := "st-arrow",
          s.viewBox := "0 0 10 10",
          s.refX := "10",
          s.refY := "5",
          s.markerWidth := "7",
          s.markerHeight := "7",
          s.orient := "auto",
          s.path(s.d := "M 0 1 L 10 5 L 0 9 z", s.cls("st-arrowhead"))
        )
      ),
      l.markers.map(marker),
      l.lanes.map(lane),
      List(now(l.now)),
      l.arrows.map(arrow),
      cutLine(l.cut),
      l.dots.map(dot(_, dispatch))
    )
  }

  private def lane(l: SpaceLane): Svg = {
    s.g(
      s.line(
        s.cls("st-lane"),
        s.x1 := l.from.x.toString,
        s.y1 := l.from.y.toString,
        s.x2 := l.to.x.toString,
        s.y2 := l.to.y.toString
      ),
      s.text(
        s.cls("st-label"),
        s.x := l.label.x.toString,
        s.y := (l.label.y + 4).toString,
        s.textAnchor := "start",
        s"Node ${l.node.value}"
      )
    )
  }

  /** The line of the shown step across all node lines. */
  private def now(ends: (Point, Point)): Svg = {
    s.line(
      s.cls("st-now"),
      s.x1 := ends._1.x.toString,
      s.y1 := ends._1.y.toString,
      s.x2 := ends._2.x.toString,
      s.y2 := ends._2.y.toString
    )
  }

  private def marker(m: SpaceMarker): Svg = {
    val (name, css, anchor) = m.kind match {
      case MarkerKind.Decided   => ("decided", "st-marker--decided", "start")
      case MarkerKind.Quiescent => ("quiescent", "st-marker--quiescent", "end")
    }
    s.g(
      s.line(
        s.cls("st-marker", css),
        s.x1 := m.from.x.toString,
        s.y1 := m.from.y.toString,
        s.x2 := m.to.x.toString,
        s.y2 := m.to.y.toString
      ),
      s.text(
        s.cls("st-marker-label", css),
        s.x := m.label.x.toString,
        s.y := m.label.y.toString,
        s.textAnchor := anchor,
        s"$name at step ${m.step}"
      )
    )
  }

  private def arrow(a: SpaceArrow): Svg = {
    s.line(
      s.cls("st-arrow"),
      s.cls("st-arrow--open") := a.open,
      s.x1 := a.from.x.toString,
      s.y1 := a.from.y.toString,
      s.x2 := a.to.x.toString,
      s.y2 := a.to.y.toString,
      s.markerEnd := (if a.open then { "" }
                      else { "url(#st-arrow)" }),
      s.titleTag(
        if a.open then { s"${a.label}, in flight" }
        else { a.label }
      )
    )
  }

  /** The cut of a snapshot: a dashed line through the record point of each node. */
  private def cutLine(points: List[Point]): List[Svg] = {
    if points.isEmpty then { Nil }
    else {
      val path = points.zipWithIndex
        .map { case (p, i) =>
          val command = if i == 0 then { "M" }
          else { "L" }
          s"$command ${p.x} ${p.y}"
        }
        .mkString(" ")
      val rings = points.map { p =>
        s.circle(
          s.cls("st-cut-ring"),
          s.cx := p.x.toString,
          s.cy := p.y.toString,
          s.r := "8"
        )
      }
      s.path(s.cls("st-cut"), s.d := path, s.titleTag("Cut: each node's snapshot")) :: rings
    }
  }

  private def dot(d: PlacedDot, dispatch: Observer[UiEvent]): Svg = {
    val kind = d.dot.kind match {
      case DotKind.Step     => "st-dot--step"
      case DotKind.Rejected => "st-dot--rejected"
      case DotKind.Decided  => "st-dot--decided"
    }
    s.circle(
      s.cls("st-dot", kind),
      s.cls("st-dot--now") := d.current,
      s.cx := d.at.x.toString,
      s.cy := d.at.y.toString,
      s.r := "5.5",
      s.titleTag(d.dot.tip),
      onClick.mapTo(UiEvent.Seek(d.dot.step)) --> dispatch
    )
  }
}
