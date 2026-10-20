# Tarry

Family: wave. Variant: Fokkink, *Distributed Algorithms*, 2nd edition, section 4.3 (Tarry's traversal algorithm).

## Summary from the college README

> Coordinates process traversal in a distributed system, ensuring a predetermined order of visitation and enabling synchronization.

## Variant in this project

- Topology: an undirected connected graph with exactly one initiator. `protocol.check` refuses other graphs before the run.
- Messages: `TarryMsg.Token`.
- Actions: `TarryAction.Wake` (the initiator only) and `TarryAction.Pass(to)`. The node that holds the token passes it with an action. The Simulator, the Akka engine or the learner chooses the channel from the legal channels.
- Phases: `Idle`, `Holding`, `Waiting`, `Done` (a non-initiator after it passed the token to its parent) and `Decided` (the initiator).

The two rules of Fokkink:

1. A node passes the token at most once on each channel.
2. A non-initiator passes the token to its parent only when no other channel is left.

A non-initiator makes the sender of its first token its parent. The initiator has no parent. The initiator decides when it gets the token back and it sent the token through each channel.

`Tarry.candidates` gives the legal channels. `protocol.actions` gives one `Pass` for each legal channel, so an illegal pass is never offered. `onAction` refuses an illegal pass with `IllegalAction`.

## Invariants

The tests and `Tarry.invariant` check these invariants:

- Exactly one token exists in each world: the buffered tokens plus the node in phase `Holding`. The initiator holds the token before `Wake` and after its decision.
- Each channel carries the token exactly once in each direction. The run sends 2|E| messages. The tests check this. `Tarry.invariant` does not check it separately, because exactly one token means that no channel can carry two tokens.
- A node passes the token to its parent last.
- The parent edges form a spanning tree rooted at the initiator.
- Only the initiator decides, exactly once, as the last step. At the decision, each node sent and received the token through each of its channels.

The web shows the invariant line only when it breaks.

## UI

- `Mark.OnEdge(node, to, EdgeStyle.Used)` for each channel that carried the token.
- `Mark.treeEdge(child, parent)` for each parent edge.
- `Mark.Badge(node, "token")` on the node that holds the token.
- `Mark.Fill(initiator, NodeFill.Chosen)` when the initiator decides, and `Mark.Fill(node, NodeFill.Passive)` for each node that is done.
- `Mark.Fact("parent edge is sent last")` after the first parent edge exists.
- The node fields show "sent to", "received from" and the line "the parent is the last edge".

## Presets

| Name | File | Initiator |
|---|---|---|
| `inputTarry` (default) | `inputTarry.dot` | 1. The prepare step omits node 0, which has no edges. |
| `netgraph-6` | `NetGraph_30-03-24-18-54-55.ngs.dot` | 0, from the `Init` label. |
| `inputEcho` | `inputEcho.dot` | 1 |

## Example

On the line 0 - 1 - 2 with initiator 0, the token goes 0 -> 1 -> 2 -> 1 -> 0. The rules are `wake`, `pass`, `first-token`, `pass`, `first-token`, `pass-to-parent`, `token-back`, `pass-to-parent` and `decide`. The run sends 4 messages, which is 2|E|.

On a ring of 4 with initiator 1, the initiator chooses 2 or 4 first. Each choice gives a traversal of 8 messages.

## College bugs

The review is in `docs/notes/2026-10-review-of-college-code.md`, section `tarry`. Each verified bug has a regression test in `TarrySpec` or a reason why it cannot happen:

| Bug | Result in the new design |
|---|---|
| The tests assert only one `TerminateTarry`, so the mutants pass. | Test "regression: the mutants that the college tests let pass fail here", and the property tests. |
| Termination uses a 30 s `Await` that leaks the ActorSystem. | Cannot happen: the node actors use no `Await`. The run ends at quiescence, and the Controller detects it. |
| The DOT `Init` marker is ignored, and the initiator is random with no seed. | Test "regression: the initiator comes from the topology, and the run replays from its seed". |
| The algorithm gives only log lines. | Test "regression: the run gives a typed trace, a parent tree and a decision, not log lines". |
| No pure step function exists. | Cannot happen: `Tarry.protocol` is pure, and the Simulator and Akka call it. |
| The initiator sets a parent. | Test "regression: the initiator has no parent, so the parent edges form a tree". |
| The initiator flag and `InitiateTarry` are two sources of truth. | Test "regression: Wake is legal only for the initiator, so one source of truth gives the role". |
| A missing actor reference drops the token. | Cannot happen: the engine checks each send against the topology (`NotANeighbour`), and the mediated engine gets each channel ref before the run. |
| A shared mutable map holds the actor references. | Cannot happen: core has no mutable state, and each node actor gets its own channel refs. |
| `InitiateTarry` is an empty case class that the code uses through its companion. | Cannot happen: actions and messages are enums, and the compiler checks each match. |
| The termination check tests "sent to all", not "received through all". | The state keeps `sent` and `received`. Test "property: at the end each node sent and received the token through each of its channels". |
| Neighbour lists can have duplicates, and connectivity is not checked. | Tests "regression: a duplicate edge in the DOT file gives one channel, so 2\|E\| still holds" and "regression: a disconnected graph is refused before the run, not partly visited". |
