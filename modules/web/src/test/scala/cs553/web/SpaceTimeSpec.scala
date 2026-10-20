package cs553.web

import cs553.core.ids.AlgorithmId
import cs553.core.registry.Registry
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec

final class SpaceTimeSpec extends AnyFunSuite {

  private val wide = Size(900, 520)
  private val phone = Size(390, 480)

  private def session(id: String): Session[?, ?, ?, ?] =
    Registry
      .find(AlgorithmId(id))
      .flatMap(any => Session.open(any.algorithm).toOption)
      .getOrElse(fail(s"no algorithm $id"))

  @tailrec
  private def runOut(s: Session[?, ?, ?, ?]): Session[?, ?, ?, ?] =
    if s.finished then { s }
    else { runOut(s.forward) }

  private def finished(id: String): Session[?, ?, ?, ?] = runOut(session(id))

  test("each node has one line, and each event is a dot on its line, ordered by step") {
    val s = finished("chandy-lamport")
    val model = s.spaceModel
    val layout = SpaceTime.layout(model, wide)
    assert(layout.orientation == Orientation.Horizontal)
    assert(layout.lanes.map(_.node) == model.nodes)
    assert(layout.dots.map(_.dot.step) == layout.dots.map(_.dot.step).sorted)
    layout.dots.foreach { d =>
      val lane = layout.lanes.find(_.node == d.dot.node)
      assert(lane.exists(_.from.y == d.at.y), s"dot of step ${d.dot.step} is off its line")
    }
    val xs = layout.dots.map(_.at.x)
    assert(xs == xs.sorted)
  }

  test("a delivered message goes forward from its send to its delivery, and pairs by id") {
    val s = finished("chandy-lamport")
    val model = s.spaceModel
    val layout = SpaceTime.layout(model, wide)
    assert(layout.arrows.map(_.id) == model.messages.map(_.id))
    val delivered = model.messages.filter(_.deliveredStep.isDefined)
    assert(delivered.nonEmpty && delivered.size == model.messages.size)
    delivered.foreach { m =>
      assert(m.deliveredStep.exists(_ > m.sentStep), s"${m.id} does not go forward")
    }
    assert(layout.arrows.forall(a => !a.open && a.to.x > a.from.x))
  }

  test("a message in flight is an open arrow, and the current step is marked") {
    val s = finished("chandy-lamport")
    val mid = s.seek(5)
    val model = mid.spaceModel
    val layout = SpaceTime.layout(model, wide)
    val inFlight = model.messages.filter(_.deliveredStep.isEmpty)
    assert(inFlight.nonEmpty)
    assert(layout.arrows.filter(_.open).map(_.id) == inFlight.map(_.id))
    // An open arrow stops short of the receiver line.
    layout.arrows.filter(_.open).foreach { a =>
      val lane = layout.lanes.find(_.node == a.id.to).getOrElse(fail("no receiver line"))
      assert(a.to.y != lane.from.y)
    }
    assert(layout.dots.filter(_.current).map(_.dot.step).forall(_ == 5))
    assert(layout.now._1.x == layout.now._2.x)
    assert(model.dots.forall(_.step <= 5))
  }

  test("a snapshot algorithm draws the cut through the record point of each node") {
    val s = finished("chandy-lamport")
    val model = s.spaceModel
    assert(model.cuts.size == model.nodes.size)
    val layout = SpaceTime.layout(model, wide)
    assert(layout.cut.size == model.cuts.size)
    // One point for each node line, from the first line to the last.
    assert(layout.cut.map(_.y) == layout.lanes.map(_.from.y))
    val early = SpaceTime.layout(session("chandy-lamport").spaceModel, wide)
    assert(early.cut.isEmpty)
  }

  test("the view shows the decided marker and the quiescent marker as two lines") {
    val layout = SpaceTime.layout(finished("echo").spaceModel, wide)
    assert(layout.markers.map(_.kind).toSet == Set(MarkerKind.Decided, MarkerKind.Quiescent))
    val before = SpaceTime.layout(session("echo").spaceModel, wide)
    assert(before.markers.isEmpty)
  }

  test("on a narrow screen time goes down, and the lines go across") {
    val model = finished("echo").spaceModel
    val layout = SpaceTime.layout(model, phone)
    assert(layout.orientation == Orientation.Vertical)
    assert(layout.lanes.forall(l => l.from.x == l.to.x && l.to.y > l.from.y))
    val ys = layout.dots.map(_.at.y)
    assert(ys == ys.sorted)
  }

  test("ten nodes and eighty steps fit in a drawing that scrolls, and all points are inside it") {
    // Franklin on 8 nodes has 60 steps. Stretch the check with a made model of 10 nodes and 80.
    val franklin = finished("franklin").spaceModel
    val big = franklin.copy(
      nodes = (0 until 10).map(cs553.core.ids.NodeId(_)).toList,
      last = 80
    )
    List(wide, phone).foreach { size =>
      val layout = SpaceTime.layout(big, size)
      assert(layout.width > 0 && layout.height > 0)
      val points = layout.dots.map(_.at) ++ layout.arrows.flatMap(a => List(a.from, a.to))
      points.foreach { p =>
        assert(p.x >= 0 && p.x <= layout.width && p.y >= 0 && p.y <= layout.height)
      }
    }
    assert(SpaceTime.layout(big, wide).width < 4000)
  }

  test("the sandbox shows the space-time layout only in that mode") {
    val opened = Ui.update(Ui.initial, UiEvent.Navigate(Route.Open(AlgorithmId("echo"))))
    assert(Ui.screen(opened).exists(s => s.mode == ViewMode.Graph && s.spaceTime.isEmpty))
    val switched = Ui.update(opened, UiEvent.SetMode(ViewMode.SpaceTime))
    assert(Ui.screen(switched).exists(s => s.mode == ViewMode.SpaceTime && s.spaceTime.isDefined))
  }
}
