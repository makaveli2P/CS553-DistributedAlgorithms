package cs553.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.funsuite.AnyFunSuiteLike

final class HelloSpec extends ScalaTestWithActorTestKit with AnyFunSuiteLike {

  test("Hello replies with the core summary") {
    val probe = createTestProbe[Hello.Greeted]()
    val hello = spawn(Hello())
    hello ! Hello.Greet("learner", probe.ref)
    probe.expectMessage(Hello.Greeted("Hello, learner. 8 algorithms in 4 families."))
  }
}
