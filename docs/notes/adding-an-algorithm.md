# Adding an algorithm

This checklist is for the builders of Tarry, Tree, Franklin (M4) and Chandy-Lamport, Lai-Yang, Bracha-Toueg (M5). Echo and Chang-Roberts are the templates. Copy their structure.

The shared lists already hold a line for each algorithm. You add new files and replace one stub file. You do not edit a shared list. So the six branches merge without conflicts.

Names in this file: `<alg>` is the id (`tarry`), `<Name>` is the Scala name (`Tarry`), `<family>` is `wave`, `election`, `snapshot` or `deadlock`.

## 1. Read first

1. `docs/notes/2026-10-rewrite-design.md`, section 3 (your algorithm: topology, messages, invariants, UI), and sections 4, 6 and 7.
2. `docs/algorithms/<alg>.md` (the Fokkink variant). Update it when your code differs.
3. `docs/notes/2026-10-review-of-college-code.md`, the section of your algorithm. Each verified bug becomes a named regression test (`test("regression: ...")`) or one line in `docs/algorithms/<alg>.md` that says why it cannot happen in the new design.
4. `docs/coding-rules.md`.

## 2. Files to create

| File | Content |
|---|---|
| `modules/core/shared/src/main/scala/cs553/core/algorithms/<family>/<Name>.scala` | The enums for `S`, `M`, `A`, `O` (each `derives Codec.AsObject`), `object <Name>` with `id`, `Rules`, `protocol` and `def invariant(w: World[S, M]): Option[String]`, and the givens `Inspect[S]`, `Inspect[M]`, `Inspect[A]`, `Inspect[O]` and `Overlay[S]` in the companions. |
| `modules/core/shared/src/test/scala/cs553/core/algorithms/<family>/<Name>Spec.scala` | The property tests and the regression tests. |
| `fixtures/topologies/<file>.dot` (optional) | A DOT preset. The build embeds each `.dot` file in `TopologyFiles`. |

## 3. The one file to replace

Replace the stub `modules/core/shared/src/main/scala/cs553/core/registry/<Name>Entry.scala`:

```scala
object <Name>Entry {
  val algorithm: Algorithm[<S>, <M>, <A>, <O>] = Algorithm(
    <Name>.protocol,
    "<Display name>",
    Family.<Family>,
    "<One sentence for the home card>",
    List(Presets.ring(...), Presets.fromFixture(...)).flatten,  // the first preset is the default
    <Name>.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))
}
```

When `entry` is `Some`, these places take the algorithm with no other change:

- `Registry.all` (the line is already there, in catalogue order).
- The home card and its thumbnail (`Home`, `Thumbnail`).
- The sandbox and shared links (`Ui`, `Session`).
- `Goldens.cases`: seeds 1 and 7 on the first preset.
- `ConformanceSpec`: the Akka trace equals the Simulator trace for each preset, order and 20 seeds.
- `NodeAdapterSpec`, `RegistrySpec` (each preset decides and ends quiescent, the invariant holds in each world, the traces round-trip), `CatalogueSpec`, the web `UiSpec` run test.
- The CLI: `simulate --alg <alg> --topology FILE.dot` and `record --engine mediated --alg <alg> --preset NAME`.

Do not add `Registry.<name>` aliases. In tests, use `<Name>Entry.algorithm`.

## 4. Extension points

**Overlay marks** (`cs553.core.trace.Overlay`). The web draws marks and knows no algorithm.

