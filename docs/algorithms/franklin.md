# Franklin

Family: election. Variant: Fokkink, *Distributed Algorithms: An Intuitive Approach*, 2nd edition, section 9.1, with a leader announcement.

Source: `modules/core/shared/src/main/scala/cs553/core/algorithms/election/Franklin.scala`.

## Summary from the college README

> Requires an undirected ring, improves on the worst-case message complexity of the Chang-Roberts algorithm. In an election round, each active process compares its own ID with the IDs of its nearest active neighbors on both sides.

## Variant in this project

- Topology: a bidirectional ring of three or more nodes. Each node has two neighbours, with one edge in each direction. One or more nodes are initiators, and each initiator has a `uid`. No two nodes have the same `uid`. `Franklin.protocol.check` tests these global requirements. `Protocol.init` tests that the node has two neighbours and that an initiator has a `uid`.
- Channel order: locked to `Fifo`, because messages from different rounds must not overtake each other. A passive node must not let the round r+1 message of a candidate go before its round r message.
- Messages: `Id(uid, round)` and `Leader(uid)`. Action: `Wake`, only at an initiator that is `Asleep`.
- Decisions: the leader decides `Elected(uid)`. Each other node decides `Follows(uid)` when the announcement reaches it.
- State: one enum, `FrState`: `Asleep(uid)`, `Active(uid, round, heard)`, `Passive(uid?)`, `Announcing(uid)`, `Leader(uid)` and `Follower(uid?, leader)`. `heard` holds the `Id` messages that an active node received and did not use yet.
- Rounds start at 1.

### The rules of Fokkink

Initiators are active, and non-initiators are passive. In each round, each active node `p` sends its uid in the two directions. Passive nodes pass each message on. When `p` has the uids `q` and `r` of its nearest active neighbours of this round:

- if `max(q, r) < p`, then `p` starts the next round;
- if `max(q, r) > p`, then `p` becomes passive;
- if `p` gets its own uid back, then `p` is the only active node, and it becomes the leader.

The number of active nodes at least halves in each round, because two adjacent active nodes cannot both survive. So the algorithm needs at most ⌊log₂ n⌋ + 1 rounds and O(n log n) messages.

### The decisions of this project

1. **Each node decides by itself.** No central process runs the rounds. Each message carries its round number, so no global barrier is necessary.
2. **An active node keeps early messages.** A neighbour can start round r+1 before `p` completes round r. With `Fifo`, at most one early message from each side can arrive. `p` keeps it in `heard` and uses it when it starts round r+1. A survivor can complete the next round at once with these messages. A defeated node relays the early messages to the other side.
3. **An initiator wakes with `Wake` or with its first message.** The scheduler chooses when `Wake` fires, so an `Id` can reach an initiator before it wakes. The initiator then starts round 1 first: it sends its uid in the two directions, and then it records the message (`wake-by-message`). The first message of a sleeping node is always of round 1, because a message of round 2 from that side must pass through the node first.
4. **The leader announces itself.** When the leader gets its own uid back from the two sides, it decides `Elected(uid)` and sends `Leader(uid)` to its neighbour with the lower node id. Each passive node decides `Follows(uid)` and passes the announcement on to the other side. When the announcement comes back, the leader stops.

| Rule id | Handler | Condition | Effect |
|---|---|---|---|
| `wake` | `onAction(Wake)` | `Asleep(p)` | `Active(p, 1)`, `Id(p, 1)` to the two neighbours |
| `wake-by-message` | `onMessage(Id(q, 1))` | `Asleep(p)` | as `wake`, and the message goes into `heard` |
| `record` | `onMessage(Id(q, r))` | `Active(p, r)` or `Active(p, r-1)`, the round is not complete | the message goes into `heard` |
| `survive` | `onMessage(Id(q, r))` | the round is complete and both uids are smaller than `p` | `Active(p, r+1)`, `Id(p, r+1)` to the two neighbours |
| `defeat` | `onMessage(Id(q, r))` | the round is complete and one uid is larger than `p` | `Passive(p)`, the early messages go to the other side |
| `elected` | `onMessage(Id(q, r))` | the round is complete and both uids are `p` | `Announcing(p)`, `Leader(p)` to one neighbour, decision `Elected(p)` |
| `relay-id` | `onMessage(Id(q, r))` | `Passive` | `Id(q, r)` to the other neighbour |
| `learn-leader` | `onMessage(Leader(u))` | `Passive(own)` | `Follower(own, u)`, `Leader(u)` to the other neighbour, decision `Follows(u)` |
| `announce-returned` | `onMessage(Leader(u))` | `Announcing(u)` | `Leader(u)`, no send |

