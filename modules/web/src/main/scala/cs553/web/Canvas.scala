package cs553.web

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg as s
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.laminar.nodes.ReactiveSvgElement
import cs553.core.ids.NodeId
import cs553.core.trace.ChannelRecord
import cs553.core.trace.Chip
import cs553.core.trace.EdgeStyle
import cs553.core.trace.NodeFill
import org.scalajs.dom

/** The SVG canvas: edges, nodes, phase labels, actions and mailboxes. It draws a `Frame` and
  * computes nothing about the algorithm.
  *
  * The `svg` element stays mounted. Each node, edge and envelope is one keyed element whose
  * attributes follow the frame, so a step changes only what it touches. The canvas reports its
  * measured size, and the view box is that size, so one unit is one pixel.
  */
object Canvas {

  private type Svg = ReactiveSvgElement[dom.svg.Element]

  def render(frame: Signal[Frame], dispatch: Observer[UiEvent]): HtmlElement = {
    div(
      cls("canvas"),
      s.svg(
        s.cls("graph"),
        s.viewBox <-- frame.map(_.box.attr).distinct,
        s.preserveAspectRatio := "xMidYMid meet",
        measure(dispatch),
        s.defs(
          s.marker(
            s.idAttr := "arrow",
            s.viewBox := "0 0 10 10",
            s.refX := "10",
            s.refY := "5",
            s.markerWidth := "8",
            s.markerHeight := "8",
            s.orient := "auto-start-reverse",
            s.path(s.d := "M 0 1 L 10 5 L 0 9 z", s.cls("arrowhead"))
          )
        ),
        s.g(children <-- frame.map(_.edges).split(e => (e.from, e.to))((_, _, e) => edge(e))),
        s.g(children <-- frame.map(_.notes).split(n => (n.from, n.to))((_, _, n) => note(n))),
        s.g(children <-- frame.map(_.nodes).split(_.id)((id, _, n) => node(id, n, dispatch))),
        s.g(
          children <-- frame
            .map(_.envelopes)
            .split(_.id)((_, _, e) => Mailbox.envelope(e, dispatch))
        ),
        s.g(children <-- frame.map(_.more).split(m => (m.from, m.to))((_, _, m) => Mailbox.more(m)))
      )
    )
  }

  /** Reports the size of the `svg` element on mount and on each resize. */
  private def measure(dispatch: Observer[UiEvent]): Modifier[Svg] = {
    onMountUnmountCallbackWithState[Svg, dom.ResizeObserver](
      mount = ctx => {
        val observer = new dom.ResizeObserver((entries, _) => {
          entries.headOption.foreach { entry =>
            val rect = entry.contentRect
            if rect.width > 0 && rect.height > 0 then {
              dispatch.onNext(
                UiEvent.Resized(
                  Size(math.round(rect.width).toDouble, math.round(rect.height).toDouble)
                )
              )
            }
          }
        })
        observer.observe(ctx.thisNode.ref)
        observer
      },
      unmount = (_, observer) => observer.foreach(_.disconnect())
    )
  }

  private val tabIndexAttr = s.svgAttr("tabindex", StringAsIsCodec, None)
  private val roleAttr = s.svgAttr("role", StringAsIsCodec, None)
  private val ariaLabelAttr = s.svgAttr("aria-label", StringAsIsCodec, None)

  /** Makes an SVG element work like a button: a click, Enter or Space sends the event. */
  def pressable(label: Signal[String], event: Signal[UiEvent], dispatch: Observer[UiEvent]) = {
    List[Modifier[Svg]](
      tabIndexAttr := "0",
      roleAttr := "button",
      ariaLabelAttr <-- label,
      onClick.compose(_.sample(event)) --> dispatch,
      onKeyDown
        .filter(k => k.key == "Enter" || k.key == " ")
        .preventDefault
        .compose(_.sample(event)) --> dispatch
    )
  }

  private def edge(e: Signal[EdgeView]): Svg = {
    def ends(v: EdgeView): (Double, Double, Double, Double) = {
      val dx = v.b.x - v.a.x
      val dy = v.b.y - v.a.y
      val length = math.max(1.0, math.sqrt(dx * dx + dy * dy))
      val inset = Layout.nodeRadius + 3
      val (ux, uy) = (dx / length, dy / length)
      (v.a.x + ux * inset, v.a.y + uy * inset, v.b.x - ux * inset, v.b.y - uy * inset)
    }
    val line = e.map(ends)
    s.line(
      s.cls("edge"),
      s.cls <-- e.map(v => v.style.map(edgeClass).toList ++ v.record.map(recordClass).toList),
      s.x1 <-- line.map(_._1.toString),
      s.y1 <-- line.map(_._2.toString),
      s.x2 <-- line.map(_._3.toString),
      s.y2 <-- line.map(_._4.toString),
      s.markerEnd <-- e.map(v =>
        if v.directed then { "url(#arrow)" }
        else { "" }
      )
    )
  }

