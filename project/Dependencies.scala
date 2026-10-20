import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

// Library coordinates. Versions come from Versions.scala.
object Dependencies {

  // Cross-built libraries use %%%, so each one needs a setting.
  val scalatest = Def.setting("org.scalatest" %%% "scalatest" % Versions.scalatest % Test)
  val scalacheck = Def.setting("org.scalacheck" %%% "scalacheck" % Versions.scalacheck % Test)
  val scalatestplusScalacheck = Def.setting(
    "org.scalatestplus" %%% "scalacheck-1-18" % Versions.scalatestplusScalacheck % Test
  )

  // circe brings cats-core. Core uses cats.data.NonEmptyList from it, with no extra dependency.
  val circeCore = Def.setting("io.circe" %%% "circe-core" % Versions.circe)
  val circeParser = Def.setting("io.circe" %%% "circe-parser" % Versions.circe)

  val laminar = Def.setting("com.raquo" %%% "laminar" % Versions.laminar)
  val scalajsDom = Def.setting("org.scala-js" %%% "scalajs-dom" % Versions.scalajsDom)

  // JVM only. These modules resolve only from repo.akka.io with a token.
  val akkaActorTyped = "com.typesafe.akka" %% "akka-actor-typed" % Versions.akka
  val akkaActorTestkitTyped =
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % Versions.akka % Test

  val logback = "ch.qos.logback" % "logback-classic" % Versions.logback
}
