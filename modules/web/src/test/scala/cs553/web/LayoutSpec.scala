package cs553.web

import cs553.core.ids.NodeId
import cs553.core.registry.Registry
import org.scalatest.funsuite.AnyFunSuite

final class LayoutSpec extends AnyFunSuite {

  test("a directed ring goes clockwise from the top in ring order") {
    Registry.changRoberts.preset("ring-6").foreach { preset =>
      val plan = Layout.plan(preset.topology, Layout.defaultSize)
      val at = plan.positions
      val order = (0 until 6).map(i => at.get(NodeId(i)))
      assert(order.forall(_.nonEmpty))
      // The ring is an ellipse, so compare the angles on the circle that it stretches.
      val angles = order.flatten.map(p => math.atan2(p.y / plan.ry, p.x / plan.rx))
      val steps = angles.zip(angles.drop(1)).map { case (a, b) =>
        val d = b - a
        if d < 0 then { d + 2 * math.Pi }
        else { d }
      }
      steps.foreach(d => assert(math.abs(d - math.Pi / 3) < 0.01))
      assert(order.headOption.flatten.exists(p => p.x == 0 && p.y < 0))
    }
  }

  private val sizes = List(Size(930, 496), Size(614, 496), Size(356, 490))

  test(
    "each preset gives distinct positions with room for the labels, and the same picture twice"
  ) {
    for {
      preset <- Registry.all.flatMap(_.algorithm.presets)
      size <- sizes
    } {
      val plan = Layout.plan(preset.topology, size)
      val at = plan.positions
      assert(at.keySet == preset.topology.nodes.toSet, preset.name)
      assert(at.values.toSet.size == at.size, preset.name)
      // The view box is the measured size, so one unit is one pixel.
      assert(plan.box.width == size.width && plan.box.height == size.height)
      val box = plan.box
      val r = Layout.nodeRadius
      at.values.foreach { p =>
        assert(p.x - r - 30 > box.x && p.x + r + 30 < box.x + box.width, (preset.name, size))
        assert(p.y - r - 60 > box.y && p.y + r + 60 < box.y + box.height, (preset.name, size))
      }
      assert(Layout.plan(preset.topology, size) == plan, preset.name)
    }
  }

  test("a phone canvas puts each label above or below its node and uses a tall ring") {
    Registry.changRoberts.preset("ring-8").foreach { preset =>
      val plan = Layout.plan(preset.topology, Size(356, 490))
      assert(plan.narrow)
      val sides = plan.positions.values.map(plan.side).toSet
      assert(sides == Set(LabelSide.Above, LabelSide.Below))
      val xs = plan.positions.values.map(_.x)
      val ys = plan.positions.values.map(_.y)
      assert(ys.max - ys.min > xs.max - xs.min)
    }
    Registry.changRoberts.preset("ring-6").foreach { preset =>
      val wide = Layout.plan(preset.topology, Size(930, 496))
      assert(!wide.narrow)
      assert(wide.positions.values.map(wide.side).toSet.contains(LabelSide.Right))
    }
  }

  test("a queue places only the envelopes that fit between the two nodes") {
    val from = Point(0, 0)
    val to = Point(200, 0)
    val spots = Layout.queue(from, to, Vector.fill(10)(50.0), shared = false)
    assert(spots.nonEmpty && spots.size < 10)
    spots.foreach(p => assert(p.x - 25 > Layout.nodeRadius && p.x + 25 < 200 - Layout.nodeRadius))
    // The receiver end comes first, and the envelopes do not overlap.
    assert(spots.map(_.x) == spots.map(_.x).sorted.reverse)
    spots.zip(spots.drop(1)).foreach { case (a, b) => assert(a.x - b.x >= 50) }
    // A shared edge gives each queue only the half at its receiver.
    val half = Layout.queue(from, to, Vector.fill(10)(50.0), shared = true)
    assert(half.size < spots.size)
    half.foreach(p => assert(p.x - 25 >= 100))
    assert(Layout.queue(from, from, Vector(50.0), shared = false) == Vector.empty)
  }

  test("regression: a queue slides away from a label card, and never disappears") {
    val to = Point(0, 0)
    val below = Point(0, 300)
    val plain = Layout.queue(below, to, Vector(50.0), shared = false)
    val card = plain.headOption.toList.map(p => Box(p.x - 40, p.y - 15, 80, 30))
    val moved = Layout.queue(below, to, Vector(50.0), shared = false, card)
    assert(moved.headOption.zip(plain.headOption).exists { case (a, b) => a.y > b.y + 20 })
    // An edge too short for any envelope still shows the first one.
    assert(Layout.queue(Point(0, 50), to, Vector(50.0, 50.0), shared = true).size == 1)
  }
}
