# Tarry

Family: wave. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> Coordinates process traversal in a distributed system, ensuring a predetermined order of visitation and enabling synchronization.

## Variant in this project

- Topology: an undirected connected graph.
- Messages: `Token`.
- One token exists at all times. Each channel carries the token once in each direction. A node sends to its parent last.

Status: stub. Milestone M4 adds the rules, the invariants and the lesson.
