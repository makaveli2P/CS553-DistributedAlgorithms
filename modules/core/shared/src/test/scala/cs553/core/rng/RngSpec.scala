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
      val value = Rng(Seed(seed)).nextInt(bound).map(_._2)
      assert(value.exists(v => v >= 0 && v < bound))
    }
  }

  test("nextInt gives None for a bound that is not positive") {
    assert(Rng(Seed(1L)).nextInt(0).isEmpty)
    assert(Rng(Seed(1L)).nextInt(-5).isEmpty)
    assert(Rng(Seed(1L)).pick(List.empty[Int]).isEmpty)
  }

  /** `count` values of `nextInt(bound)` from one generator. */
  private def draws(seed: Long, bound: Int, count: Int): List[Int] = {
    LazyList
      .unfold(Rng(Seed(seed)))(_.nextInt(bound).map { case (next, value) => (value, next) })
      .take(count)
      .toList
  }

  test("nextInt gives each value of the bound with a near-uniform count") {
    // Expected count e = count / bound. Each bucket must be within 5 standard deviations of e.
    List((2, 10000), (7, 10000), (1000, 100000)).foreach { case (bound, count) =>
      val values = draws(42L, bound, count)
      assert(values.size == count)
      val buckets = values.groupBy(identity).view.mapValues(_.size).toMap
      assert(buckets.keySet == (0 until bound).toSet, s"bound $bound")
      val expected = count.toDouble / bound
      val tolerance = 5 * math.sqrt(expected)
      buckets.values.foreach { n =>
        assert(math.abs(n - expected) <= tolerance, s"bound $bound: count $n, expected $expected")
      }
    }
  }

  test("different seeds give different sequences of nextInt") {
    val sequences = (1L to 20L).map(seed => draws(seed, 10, 20)).toSet
    assert(sequences.size == 20)
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
