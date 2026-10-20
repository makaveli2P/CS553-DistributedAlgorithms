package cs553.web

import cs553.core.ids.NodeId
import cs553.core.lessons.Lessons
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec

final class LessonUiSpec extends AnyFunSuite {

  private def open(lesson: cs553.core.lessons.Lesson): UiState =
    Ui.update(Ui.initial, UiEvent.Navigate(Route.Lesson(lesson.algorithm)))

  @tailrec
  private def next(state: UiState, times: Int): UiState =
    if times == 0 then { state }
    else { next(Ui.update(state, UiEvent.LessonNext), times - 1) }

  test("each lesson shows one sentence for each beat, then the question, with Next and Back") {
    Lessons.all.foreach { lesson =>
      val state = lesson.beats.zipWithIndex.foldLeft(open(lesson)) { case (shown, (beat, i)) =>
        val screen = Ui.lessonScreen(shown).getOrElse(fail(s"no screen ${lesson.algorithm}"))
        assert(screen.text == beat.text && screen.frame.step == beat.step)
        assert(screen.index == i && !screen.asking)
        Ui.update(shown, UiEvent.LessonNext)
      }
      val last = Ui.lessonScreen(state).getOrElse(fail("no question screen"))
      assert(last.asking && last.text == lesson.question.text)
      assert(last.frame.step == lesson.question.step)
      // Next stops at the question, and Back goes one screen back.
      assert(Ui.lessonScreen(Ui.update(state, UiEvent.LessonNext)).map(_.index) == Some(last.index))
      val back = Ui.lessonScreen(Ui.update(state, UiEvent.LessonBack))
      assert(back.map(_.index) == Some(last.index - 1))
    }
  }

  test("a click on the right node gets a calm yes, and a click on another node gets a calm no") {
    Lessons.all.foreach { lesson =>
      val asking = next(open(lesson), lesson.beats.size)
      val nodes = Ui.lessonScreen(asking).map(_.frame.nodes.map(_.id)).getOrElse(Nil)
      assert(nodes.contains(lesson.question.answer))
      val wrong: NodeId = nodes.find(_ != lesson.question.answer).getOrElse(fail("one node"))
      val no = Ui.lessonScreen(Ui.update(asking, UiEvent.SelectNode(wrong)))
      assert(no.flatMap(_.reply) == Some(Reply.Wrong(lesson.question.wrong)))
      val yes = Ui.lessonScreen(Ui.update(asking, UiEvent.SelectNode(lesson.question.answer)))
      assert(yes.flatMap(_.reply) == Some(Reply.Right(lesson.question.right)))
    }
  }

  test("a click on a node before the question does not answer, and Back clears a reply") {
    val lesson = Lessons.all.head
    val first = open(lesson)
    val clicked = Ui.update(first, UiEvent.SelectNode(lesson.question.answer))
    assert(Ui.lessonScreen(clicked).exists(_.reply.isEmpty))
    val asking = next(first, lesson.beats.size)
    val answered = Ui.update(asking, UiEvent.SelectNode(lesson.question.answer))
    assert(Ui.lessonScreen(Ui.update(answered, UiEvent.LessonBack)).exists(_.reply.isEmpty))
  }

  test("the lesson has its own route, and the sandbox links back to it") {
    val lesson = Lessons.all.head
    assert(Ui.route(open(lesson)) == Route.Lesson(lesson.algorithm))
    val sandbox = Ui.update(Ui.initial, UiEvent.Navigate(Route.Open(lesson.algorithm)))
    assert(Ui.screen(sandbox).exists(_.lesson.contains(Route.Lesson(lesson.algorithm))))
    assert(Route.changesScreen(Route.Lesson(lesson.algorithm), Route.Open(lesson.algorithm)))
  }
}
