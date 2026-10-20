package cs553.core.rng

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder

import scala.annotation.targetName
import scala.util.Try

/** The seed of a run.
  *
  * JSON holds a seed as a decimal string, because the JavaScript `JSON.parse` loses the low bits of
  * a large 64-bit number.
  */
opaque type Seed = Long

object Seed {
  def apply(value: Long): Seed = value

  extension (seed: Seed) {
    def value: Long = seed
  }

  given Ordering[Seed] = Ordering.Long
  given Codec[Seed] = Codec.from(
    Decoder.decodeString.emap { text =>
      Try(text.toLong).toOption.toRight(s"the seed '$text' is not a 64-bit integer")
    },
    Encoder.encodeString.contramap[Long](_.toString)
  )
}

/** A pure SplitMix64 generator (Steele, Lea and Flood, 2014).
  *
  * Each call returns the next generator with the value. The same seed gives the same sequence on
  * the JVM and on JavaScript.
  */
final case class Rng private (state: Long) {

  /** Returns the next 64-bit value and the next generator. */
  def nextLong: (Rng, Long) = {
    val next = state + Rng.Gamma
    val z1 = (next ^ (next >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    (new Rng(next), z2 ^ (z2 >>> 31))
  }

  /** Returns a value in `[0, bound)` and the next generator. The bound must be positive. */
  def nextInt(bound: Int): (Rng, Int) = {
    val (rng, raw) = nextLong
    (rng, ((raw >>> 1) % bound.toLong).toInt)
  }
}

object Rng {
  private val Gamma: Long = 0x9e3779b97f4a7c15L

  /** The generator for one run. Seed all randomness here (coding rule 18). */
  @targetName("fromSeed")
  def apply(seed: Seed): Rng = new Rng(seed.value)
}
