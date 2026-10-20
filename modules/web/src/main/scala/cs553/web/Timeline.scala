package cs553.web

import com.raquo.laminar.api.L.*

/** The quiet row under the canvas: step back, the slider, play and speed. */
object Timeline {

  def render(screen: Signal[Screen], dispatch: Observer[UiEvent]): HtmlElement = {
    val frame = screen.map(_.frame)
    div(
      cls("timeline"),
      button(
        cls("quiet"),
        "Back",
        disabled <-- frame.map(_.step == 0),
        onClick.mapTo(UiEvent.StepBack) --> dispatch
      ),
      input(
        cls("slider"),
        typ("range"),
        minAttr("0"),
        aria.label("Step"),
        maxAttr <-- frame.map(_.last.toString),
        controlled(
          value <-- frame.map(_.step.toString),
          onInput.mapToValue.map(v => UiEvent.Seek(v.toIntOption.getOrElse(0))) --> dispatch
        )
      ),
      span(cls("step-count"), text <-- frame.map(f => s"${f.step} / ${f.last}")),
      button(
        cls("quiet"),
        text <-- screen.map(s =>
          if s.playing then { "Pause" }
          else { "Play" }
        ),
        onClick.mapTo(UiEvent.TogglePlay) --> dispatch
      ),
      button(
        cls("quiet", "speed"),
        aria.label("Speed"),
        text <-- screen.map(_.speed.label),
        onClick.mapTo(UiEvent.CycleSpeed) --> dispatch
      )
    )
  }
}
