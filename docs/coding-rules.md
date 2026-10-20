# Coding rules

## Architecture
1. Put all algorithm logic in `core` as pure functions. Reason: the browser, the tests and Akka run the same code.
2. Do not import `akka`, `java.io`, `java.time` or `scala.util.Random` in `core`. Reason: core must stay pure and cross-build to JS.
3. Keep dependencies in one direction: `web -> core`, `cli/server -> actors -> core`. Reason: no cycles and no Akka in the browser.
4. Return effects as data (`Step`). Do not perform them in `core`. Reason: replay and tests need pure transitions.
5. Make each UI event come from a `TraceEvent`. Reason: the UI must not infer state.
6. Do not put algorithm code in `web`. Use `Inspect` and `Overlay` givens. Reason: one place for each rule.

## Types
7. Model each protocol message, action and phase as an `enum`. Reason: the compiler checks every case.
8. Do not use strings, Booleans or sentinel values (-1) to select a variant. Reason: the old code failed on "Increment" vs "increment".
9. Use one phase enum, not several Booleans. Reason: illegal states become unrepresentable.
10. Use opaque types for ids, with `Ordering` and `Codec` givens. Reason: a `NodeId` is not a `Uid`.
11. Make public data types `final case class` or `enum`. Do not use inheritance for reuse. Reason: plain data is easy to encode and compare.
12. Use `given` and `using` only for type classes. Do not use implicit conversions. Reason: no hidden behavior.

## FP
13. Do not use `var`, `null`, `return`, `throw`, `while` or `scala.collection.mutable`. Reason: DisableSyntax enforces this, and immutable state replays.
14. Do not use a wildcard case on a protocol enum. Put the last case without a guard. Reason: guards and wildcards hide missing cases.
15. Do not call `.get`, `.head` or `Map.apply` on data that can be absent. Reason: these calls throw at runtime.
16. Parse external input (DOT, URL, JSON) into `Either[NonEmptyList[E], A]` at the edge. Reason: the inner code gets only valid values.
17. Return `Either[ProtocolError, Step]` for illegal protocol input. Reason: the engine records `Rejected`, and the actor does not crash.
18. Seed all randomness through `Rng(seed)` from `RunSpec`. Reason: every run must replay.

## Akka Typed
19. Write behaviors in the functional style: `Behaviors.receiveMessage` that returns `running(newState)`. Do not use `AbstractBehavior`. Reason: state stays in parameters.
20. Keep node actors thin: call the protocol, send the effects, report to the Controller. Reason: logic lives in tested core code.
21. In mediated mode, a node sends only to its channel refs with `!`. Reason: learners see real asynchronous sends, and the Controller gates delivery.
22. Do not use timers, `ask`, `Future` or `pipeToSelf` in node actors. Reason: they break deterministic replay.
23. Make internal messages `private`. Name reply refs `replyTo`. Reason: this is the Akka style guide.
24. Pass dependencies through factory parameters. Do not use global objects that have state. Reason: each run is one isolated subtree.
25. Do not use `Thread.sleep`, `Await`, polling or busy-waits. Detect completion with protocol messages. Reason: sleeps hide races and slow the tests.

## Testing
26. Make each test assert a result (a decision, a tree, a cut, a verdict). Reason: a test that only waits for a message proves nothing.
27. Do not use `expectNoMessage` as proof of correctness. Use timeouts only as failure guards. Reason: absence in a time window is not correctness.
28. Give each algorithm property tests for its invariants, one conformance test and one golden trace. Reason: examples miss schedules.
29. Add one named regression test for each bug fix. Reason: the bug must not come back.
30. Change golden traces only with `sbt goldenPromote`, and explain the diff in the commit. Reason: a trace diff is a behavior change.

## Build, format and commits
31. Set compiler flags only through `tpolecatScalacOptions`. Reason: one source for `-Werror`, `-Wunused:all`, `-Wvalue-discard` and `-Wnonunit-statement` (off in Test).
32. Use braces. Do not use significant indentation. Reason: scalafmt enforces one style.
33. Run `sbt scalafmtCheckAll "scalafixAll --check" test` before each commit. Reason: CI runs the same check.
34. Do not commit `AKKA_REPO_TOKEN` or `AKKA_LICENSE_KEY`. Read them from the environment. Reason: the tokens are personal.
35. Record each change to the stack or a module boundary as a Nygard ADR in `docs/adr`. Write prose in ASD-STE100. Reason: decisions stay visible and clear.

## Enforcement

These files enforce the rules:

- `.scalafmt.conf`: the Scala 3 dialect with braces. Significant indentation is off.
- `.scalafix.conf`: `OrganizeImports`, `DisableSyntax` (no `var`, `null`, `return`, `throw` or `while`) and `RemoveUnused` for privates and locals.
- `build.sbt`: sbt-tpolecat sets the compiler flags. The build removes `-language:implicitConversions` (rule 12) and turns off `-Wnonunit-statement` in `Test`.
