# 5. Use sbt 1.12 for now

Date: 2026-10-20

## Status

Accepted

## Context

sbt 2 exists, but nobody confirmed that sbt-scalafmt, sbt-scalafix, sbt-tpolecat and the Scala.js plugins work on it.

## Decision

Use sbt 1.12.15, pinned in `project/build.properties`. Pin these plugins in `project/plugins.sbt`: sbt-scalajs 1.22.0, sbt-scalajs-crossproject 1.4.0, sbt-tpolecat 0.5.7, sbt-scalafmt 2.6.2 and sbt-scalafix 0.14.9. `project/Versions.scala` records the source of each version.

## Consequences

- The build uses the sbt 1 syntax.
- A later move to sbt 2 needs a new ADR that supersedes this one. Do the move when all the plugins above publish sbt 2 releases.
