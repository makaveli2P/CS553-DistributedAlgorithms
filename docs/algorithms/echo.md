# Echo

Family: wave. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> A fundamental communication protocol where a message is sent through the network and echoed back by each recipient, confirming its receipt.

## Variant in this project

- Topology: an undirected connected graph with one initiator.
- Messages: `Token`.
- The algorithm sends exactly 2|E| messages. The parent edges form a spanning tree. Only the initiator decides.

Status: stub. Milestone M1 adds the rules, the invariants and the lesson.
