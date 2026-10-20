# Chandy-Lamport

Family: snapshot. Variant: Fokkink, *Distributed Algorithms: An Intuitive Approach*, 2nd edition, section 8.1, with a bank application.

Source: `modules/core/shared/src/main/scala/cs553/core/algorithms/snapshot/ChandyLamport.scala`.

## Summary from the college README

> A snapshot algorithm that is used in distributed systems for recording a consistent global state of an asynchronous system.

## Variant in this project

- Topology: a strongly connected directed graph. One or more nodes are initiators. `ChandyLamport.protocol.check` tests these global requirements.
- Channel order: locked to `Fifo`, because markers must not overtake transfers. A run with `AnyOrder` gives `RunError.OrderLocked`.
- Messages: `Transfer(amount)` and `Marker`.
- Actions: `Spend(to, amount)` and `Initiate`.
- Node attributes: `balance` (the initial balance, default 10) and `budget` (the number of transfers that the node can start, default 3). DOT example: `0 [initiator=true, balance=10, budget=3]`. `Protocol.init` gives `TopologyError.BadAttribute` for text that is not a number, or for a negative number.
- State: `ClState(role, balance, budget, phase)`. The phase is one enum: `Waiting`, `Recording(saved, channels)` or `Recorded(saved, channels)`. Each channel record is `Open(from, amounts)` or `Closed(from, amounts)`.
- Decision: a node decides `Snapshot(balance, channels)` when it closes its last incoming channel. So each node decides exactly once.

### The bank application

Each node has a balance. A node with a budget greater than 0 can do `Spend(to, amount)` for each out-neighbour `to` and each amount from 1 to the smaller of its balance and 3. The action decreases the balance and the budget, and it sends `Transfer(amount)`. The receiver adds the amount to its balance. The budget makes each run finite. The sum of the balances plus the transfers in the channels is always the initial total.

### The rules of Fokkink

- An initiator takes its local snapshot. It saves its balance, sends a marker on each outgoing channel and starts to record each incoming channel.
- When a node gets its first marker, it takes its local snapshot in the same way. The channel of the first marker records empty.
- When a node gets a marker on an open channel, it closes the record of that channel.
- A transfer that arrives on an open channel goes into the record of that channel.
- A node completes when each incoming channel is closed.

| Rule id | Handler | Condition | Effect |
|---|---|---|---|
| `spend` | `onAction(Spend(to, a))` | budget > 0, 1 <= a <= balance | balance - a, budget - 1, `Transfer(a)` to `to` |
| `initiate` | `onAction(Initiate)` | initiator, `Waiting` | `Recording` (or `Recorded`), `Marker` on each outgoing channel |
| `first-marker` | `onMessage(Marker)` | `Waiting` | the same, and the channel of the marker is `Closed` and empty |
| `close-channel` | `onMessage(Marker)` | `Recording`, the channel is open | the channel is `Closed`; `Recorded` and the decision when no channel is open |
| `receive-record` | `onMessage(Transfer(a))` | `Recording`, the channel is open | balance + a, and `a` goes into the record |
| `receive` | `onMessage(Transfer(a))` | each other phase or a closed channel | balance + a |

A marker on a closed channel, and a marker at a `Recorded` node, give `Left(ProtocolError.UnexpectedMessage)`. `Initiate` is offered only at an initiator in the `Waiting` phase. In a valid run, no `Rejected` event occurs.

## Invariants

The property tests in `ChandyLamportSpec` check each invariant on random strongly connected graphs of 2 to 8 nodes (a directed cycle through all nodes plus random edges), with random balances, budgets, a random non-empty set of initiators and random seeds. Each test runs 200 cases, in `Fifo` order.

- The run ends `Quiescent`, with no `Rejected` event. Each node decides exactly once.
- Conservation: the saved balances plus the recorded channel contents equal the initial total.
- The cut is consistent: no transfer is sent after the cut of its sender and received before the cut of its receiver. Each channel records exactly the transfers that the sender sent before its cut and the receiver got after its cut, in order.
- The channel of the first marker records empty.
- A node records only its incoming channels. Each node takes its local snapshot once.
- `ChandyLamport.invariant` holds in each world.

