# 7. Report step events from the node, and record Akka goldens from the cli tests

Date: 2026-10-20

## Status

Accepted

## Context

M3 adds the mediated Akka engine (ADR-0004). Its trace must be equal to the Simulator trace for the same `RunSpec`. The Simulator writes the events of a step with `Effects.of`. That function needs the step number, the Lamport clock of the node and the next sequence number of each outgoing channel.

The design names the node report `Handled(node, cause, after, sent, decision, actions)`.

M3 also writes recorded Akka traces for the golden cases. The list of golden cases is in the `core` test code (`Goldens`). The `actors` and `cli` modules need the Akka token, and `core` must not depend on them.

## Decision

- The node actor owns its Lamport clock and the next sequence number of its outgoing channels. The Controller sends the global step number in `Act` and in `Release`, and the channel copies it into `Deliver`.
- The node calls `Effects.of` itself. Its `Handled` report holds the events of the step (`Stepped` or `Rejected`, `Sent`, `Decided`), the sent ids and the new actions. The events hold the cause, the new state and the decision of the design.
- The Controller gets the legal choices from `World.enabledOf` and the stop decision from `Simulator.stopWhen`. Both are new pure functions in `core`. `World.enabled` and `Simulator.stopAt` call them, so each engine uses one rule.
- The `cli` tests depend on the `core` tests (`coreJVM % "test->test"`). `cs553.cli.GoldenRecord`, in the `cli` test code, records each golden case with the `record` command. `sbt goldenPromote` runs it after `GoldenPromote` when `AKKA_REPO_TOKEN` is set.
- The `core` `GoldenSpec` checks that each committed `*-akka-mediated-*.json` file is equal to the Simulator golden trace, except for the engine field. This check needs no token.

## Consequences

- The rules for envelope ids, stamps and `Rejected` stay in one function, `Effects.of`.
- The dependency direction of the main code does not change: `cli -> actors -> core`.
- Without the token, `goldenPromote` writes only the Simulator traces and prints a warning. A behavior change then makes the committed Akka traces stale, and `GoldenSpec` fails until a person with the token runs `goldenPromote`.
