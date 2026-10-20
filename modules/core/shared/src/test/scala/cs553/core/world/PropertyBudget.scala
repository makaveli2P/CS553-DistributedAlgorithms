package cs553.core.world

import org.scalacheck.Shrink
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** The property checks of the Simulator and algorithm specs.
  *
  * Each property runs 200 cases. The ScalaTest default of 10 cases is too few for properties that
  * depend on the schedule. A `Long` seed does not shrink, because a shrunk seed is a different
  * schedule, so the shrunk case does not show the failure.
  */
trait PropertyBudget extends ScalaCheckPropertyChecks {
  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 200)

  given noSeedShrink: Shrink[Long] = Shrink.shrinkAny[Long]
}
