package cs553.core.golden

import cs553.core.trace.TraceEvent
import org.scalatest.funsuite.AnyFunSuite

final class GoldenSpec extends AnyFunSuite {

  private val committed: Map[String, String] = GoldenFiles.all.toMap

  private val hint: String =
    "If the change of behavior is intended, run `sbt goldenPromote` and explain the trace diff " +
      "in the commit (coding rule 30)."

  Goldens.cases.foreach { c =>
    test(s"the Simulator gives the committed golden trace ${c.path}") {
      val simulated = Goldens.render(c)
      assert(simulated.isRight, simulated.left.getOrElse(""))
      simulated.foreach { actual =>
        committed.get(c.path) match {
          case None =>
            fail(s"fixtures/traces/${c.path} is missing. Run `sbt goldenPromote` to write it.")
          case Some(expected) =>
            Goldens.firstDifference(expected, actual).foreach { diff =>
              fail(s"fixtures/traces/${c.path} differs from the Simulator at $diff\n$hint")
            }
        }
      }
    }
  }

  test("each file in fixtures/traces belongs to a golden case") {
    val known = Goldens.cases.map(_.path).toSet
    val stale = committed.keySet.diff(known).toList.sorted
    assert(stale.isEmpty, s"remove these files or add a case to Goldens: ${stale.mkString(", ")}")
  }

  test("each golden trace decodes, has a decision and has no Rejected event") {
    Goldens.cases.foreach { c =>
      val alg = c.algorithm.algorithm
      committed.get(c.path).foreach { text =>
        val run = alg.readTrace(text)
        assert(run.isRight, c.path)
        run.foreach { r =>
          assert(r.decisions.nonEmpty, c.path)
          assert(r.trace.collectFirst { case TraceEvent.Rejected(_, _, _, e) => e }.isEmpty, c.path)
        }
      }
    }
  }
}
