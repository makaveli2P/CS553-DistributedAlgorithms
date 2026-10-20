# Chandy-Lamport

Family: snapshot. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> A snapshot algorithm that is used in distributed systems for recording a consistent global state of an asynchronous system.

## Variant in this project

- Topology: a strongly connected directed graph.
- Channel order: locked to `Fifo`, because markers must not overtake transfers.
- Messages: `Transfer(amount)` and `Marker`. Actions: `Spend(to, amount)` and `Initiate`.

Status: stub. Milestone M5 adds the rules, the invariants and the lesson.
