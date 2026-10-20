// The single list of pinned versions.
// Each value was checked on 2026-10-20 against the source in its comment.
// Plugin versions are in project/plugins.sbt, because sbt reads that file before this one.
// They are listed here as comments so that this file stays the one place to read.
object Versions {

  // https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/maven-metadata.xml
  // 3.9.0 is the newest 3.9.x LTS release (GitHub release notes for scala/scala3 3.9.0: "Long Term Support").
  val scala3 = "3.9.0"

  // https://github.com/akka/akka-core/releases (v2.10.24, 2026-10-09). Built with Scala 3.3.7.
  // repo.akka.io needs a token, so Maven Central cannot confirm it.
  val akka = "2.10.24"

  // https://repo1.maven.org/maven2/com/raquo/laminar_sjs1_3/maven-metadata.xml (newest 17.x)
  val laminar = "17.2.1"

  // https://repo1.maven.org/maven2/org/scala-js/scalajs-dom_sjs1_3/maven-metadata.xml
  val scalajsDom = "2.8.1"

  // https://repo1.maven.org/maven2/org/scalatest/scalatest_3/maven-metadata.xml (newest 3.2.x)
  val scalatest = "3.2.20"

  // https://repo1.maven.org/maven2/org/scalatestplus/scalacheck-1-18_3/maven-metadata.xml
  // 3.2.19.0 is the newest release for scalacheck-1-18. It depends on scalatest 3.2.19,
  // and sbt evicts that to 3.2.20. The 3.2.x releases are binary compatible.
  val scalatestplusScalacheck = "3.2.19.0"

  // https://repo1.maven.org/maven2/org/scalacheck/scalacheck_3/maven-metadata.xml
  val scalacheck = "1.18.1"

  // https://repo1.maven.org/maven2/io/circe/circe-core_3/maven-metadata.xml (newest 0.14.x).
  // Pinned now for M1. M0 does not use it.
  val circe = "0.14.17"

  // https://repo1.maven.org/maven2/ch/qos/logback/logback-classic/maven-metadata.xml (newest 1.5.x)
  val logback = "1.5.38"

  // Plugins (project/plugins.sbt) and tools, for reference:
  // sbt                       1.12.15 https://repo1.maven.org/maven2/org/scala-sbt/sbt/maven-metadata.xml
  // sbt-scalajs (Scala.js)    1.22.0  https://repo1.maven.org/maven2/org/scala-js/sbt-scalajs_2.12_1.0/maven-metadata.xml
  // sbt-scalajs-crossproject  1.4.0   https://repo1.maven.org/maven2/org/portable-scala/sbt-scalajs-crossproject_2.12_1.0/maven-metadata.xml
  // sbt-tpolecat              0.5.7   https://repo1.maven.org/maven2/org/typelevel/sbt-tpolecat_2.12_1.0/maven-metadata.xml
  // sbt-scalafmt              2.6.2   https://repo1.maven.org/maven2/org/scalameta/sbt-scalafmt_2.12_1.0/maven-metadata.xml
  // scalafmt (.scalafmt.conf) 3.11.5  https://repo1.maven.org/maven2/org/scalameta/scalafmt-core_2.13/maven-metadata.xml
  // sbt-scalafix              0.14.9  https://repo1.maven.org/maven2/ch/epfl/scala/sbt-scalafix_2.12_1.0/maven-metadata.xml
  // vite                      7.3.7   npm view vite (newest 7.x; the Scala.js plugin accepts vite 4.1.4 - 7)
  // @scala-js/vite-plugin-scalajs 1.1.0 npm view @scala-js/vite-plugin-scalajs
}
