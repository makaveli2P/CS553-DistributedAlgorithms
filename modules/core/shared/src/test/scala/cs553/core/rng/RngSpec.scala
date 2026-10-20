package cs553.core.rng

import io.circe.syntax.*
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

final class RngSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  test("SplitMix64 with seed 0 gives the reference first value") {
    // java.util.SplittableRandom(0).nextLong() uses the same SplitMix64 function.
    assert(Rng(Seed(0L)).nextLong._2 == 0xe220a8397b1dcdafL)
  }

  test("the same seed gives the same sequence") {
    forAll { (seed: Long) =>
      val a = List.iterate(Rng(Seed(seed)).nextLong, 5)(_._1.nextLong).map(_._2)
      val b = List.iterate(Rng(Seed(seed)).nextLong, 5)(_._1.nextLong).map(_._2)
      assert(a == b)
    }
  }

  test("nextInt stays in [0, bound)") {
    forAll(Gen.long, Gen.choose(1, 1000)) { (seed: Long, bound: Int) =>
      val (_, value) = Rng(Seed(seed)).nextInt(bound)
      assert(value >= 0 && value < bound)
    }
  }

  test("a seed round-trips through JSON as a string, also at the 64-bit limits") {
    List(Long.MaxValue, Long.MinValue, 0L).foreach { value =>
      val json = Seed(value).asJson
      assert(json.isString)
      assert(json.as[Seed].map(_.value) == Right(value))
    }
    assert(io.circe.Json.fromString("x").as[Seed].isLeft)
  }
}