`ChandyLamport.invariant` checks one world, and the web shows its line when it breaks:

- The balances plus the transfers in the channels equal the initial total.
- A marker is in a channel only when the sender took its snapshot.
- A node records only its incoming channels.
- When each node is `Recorded`, the saved balances plus the recorded channels equal the initial total.

## Regression tests

- **chandy-lamport-1 ("Increment" vs "increment"):** the messages and actions are enums, so a typo does not compile. The test checks that `Transfer(4)` adds 4 to the balance and that `Spend` subtracts the amount.
- **chandy-lamport-2 (the channel of the first marker kept recording):** on the college topology `0 - 1 - 5`, node 1 gets the marker from 0, then a later transfer from 0. The record of channel 0 stays `Closed` and empty. The transfer from 5 goes into the record of channel 5.
- **No double snapshot:** after `Initiate`, the action is not offered again. A second marker on a closed channel, and a marker at a `Recorded` node, give `Rejected`. The property test checks that each node takes its snapshot once.

These college bugs cannot happen in the new design:

- **chandy-lamport-3 and -4 (a shared counter and a shared `HashMap`):** the core has no shared mutable state. The engines detect quiescence, and each node decides when it completes.
- **chandy-lamport-5 (no test of the recorded state):** the property tests check the recorded state, the channel records, the completion and the consistency.
- **chandy-lamport-6 (global `null` vars):** the protocol is pure functions. Each run is one `World` value.
- **chandy-lamport-7 (an unbounded driver loop):** the budget makes each run finite, and the scheduler picks one step at a time.
- **chandy-lamport-8 (a vacuous `expectNoMessage`):** each test asserts a result.
- **chandy-lamport-9 and -11 (Boolean and String messages, mutable vars):** the messages, actions and phases are enums, and the handlers are pure functions.
- **chandy-lamport-10 (no result):** each node decides `Snapshot(balance, channels)`, and the trace holds each step.
- **chandy-lamport-12 (unseeded random):** each run uses `Rng(seed)` from the `RunSpec`.
- **chandy-lamport-13 and -14 (file handles and the shutdown timeout):** the core does not read files, and the engines do not wait with a timeout.

## Presets

| Name | Topology |
|---|---|
| `bank-4` | DOT text in `ChandyLamportEntry`: four nodes, initiator 0, balances 10, 20, 5 and 15 (total 50). Node 2 has budget 0, so it only receives. |
| `ring-5` | A directed ring of 5 nodes, initiators 0 and 2, budget 2. Two snapshots start at the same time and make one cut. |
| `netgraph-13` | The college network `NetGraph_21-04-24-18-24-58.ngs.perturbed.dot`, with each edge in both directions. Initiator 0. |

## UI

The overlay gives these marks:

- `Cut(node)` and `Badge(node, "saved B")` for each node that took its snapshot.
- `Channel(from, node, Recording or Closed, amounts)` for each incoming channel of such a node.
- `Fill(node, Chosen)` for each initiator that took its snapshot.

## Example

On `bank-4`, a run can go as follows:

1. Node 1 spends 3 to node 2. Its balance becomes 17.
2. Node 0 initiates. It saves 10 and sends a marker to 1 and to 3. It records channels 2 and 3.
3. Node 3 gets the marker. It saves 15 and sends a marker to 0. Channel 0 is closed and empty, and node 3 records channel 2.
4. Node 1 gets the marker. It saves 17 and sends a marker to 2. Channel 0 is its only incoming channel, so node 1 decides at once.
5. Node 2 gets the transfer of 3 before the marker, because the channel is FIFO. Node 2 is still `Waiting`, so the transfer is part of its saved balance 8. Then the marker comes, and node 2 sends a marker to 0 and to 3.
6. The markers close each channel. The saved balances 10 + 17 + 8 + 15 equal 50.

The CLI records a run of the mediated Akka engine on a preset:

```
sbt "cli/run record --engine mediated --alg chandy-lamport --preset bank-4 --seed 7"
```

`simulate --alg chandy-lamport --topology FILE.dot` runs the algorithm on a DOT file. The graph must be strongly connected, and one or more nodes must have `initiator=true`.
