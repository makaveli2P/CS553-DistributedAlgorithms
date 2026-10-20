# Echo

Family: wave. Variant: Fokkink, *Distributed Algorithms: An Intuitive Approach*, 2nd edition, section 4.3.

Source: `modules/core/shared/src/main/scala/cs553/core/algorithms/wave/Echo.scala`.

## Summary from the college README

> A fundamental communication protocol where a message is sent through the network and echoed back by each recipient, confirming its receipt.

## Variant in this project

- Topology: an undirected connected graph with exactly one initiator. `Echo.check` tests these global requirements. `Protocol.init` tests only that each node has the same in-neighbours and out-neighbours.
- Channel order: `Fifo` by default. The algorithm is also correct with `AnyOrder`.
- Message: `EchoMsg.Token`. Action: `EchoAction.Wake`, only at the initiator. Decision: `EchoOutput.WaveComplete`, only at the initiator.
- State: `EchoState(role, phase, parent, received)`. The phase is one enum: `Idle`, `Waiting`, `Echoed` or `Decided`. The state has no Boolean flags.

The rules follow Fokkink:

1. The initiator sends a token to each neighbour.
2. When a non-initiator gets its first token, the sender becomes its parent. The node then sends a token to each neighbour except its parent.
3. When a non-initiator has a token from each neighbour, it sends a token to its parent.
4. When the initiator has a token from each neighbour, it decides.

| Rule id | Handler | Condition | Effect |
|---|---|---|---|
| `wake` | `onAction(Wake)` | initiator, `Idle`, one or more neighbours | phase `Waiting`, a token to each neighbour |
| `wake-alone` | `onAction(Wake)` | initiator, `Idle`, no neighbours | phase `Decided`, decision `WaveComplete` |
| `first-token` | `onMessage(Token)` | non-initiator, `Idle`, two or more neighbours | parent is the sender, `received = 1`, phase `Waiting`, a token to each other neighbour |
| `first-token-leaf` | `onMessage(Token)` | non-initiator, `Idle`, one neighbour | parent is the sender, phase `Echoed`, the token goes back to the parent |
| `count-token` | `onMessage(Token)` | `Waiting`, more tokens to come | `received + 1` |
| `echo-to-parent` | `onMessage(Token)` | non-initiator, `Waiting`, last token | phase `Echoed`, a token to the parent |
| `decide` | `onMessage(Token)` | initiator, `Waiting`, last token | phase `Decided`, decision `WaveComplete` |

The protocol returns `Left(ProtocolError)` for a token at an `Echoed` or `Decided` node, for a token at the initiator before `Wake`, and for `Wake` in a phase other than `Idle`. The engine records each one as `Rejected`. In a valid run, none of these occur.

## Invariants

The property tests in `EchoSpec` check each invariant on random connected graphs (a random spanning tree plus random extra edges), with a random initiator, random seeds and both channel orders.

- The run ends `Quiescent`, and the trace has no `Rejected` event.
- The run sends exactly 2|E| messages: one token in each direction of each edge.
- The parent edges form a spanning tree with the initiator as the root. Only the initiator has no parent.
- Only the initiator decides, and it decides exactly once. The decision is the last step of the run.
- At the end, each non-initiator is `Echoed`, the initiator is `Decided`, and each node has `received` equal to its degree.

## Example

The line `0 - 1 - 2` with initiator 0 has one legal choice at each step, so each seed gives this trace:

| Step | Node | Cause | Rule | Sends |
|---|---|---|---|---|
| 1 | 0 | `Fire(Wake)` | `wake` | `Token` to 1 |
| 2 | 1 | `Deliver(0->1#0)` | `first-token` | `Token` to 2 |
| 3 | 2 | `Deliver(1->2#0)` | `first-token-leaf` | `Token` to 1 |
| 4 | 1 | `Deliver(2->1#0)` | `echo-to-parent` | `Token` to 0 |
| 5 | 0 | `Deliver(1->0#0)` | `decide` | none; node 0 decides `WaveComplete` |

The run sends 4 messages, which is 2|E| for 2 edges.

## The college fixture `inputEcho.dot`

The file is a `digraph`. Echo uses its symmetric closure. The file marks node 0 as `Init`, but node 0 has no edges. So `Echo.check` rejects the file as it is, with the message "Echo needs a connected graph; these nodes have no edges: 0". The college code read only the edge lines and chose a random initiator, so it did not see this problem.

To run Echo on the file, remove node 0 and choose an initiator in the connected part. The CLI does this with `--initiator`:

```
sbt "cli/run simulate --alg echo --topology fixtures/topologies/inputEcho.dot --initiator 1 --seed 7"
```

The CLI removes each node that has no edges and is not an initiator. The run has 5 nodes and 7 edges, and it sends 14 messages.
