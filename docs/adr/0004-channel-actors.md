# 4. Model each directed channel as an actor

Date: 2026-10-20

## Status

Accepted

## Context

The mediated Akka engine must give the same trace as the Simulator for the same choices. Akka does not order messages from two different senders. Learners must see real asynchronous sends with `!`, not sends that a node returns inside a reply.

## Decision

`RunGuardian` spawns one `Controller`, one node actor for each process and one `Channel(i->j)` actor for each directed edge.

- A node holds only the refs of its outgoing channels and sends with `out(j) ! Put(env)`.
- The channel buffers the envelope and tells the Controller `Enqueued(id)`.
- The Controller sends `Release(id)` to a channel or `Act(a)` to a node.
- The node calls the pure `onMessage`, performs the sends and reports `Handled` to the Controller.
- The Controller ends a step only when it has the `Handled` report and one `Enqueued` for each sent id. Only one step runs at a time.

## Consequences

- The choices alone define the trace, so the conformance test can compare traces.
- A graph with 50 nodes needs a few hundred actors. This load is small for Akka.
- The free-run engine does not use channel actors. Nodes send to peers directly there.
