package cs553.web

import com.raquo.laminar.api.L.*
import cs553.core.protocol.ChannelOrder

/** The side column: the settings, which are always visible, and the inspector of a node or an
  * envelope, which opens on a click and is closed by default.
  */
object Panels {

  /** The open inspector, or nothing. */
  def render(
      panel: Signal[Option[PanelView]],
      dispatch: Observer[UiEvent]
  ): Modifier[HtmlElement] = {
    child.maybe <-- panel.map(_.map(view => sectionTag(cls("panel"), content(view, dispatch))))
  }

  /** The settings card. A change starts a new run, so the card renders again with the new run. */
  def settings(
      settings: Signal[SettingsView],
      dispatch: Observer[UiEvent]
  ): Modifier[HtmlElement] = {
    child <-- settings.distinct.map(view =>
      sectionTag(cls("panel", "settings"), fields(view, dispatch))
    )
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
      case PanelView.NodeInfo(id, fields, waiting, lastMessage, lastRule, decision, actions) =>
        List(
          header(s"Node ${id.value}", dispatch),
          rows(fields ++ decision.map("decision" -> _).toList),
          if actions.isEmpty then { div(cls("actions-none")) }
          else {
            div(
              cls("actions"),
              h3("Actions"),
              actions.map { a =>
                button(
                  cls(if a.next then {
                    "primary"
                  } else { "secondary" }),
                  a.label,
                  onClick.mapTo(UiEvent.Fire(id, a.index)) --> dispatch
                )
              }
            )
          },
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
          case Some(Blocked.Recorded)     => Session.recordingLine
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
    }

  private def fields(settings: SettingsView, dispatch: Observer[UiEvent]): List[HtmlElement] =
    List(
      div(
        cls("field"),
        span("Source"),
        div(
          cls("choice-col"),
          settings.sources.map(src => sourceOption(src, settings, dispatch))
        ),
        settings.recordedLine.map(line => p(cls("hint"), line))
      ),
      label(
        cls("field"),
        span("Topology"),
        select(
          disabled(settings.source != RunSource.Browser),
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
          disabled(settings.source != RunSource.Browser),
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
    ) ++ Option
      .when(settings.source == RunSource.Browser)(
        p(cls("hint", "settings-hint"), "A change starts a new run.")
      )
      .toList

  private def sourceOption(
      src: RunSource,
      settings: SettingsView,
      dispatch: Observer[UiEvent]
  ): HtmlElement = {
    label(
      cls("choice"),
      input(
        typ("radio"),
        nameAttr("source"),
        checked(settings.source == src),
        onChange.mapTo(UiEvent.ChooseSource(src)) --> dispatch
      ),
      span(src.label)
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
        disabled(settings.orderLock.isDefined || settings.source != RunSource.Browser),
        onChange.mapTo(UiEvent.ChooseOrder(order)) --> dispatch
      ),
      span(text)
    )
  }
}
