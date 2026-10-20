package cs553.web

import com.raquo.laminar.api.L.*
import cs553.core.protocol.ChannelOrder

/** The side panel: a node, an envelope or the settings. It opens on a click and is closed by
  * default.
  */
object Panels {

  /** The open panel, as a child of the stage. The stage gives it a column next to the canvas. */
  def render(
      panel: Signal[Option[PanelView]],
      dispatch: Observer[UiEvent]
  ): Modifier[HtmlElement] = {
    child.maybe <-- panel.map(_.map(view => asideTag(cls("panel"), content(view, dispatch))))
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
      case PanelView.NodeInfo(id, fields, waiting, lastMessage, lastRule, decision) =>
        List(
          header(s"Node ${id.value}", dispatch),
          rows(fields ++ decision.map("decision" -> _).toList),
          h3("Waiting for this node"),
          if waiting.isEmpty then { p(cls("empty"), "Nothing waiting") }
          else { ul(cls("waiting"), waiting.map(text => li(text))) },
          rows(
            lastMessage.map("last handled" -> _).toList ++ lastRule.map("rule" -> _).toList
          )
        )
      case PanelView.EnvelopeInfo(env, canDeliver) =>
        val status = env.blocked match {
          case None                       => "Can be delivered now."
          case Some(Blocked.Behind(head)) => s"Waits behind $head. This channel is FIFO."
          case Some(Blocked.Past)         => "Continue from this step to deliver it."
        }
        List(
          header(env.label, dispatch),
          p(s"${env.describe}. $status"),
          rows(env.sentAt.map(s => "sent at step" -> s.toString).toList ++ env.fields)
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
              settings.presets.map(choice =>
                option(
                  value(choice.name),
                  choice.title,
                  selected(settings.preset.contains(choice.name))
                )
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
              stepAttr("1"),
              defaultValue(settings.seed.toString),
              // A value that is not a whole number from 0 goes back to the seed of the run.
              inContext { el =>
                onChange.mapToValue --> { text =>
                  text.trim.toLongOption.filter(_ >= 0) match {
                    case Some(seed) => dispatch.onNext(UiEvent.ChooseSeed(seed))
                    case None       => el.ref.value = settings.seed.toString
                  }
                }
              }
            )
          ),
          div(
            cls("field"),
            span("Channel order"),
            div(
              cls("choice-row"),
              orderOption("FIFO", ChannelOrder.Fifo, settings, dispatch),
              orderOption("Any order", ChannelOrder.AnyOrder, settings, dispatch)
            ),
            settings.orderLock.map(reason => p(cls("hint"), reason))
          )
        )
    }

  private def orderOption(
      text: String,
      order: ChannelOrder,
      settings: SettingsView,
      dispatch: Observer[UiEvent]
  ): HtmlElement = {
    label(
      cls("choice"),
      input(
        typ("radio"),
        nameAttr("order"),
        checked(settings.order == order),
        disabled(settings.orderLock.isDefined),
        onChange.mapTo(UiEvent.ChooseOrder(order)) --> dispatch
      ),
      span(text)
    )
  }
}
