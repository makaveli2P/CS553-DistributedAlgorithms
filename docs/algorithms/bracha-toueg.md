# Bracha-Toueg

Family: deadlock detection. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> Employed for deadlock detection in distributed systems. It monitors resource allocation and process interactions to detect potential deadlocks and take corrective actions to resolve them.

## Variant in this project

- Topology: a directed wait-for graph. Each node has an `N of M` request attribute.
- Messages: `Notify`, `Done`, `Grant` and `Ack`.
- The verdict equals a pure fixpoint reduction of the wait-for graph.

Status: stub. Milestone M5 adds the rules, the invariants and the lesson.
