package cs553.web

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg as s
import com.raquo.laminar.nodes.ReactiveSvgElement
import cs553.core.Catalogue
import cs553.core.Family
import cs553.core.registry.Algorithm
import cs553.core.registry.Registry
import org.scalajs.dom

/** The home screen. Its one question: which algorithm to open. Each algorithm is one card with a
  * picture of the end of its default run. The algorithms that are not ready are small muted tiles
  * with no picture, under a faint "Later" label, so the ready cards hold the visual weight. When
  * all the algorithms are ready, the screen shows no "Later" label.
  */
object Home {

  private type Svg = ReactiveSvgElement[dom.svg.Element]

  def render(notice: Signal[Option[Notice]]): HtmlElement = {
    val ready = Registry.all.map(_.algorithm)
    val later = Catalogue.algorithms.filter(e => Registry.find(e.id).isEmpty)
    div(
      cls("page", "home"),
      headerTag(cls("bar"), span(cls("brand"), "Distributed Algorithms")),
      mainTag(
        h1("Which algorithm do you want to run?"),
        child.maybe <-- notice.map(_.map { n =>
          p(
            cls("notice"),
            title(n.detail),
            onMountCallback(_ => dom.console.debug(s"cs553: ${n.detail}")),
            n.text
          )
        }),
        Family.values.toList.flatMap { family =>
          val group = ready.filter(_.family == family)
          if group.isEmpty then { Nil }
          else {
            List(
              sectionTag(
                cls("family-group"),
                h2(cls("family-title"), family.toString),
                div(cls("cards"), group.map(alg => card(alg)))
              )
            )
          }
        },
        if later.isEmpty then emptyNode
        else
          div(
            p(cls("later-label"), "Later"),
            div(
              cls("cards", "cards--later"),
              later.map { entry =>
                div(
                  cls("card", "card--later"),
                  aria.disabled(true),
                  span(cls("card-name"), entry.name)
                )
              }
            )
          )
      )
    )
  }

  private def card[S, M, A, O](alg: Algorithm[S, M, A, O]): HtmlElement = {
    a(
      cls("card"),
      href(Route.render(Route.Open(alg.id))),
      title(alg.summary),
      Thumbnail.of(alg).fold(placeholder)(picture),
      div(cls("card-label"), span(cls("card-name"), alg.name))
    )
  }

  private def frame(box: Box, content: List[Svg]): Svg = {
    s.svg(
      s.cls("thumb"),
      s.viewBox := box.attr,
      s.preserveAspectRatio := "xMidYMid meet",
      aria.hidden(true),
      content
    )
  }

  private def picture(t: Thumb): Svg = {
    val r = Thumbnail.nodeRadius.toString
    val edges = t.edges.sortBy(_.tree).map { e =>
      // A directed edge stops at the rim of the target node, so that the arrowhead shows.
      val dx = e.b.x - e.a.x
      val dy = e.b.y - e.a.y
      val length = math.max(1.0, math.sqrt(dx * dx + dy * dy))
      val inset = if e.directed then { Thumbnail.nodeRadius + 1.5 }
      else { 0.0 }
      s.line(
        s.cls("thumb-edge"),
        s.cls("thumb-edge--tree") := e.tree,
        s.x1 := e.a.x.toString,
        s.y1 := e.a.y.toString,
        s.x2 := (e.b.x - dx / length * inset).toString,
        s.y2 := (e.b.y - dy / length * inset).toString,
        s.markerEnd := (if e.directed then { "url(#thumb-arrow)" }
                        else { "" })
      )
    }
    val nodes = t.nodes.map { n =>
      s.circle(
        s.cls("thumb-node"),
        s.cls("thumb-node--chosen") := n.chosen,
        s.cx := n.at.x.toString,
        s.cy := n.at.y.toString,
        s.r := r
      )
    }
    val arrow = s.defs(
      s.marker(
        s.idAttr := "thumb-arrow",
        s.viewBox := "0 0 10 10",
        s.refX := "10",
        s.refY := "5",
        s.markerWidth := "6",
        s.markerHeight := "6",
        s.orient := "auto",
        s.path(s.d := "M 0 1 L 10 5 L 0 9 z", s.cls("thumb-arrowhead"))
      )
    )
    frame(t.box, arrow :: edges ++ nodes)
  }

  /** A faint ring of dots for an algorithm that has no run yet. */
  private def placeholder: Svg = {
    val size = Thumbnail.size
    val box = Box(-size.width / 2, -size.height / 2, size.width, size.height)
    val dots = (0 until 6).toList.map { i =>
      val angle = -math.Pi / 2 + 2 * math.Pi * i / 6
      s.circle(
        s.cls("thumb-dot"),
        s.cx := (Thumbnail.rx * math.cos(angle)).toString,
        s.cy := (Thumbnail.ry * math.sin(angle)).toString,
        s.r := "5"
      )
    }
    frame(box, dots)
  }
}
