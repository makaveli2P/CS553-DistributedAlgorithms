package cs553.web

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg as s
import com.raquo.laminar.nodes.ReactiveSvgElement
import cs553.core.Catalogue
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
      headerTag(cls("bar"), span(cls("brand"), "CS553 Distributed Algorithms")),
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
        div(
          cls("cards"),
          ready.map { alg =>
            a(
              cls("card"),
              href(Route.render(Route.Open(alg.id))),
              title(alg.summary),
              Thumbnail.of(alg).fold(placeholder)(picture),
              div(
                cls("card-label"),
                span(cls("card-name"), alg.name),
                span(cls("family"), alg.family.toString)
              )
            )
          }
        ),
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
      s.line(
        s.cls("thumb-edge"),
        s.cls("thumb-edge--tree") := e.tree,
        s.x1 := e.a.x.toString,
        s.y1 := e.a.y.toString,
        s.x2 := e.b.x.toString,
        s.y2 := e.b.y.toString
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
    frame(t.box, edges ++ nodes)
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
