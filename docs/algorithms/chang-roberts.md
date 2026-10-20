# Chang-Roberts

Family: election. Variant: Fokkink, *Distributed Algorithms: An Intuitive Approach*, 2nd edition, section 9.1, with a leader announcement.

Source: `modules/core/shared/src/main/scala/cs553/core/algorithms/election/ChangRoberts.scala`.

## Summary from the college README

> Election algorithm for a directed ring which assumes that each process has a Unique Identification (UID). The idea is that only the message with the highest ID will complete the round trip, because every other message is stopped.

## Variant in this project

- Topology: a directed ring. One or more nodes are initiators, and each initiator has a `uid`. A non-initiator can have a `uid`, but the algorithm does not use it. No two nodes have the same `uid`. `ChangRoberts.protocol.check` tests these global requirements, and `World.init` calls it before each run. `Protocol.init` tests that the node has one in-edge and one out-edge, and that an initiator has a `uid`.
- Channel order: `Fifo` by default. The algorithm is also correct with `AnyOrder`.
- Messages: `Elect(uid)` and `Leader(uid)`. Action: `Wake`, only at an initiator that is `Asleep`.
- Decisions: the leader decides `Elected(uid)`. Each other node decides `Follows(uid)` when the announcement reaches it. So each node decides exactly once.
- State: one enum, `CrState`. Each case holds only the data that is legal in that phase: `Asleep(uid)`, `Active(uid)`, `Passive(uid?)`, `Announcing(uid)`, `Leader(uid)` and `Follower(uid?, leader)`.

### The rules of Fokkink

Initiators are active, and non-initiators are passive. Each active node sends its uid to its successor. When a node `p` gets the uid `q`:

- if `q < p`, then `p` purges the message;
- if `q > p`, then `p` becomes passive and passes the message on;
- if `q = p`, then `p` becomes the leader.

Passive nodes pass each message on. This includes all non-initiators.

### The decisions of this project

1. **A non-initiator is passive from the start.** In Fokkink, a non-initiator never bids. It does not join the election when a message arrives. So a non-initiator that gets an `Elect` stays `Passive` and passes the message on (`relay-elect`). This is the Fokkink rule "a non-initiator that receives a message becomes passive": the node is already passive, so its state does not change. The college code used the "participant" variant, where a non-initiator replaces a smaller uid with its own uid. That variant elects the node with the maximum uid of all nodes, not the initiator with the maximum uid (review finding chang-roberts-3).
2. **An initiator wakes with the `Wake` action.** In Fokkink, each initiator starts spontaneously. Here the scheduler chooses when each `Wake` fires, so a bid can reach an initiator before it wakes. An `Asleep` initiator applies the same comparison as an `Active` one:
   - for `q < p`, it purges the message and stays `Asleep`. It bids later, when `Wake` fires;
   - for `q > p`, it becomes `Passive` and passes the message on. `Wake` is then not offered, so the node never bids. This is the fix for review finding chang-roberts-1, where a late `Initiate` sent a second bid.
3. **The leader announces itself.** When the leader gets its own bid back, it decides `Elected(uid)` and sends `Leader(uid)`. Each passive node decides `Follows(uid)` and passes the announcement on. When the announcement comes back, the leader stops.
4. **The leader purges each late, smaller bid.** A smaller bid can still be on the ring after the election, so `Announcing` and `Leader` purge it.

| Rule id | Handler | Condition | Effect |
|---|---|---|---|
| `wake` | `onAction(Wake)` | `Asleep(p)` | `Active(p)`, `Elect(p)` to the successor |
| `purge` | `onMessage(Elect(q))` | `Asleep(p)` or `Active(p)` with `q < p`; or the leader `m` with `q < m` | no change, no send |
| `defeat` | `onMessage(Elect(q))` | `Asleep(p)` or `Active(p)` with `q > p` | `Passive(p)`, `Elect(q)` to the successor |
| `relay-elect` | `onMessage(Elect(q))` | `Passive` or `Follower` | no change, `Elect(q)` to the successor |
| `elected` | `onMessage(Elect(q))` | `Active(p)` with `q = p` | `Announcing(p)`, `Leader(p)` to the successor, decision `Elected(p)` |
| `learn-leader` | `onMessage(Leader(u))` | `Passive` | `Follower(u)`, `Leader(u)` to the successor, decision `Follows(u)` |
| `announce-returned` | `onMessage(Leader(u))` | `Announcing(u)` | `Leader(u)`, no send |

Each other combination returns `Left(ProtocolError)`, and the engine records `Rejected`. In a valid run, none of these occur.

## Invariants

The property tests in `ChangRobertsSpec` check each invariant on random rings of 2 to 12 nodes, with distinct uids, a random non-empty set of initiators, random seeds and both channel orders.

- The run ends `Quiescent`, and the trace has no `Rejected` event.
- Exactly one node decides `Elected`. It is the initiator with the maximum uid.
- Each node decides exactly once. Each decision holds the uid of the leader. At the end, the leader is `Leader(m)`, and each other node is `Follower(_, m)`.
- The run sends at most n(n+1)/2 `Elect` messages and exactly n `Leader` messages.

An example test reaches the worst case: on a ring where the uids decrease along the ring and each node is an initiator, all `Wake` actions fire first. The run then sends exactly n(n+1)/2 `Elect` messages.

## Regression tests

- **chang-roberts-1:** an initiator that a larger bid defeats before `Wake` becomes `Passive`, and `Wake` is not offered after that.
- **chang-roberts-3:** on the college ring `0(10) -> 1(20) -> 2(5) -> 3(15)` with only node 2 as initiator, uid 5 wins.
- **chang-roberts-4:** the college code put the Terminator (map key -1) into the pool of possible initiators. In the new design, this cannot occur. An initiator is an attribute of a topology node, so `Topology.withInitiators` rejects an unknown node, and `ChangRoberts.randomRing` chooses only ring nodes. `World.enabled` offers `Wake` only at nodes of the topology, and a script that fires `Wake` at node -1 gives `BadChoice(UnknownNode(-1))`. The test checks each of these facts, and it checks that each `Wake` in a run fires at an initiator of the ring.
- **chang-roberts-5:** `ChangRoberts.protocol.check` rejects a duplicate uid, a missing uid on an initiator, a topology with no initiator and a graph that is not a directed ring.

## Example

The college ring `0(10) -> 1(20) -> 2(5) -> 3(15) -> 0` with initiators 0 and 1:

1. Node 0 and node 1 wake. Node 0 sends `Elect(10)` to node 1. Node 1 sends `Elect(20)` to node 2.
2. Node 1 purges `Elect(10)`, because 10 < 20.
3. Nodes 2, 3 and 0 pass `Elect(20)` on. Node 0 becomes `Passive` (`defeat`), because 20 > 10.
4. `Elect(20)` comes back to node 1. Node 1 decides `Elected(20)` and sends `Leader(20)`.
5. Nodes 2, 3 and 0 each decide `Follows(20)` and pass the announcement on.
6. `Leader(20)` comes back to node 1, and the run is quiescent.

The CLI runs Chang-Roberts on a ring that it makes from the seed:

```
sbt "cli/run simulate --alg chang-roberts --ring 6 --seed 7"
```