Each other combination returns `Left(ProtocolError)`, for example a message two rounds early, a second message of one round from one side, or a message from a node that is not a neighbour. The engine records `Rejected`. In a valid run, none of these occur.

## UI

- `Overlay[FrState]`: the leader is `Fill(Chosen)`. Each passive node and each follower is `Fill(Passive)`. Each active node shows the badge "round r". `Fact("round r: k active")` gives the number of active nodes in the newest round.
- The canvas draws `Id(6, round 2)` as the short chip "6·r2". The tip and the panel show the full message. A follower keeps its uid in the label.
- The node panel shows the round and the `Id` messages in `heard`, so the learner sees the comparison with the two neighbours.

## Invariants

`Franklin.invariant` checks each world. It gives one line when one of these breaks:

- At most one node is the leader.
- The leader and each follower name the largest initiator uid.
- No active node is in a round after ⌊log₂ n⌋ + 1.

The property tests in `FranklinSpec` run on random bidirectional rings of 3 to 12 nodes, with distinct uids, a random non-empty set of initiators, plain or sparse node ids, and random seeds. The order is `Fifo` only, because the protocol locks it. The tests check:

- The run ends `Quiescent`, and the trace has no `Rejected` event.
- Exactly one node decides `Elected`. It is the initiator with the maximum uid.
- The run has at most ⌊log₂ n⌋ + 1 rounds, and at most ⌊log₂ k⌋ + 1 rounds for k initiators.
- In round 1, all initiators are active. While two or more nodes are active, the active count of round r+1 is at most half of round r. The last round has one active node, and it is the leader.
- All nodes learn the leader, and each node decides exactly once.
- The run sends exactly 2n `Id` messages in each round and exactly n `Leader` messages.
- `Franklin.invariant` holds in each world.

## Regression tests and "cannot happen"

- **franklin-1 (central orchestrator):** `regression franklin-1` checks that each message goes between ring neighbours, that the leader decides with the rule `elected` when its own uid comes back, and that each node ends with the leader in its own state. The new code has no orchestrator.
- **franklin-2 (tests check only fan-out):** `regression franklin-2` checks the elected uid on a ring with one correct answer. The property tests check the leader, the rounds, the relays and the decisions.
- **franklin-3 and franklin-4 (`Option.get` on a neighbour, no ring validation):** `regression franklin-3 and franklin-4` checks that `check` rejects a node with three neighbours and a ring with a missing back edge, and that a message from a node that is not a neighbour is a rejected input. The code uses no `.get`.
- **franklin-5 (30 s `Await.ready`):** cannot happen. The Simulator stops at quiescence, and the mediated Akka engine detects completion with protocol messages (ADR-0007).
- **franklin-6 (companion objects as messages):** cannot happen. `FrMsg` and `FrAction` are enums, and the compiler checks each case.
- **franklin-7 (mutable vars and the -1 sentinel):** cannot happen. The state is one immutable enum. "Not received" is the absence of an entry in `heard`.
- **franklin-8 (`passiveProcess` never cleared):** `regression franklin-8` runs the ring of the finding. Each node goes passive at most once, and the run sends exactly 2n messages in each round plus n announcements.
- **franklin-9 (shared mutable map):** cannot happen. A node knows only its `NodeCtx`, and the engine owns the channels.
- **franklin-10 (dead code):** cannot happen. The protocol has no actor plumbing, and scalafix removes unused code.

## Example

The preset `ring-8` has the uids `3, 7, 1, 8, 5, 2, 6, 4` in ring order. All nodes are initiators.

1. Round 1: each node sends its uid in the two directions. The nodes with uids 7, 8 and 6 are larger than both neighbours, so they survive. The other five nodes become passive.
2. Round 2: uid 7 sees 6 and 8, and uid 6 sees 8 and 7. Both become passive. Uid 8 sees 7 and 6, and it survives.
3. Round 3: uid 8 is the only active node. Its uid comes back from the two sides, and node 3 decides `Elected(8)`.
4. Node 3 sends `Leader(8)`. Each other node decides `Follows(8)` and passes the announcement on. The announcement comes back, and the run is quiescent.

The active counts are 8, 3 and 1, and ⌊log₂ 8⌋ + 1 = 4 is the bound. The preset `college-5` is the ring of the college config (uids 0 to 4): uid 4 wins in round 2. The preset `ring-6-some` has three initiators and three passive relays.
