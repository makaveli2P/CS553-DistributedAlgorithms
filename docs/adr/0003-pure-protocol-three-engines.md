# 3. Use one pure protocol type class and three engines

Date: 2026-10-20

## Status

Accepted

## Context

The visualizer must run the algorithms in the browser, where no JVM and no Akka exist. Akka must stay the real runtime. The college code mixed algorithm logic into actors, so no test could replay a run.

## Decision

Each algorithm is one instance of the pure type class `Protocol[S, M, A, O]` in `core`. Three engines run it and write one trace format (`TraceEvent`):

1. The **Simulator** in `core`, for the JVM and for JS.
2. The **mediated Akka engine**: real actors and real `!`, with delivery gated by channel actors (ADR-0004).
3. The **free-run Akka engine**: it records the real non-determinism of the dispatcher.

A conformance test makes engine 2 give the same trace as engine 1 for the same choices. A `RunSpec` defines a run completely, so a URL can replay it.

## Consequences

- The browser gets the full sandbox with no server.
- The CLI records Akka runs to JSON, and the site replays them.
- Algorithm logic has one location, and the tests cover it on the JVM and in JS.
- The node actors stay thin: they call the protocol, send the effects and report.
