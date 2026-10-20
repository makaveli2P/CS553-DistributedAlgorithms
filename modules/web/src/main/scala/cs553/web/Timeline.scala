package cs553.web

import com.raquo.laminar.api.L.*

/** The quiet row under the canvas: step back, the slider, play and speed. A tick on the slider
  * marks the step where the algorithm decided and the step where the network became quiescent.
  */
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
      div(
        cls("track"),
        children <-- frame.map(ticks),
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

  /** The ticks of `frame`. The centre of the slider thumb moves from 7 px to the width less 7 px.
    */
  private def ticks(frame: Frame): List[HtmlElement] = {
    def tick(step: Int, kind: String, hint: String): HtmlElement = {
      val fraction = if frame.last <= 0 then { 0.0 }
      else { step.toDouble / frame.last }
      span(
        cls("tick", kind),
        title(hint),
        aria.hidden(true),
        styleAttr(s"left: calc(7px + (100% - 14px) * $fraction)")
      )
    }
    List(
      frame.decidedAt.map(n => tick(n, "tick--decided", s"Decided at step $n")),
      frame.quiescentAt.map(n => tick(n, "tick--quiescent", s"Quiescent at step $n"))
    ).flatten
  }
}
