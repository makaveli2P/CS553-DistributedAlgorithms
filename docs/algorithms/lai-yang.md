# Lai-Yang

Family: snapshot. Variant: Fokkink, *Distributed Algorithms: An Intuitive Approach*, 2nd edition, section 3.2.

Source: `modules/core/shared/src/main/scala/cs553/core/algorithms/snapshot/LaiYang.scala`.

## Summary from the college README

> A snapshot algorithm used for taking consistent global snapshots of a distributed system. This algorithm does not enforce the FIFO property of channels.

## Variant in this project

- Topology: a strongly connected directed graph with two or more nodes and one or more initiators. `LaiYang.protocol.check` tests these global requirements.
- Channel order: `AnyOrder` by default. The algorithm is also correct with `Fifo`.
- Application: a bank. Each node has a balance and a transfer budget. The DOT attributes `balance` and `budget` give them, for example `0 [initiator=true, balance=10, budget=3]`. The defaults are balance 10 and budget 2. `init` gives `TopologyError.BadAttribute` for a value that is not a number or is negative.
- Messages: `LyMsg.Transfer(amount, color)` and `LyMsg.Control(preSnapshotCount)`. The color is `LyColor.White` or `LyColor.Red`. A transfer has the color of its sender at the time of the send.
- Actions: `LyAction.Initiate`, only at a white initiator, and `LyAction.Spend(to, amount)` for each out-neighbour and each amount in `LaiYang.amounts` (1 and 5) that the balance allows. Each spend uses one unit of the budget. When the budget is 0, the node has no spend actions, so each run ends.
- Decision: `LyOutput.Recorded(balance, channels)`. Each node decides once, when it recorded each of its in-channels.
- State: `LyState(role, balance, budget, sentWhite, receivedWhite, phase)`. The phase is one enum: `White`, `Recording(record)` or `Done(record)`. The color of the node follows from the phase. The state has no Boolean flags.
- `LyRecord(trigger, balance, expected, channels)` holds the local snapshot: why the node took it, the balance at the snapshot, the count of each control message that arrived, and the white transfers that arrived after the snapshot, by sender.

The rules follow Fokkink:

1. A node takes its local snapshot when it initiates, when it gets a red transfer while it is white, or when it gets a control message while it is white.
2. At the snapshot, the node records its balance and becomes red. It then sends `Control(n)` on each out-channel, where `n` is the number of white transfers that it sent on that channel.
3. A red transfer at a white node makes the node take its snapshot **before** it handles the transfer. So the recorded balance does not hold the red transfer.
4. A white transfer at a red node goes into the recorded state of its channel.
5. The channel from `q` is closed when the control message from `q` arrived and the node received as many white transfers from `q` as the control message counts. When each in-channel is closed, the node decides.

| Rule id | Handler | Condition | Effect |
|---|---|---|---|
| `initiate` | `onAction(Initiate)` | initiator, `White` | snapshot, a control message on each out-channel |
| `spend-white` | `onAction(Spend)` | `White`, budget left, enough balance | white transfer, `sentWhite + 1` on the channel |
| `spend-red` | `onAction(Spend)` | red, budget left, enough balance | red transfer |
| `receive-white` | `onMessage(Transfer White)` | `White` | balance + amount, `receivedWhite + 1` |
| `snapshot-on-red` | `onMessage(Transfer Red)` | `White` | snapshot first, then balance + amount |
| `receive-red` | `onMessage(Transfer Red)` | red | balance + amount |
| `record-white` | `onMessage(Transfer White)` | `Recording` | balance + amount, the amount goes into the channel record; decide when each in-channel is closed |
| `snapshot-on-control` | `onMessage(Control)` | `White` | snapshot, a control message on each out-channel, store the count; decide when each in-channel is closed |
| `count-control` | `onMessage(Control)` | `Recording` | store the count; decide when each in-channel is closed |

The protocol returns `Left(ProtocolError)` for a second control message on one channel, for a control message at a `Done` node, for a white transfer that the control message did not count, for `Initiate` at a red node or a non-initiator, and for a spend that the budget or the balance does not allow or that goes to a node that is not an out-neighbour. In a valid run, none of these occur.

## Invariants

The property tests in `LaiYangSpec` check each invariant on random strongly connected directed graphs (a random Hamiltonian cycle plus random extra edges), with random balances, budgets and initiator sets, random seeds and both channel orders.

