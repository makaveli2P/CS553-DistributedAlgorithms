package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.Catalogue

/** A minimal behavior. M0 uses it to prove that Akka 2.10 `_3` artifacts run on Scala 3.9. */
object Hello {

  final case class Greet(name: String, replyTo: ActorRef[Greeted])
  final case class Greeted(text: String)

  def apply(): Behavior[Greet] = {
    Behaviors.receiveMessage { case Greet(name, replyTo) =>
      replyTo ! Greeted(s"Hello, $name. ${Catalogue.summary}.")
      Behaviors.same
    }
  }
}