| Mark | Use |
|---|---|
| `Mark.OnEdge(from, to, EdgeStyle.Tree)` (or `Mark.treeEdge(child, parent)`) | The parent edges of Tarry and the tree. |
| `Mark.OnEdge(from, to, EdgeStyle.Used)` | The channels that Tarry used. |
| `Mark.OnEdge(from, to, EdgeStyle.Removed)` | The wait-for edges that Bracha-Toueg removed. |
| `Mark.Fill(node, NodeFill.X)` | `Chosen` (leader, deciding initiator, the two deciders of Tree), `Decided`, `Passive` (Franklin relays), `Red`, `White` (Lai-Yang), `Free`, `Deadlocked` (Bracha-Toueg). The last fill of a node wins. |
| `Mark.Badge(node, text)` | One extra label line: "2 of 3", "round 2", "waits for 4". |
| `Mark.Channel(from, to, ChannelRecord.Recording or Closed, recorded)` | The record state of a snapshot channel, with the recorded messages as text. |
| `Mark.Cut(node)` | The node took its local snapshot. The web shows a dashed ring and the step of the first world with the mark (`Overlay.cutSteps`). |

Give `Mark.Fill(node, NodeFill.Chosen)` to the node or nodes that the home thumbnail fills.

**Invariant.** `<Name>.invariant(w: World[S, M]): Option[String]` reads the node states, the buffered envelopes (`w.channels`, `w.buffered`) and the topology (`w.topology.attrsOf(n)`). Give `None` when the invariant holds. Give one line for the learner when it breaks, for example "Total 30 is not 35: a transfer was dropped." The web shows the line only when it breaks. `RegistrySpec` checks that it holds in each world of seeded runs. Also test that it breaks on a forged world.

**Topology and presets.**

| Helper | Use |
|---|---|
| `Topology.ring(n, attrs)`, `Presets.ring(name, title, uids, initiators)` | Directed ring. |
| `Topology.bidirectionalRing(n, attrs)`, `Presets.bidirectionalRing(...)` | Franklin. |
| `Topology.tree(nodes, edges)`, `Presets.tree(name, title, edges)`, `topology.isTree` | Tree. |
| `Topology.directed(...)`, `Presets.fromDot(name, title, dot)`, `topology.isStronglyConnected` | Wait-for graphs, snapshot graphs. |
| `Presets.fromFixture(name, title, file, prepare)` | A DOT file in `fixtures/topologies`. |
| `NodeAttrs.Keys.request` | Bracha-Toueg: N in "N of M". M is `topology.outDegree(node)`. DOT: `0 [request=2]`. |
| `NodeAttrs.Keys.balance`, `NodeAttrs.Keys.budget` | Snapshots: the initial balance and the transfer budget. DOT: `0 [balance=10, budget=3]`. |
| `attrs.int(node, key)` | Reads an integer attribute. Gives `TopologyError.BadAttribute` for bad text. |
| `attrs.withExtra(key, value)`, `topology.withAttrs(...)` | Sets attributes in a preset. |

Parse the attributes in `protocol.init`, and give a `TopologyError` when they are not valid.

## 5. Tests to write

1. Property tests on generated topologies (`Graphs`, `PropertyBudget`): each invariant of design section 3, with `Seeded` and `Choose` schedules, in each channel order that the protocol allows.
2. One named regression test for each verified bug of the college code, or a documented "cannot happen".
3. An overlay test: the marks at the end of a preset run (for example, the tree has |V| - 1 edges).
4. An invariant test: it holds on runs, and it gives the line on a forged world.
5. `RegistrySpec` already tests the presets. Add a test for each preset that needs a special property (for example, the deadlocked set).

## 6. Goldens and the gate

1. Run the targeted tests while you work: `coreJVM/testOnly cs553.core.algorithms.<family>.<Name>Spec`.
2. Write the golden traces: `sbt goldenPromote` with `AKKA_REPO_TOKEN` set. It writes `fixtures/traces/<alg>/<preset>-simulator-{1,7}.json` and `<preset>-akka-mediated-{1,7}.json`. Commit them. Do not edit them by hand.
3. Run the full gate once at the end: `scalafmtSbtCheck scalafmtCheckAll "scalafixAll --check" test web/fastLinkJS`.
4. Update `docs/algorithms/<alg>.md`. Do not edit `README.md` or the design notes in the algorithm branch: the merge step updates the status.
5. Write the commit message in ASD-STE100.