  private def node(id: NodeId, n: Signal[NodeView], dispatch: Observer[UiEvent]): Svg = {
    val r = Layout.nodeRadius
    s.g(
      s.cls("node"),
      s.cls("node--current") <-- n.map(_.current),
      s.cls("node--decided") <-- n.map(_.decided),
      s.cls <-- n.map(_.fill.map(fillClass).toList),
      child.maybe <-- n
        .map(v => v.cutAt.map(step => (v.at, step)))
        .distinct
        .map(_.map { case (at, step) =>
          cut(at, step)
        }),
      s.g(
        s.cls("node-hit"),
        pressable(Val(s"Node ${id.value}"), Val(UiEvent.SelectNode(id)), dispatch),
        s.circle(
          s.cls("node-circle"),
          s.cx <-- n.map(_.at.x.toString),
          s.cy <-- n.map(_.at.y.toString),
          s.r := r.toString
        ),
        s.text(
          s.cls("node-id"),
          s.x <-- n.map(_.at.x.toString),
          s.y <-- n.map(v => (v.at.y + 5).toString),
          s.textAnchor := "middle",
          id.value.toString
        )
      ),
      child <-- n.map(v => (Layout.labelSpot(v.at, v.side, v.lines.size), v.lines)).distinct.map {
        case (spot, lines) =>
          label(spot, lines)
      },
      child <-- n.map(v => (Layout.labelSpot(v.at, v.side, v.lines.size), v.actions)).distinct.map {
        case (spot, actions) =>
          s.g(Layout.actionBox(spot, actions).toList.map(box => action(id, actions, box, dispatch)))
      }
    )
  }

  /** The CSS class of an edge style. */
  def edgeClass(style: EdgeStyle): String = style match {
    case EdgeStyle.Tree     => "edge--tree"
    case EdgeStyle.Used     => "edge--used"
    case EdgeStyle.Removed  => "edge--removed"
    case EdgeStyle.WaitsFor => "edge--waits"
  }

  /** The CSS class of the record state of a snapshot channel. */
  def recordClass(record: ChannelRecord): String = record match {
    case ChannelRecord.Recording => "edge--recording"
    case ChannelRecord.Closed    => "edge--closed"
  }

  /** The CSS class of a node fill. */
  def fillClass(fill: NodeFill): String = fill match {
    case NodeFill.Chosen     => "node--chosen"
    case NodeFill.Decided    => "node--decided"
    case NodeFill.Passive    => "node--passive"
    case NodeFill.Red        => "node--red"
    case NodeFill.White      => "node--white"
    case NodeFill.Free       => "node--free"
    case NodeFill.Deadlocked => "node--deadlocked"
  }

  /** A dashed ring around a node that took its local snapshot at `step`. */
  private def cut(at: Point, step: Int): Svg = {
    s.circle(
      s.cls("node-cut"),
      s.cx := at.x.toString,
      s.cy := at.y.toString,
      s.r := (Layout.nodeRadius + 5).toString,
      s.titleTag(s"Local snapshot at step $step")
    )
  }

  /** The text of a `Channel` mark next to its channel. */
  private def note(n: Signal[NoteView]): Svg = {
    s.text(
      s.cls("channel-note"),
      s.x <-- n.map(_.at.x.toString),
      s.y <-- n.map(_.at.y.toString),
      s.textAnchor := "middle",
      child.text <-- n.map(_.text)
    )
  }

  /** The label lines on a card of the canvas colour, so that an edge does not cross the text. */
  private def label(spot: LabelSpot, lines: List[String]): Svg = {
    val card = Layout.labelCard(spot, lines)
    s.g(
      s.rect(
        s.cls("label-card"),
        s.x := card.x.toString,
        s.y := card.y.toString,
        s.width := card.width.toString,
        s.height := card.height.toString,
        s.rx := "3"
      ),
      labelText(spot, lines)
    )
  }

  private def labelText(spot: LabelSpot, lines: List[String]): Svg = {
    s.text(
      s.cls("node-phase"),
      s.x := spot.x.toString,
      s.y := spot.firstY.toString,
      s.textAnchor := spot.anchor,
      lines.zipWithIndex.map { case (line, i) =>
        s.tspan(
          s.x := spot.x.toString,
          s.dy := (if i == 0 then { "0" }
                   else { Layout.lineHeight.toString }),
          line
        )
      }
    )
  }

  /** The one action chip of a node, drawn like an envelope next to the node. A node with one action
    * shows it and fires it. A node with several actions shows a count and opens the inspector of
    * the node, which lists the actions as buttons.
    */
  private def action(
      id: NodeId,
      actions: List[ActionView],
      box: Box,
      dispatch: Observer[UiEvent]
  ): Svg = {
    val w = box.width
    val h = box.height
    val text = Layout.actionText(actions).getOrElse("")
    val (event, tip) = actions match {
      case one :: Nil => (UiEvent.Fire(id, one.index), s"Node ${id.value}: ${one.label}")
      case _          => (UiEvent.SelectNode(id), s"Node ${id.value}: choose an action")
    }
    s.g(
      s.cls("envelope", "action"),
      s.cls("envelope--next") := actions.exists(_.next),
      s.transform := s"translate(${box.x + w / 2} ${box.y + h / 2})",
      pressable(Val(tip), Val(event), dispatch),
      s.titleTag(tip),
      Mailbox.hit(w),
      s.rect(
        s.cls("token"),
        s.x := (-w / 2).toString,
        s.y := (-h / 2).toString,
        s.width := w.toString,
        s.height := h.toString,
        s.rx := "4"
      ),
      s.text(s.y := "4", s.textAnchor := "middle", text)
    )
  }
}

