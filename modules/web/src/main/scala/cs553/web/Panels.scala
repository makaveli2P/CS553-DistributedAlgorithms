package cs553.web

import com.raquo.laminar.api.L.*
import cs553.core.protocol.ChannelOrder

/** The side panel: a node, an envelope or the settings. It opens on a click and is closed by
  * default.
  */
object Panels {

  def render(panel: Signal[Option[PanelView]], dispatch: Observer[UiEvent]): HtmlElement = {
    div(child.maybe <-- panel.map(_.map(view => asideTag(cls("panel"), content(view, dispatch)))))
  }

  private def header(title: String, dispatch: Observer[UiEvent]): HtmlElement = {
    div(
      cls("panel-head"),
      h2(title),
      button(cls("quiet"), "Close", onClick.mapTo(UiEvent.ClosePanel) --> dispatch)
    )
  }

  private def rows(items: List[(String, String)]): HtmlElement = {
    dl(items.flatMap { case (k, v) => List(dt(k), dd(v)) })
  }

  private def content(view: PanelView, dispatch: Observer[UiEvent]): List[HtmlElement] =
    view match {
      case PanelView.NodeInfo(id, phase, fields, lastMessage, lastRule, decision) =>
        List(
          header(s"Node ${id.value}", dispatch),
          p(phase),
          rows(
            fields ++
              lastMessage.map("last message" -> _).toList ++
              lastRule.map("last rule" -> _).toList ++
              decision.map("decision" -> _).toList
          )
        )
      case PanelView.EnvelopeInfo(env, from, to, canDeliver) =>
        val status = env.blocked match {
          case None                       => "Can be delivered now."
          case Some(Blocked.Behind(head)) => s"Waits behind $head. This channel is FIFO."
          case Some(Blocked.Past)         => "Continue from this step to deliver it."
        }
        List(
          header(env.label, dispatch),
          p(status),
          rows(
            List(
              "from" -> s"node ${from.value}",
              "to" -> s"node ${to.value}",
              "sequence" -> env.id.seq.toString
            ) ++
              env.sentAt.map(s => "sent at step" -> s.toString).toList ++
              env.fields
          )
        ) ++ (if canDeliver then {
                List(
                  button(
                    cls("secondary"),
                    "Deliver",
                    onClick.mapTo(UiEvent.Deliver(env.id)) --> dispatch
                  )
                )
              } else { Nil })
      case PanelView.SettingsInfo(settings) =>
        List(
          header("Settings", dispatch),
          p("A change starts a new run."),
          label(
            cls("field"),
            span("Topology"),
            select(
              settings.presets.map(name =>
                option(value(name), name, selected(settings.preset.contains(name)))
              ),
              onChange.mapToValue.map(UiEvent.ChoosePreset(_)) --> dispatch
            )
          ),
          label(
            cls("field"),
            span("Seed"),
            input(
              typ("number"),
              minAttr("0"),
              defaultValue(settings.seed.toString),
              onChange.mapToValue.map(_.toLongOption).collect { case Some(seed) =>
                UiEvent.ChooseSeed(seed)
              } --> dispatch
            )
          ),
          div(
            cls("field"),
            span("Channel order"),
            div(
              cls("choice-row"),
              orderOption(
                "FIFO",
                ChannelOrder.Fifo,
                settings.fifo,
                settings.orderLock.isDefined,
                dispatch
              ),
              orderOption(
                "Any order",
                ChannelOrder.AnyOrder,
                !settings.fifo,
                settings.orderLock.isDefined,
                dispatch
              )
            ),
            settings.orderLock.map(reason => p(cls("hint"), reason))
          )
        )
    }

  private def orderOption(
      text: String,
      order: ChannelOrder,
      on: Boolean,
      locked: Boolean,
      dispatch: Observer[UiEvent]
  ): HtmlElement = {
    label(
      cls("choice"),
      input(
        typ("radio"),
        nameAttr("order"),
        checked(on),
        disabled(locked),
        onChange.mapTo(UiEvent.ChooseOrder(order)) --> dispatch
      ),
      span(text)
    )
  }
}
