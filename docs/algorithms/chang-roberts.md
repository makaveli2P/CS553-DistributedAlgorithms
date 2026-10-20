# Chang-Roberts

Family: election. Variant: Fokkink, *Distributed Algorithms*, 2nd edition.

## Summary from the college README

> Election algorithm for a directed ring which assumes that each process has a Unique Identification (UID). The idea is that only the message with the highest ID will complete the round trip, because every other message is stopped.

## Variant in this project

- Topology: a directed ring with marked initiators. Channel order: `Fifo` by default.
- Messages: `Elect(uid)` and `Leader(uid)`. Action: `Wake`.
- Non-initiators stay passive and forward messages. The leader sends a `Leader` announcement around the ring, so every node decides.

Status: stub. Milestone M1 adds the rules, the invariants and the lesson.