/** The envelopes of the mailboxes. Each one waits on its edge near the receiver, with a notch that
  * points at the receiver.
  */
object Mailbox {

  private type Svg = ReactiveSvgElement[dom.svg.Element]

  /** A transparent rectangle that makes a small mark easy to tap. */
  def hit(w: Double): Svg = {
    val hw = math.max(w + 8, 40.0)
    s.rect(
      s.cls("hit"),
      s.x := (-hw / 2).toString,
      s.y := "-16",
      s.width := hw.toString,
      s.height := "32"
    )
  }

  private def tip(e: EnvelopeView): String = e.blocked match {
    case None                       => s"${e.describe}. Click to deliver."
    case Some(Blocked.Behind(head)) =>
      s"${e.describe}. It waits behind $head: this channel is FIFO."
    case Some(Blocked.Past)     => s"${e.describe}. Continue from this step to deliver it."
    case Some(Blocked.Recorded) => s"${e.describe}. ${Session.recordingLine}"
  }

  private def event(e: EnvelopeView): UiEvent = e.blocked match {
    case None    => UiEvent.Deliver(e.id)
    case Some(_) => UiEvent.SelectEnvelope(e.id)
  }

  /** The notch: a small triangle on the side of the envelope that faces the receiver. */
  private def notch(e: EnvelopeView): String = {
    val w = Layout.chipWidth(e.chip)
    val h = e.chip match {
      case Chip.Control               => Layout.controlSize
      case Chip.Text(_) | Chip.Red(_) => Layout.envelopeHeight
    }
    val dx = e.toward.x - e.at.x
    val dy = e.toward.y - e.at.y
    val length = math.max(1.0, math.sqrt(dx * dx + dy * dy))
    val (ux, uy) = (dx / length, dy / length)
    val t = math.min((w / 2) / math.max(math.abs(ux), 0.01), (h / 2) / math.max(math.abs(uy), 0.01))
    val (bx, by) = (ux * t, uy * t)
    val (px, py) = (-uy * 4, ux * 4)
    val (tx, ty) = (ux * (t + 6), uy * (t + 6))
    def r(v: Double): String = (math.round(v * 10) / 10.0).toString
    s"M ${r(bx + px)} ${r(by + py)} L ${r(tx)} ${r(ty)} L ${r(bx - px)} ${r(by - py)} Z"
  }

  def envelope(e: Signal[EnvelopeView], dispatch: Observer[UiEvent]): Svg = {
    val chip = e.map(_.chip).distinct
    s.g(
      s.cls("envelope"),
      s.cls("envelope--blocked") <-- e.map(_.blocked.isDefined),
      s.cls("envelope--next") <-- e.map(_.next),
      s.cls("envelope--fresh") <-- e.map(_.fresh),
      s.cls("envelope--red") <-- chip.map {
        case Chip.Red(_)                 => true
        case Chip.Text(_) | Chip.Control => false
      },
      s.cls("envelope--control") <-- chip.map(_ == Chip.Control),
      s.transform <-- e.map(v => s"translate(${v.at.x} ${v.at.y})"),
      Canvas.pressable(e.map(_.describe), e.map(event), dispatch),
      s.titleTag(child.text <-- e.map(tip)),
      child <-- chip.map(c => hit(Layout.chipWidth(c))),
      s.path(s.cls("notch"), s.d <-- e.map(notch)),
      child <-- chip.map(body)
    )
  }

  /** The body of a chip: a card with short text, or a diamond for a control message. */
  private def body(chip: Chip): Svg = chip match {
    case Chip.Control =>
      val d = Layout.controlSize / 2
      s.path(s.cls("token"), s.d := s"M 0 ${-d} L $d 0 L 0 $d L ${-d} 0 Z")
    case Chip.Text(text) => card(text)
    case Chip.Red(text)  => card(text)
  }

  private def card(text: String): Svg = {
    val w = Layout.chipWidth(Chip.Text(text))
    val h = Layout.envelopeHeight
    s.g(
      s.rect(
        s.cls("token"),
        s.x := (-w / 2).toString,
        s.y := (-h / 2).toString,
        s.width := w.toString,
        s.height := h.toString,
        s.rx := "4"
      ),
      s.text(s.y := "4", s.textAnchor := "middle", text)
    )
  }

  def more(m: Signal[MoreView]): Svg = {
    s.text(
      s.cls("queue-more"),
      s.x <-- m.map(_.at.x.toString),
      s.y <-- m.map(v => (v.at.y + 4).toString),
      s.textAnchor := "middle",
      child.text <-- m.map(v => s"+${v.count}")
    )
  }
}
