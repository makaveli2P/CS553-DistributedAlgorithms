package cs553.core.golden

import cs553.core.registry.AnyAlgorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed

/** The golden traces: a small fixed set of Simulator runs on presets of the registry.
  *
  * `sbt goldenPromote` writes each trace to `fixtures/traces/<path>`. `GoldenSpec` simulates each
  * case again and compares the result with the committed file (coding rules 28 and 30).
  */
object Goldens {

  final case class Case(algorithm: AnyAlgorithm, preset: String, seed: Long) {

    /** The path under `fixtures/traces`: `<alg>/<topology>-simulator-<seed>.json`. */
    def path: String = s"${algorithm.id.value}/$preset-simulator-$seed.json"

    /** The path of the same run, recorded by the mediated Akka engine:
      * `<alg>/<topology>-akka-mediated-<seed>.json`. `sbt goldenPromote` writes it with the CLI
      * when `AKKA_REPO_TOKEN` is set.
      */
    def mediatedPath: String = s"${algorithm.id.value}/$preset-akka-mediated-$seed.json"
  }

  /** The seeds of each case. */
  val seeds: List[Long] = List(1L, 7L)

  /** One case for each algorithm of the registry and each seed, on the first preset of the
    * algorithm. A new algorithm in the registry gets its cases with no change here.
    */
  val cases: List[Case] = for {
    algorithm <- Registry.all
    preset <- algorithm.algorithm.presets.headOption.toList
    seed <- seeds
  } yield Case(algorithm, preset.name, seed)

  /** The trace of the case as pretty JSON with a final newline, or the reason that it failed. */
  def render(c: Case): Either[String, String] = {
    val alg = c.algorithm.algorithm
    for {
      preset <- alg.preset(c.preset).toRight(s"${alg.name} has no preset '${c.preset}'")
      simulation <- alg.simulate(alg.spec(preset, Seed(c.seed))).left.map { error =>
        s"the Simulator cannot run ${c.path}: $error"
      }
    } yield alg.writeTracePretty(simulation.run) + "\n"
  }

  /** The first line where `expected` and `actual` differ, as a short report for a test failure. */
  def firstDifference(expected: String, actual: String): Option[String] = {
    val e = expected.linesIterator.toVector
    val a = actual.linesIterator.toVector
    (0 until math.max(e.size, a.size)).find(i => e.lift(i) != a.lift(i)).map { i =>
      val committed = e.lift(i).getOrElse("<end of file>")
      val simulated = a.lift(i).getOrElse("<end of file>")
      s"line ${i + 1}:\n  committed: $committed\n  simulated: $simulated"
    }
  }
}
