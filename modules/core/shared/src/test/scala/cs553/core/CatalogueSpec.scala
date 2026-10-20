package cs553.core

import cs553.core.registry.Registry
import org.scalatest.funsuite.AnyFunSuite

final class CatalogueSpec extends AnyFunSuite {

  test("the catalogue has eight algorithms in four families") {
    assert(Catalogue.summary == "8 algorithms in 4 families")
  }

  test("each family has at least one algorithm") {
    assert(Family.values.toSet == Catalogue.algorithms.map(_.family).toSet)
  }

  test("each ready algorithm has a catalogue entry with the same id, name and family") {
    Registry.all.foreach { any =>
      val alg = any.algorithm
      val entry = Catalogue.algorithms.find(_.id == alg.id)
      assert(entry.map(e => (e.name, e.family)) == Some((alg.name, alg.family)), alg.name)
    }
  }
}
