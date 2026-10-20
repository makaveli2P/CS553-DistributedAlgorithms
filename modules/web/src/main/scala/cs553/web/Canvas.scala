package cs553.web

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg as s
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.scalajs.dom

/** The SVG canvas: edges, nodes, phase labels, actions and mailboxes. It draws a `Frame` and
  * computes nothing about the algorithm.
  */
object Canvas {

  private type Svg = ReactiveSvgElement[dom.svg.Element]

  def render(frame: Signal[Frame], dispatch: Observer[UiEvent]): HtmlElement = {
    div(cls("canvas"), child <-- frame.map(f => draw(f, dispatch)))
  }

  private def draw(frame: Frame, dispatch: Observer[UiEvent]): Svg = {
    val at = frame.nodes.map(n => n.id -> n.at).toMap
    s.svg(
      s.cls("graph"),
      s.viewBox := frame.box.attr,
      s.preserveAspectRatio := "xMidYMid meet",
      s.defs(
        s.marker(
          s.idAttr := "arrow",
          s.viewBox := "0 0 10 10",
          s.refX := "10",
          s.refY := "5",
          s.markerWidth := "7",
          s.markerHeight := "7",
          s.orient := "auto-start-reverse",
          s.path(s.d := "M 0 1 L 10 5 L 0 9 z", s.cls("arrowhead"))
        )
      ),
      s.g(frame.edges.flatMap(e => at.get(e.from).zip(at.get(e.to)).map((a, b) => edge(e, a, b)))),
      s.g(frame.nodes.map(n => node(n, dispatch))),
      s.g(
        frame.channels.flatMap { c =>
          at.get(c.from)
            .zip(at.get(c.to))
            .toList
            .flatMap((a, b) => Mailbox.render(c, a, b, dispatch))
        }
      )
    )
  }

  private def edge(e: EdgeView, a: Point, b: Point): Svg = {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = math.max(1.0, math.sqrt(dx * dx + dy * dy))
    val inset = Layout.nodeRadius + 3
    val (ux, uy) = (dx / length, dy / length)
    s.line(
      s.cls("edge"),
      s.cls("edge--tree") := e.tree,
      s.x1 := (a.x + ux * inset).toString,
      s.y1 := (a.y + uy * inset).toString,
      s.x2 := (b.x - ux * inset).toString,
      s.y2 := (b.y - uy * inset).toString,
      if e.directed then { s.markerEnd := "url(#arrow)" }
      else { emptyMod }
    )
  }

  private def node(n: NodeView, dispatch: Observer[UiEvent]): Svg = {
    val r = Layout.nodeRadius
    // Nodes at the top or bottom of the circle get the label above or below. Nodes at the sides
    // get it outside, wrapped at each comma, so that a narrow screen does not clip it.
    val length = math.max(1.0, math.sqrt(n.at.x * n.at.x + n.at.y * n.at.y))
    val vertical = math.abs(n.at.y / length) > 0.9
    val above = n.at.y < 0
    val lines =
      if vertical then { List(n.phase) }
      else { n.phase.split(", ").toList }
    val lineHeight = 17.0
    val (lx, ly, anchor) =
      if vertical && above then { (n.at.x, n.at.y - r - 12, "middle") }
      else if vertical then { (n.at.x, n.at.y + r + 22, "middle") }
      else if n.at.x > 0 then {
        (n.at.x + r + 10, n.at.y + 5 - lineHeight * (lines.size - 1) / 2, "start")
      } else { (n.at.x - r - 10, n.at.y + 5 - lineHeight * (lines.size - 1) / 2, "end") }
    val actionDy = if vertical && above then { -20.0 }
    else { 20.0 }
    val actionY = if vertical then { ly }
    else { ly + lineHeight * (lines.size - 1) }
    s.g(
      s.cls("node"),
      s.cls("node--current") := n.current,
      s.cls("node--decided") := n.decided,
      s.circle(
        s.cx := n.at.x.toString,
        s.cy := n.at.y.toString,
        s.r := r.toString,
        s.cls("node-circle"),
        onClick.mapTo(UiEvent.SelectNode(n.id)) --> dispatch
      ),
      s.text(
        s.cls("node-id"),
        s.x := n.at.x.toString,
        s.y := (n.at.y + 5).toString,
        s.textAnchor := "middle",
        n.id.value.toString
      ),
      s.text(
        s.cls("node-phase"),
        s.x := lx.toString,
        s.y := ly.toString,
        s.textAnchor := anchor,
        lines.zipWithIndex.map { case (line, i) =>
          s.tspan(
            s.x := lx.toString,
            s.dy := (if i == 0 then { "0" }
                     else { lineHeight.toString }),
            line
          )
        }
      ),
      n.actions.zipWithIndex.map { case (a, i) =>
        s.text(
          s.cls("node-action"),
          s.x := lx.toString,
          s.y := (actionY + actionDy * (i + 1)).toString,
          s.textAnchor := anchor,
          s.titleTag(s"Node ${n.id.value}: ${a.label}"),
          a.label,
          onClick.mapTo(UiEvent.Fire(n.id, a.index)) --> dispatch
        )
      }
    )
  }
}

/** The queue of one channel, drawn near the receiver. */
object Mailbox {

  private type Svg = ReactiveSvgElement[dom.svg.Element]

  def render(c: ChannelView, from: Point, to: Point, dispatch: Observer[UiEvent]): List[Svg] = {
    val widths = c.queue.map(e => Layout.envelopeWidth(e.label))
    val spots = Layout.queue(from, to, widths, c.shared)
    val hidden = c.queue.size - spots.size
    if hidden <= 0 then { c.queue.zip(spots).toList.map((e, p) => envelope(e, p, dispatch)) }
    else {
      val shown = c.queue.zip(spots).dropRight(1).toList.map((e, p) => envelope(e, p, dispatch))
      val more = spots.lastOption.toList.map { p =>
        s.text(
          s.cls("queue-more"),
          s.x := p.x.toString,
          s.y := (p.y + 4).toString,
          s.textAnchor := "middle",
          s"+${hidden + 1}"
        )
      }
      shown ++ more
    }
  }

  private def envelope(e: EnvelopeView, p: Point, dispatch: Observer[UiEvent]): Svg = {
    val w = Layout.envelopeWidth(e.label)
    val h = Layout.envelopeHeight
    val tip = {
      val base = s"${e.label} from node ${e.id.from.value} to node ${e.id.to.value}"
      e.blocked match {
        case None                       => s"$base. Click to deliver."
        case Some(Blocked.Behind(head)) => s"$base. It waits behind $head: this channel is FIFO."
        case Some(Blocked.Past)         => s"$base. Continue from this step to deliver it."
      }
    }
    val event = e.blocked match {
      case None    => UiEvent.Deliver(e.id)
      case Some(_) => UiEvent.SelectEnvelope(e.id)
    }
    s.g(
      s.cls("envelope"),
      s.cls("envelope--blocked") := e.blocked.isDefined,
      s.cls("envelope--next") := e.next,
      s.transform := s"translate(${p.x} ${p.y})",
      s.titleTag(tip),
      s.rect(
        s.x := (-w / 2).toString,
        s.y := (-h / 2).toString,
        s.width := w.toString,
        s.height := h.toString,
        s.rx := "4"
      ),
      s.text(s.y := "4", s.textAnchor := "middle", e.label),
      onClick.mapTo(event) --> dispatch
    )
  }
}
