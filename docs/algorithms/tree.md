# Tree

Family: wave. Variant: Fokkink, *Distributed Algorithms: An Intuitive Approach*, 2nd edition, section 4.2 (the tree algorithm).

Source: `modules/core/shared/src/main/scala/cs553/core/algorithms/wave/Tree.scala`.

## Summary from the college README

> Structures the communication network in a hierarchical tree-like fashion, facilitating efficient message propagation and information dissemination.

## Variant in this project

- Topology: an undirected tree with 2 or more nodes. `Tree.protocol.check` tests these global requirements, and `World.init` calls it before each run. `Protocol.init` tests only that each node has the same in-neighbours and out-neighbours. The algorithm does not use the initiator attributes: each leaf starts.
- Channel order: `Fifo` by default. The algorithm is also correct with `AnyOrder`.
- Message: `TreeMsg.Token`. Action: `TreeAction.Start`, only at a leaf that did not send. Decision: `TreeOutput.Decided(partner)`, where `partner` is the other decider.
- State: `TreeState(phase, received, parent, degree)`. The phase is one enum: `Collecting`, `Sent` or `Decided`. `received` holds the senders in the order of arrival. `parent` is the neighbour that got the token of the node.
- `Tree.prepare` adds the reverse of each edge, so a DOT `digraph` becomes undirected, and then it runs `check`.

The rules follow Fokkink:

1. When a node has a token from each neighbour except one, it sends its token to that neighbour. That neighbour is its parent. A leaf has one neighbour, so it can send at once.
2. When a node gets a token from its parent, it decides.

Each node sends exactly one token. The last token of the run crosses the edge between the two deciders in the opposite direction to the other token on that edge.

| Rule id | Handler | Condition | Effect |
|---|---|---|---|
| `start` | `onAction(Start)` | `Collecting`, one neighbour left | phase `Sent`, parent is that neighbour, a token to the parent |
| `collect` | `onMessage(Token)` | `Collecting`, two or more neighbours left after this token | add the sender to `received` |
| `send-to-parent` | `onMessage(Token)` | `Collecting`, one neighbour left after this token | phase `Sent`, parent is that neighbour, a token to the parent |
| `token-before-start` | `onMessage(Token)` | `Collecting` leaf, the token comes from its only neighbour before `Start` | phase `Decided`, a token to the neighbour, decision `Decided(neighbour)` |
| `decide` | `onMessage(Token)` | `Sent`, the token comes from the parent | phase `Decided`, decision `Decided(parent)` |

The rule `token-before-start` gives the same result as `Start` first and then the token. Fokkink's leaf sends before it receives. In this model a delivery cannot wait, so the leaf does both in one step.

The protocol returns `Left(ProtocolError)` for a second token from one sender, for a token from a node that is not a neighbour, for a token in phase `Sent` that does not come from the parent, for a token after the decision, and for `Start` in a phase other than `Collecting`. The engine records each one as `Rejected`. In a valid run, none of these occur.

## Invariants

The property tests in `TreeSpec` check each invariant on random trees of 2 to 12 nodes (plain ids and sparse relabelled ids), with random seeds and both channel orders. A second schedule delivers each enabled token before it lets a leaf start, so the rule `token-before-start` occurs often.

- The run ends `Quiescent`, and the trace has no `Rejected` event.
- Each node sends exactly one message. The run sends |V| messages.
- Exactly two nodes decide, once each. They are neighbours, and each decision names the other decider.
- The parent path of each node ends at a decider. Each node that does not decide ends in phase `Sent`.
- At the end, the overlay has |V| - 1 tree edges and fills the two deciders.

`Tree.invariant` is the invariant that the web checks at each step. `RegistrySpec` checks it in each world of seeded runs on each preset:

- Each channel holds at most one token.
- At most two nodes decide. When two nodes decide, each one is the parent of the other.

## UI

- The label of a node shows "k of d-1 received" while it collects, "waits for q" after it sends to its parent q, and "decided with q" at the end.
- The overlay draws each parent edge as a tree edge. The edge between the two deciders shows once. The two deciders get the `Chosen` fill.

## Example

The line `0 - 1 - 2`. Both leaves start, and then the tokens arrive:

| Step | Node | Cause | Rule | Sends |
|---|---|---|---|---|
| 1 | 0 | `Fire(Start)` | `start` | `Token` to 1 |
| 2 | 2 | `Fire(Start)` | `start` | `Token` to 1 |
| 3 | 1 | `Deliver(0->1#0)` | `send-to-parent` | `Token` to 2 |
| 4 | 1 | `Deliver(2->1#0)` | `decide` | none; node 1 decides `Decided(2)` |
| 5 | 2 | `Deliver(1->2#0)` | `decide` | none; node 2 decides `Decided(1)` |

The run sends 3 messages, which is |V|. Nodes 1 and 2 decide.

## Presets

- `tree-7` (the default): a tree of 7 nodes in three levels.
- `netgraph-13`: the college tree `NetGraph_21-04-24-18-24-58.ngs.perturbed.dot` with 13 nodes and 12 edges. The file marks node 0 as `Init`. The algorithm does not use that mark.
- `line-5`: a line of 5 nodes.

## Bugs of the college code

The review `docs/notes/2026-10-review-of-college-code.md`, section "tree", lists these verified bugs:

- The start signal and the token were the same message, so a leaf that got a token before its start decided twice, and its neighbour did not decide. Regression test: "regression: a leaf that gets its neighbour's token before Start decides once". `Start` is an action and `Token` is a message, and the phase guards each one.
- The run stopped at the first decision. Regression test: "regression: the run does not stop at the first decision; both decisions are in the trace". The Simulator stops at quiescence, and the trace keeps both decisions.
- Only cycles were rejected. Regression tests: "regression: check rejects a forest, a single node, a cycle and a directed graph" and "regression: a DOT file that lists one edge in both directions gives one tree edge".
- The test copied the topology by hand. Regression test: "regression: the preset comes from the fixture file, not from a hand copy".
- The only test accepted any single decision. The property tests now check the number of messages, the two deciders, the parents and the end phases.
- Cannot happen in the new design: the `Await.ready` timeout and the leaked ActorSystem. The core has no `Await`, and the engines return a typed result.
- Cannot happen in the new design: the sender lookup that gave the id -1. Each delivery carries the `EnvelopeId` with the sender `NodeId`, and a token from a node that is not a neighbour gives `Rejected`.
- Cannot happen in the new design: the empty case classes that the code sent through their companion objects. `TreeMsg` and `TreeAction` are enums, and the compiler checks each match.
- The finding about an unchecked `Map.apply` is refuted in the review. No test is necessary.
