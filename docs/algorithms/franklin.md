# Franklin

Family: election. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> Requires an undirected ring, improves on the worst-case message complexity of the Chang-Roberts algorithm. In an election round, each active process compares its own ID with the IDs of its nearest active neighbors on both sides.

## Variant in this project

- Topology: a bidirectional ring.
- Channel order: locked to `Fifo`, because messages from different rounds must not overtake each other.
- Messages: `Id(uid, round)` and `Leader(uid)`. The algorithm needs at most ⌊log₂ n⌋ + 1 rounds.

Status: stub. Milestone M4 adds the rules, the invariants and the lesson.
