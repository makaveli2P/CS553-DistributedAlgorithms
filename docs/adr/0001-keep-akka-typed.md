# 1. Keep Akka Typed as the actor runtime

Date: 2026-10-20

## Status

Accepted

## Context

The college project used Akka 2.6 classic and typed actors on Scala 2.13. The rewrite must teach the actor model with a real actor runtime. The user chose Akka. Akka 2.10 has the same Typed API as Akka 2.6. Akka 2.6.21 is end of life.

The rewrite uses Scala 3.9.0, the current LTS release. Akka 2.10.24 publishes `_3` artifacts that Scala 3.3.7 builds. A Scala 3.9 compiler reads TASTy from older Scala 3 versions, so the build can use these artifacts.

## Decision

Use Akka 2.10.24 (`akka-actor-typed` and `akka-actor-testkit-typed`) in the modules `actors` and `cli`. Use Scala 3.9.0 for all modules.

Milestone M0 must prove that the Akka `_3` artifacts resolve and run on Scala 3.9 with the test `cs553.actors.HelloSpec`. If this proof fails, use Scala 3.3.x LTS for all modules and record the reason in this ADR.

## Consequences

- Learners see real Akka Typed behaviors and real asynchronous sends with `!`.
- The Akka modules need a token. ADR-0002 describes the token and the fallback.
- On 2026-10-20 the proof is open, because `AKKA_REPO_TOKEN` is not available yet. `core` and `web` compile and pass their tests on Scala 3.9.0 with Scala.js 1.22.0 and Laminar 17.2.1. Run `sbt actors/test` with the token to close the proof.
