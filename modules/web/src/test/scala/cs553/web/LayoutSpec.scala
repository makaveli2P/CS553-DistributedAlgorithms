package cs553.web

import cs553.core.ids.NodeId
import cs553.core.registry.Registry
import org.scalatest.funsuite.AnyFunSuite

final class LayoutSpec extends AnyFunSuite {

  test("a directed ring goes clockwise from the top in ring order") {
    Registry.changRoberts.preset("ring-6").foreach { preset =>
      val at = Layout.positions(preset.topology)
      val order = (0 until 6).map(i => at.get(NodeId(i)))
      assert(order.forall(_.nonEmpty))
      val angles = order.flatten.map(p => math.atan2(p.y, p.x))
      val steps = angles.zip(angles.drop(1)).map { case (a, b) =>
        val d = b - a
        if d < 0 then { d + 2 * math.Pi }
        else { d }
      }
      steps.foreach(d => assert(math.abs(d - math.Pi / 3) < 0.01))
      assert(order.headOption.flatten.exists(p => p.x == 0 && p.y < 0))
    }
  }

  test("each preset gives distinct positions inside the view box, and the same picture twice") {
    Registry.all.flatMap(_.algorithm.presets).foreach { preset =>
      val at = Layout.positions(preset.topology)
      assert(at.keySet == preset.topology.nodes.toSet, preset.name)
      assert(at.values.toSet.size == at.size, preset.name)
      val box = Layout.box(at.values)
      at.values.foreach { p =>
        assert(p.x > box.x && p.x < box.x + box.width && p.y > box.y && p.y < box.y + box.height)
      }
      assert(Layout.positions(preset.topology) == at, preset.name)
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
}
