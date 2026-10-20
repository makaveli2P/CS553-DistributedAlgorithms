package cs553.web

import com.raquo.laminar.api.L.*

/** The lesson screen: the canvas at the step of the screen, one short sentence, and Back and Next.
  * The last screen asks one question. The learner answers with a click on a node of the canvas. The
  * lesson counts as seen when the learner answers it right or leaves it for the sandbox.
  */
object LessonView {

  def render(screen: Signal[LessonScreen], dispatch: Observer[UiEvent]): HtmlElement = {
    val frame = screen.map(_.frame)
    val algorithm = screen.map(_.algorithm).distinct
    div(
      cls("page", "sandbox", "lesson"),
      screen.map(_.reply).distinct.withCurrentValueOf(algorithm) --> Observer[
        (Option[Reply], cs553.core.ids.AlgorithmId)
      ] {
        case (Some(Reply.Right(_)), id) => Seen.mark(id)
        case _                          => ()
      },
      headerTag(
        cls("bar"),
        a(cls("back"), href(Route.render(Route.Home)), "All algorithms"),
        div(
          cls("bar-actions"),
          a(
            cls("quiet", "link"),
            href <-- algorithm.map(id => Route.render(Route.Open(id))),
            onClick
              .compose(_.sample(algorithm)) --> Observer[cs553.core.ids.AlgorithmId](Seen.mark),
            "Skip to sandbox"
          )
        )
      ),
      mainTag(
        h1(text <-- screen.map(_.title)),
        div(cls("stage"), Canvas.render(frame, dispatch)),
        div(
          cls("lesson-text"),
          p(cls("narration"), text <-- screen.map(_.text)),
          child.maybe <-- screen
            .map(_.reply)
            .distinct
            .map(_.map {
              case Reply.Right(text) => p(cls("reply", "reply--right"), text)
              case Reply.Wrong(text) => p(cls("reply", "reply--wrong"), text)
            }),
          child.maybe <-- screen
            .map(s => Option.when(s.asking && s.reply.isEmpty)(s))
            .distinct
            .map(
              _.map(_ => p(cls("aside"), "Click a node on the canvas to answer."))
            )
        ),
        div(
          cls("controls", "lesson-controls"),
          button(
            cls("quiet"),
            "Back",
            disabled <-- screen.map(_.index == 0),
            onClick.mapTo(UiEvent.LessonBack) --> dispatch
          ),
          span(cls("step-count"), text <-- screen.map(s => s"${s.index + 1} / ${s.total}")),
          child <-- screen
            .map(s => (s.asking, s.reply.exists(_.isInstanceOf[Reply.Right]), s.algorithm))
            .distinct
            .map {
              case (_, true, id) =>
                a(
                  cls("primary", "primary--link"),
                  href(Route.render(Route.Open(id))),
                  "Open the sandbox"
                )
              case (true, false, _)  => span(cls("hint"), "Pick a node")
              case (false, false, _) =>
                button(cls("primary"), "Next", onClick.mapTo(UiEvent.LessonNext) --> dispatch)
            }
        )
      )
    )
  }
}
