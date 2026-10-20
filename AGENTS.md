# Agent guide

Distributed Algorithms: eight textbook algorithms (Fokkink variants) as one pure Scala 3 model, a browser visualizer (Scala.js and Laminar) and Akka Typed engines.

The full rules are in [docs/coding-rules.md](docs/coding-rules.md). Read them before you change code. The setup and the commands are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Summary of the rules

- Put all algorithm logic in `modules/core` as pure functions. Do not import Akka, `java.io`, `java.time` or `scala.util.Random` in `core`.
- Keep dependencies in one direction: `web -> core` and `cli -> actors -> core`.
- Model messages, actions and phases as enums. Use opaque types for ids. Do not use strings or Booleans to select a variant.
- Do not use `var`, `null`, `return`, `throw`, `while` or mutable collections.
- Write Akka behaviors in the functional style. Do not use timers, `ask`, `Future`, `Thread.sleep` or `Await` in node actors.
- Make each test assert a result. Add one named regression test for each bug fix.
- Set compiler flags only through sbt-tpolecat. Use braces.
- Run `sbt scalafmtCheckAll "scalafixAll --check" test` before each commit.
- Do not commit `AKKA_REPO_TOKEN` or `AKKA_LICENSE_KEY`.
- Record each change to the stack or a module boundary as an ADR in `docs/adr`. Write prose in ASD-STE100 Simplified Technical English.
