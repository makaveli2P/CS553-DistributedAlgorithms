package cs553.core.lessons

import cs553.core.ids.NodeId
import cs553.core.registry.Algorithm
import cs553.core.registry.Registry
import cs553.core.trace.Outcome
import cs553.core.trace.Overlay
import cs553.core.trace.TraceEvent
import org.scalatest.Assertion
import org.scalatest.funsuite.AnyFunSuite

final class LessonsSpec extends AnyFunSuite {

  private def check[S, M, A, O](lesson: Lesson, alg: Algorithm[S, M, A, O]): Assertion = {
    val name = alg.name
    val preset = alg.preset(lesson.preset)
    assert(preset.isDefined, s"$name: no preset ${lesson.preset}")
    val sim = preset
      .map(p => alg.simulate(alg.spec(p, lesson.seed)))
      .getOrElse(fail(s"$name: no preset"))
      .fold(error => fail(s"$name: the run fails: $error"), identity)
    assert(sim.run.outcome.contains(Outcome.Quiescent), name)
    assert(!sim.run.trace.exists(_.isInstanceOf[TraceEvent.Rejected[?, ?, ?, ?]]), name)
    val last = sim.worlds.size - 1

    // The beats go forward and stay inside the run.
    assert(lesson.beats.size >= 3 && lesson.beats.size <= 6, name)
    val steps = lesson.beats.map(_.step)
    assert(steps == steps.sorted && steps.distinct == steps, name)
    assert(steps.forall(s => s >= 0 && s <= last), name)
    assert(lesson.question.step >= steps.last && lesson.question.step <= last, name)

    // One short sentence for each beat, and a question.
    lesson.beats.map(_.text).foreach { text =>
      assert(text.split("\\s+").length <= 20, s"$name: too long: $text")
    }
    assert(lesson.question.text.endsWith("?"), name)

    // The expected answer matches the final run.
    val cuts = Overlay.cutSteps(alg.overlay, sim.worlds.map(_.states))
    val truth: Option[NodeId] = lesson.question.truth match {
      case Truth.Decider =>
        sim.run.trace.collectFirst { case TraceEvent.Decided(_, node, _) => node }
      case Truth.FirstToRecord => cuts.toList.minByOption(_._2).map(_._1)
      case Truth.LastToRecord  => cuts.toList.maxByOption(_._2).map(_._1)
    }
    assert(truth.contains(lesson.question.answer), name)
    // The record steps are all different, so first and last are unique.
    assert(lesson.question.truth == Truth.Decider || cuts.values.toSet.size == cuts.size, name)
  }

  test("each of the eight algorithms has one lesson, and each lesson replays to its answer") {
    assert(Lessons.all.map(_.algorithm) == Registry.all.map(_.id))
    Registry.all.foreach { any =>
      val lesson = Lessons.find(any.id).getOrElse(fail(s"no lesson for ${any.name}"))
      check(lesson, any.algorithm)
    }
  }

  test("a lesson has one screen for each beat and one for the question") {
    Lessons.all.foreach { lesson =>
      assert(lesson.size == lesson.beats.size + 1)
      assert(lesson.stepAt(lesson.beats.size) == lesson.question.step)
      assert(lesson.textAt(0) == lesson.beats.head.text)
    }
  }
}