- The run ends `Quiescent`, and the trace has no `Rejected` event. Each node decides exactly once and ends `Done`.
- Conservation: in each world, the balances plus the transfers in transit equal the initial total. At the end, the recorded balances plus the recorded channel contents equal the initial total.
- Consistent cut: each node records the balance that it had before the step that made it red. A transfer is white if and only if its sender sent it before its own snapshot. No red transfer arrives at a node before its snapshot. The recorded state of each channel is exactly the white transfers that arrive after the snapshot of the receiver.
- Each node becomes red exactly once and never becomes white again. Each channel carries exactly one control message. Only initiators fire `Initiate`, and each one fires it at most once.

`LaiYang.invariant` checks conservation in each world for the web. It also checks the snapshot total when each node is done. It gives a line such as "Total 15 is not 20: a transfer was lost." `RegistrySpec` checks that it holds in each world of seeded runs on each preset.

## Overlay

- `Fill(White)` for a white node and `Fill(Red)` for a red node. An initiator that took its snapshot by `Initiate` and is done gets `Fill(Chosen)`, which the home thumbnail shows.
- `Cut(node)` for each red node. The web shows the step of the snapshot.
- `Badge(node, "k of n closed")` for each red node.
- `Channel(from, node, Recording or Closed, amounts)` for each in-channel of a red node, with the recorded white transfers.

## Presets

| Name | Topology |
|---|---|
| `bank-4` | Four nodes on a directed graph that is strongly connected but not symmetric. Node 0 is the initiator. Balances 10, 20, 5 and 15. This preset is the default and has the golden traces. |
| `netgraph-6` | The college fixture `NetGraph_30-03-24-18-54-55.ngs.dot` with both directions of each edge. Node 0 is the initiator. Nodes 3 and 4 have budget 0, so they only receive. |
| `ring-5` | A bidirectional ring of 5 with initiators 0 and 2, so two snapshots can start at the same time. Node 4 has budget 0. |

## Example

Two nodes 0 and 1 with one edge in each direction, balance 10 each. Node 0 is the initiator. The control message overtakes a white transfer:

| Step | Node | Cause | Rule | Effect |
|---|---|---|---|---|
| 1 | 0 | `Fire(Spend(5 to 1))` | `spend-white` | `Transfer(5, white)` to 1, balance 5 |
| 2 | 0 | `Fire(Initiate)` | `initiate` | records 5, `Control(1)` to 1 |
| 3 | 1 | `Deliver(0->1#1)` | `snapshot-on-control` | records 10, `Control(0)` to 0; waits for 1 white transfer |
| 4 | 0 | `Deliver(1->0#0)` | `count-control` | decides `Recorded(5, none)` |
| 5 | 1 | `Deliver(0->1#0)` | `record-white` | records 5 on the channel from 0; decides `Recorded(10, 0: 5)` |

The snapshot total is 5 + 10 + 5 = 20, which is the initial total.

## The bugs of the college code

Each verified bug in `docs/notes/2026-10-review-of-college-code.md` has a regression test in `LaiYangSpec` or a reason why it cannot occur:

- lai-yang-1, a red message that the node applied before its snapshot: `regression lai-yang-1`. The snapshot and the transfer are in one handler step, so no other step can come between them.
- lai-yang-2, the `snapshotTaken` reset: `regression lai-yang-2`. The phase goes from `White` to `Recording` to `Done` and never back, so the color of a node is permanent.
- lai-yang-3, the double completion: `regression lai-yang-3` and the property "each node decides once". `Done` accepts no control message and no `Initiate`.
- lai-yang-4, the "Increment" and "increment" mismatch: `regression lai-yang-4`. A transfer is one enum case with an amount, so no string selects the operation.
- lai-yang-7, the output that leaves out the message that closes the last channel: `regression lai-yang-7`. The decision comes from the same step that records the message.
- lai-yang-9, `Map.apply` on a sender that is not a neighbour: `regression lai-yang-9`. The protocol reads its maps with `getOrElse` and rejects a spend to a node that is not an out-neighbour. The `bank-4` preset is not symmetric.
- The global completion counter, the shared `HashMap`, the global singletons and the busy loop cannot occur. The state of each node is in its own `LyState`, the Simulator or the Controller detects quiescence, and each run is a pure function of its `RunSpec`.
- The weak message protocol (a Boolean that selects the variant, a -1 sentinel) cannot occur. `LyMsg`, `LyColor` and `LyAction` are enums.
- The college tests that checked only a count of 0: the property tests check the recorded balances, the channel records, the conservation and the completion of each node.
