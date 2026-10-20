# Lai-Yang

Family: snapshot. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> A snapshot algorithm used for taking consistent global snapshots of a distributed system. This algorithm does not enforce the FIFO property of channels.

## Variant in this project

- Topology: a strongly connected directed graph.
- Channel order: `AnyOrder`.
- Messages: `Transfer(amount, color)` and `Control(preSnapshotCount)`. Each node records exactly once.

Status: stub. Milestone M5 adds the rules, the invariants and the lesson.
