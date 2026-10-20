package cs553.web

import cs553.core.ids.AlgorithmId

/** The place that the URL fragment names.
  *
  *   - `#/` is the home screen.
  *   - `#/<algorithm>` opens a new run of the algorithm.
  *   - `#/learn/<algorithm>` opens the guided lesson of the algorithm.
  *   - `#/run/<link>/<step>` replays the run of a `RunLink` text and shows that step.
  */
enum Route {
  case Home
  case Open(algorithm: AlgorithmId)
  case Lesson(algorithm: AlgorithmId)
  case Replay(link: String, step: Option[Int])
}

object Route {

  def parse(fragment: String): Route = {
    fragment.stripPrefix("#").stripPrefix("/").split('/').toList.filter(_.nonEmpty) match {
      case "learn" :: id :: Nil         => Lesson(AlgorithmId(id))
      case "run" :: link :: Nil         => Replay(link, None)
      case "run" :: link :: step :: Nil => Replay(link, step.toIntOption.filter(_ >= 0))
      case id :: Nil                    => Open(AlgorithmId(id))
      case _                            => Home
    }
  }

  def render(route: Route): String = route match {
    case Home                     => "#/"
    case Open(algorithm)          => s"#/${algorithm.value}"
    case Lesson(algorithm)        => s"#/learn/${algorithm.value}"
    case Replay(link, Some(step)) => s"#/run/$link/$step"
    case Replay(link, None)       => s"#/run/$link"
  }

  /** True when a move between the two routes changes the screen, so it deserves a history entry. */
  def changesScreen(a: Route, b: Route): Boolean = screenOf(a) != screenOf(b)

  private def screenOf(route: Route): Int = route match {
    case Home                   => 0
    case Open(_) | Replay(_, _) => 1
    case Lesson(_)              => 2
  }
}
