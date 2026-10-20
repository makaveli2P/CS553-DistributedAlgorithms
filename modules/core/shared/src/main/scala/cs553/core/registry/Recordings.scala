package cs553.core.registry

import cs553.core.ids.AlgorithmId
import cs553.core.trace.Engine

/** The recorded Akka runs that the build embeds from `fixtures/traces` (seed 1, default preset of
  * each algorithm). The browser replays them, and the tests read them.
  */
object Recordings {

  private def suffix(engine: Engine): Option[String] = engine match {
    case Engine.AkkaMediated => Some("-akka-mediated-1.json")
    case Engine.AkkaFreeRun  => Some("-akka-free-1.json")
    case Engine.Simulator    => None
  }

  /** The JSON of the recording of `alg` by `engine`, or `None` when there is none. */
  def text(alg: AlgorithmId, engine: Engine): Option[String] = {
    suffix(engine).flatMap { end =>
      RecordingFiles.all.collectFirst {
        case (key, json) if key.startsWith(s"${alg.value}/") && key.endsWith(end) => json
      }
    }
  }
}
