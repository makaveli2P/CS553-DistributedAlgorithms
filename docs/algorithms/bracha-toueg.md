# Bracha-Toueg

Family: deadlock detection. Variant: Fokkink, *Distributed Algorithms*, 2nd edition, the Bracha-Toueg algorithm for N-out-of-M requests.

Code: `modules/core/shared/src/main/scala/cs553/core/algorithms/deadlock/BrachaToueg.scala`. Tests: `BrachaTouegSpec.scala`.

## Summary from the college README

> Employed for deadlock detection in distributed systems. It monitors resource allocation and process interactions to detect potential deadlocks and take corrective actions to resolve them.

## Variant in this project

- **Topology.** The input is a directed wait-for graph. The edge `u -> w` means that `u` waits for `w`. Out(u) is the set of nodes that `u` waits for. In(u) is the set of nodes that wait for `u`.
- **Requests.** The DOT attribute `request` gives N of "N of M". M is the out-degree of the node. N must be in 0 to M. When the attribute is absent, N is M (the AND model). Example: `0 [initiator=true, request=1]`.
- **Initiator.** The topology must have exactly one initiator.
- **Channels.** `Protocol.check` adds the reverse of each wait-for edge, because `Done` and `Grant` go against the wait-for edges. `check` writes Out and In into the node attributes. In always comes from Out.
- **Channel order.** The protocol is correct in each order. The default is `Fifo`.

### Messages and rules

| Message | Direction | Meaning |
|---|---|---|
| `Notify` | along a wait-for edge | Start the notify phase of the receiver. |
| `Done` | against a wait-for edge | The notify phase of the sender is complete. |
| `Grant` | against a wait-for edge | The sender is free, so the receiver has one more grant. |
| `Ack` | along a wait-for edge | The receiver counted the grant, and its own grant is complete. |

1. The initiator fires `Wake` and starts `notify`.
2. `notify(u)`: `u` sends `Notify` to each node of Out(u). When `u` needs no more grants, `u` starts `grant(u)`.
3. `grant(u)`: `u` becomes free and sends `Grant` to each node of In(u). The grant runs at most once for each node.
4. On the first `Notify` from `v`, `u` starts `notify(u)`. `v` becomes the parent of `u`. On each later `Notify`, `u` sends `Done` at once.
5. On `Grant` from `w`, `u` counts the grant when it still needs grants. When the count gets to 0, `u` starts `grant(u)` and sends `Ack` to `w` only after its own grant is complete. In all other cases, `u` sends `Ack` at once.
6. When `u` has all its `Done` messages and all the `Ack` messages of its grant, `u` sends `Done` to its parent.
7. At that point, the initiator decides: `NotDeadlocked` when it is free, `Deadlocked` when it is not free.

## Invariants

- **Fixpoint oracle.** `BrachaToueg.freeNodes` is a pure reduction of the wait-for graph. It frees each node that has N free out-neighbours, and it repeats until nothing changes. At the end of each run:
  - The verdict of the initiator equals the oracle.
  - Each node that the initiator reaches is free exactly when the oracle frees it. So the deadlocked set equals the oracle.
- **Done after all Acks.** A node ends `notify` with `Done` to its parent only when its grant is complete.
- **One grant.** Each node sends at most one `Grant` on each channel.
- **Message counts.** The number of `Notify` and of `Done` messages equals the number of wait-for edges that the initiator reaches. The number of `Grant` and of `Ack` messages equals the sum of In over the free nodes.
- **Step invariant (`BrachaToueg.invariant`).** In each world, the count of needed grants is in 0 to N, and only a node that the oracle frees can be free. The web shows a line when this breaks.

## Example

The preset `mixed-6`:

```
digraph {
  0 [initiator=true, request=1]; 1; 2; 3; 4; 5;
  0 -> 1; 0 -> 2; 1 -> 3; 3 -> 1; 2 -> 4; 2 -> 5; 5 -> 4
}
```

Node 4 waits for nobody, so it becomes free first. It grants to 2 and 5. Node 5 becomes free and grants to 2. Node 2 needs 2 of 2, so it becomes free and grants to 0. Node 0 needs 1 of 2, so it becomes free. Nodes 1 and 3 wait for each other, so they are deadlocked. The initiator decides `NotDeadlocked`.

The other presets are the college scenarios, with In from Out: `college-deadlock` (the cycle 0 -> 1 -> 2 -> 3 -> 0, all deadlocked) and `college-free` (no deadlock).

## UI

- Each node shows its notify phase, its grant phase and an "N of M" badge.
- The canvas marks the wait-for edges of a free node as removed, and fills the free nodes.
- After the decision, the canvas fills the deadlocked set. The Home thumbnail fills the same set.

## Bugs of the college code

Each verified bug of `docs/notes/2026-10-review-of-college-code.md` (section bracha-toueg) has a test in `BrachaTouegSpec` or a reason why it cannot happen:

| College bug | In this project |
|---|---|
| A node with empty Out sends `Done` after the first `Ack`. | Test "regression: on the review DAG a node sends Done only after all its Acks", and a property over random graphs. |
| `grant()` runs twice when `Grant` comes before `Notify`. | Tests "regression: grant runs at most once when Grant comes before Notify" and "a Notify to a node that already grants starts no second grant", and a property. |
| An initiator with no edges never decides. | Test "regression: an initiator with no edges decides at Wake". |
| The initiator logs the verdict again on each later `Ack`. | The initiator decides once, in `finish`, when its notify phase becomes `Notified`. Test "regression: the initiator decides exactly once". |
| The In lists do not match the Out lists. | In comes from Out in `check`. Test "regression: In comes from Out". |
| A `Grant` with no needed grants gets no `Ack` (refuted, but not Fokkink). | The node sends `Ack` at once. Test "regression: a Grant to a node that needs no more grants gets an Ack at once". |
| The tests do not check the verdict. | The properties check the verdict and the deadlocked set against the oracle. |
| Unchecked map lookups crash the actor. | Cannot happen: a node sends only with `Send` to a node of its context, and a message from an unexpected node gives `ProtocolError`. |
| Mutable actor state, companion-object messages, Unit results. | Cannot happen: the protocol is pure, the messages are an enum, and the verdict is the typed output `BtOutput`. |
| Only N-out-of-N. | The `request` attribute gives N-out-of-M. The properties run in both models. |
