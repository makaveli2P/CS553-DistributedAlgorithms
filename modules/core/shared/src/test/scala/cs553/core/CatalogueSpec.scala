package cs553.core

import org.scalatest.funsuite.AnyFunSuite

final class CatalogueSpec extends AnyFunSuite {

  test("the catalogue has eight algorithms in four families") {
    assert(Catalogue.summary == "8 algorithms in 4 families")
  }

  test("each family has at least one algorithm") {
    assert(Family.values.toSet == Catalogue.algorithms.map(_._2).toSet)
  }
}
