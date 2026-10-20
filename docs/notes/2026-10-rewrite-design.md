# CS553 rewrite: final design

The base is **pedagogy-first**, which two of the three judges chose. It takes the pure `World` model from fp-purity-first and the channel actors from actor-faithful. Section 1 fixes every factual error that the judges flagged.

## 1. Decisions

| Decision | Choice | Reason | Alternatives rejected |
|---|---|---|---|
| JDK | Temurin 21 | Akka certifies 21. Scala 3.9 needs JDK 17 or later. | 17 (also certified, but older); 25 (newer than the toolchain needs) |
| Scala | **3.9.0 LTS**. Braces only. | Current LTS. Enums, opaque types and `derives` keep protocols small. A 3.9 compiler reads older TASTy, so it can use Akka `_3` artifacts built with 3.3.7. **M0 must prove this before other work.** | 3.3.x LTS (the fallback if M0 fails); 2.13 (no enums or opaque types) |
| Actors | **Akka 2.10.24** (`akka-actor-typed`, `akka-actor-testkit-typed`). Pin the newest 2.10.x at M0, because the BOM page lists 2.10.23. | The user chose Akka. 2.10 is current and has the same Typed API as 2.6. | Akka 2.6.21 (EOL; its Apache 2.0 licence is UNCONFIRMED); Pekko as the default (the user wants Akka) |
| Akka token in a public repo | `build.sbt` adds the resolver `https://repo.akka.io/$token/secure` only when `AKKA_REPO_TOKEN` is set. Without the token, the root aggregate drops `actors` and `cli`, and the build prints one line that says how to get a token. `application.conf` sets `akka.license-key = ${?AKKA_LICENSE_KEY}`. CI has two jobs. `core-web` always runs, also on fork PRs. `akka` runs only when the secret exists. | `core` and `web` have no Akka dependency, so the Pages site and fork PRs need no token. A learner who opens the site needs no account. | Committing the token (forbidden); one CI job (fork PRs would always fail) |
| Licence fallback (corrects all three designs) | ADR-0002 records **Pekko 1.7.1 + Pekko HTTP 1.4.2** as the only token-free fallback for the full stack. Akka 2.6.21 covers `actors` only. | Akka HTTP 10.7.6 needs Akka 2.10. The first Akka HTTP with `_3` artifacts is 10.5.0, which is BSL. So no Apache-licensed Akka HTTP exists for Scala 3. | "Change one version line to 2.6.21" (wrong for HTTP) |
| Visualizer execution model | One pure `Protocol` type class for each algorithm, and three engines that write one trace format: (1) the **Simulator** in `core` (JVM and JS), (2) the **mediated Akka engine** (real actors and real `!`, with release gated by channel actors), (3) the **free-run Akka engine**, which records real dispatcher non-determinism. A conformance test makes engine 2 produce the same trace as engine 1. | The browser gets the full sandbox with no JVM. Akka stays the real runtime and the source of the recorded gallery traces. | Replay-only static site (most learners never reach Choose mode); a hub where nodes return their sends inside a reply (learners never see `!`) |
| Live server | **Deferred** to optional M8 (Akka HTTP 10.7.6 WebSocket) | The CLI records Akka traces to JSON, and Pages replays them. The plan does not need a server to reach its goal. | A server from M6 (scope and token friction too early) |
| Frontend | Scala.js 1.22, Laminar 17.2.x (patch UNCONFIRMED), Vite with `@scala-js/vite-plugin-scalajs` (version UNCONFIRMED), hand-written SVG | The JVM and the browser use one set of types and one set of step functions. Graphs have 50 nodes or fewer, so d3 is not necessary. | TypeScript/React (two type definitions to keep in sync) |
| JSON | circe 0.14.x (pin the tag at M0; patch UNCONFIRMED), `derives Codec`, hand-written givens for opaque ids | It is cross-built for JVM and JS, and it needs no Akka glue. `core` uses only the codec givens. No domain type holds a `Json` value. | uPickle (also valid, but fewer judges chose it); spray-json (JVM only) |
| Tests | ScalaTest 3.2.19, scalatestplus `scalacheck-1-18` 3.2.19.0, ScalaCheck 1.18.x, Akka testkit. Golden traces are checked-in JSON files that a custom `goldenPromote` task updates. | Akka documents ScalaTest. munit-akka is stale. Nobody verified which frameworks snapshot4s supports. | munit; snapshot4s |
| Build tooling | sbt 1.12.x (1.12.14 or newer), sbt-scalajs 1.22, sbt-scalajs-crossproject (version UNCONFIRMED), sbt-tpolecat 0.5.7, scalafmt 3.x, sbt-scalafix (version UNCONFIRMED), Logback 1.5.x (only in `cli` and tests) | Nobody confirmed that the scalafmt and scalafix plugins work on sbt 2. ADR-0005 records a later move to sbt 2. | sbt 2.0.x now |

## 2. Architecture

### Modules and data flow

```
                  modules/core  (crossProject JVM + JS, no Akka, no IO)
   Topology + DotParser -> Protocol[S,M,A,O] x8 -> World / enabled / next -> Simulator
                       TraceEvent, Choice, RunSpec, Rng (SplitMix64), Inspect, codecs
              ^                         ^                              ^
              | JS                      | JVM                          | JVM
   modules/web (Laminar)        modules/actors (Akka Typed)     modules/cli
   Simulator in the browser     Controller, Channel(i->j),      record --engine
   + replay of recorded         NodeAdapter, FreeRun,           mediated|free
     traces                     Recorder                          -> fixtures/traces/*.json
              ^                                                            |
              +------------ static site on GitHub Pages <-----------------+
   (optional M8) modules/server: Akka HTTP WebSocket -> actors
```

Dependencies go in one direction only: `web -> core` and `cli/server -> actors -> core`.

### Pure model (the single source of truth)

```scala
trait Protocol[S, M, A, O] {
  def id: AlgorithmId
  def order: OrderPolicy                       // Locked(Fifo) | Default(ChannelOrder)
  def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, S]
  def actions(ctx: NodeCtx, s: S): List[A]     // spontaneous moves: Wake, Initiate, Spend(to, 5)
  def onAction(ctx: NodeCtx, s: S, a: A): Either[ProtocolError, Step[S, M, O]]
  def onMessage(ctx: NodeCtx, s: S, from: NodeId, m: M): Either[ProtocolError, Step[S, M, O]]
}
final case class Send[+M](to: NodeId, msg: M)
final case class Step[+S, +M, +O](state: S, sends: List[Send[M]], decision: Option[O], rule: RuleId)
final case class NodeCtx(self: NodeId, in: Set[NodeId], out: Set[NodeId])
```

`Protocol` is a type class with type parameters. It has no path-dependent members. An existential `AnyAlgorithm` wraps it with its `Inspect` and `Codec` givens at the UI edge.

`World[S, M]` holds the node states, `channels: Map[(NodeId, NodeId), Vector[Envelope[M]]]`, a sequence counter for each channel, and the step number. It has two pure functions:

- `enabled(p, w): List[Choice[A]]` lists the legal choices. In `Fifo` mode, only the head of each channel is a legal delivery. In `AnyOrder` mode, every buffered envelope is a legal delivery. Every action that `actions` returns is also a legal choice. The list has a canonical order (by `EnvelopeId`, then by node, then by action list order), so seeded choice is deterministic.
- `next(p, w, choice): Either[ChoiceError, (World[S, M], List[TraceEvent[S, M, A, O]])]` applies one choice.

### Execution model

- **Stepping.** One step applies one `Choice`. Policies are pure. `Choose` takes the envelope that the learner clicks. `Seeded(seed)` uses SplitMix64 on the `enabled` list. `Script(choices)` replays a list.
- **Replay and share.** A `RunSpec(algorithm, topology, order, seed, choices)` defines a run completely. `EnvelopeId(from, to, seq)` uses a sequence number for each channel, which the sender assigns. So ids are stable across engines and across runs, and the URL encodes the `RunSpec`.
- **Scrub.** The UI keeps a `Vector[World]` and indexes into it. It never runs the engine backward.
- **Branch.** "Continue from step k" starts a new run with `choices.take(k)`. The UI says: "Actors cannot go back in time. The run restarts and replays the first k choices."
- **Mediated Akka engine.** `RunGuardian` spawns one `Controller`, one `Node(i)` for each process and one `Channel(i->j)` for each directed edge. A 50-node graph needs a few hundred actors, which is a small load for Akka.
  1. Each node holds `out: Map[NodeId, ActorRef[Channel.In]]` and sends with an ordinary `out(j) ! Put(env)`. A node never holds the ref of a peer node.
  2. The channel buffers the envelope and tells the Controller `Enqueued(id)`.
  3. The Controller sends `Release(id)` to a channel, or `Act(a)` to a node. The channel then sends `Deliver(env)` to the receiving node.
  4. The node calls the pure `onMessage`, performs the sends, and then tells the Controller `Handled(node, cause, after, sent, decision, actions)`.
  5. Akka does not order messages from two different senders. So the Controller ends the step only when it has the `Handled` report **and** one `Enqueued` for each id in `sent`. Only one step runs at any time, so the choices alone define the trace.
- **Free-run Akka engine (recorded only).** Nodes hold peer refs and send directly. The dispatcher picks the order, and only per-pair FIFO holds. Each node stamps its reports with a node-local counter and a Lamport clock. The space-time view pairs each send with its receive by `EnvelopeId` and orders events on each node line by the local counter. So the view is causally correct, whatever order the `Recorder` gets the reports in. This mode has no observer, so the run stops when the algorithm decides. This is a lesson in itself: a real system needs termination detection.
- **Termination without sleeps or global state.** The UI shows two separate facts:
  - `Decided`: the algorithm itself detects termination (a `Step.decision`).
  - `Quiescent`: no envelope is buffered, no step is under processing and `enabled` is empty. Only an outside observer (the Simulator or the Controller) knows this.

  The Controller sends `Finished` to `replyTo`. The run keeps all of its state in one actor subtree. The code has no `Await`, no `Thread.sleep` and no global objects.
- **Errors.** `onMessage` returns `Left(ProtocolError)` for illegal input. The engine records `Rejected`, and the actor does not crash.
- **Termination of application traffic.** Spontaneous `Spend` actions take from a transfer budget for each node. When the budget is empty, the action list is empty, so every run ends.

### Trace contract (`core/shared`)

```scala
opaque type NodeId = Int
object NodeId {
  def apply(i: Int): NodeId = i
  extension (n: NodeId) { def value: Int = n }
  given Ordering[NodeId] = Ordering.Int
  given Codec[NodeId] = Codec.from(Decoder.decodeInt, Encoder.encodeInt)
}
opaque type RuleId = String          // label of the step case; links to the docs and source

final case class EnvelopeId(from: NodeId, to: NodeId, seq: Int) derives Codec
enum ChannelOrder derives Codec { case Fifo, AnyOrder }
enum Engine derives Codec { case Simulator, AkkaMediated, AkkaFreeRun }
enum Choice[+A] {
  case Deliver(id: EnvelopeId)
  case Fire(node: NodeId, action: A)
  case Drop(id: EnvelopeId)
}
final case class Envelope[+M](id: EnvelopeId, msg: M)
final case class Stamp(step: Int, local: Int, lamport: Int) derives Codec

enum TraceEvent[+S, +M, +A, +O] {
  case Started(spec: RunSpec, engine: Engine, initial: Map[NodeId, S])
  case Sent(at: Stamp, env: Envelope[M])
  case Stepped(at: Stamp, node: NodeId, cause: Choice[A], rule: RuleId, after: S, sent: List[EnvelopeId])
  case Rejected(at: Stamp, node: NodeId, cause: Choice[A], error: ProtocolError)
  case Dropped(at: Stamp, id: EnvelopeId)
  case Decided(at: Stamp, node: NodeId, output: O)
  case Finished(at: Stamp, outcome: Outcome)
}
enum Outcome derives Codec { case Quiescent, AlgorithmDecided, StepLimit }

trait Inspect[A] {                    // one given for each S, M, A, O
  def label(a: A): String             // "recording", "Marker", "Elect(7)"
  def fields(a: A): List[(String, String)]
}
trait Overlay[S] { def marks(states: Map[NodeId, S]): List[Mark] }  // tree edges, cut, wait-for
```

Payloads stay typed in the trace. Strings appear only through `Inspect` at the render edge.

## 3. Algorithm catalogue

Each algorithm follows the Fokkink variant, and `docs/algorithms/<alg>.md` documents that variant.

- **Chandy-Lamport**
  - Topology: a strongly connected directed graph. Order **locked to Fifo** ("markers must not overtake transfers").
  - Messages: `enum ClMsg { case Transfer(amount: Int); case Marker }`. Actions: `Spend(to, amount)`, `Initiate`.
  - Invariants: the recorded balances plus the recorded channel contents equal the initial total. The cut is consistent. The channel on which a node gets its first marker records empty. A node records only from **incoming** channels that are still open.
  - UI: markers look different from transfers. The recorded balance shows on each node. Each channel shows "recording" or "closed" and its recorded amounts. The space-time view draws the cut line.
- **Lai-Yang**
  - Topology: a strongly connected directed graph. Order AnyOrder.
  - Messages: `enum LyMsg { case Transfer(amount: Int, color: Color); case Control(preSnapshotCount: Int) }`, with `enum Color { case White, Red }`.
  - Invariants: conservation and a consistent cut. Each node records exactly once (regression: the old `snapshotTaken` reset). A red message makes a white node record before it handles the message.
  - UI: white and red nodes. Each message shows the sender's color. The UI highlights white messages that arrive at red nodes, and it shows the counters for each channel.
- **Echo**
  - Topology: an undirected connected graph with one initiator.
  - Messages: `enum EchoMsg { case Token }`.
  - Invariants: exactly 2|E| messages. The parent edges form a spanning tree. Only the initiator decides, and it decides once.
  - UI: bold parent edges that grow into a tree, "received k of deg" on each node, and the decision at the initiator.
- **Tarry**
  - Topology: an undirected connected graph.
  - Messages: `enum TarryMsg { case Token }`.
  - Invariants: exactly one token at all times. Each channel carries the token once in each direction (2|E|). A node sends to its parent last.
  - UI: the used channels on each node, the parent edge, and the line "parent is the last edge".
- **Tree**
  - Topology: an undirected tree.
  - Messages: `enum TreeMsg { case Token }`.
  - Invariants: each node sends exactly one message (|V| in total). Exactly two nodes decide, and they are neighbours.
  - UI: the leaves start. Each node shows the one neighbour that it still waits for. The two deciders show at the end.
- **Bracha-Toueg**
  - Topology: a directed wait-for graph. Each node has an `N of M` request attribute. Regression: fix the old In/Out mismatch.
  - Messages: `enum BtMsg { case Notify, Done, Grant, Ack }`.
  - Invariants: the verdict equals a pure fixpoint reduction of the wait-for graph. A node sends `Done` only after it gets all of its `Ack`s (regression DAG `0→W, 0→X, W→Y, W→Z, X→Y, Y→Z`).
  - UI: an "N of M" label on each node, the notify phase and then the grant phase, the edges that a node removes when it becomes free, and the deadlocked set at the end.
- **Chang-Roberts**
  - Topology: a directed ring. Initiators are marked. Order: Fifo by default.
  - Messages: `enum CrMsg { case Elect(uid: Uid); case Leader(uid: Uid) }`. Action: `Wake`.
  - Rules: non-initiators stay passive and forward messages. The leader sends a `Leader` announcement around the ring, so every node decides.
  - Invariants: exactly one leader, and it has the maximum initiator uid. Every node ends with that uid. Worst case n(n+1)/2 `Elect` messages.
  - UI: the uids on the ring, the dropped messages, the phase on each node, and a message counter compared with n².
- **Franklin**
  - Topology: a bidirectional ring. Order **locked to Fifo** ("messages from different rounds must not overtake each other").
  - Messages: `enum FrMsg { case Id(uid: Uid, round: Int); case Leader(uid: Uid) }`.
  - Invariants: one leader with the maximum uid. At most ⌊log₂ n⌋ + 1 rounds. The number of active nodes at least halves in each round.
  - UI: the round on each active node, the comparison with both neighbours, passive nodes that only relay, and the active count for each round.

## 4. Visualizer UX

- **Home.** The 8 algorithms in four groups (snapshot, wave, deadlock, election), with one line for each. The primary action is to open an algorithm.
- **Lesson** (the default on the first visit). The canvas, one sentence of narration under it, and **Next**. A lesson has 5 to 8 fixed `RunSpec` steps and ends with one prediction question. The learner answers with a click on the canvas.
- **Sandbox** (the same canvas):
  - The primary action is **Deliver next**. In Choose mode, the learner clicks an envelope in a mailbox. In Fifo mode, the UI disables the envelopes that are not legal yet, and a tooltip gives the reason.
  - Secondary controls are in one quiet row: step back, the timeline slider, play, speed and the Topology / Space-time toggle. The UI shows one view at a time.
  - **Settings** holds the topology, the seed, the channel order (locked where the algorithm needs it, with a one-line reason), Drop (only for algorithms that tolerate it) and the source: "Browser simulator" or a "Recorded Akka run (mediated or free-run)".
- **Panels.** Panels stay closed by default.
  - Click a node to see its state fields, its phase, its last message, its Akka actor path (`/user/run/node-3`) and **Show handler**, which names the `RuleId` and links to the source.
  - Click an envelope to see its typed payload.
- **Invariant line.** One line appears only when an invariant breaks, for example "Total 30 ≠ 35: you dropped a transfer."
- **Termination.** Two separate lines: "Algorithm decided at step 14" and "Network quiescent at step 17 (only an observer knows this)."
- **How the UI teaches the actor model:**
  - Mailboxes, not flying dots. Envelopes wait in channel queues. A notch on each envelope points at the receiver, and the hover text names the sender, the receiver and the place in the queue. The UI does not color envelopes by sender, so the one accent keeps one meaning.
  - A thin accent outline marks the one node that handles a message.
  - Phase labels change as text ("idle → recording → done"), which makes behavior change visible.
  - No panel ever shows state that two nodes share.
  - A "How this runs" page explains channel actors, the Controller, and why a free run differs from a controlled run.
- **Calm rules.** Neutral grays, one accent color for "current" and one muted color for "recorded/done". No gradients, chips, badges or decorative icons. One primary action on each screen.

## 5. Repo structure

```
build.sbt
project/{build.properties, plugins.sbt, Versions.scala, Dependencies.scala}
modules/
  core/shared/src/main/scala/cs553/core/
      ids/  topology/{Topology, DotParser}  protocol/{Protocol, Step}
      world/{World, Enabled, Next, Simulator}  trace/  rng/  inspect/  codec/  lessons/
      algorithms/{snapshot/{ChandyLamport,LaiYang}, wave/{Echo,Tarry,Tree},
                  deadlock/BrachaToueg, election/{ChangRoberts,Franklin}}
  core/shared/src/test/scala/...      core/jvm/  core/js/
  actors/src/main/scala/cs553/actors/{RunGuardian, Controller, Channel, NodeAdapter, FreeRun, Recorder}
  actors/src/test/scala/...
  cli/src/main/scala/cs553/cli/Main.scala          # record | run
  web/src/main/scala/cs553/web/{App, Home, Lesson, Sandbox, Canvas, Mailbox, SpaceTime, Timeline, Panels, Url}
  web/{index.html, vite.config.js, package.json}
  server/                                          # optional M8
fixtures/topologies/*.dot     fixtures/traces/<alg>/<topology>-<engine>-<seed>.json
docs/adr/0001-keep-akka-typed.md  0002-akka-token-and-pekko-fallback.md
         0003-pure-protocol-three-engines.md  0004-channel-actors.md  0005-sbt-1-12-for-now.md
docs/algorithms/<alg>.md   docs/coding-rules.md   docs/how-this-runs.md
AGENTS.md  CLAUDE.md  CONTRIBUTING.md  README.md  .scalafmt.conf  .scalafix.conf
.github/workflows/{ci.yml, pages.yml}
```

`AGENTS.md` holds a short summary of the rules plus a link to `docs/coding-rules.md`. `CLAUDE.md` contains one line: `@AGENTS.md`.

## 6. Coding rules (`docs/coding-rules.md`)

```markdown
# Coding rules

## Architecture
1. Put all algorithm logic in `core` as pure functions. Reason: the browser, the tests and Akka run the same code.
2. Do not import `akka`, `java.io`, `java.time` or `scala.util.Random` in `core`. Reason: core must stay pure and cross-build to JS.
3. Keep dependencies in one direction: `web -> core`, `cli/server -> actors -> core`. Reason: no cycles and no Akka in the browser.
4. Return effects as data (`Step`). Do not perform them in `core`. Reason: replay and tests need pure transitions.
5. Make each UI event come from a `TraceEvent`. Reason: the UI must not infer state.
6. Do not put algorithm code in `web`. Use `Inspect` and `Overlay` givens. Reason: one place for each rule.

## Types
7. Model each protocol message, action and phase as an `enum`. Reason: the compiler checks every case.
8. Do not use strings, Booleans or sentinel values (-1) to select a variant. Reason: the old code failed on "Increment" vs "increment".
9. Use one phase enum, not several Booleans. Reason: illegal states become unrepresentable.
10. Use opaque types for ids, with `Ordering` and `Codec` givens. Reason: a `NodeId` is not a `Uid`.
11. Make public data types `final case class` or `enum`. Do not use inheritance for reuse. Reason: plain data is easy to encode and compare.
12. Use `given` and `using` only for type classes. Do not use implicit conversions. Reason: no hidden behavior.

## FP
13. Do not use `var`, `null`, `return`, `throw`, `while` or `scala.collection.mutable`. Reason: DisableSyntax enforces this, and immutable state replays.
14. Do not use a wildcard case on a protocol enum. Put the last case without a guard. Reason: guards and wildcards hide missing cases.
15. Do not call `.get`, `.head` or `Map.apply` on data that can be absent. Reason: these calls throw at runtime.
16. Parse external input (DOT, URL, JSON) into `Either[NonEmptyList[E], A]` at the edge. Reason: the inner code gets only valid values.
17. Return `Either[ProtocolError, Step]` for illegal protocol input. Reason: the engine records `Rejected`, and the actor does not crash.
18. Seed all randomness through `Rng(seed)` from `RunSpec`. Reason: every run must replay.

## Akka Typed
19. Write behaviors in the functional style: `Behaviors.receiveMessage` that returns `running(newState)`. Do not use `AbstractBehavior`. Reason: state stays in parameters.
20. Keep node actors thin: call the protocol, send the effects, report to the Controller. Reason: logic lives in tested core code.
21. In mediated mode, a node sends only to its channel refs with `!`. Reason: learners see real asynchronous sends, and the Controller gates delivery.
22. Do not use timers, `ask`, `Future` or `pipeToSelf` in node actors. Reason: they break deterministic replay.
23. Make internal messages `private`. Name reply refs `replyTo`. Reason: this is the Akka style guide.
24. Pass dependencies through factory parameters. Do not use global objects that have state. Reason: each run is one isolated subtree.
25. Do not use `Thread.sleep`, `Await`, polling or busy-waits. Detect completion with protocol messages. Reason: sleeps hide races and slow the tests.

## Testing
26. Make each test assert a result (a decision, a tree, a cut, a verdict). Reason: a test that only waits for a message proves nothing.
27. Do not use `expectNoMessage` as proof of correctness. Use timeouts only as failure guards. Reason: absence in a time window is not correctness.
28. Give each algorithm property tests for its invariants, one conformance test and one golden trace. Reason: examples miss schedules.
29. Add one named regression test for each bug fix. Reason: the bug must not come back.
30. Change golden traces only with `sbt goldenPromote`, and explain the diff in the commit. Reason: a trace diff is a behavior change.

## Build, format and commits
31. Set compiler flags only through `tpolecatScalacOptions`. Reason: one source for `-Werror`, `-Wunused:all`, `-Wvalue-discard` and `-Wnonunit-statement` (off in Test).
32. Use braces. Do not use significant indentation. Reason: scalafmt enforces one style.
33. Run `sbt scalafmtCheckAll "scalafixAll --check" test` before each commit. Reason: CI runs the same check.
34. Do not commit `AKKA_REPO_TOKEN` or `AKKA_LICENSE_KEY`. Read them from the environment. Reason: the tokens are personal.
35. Record each change to the stack or a module boundary as a Nygard ADR in `docs/adr`. Write prose in ASD-STE100. Reason: decisions stay visible and clear.
```

The config files that enforce these rules:

- `.scalafmt.conf`: `runner.dialect = scala3`, `rewrite.scala3.convertToNewSyntax = true`, `rewrite.scala3.removeOptionalBraces = false`, `dialectOverride.allowSignificantIndentation = false`.
- `.scalafix.conf`: `OrganizeImports`, `DisableSyntax` (noVars, noNulls, noReturns, noThrows, noWhileLoops), and `RemoveUnused` for privates and locals only. Do not use `RemoveUnused.imports`.

## 7. Testing strategy

- **Core unit tests.** Example tests for each step case, on small fixtures (a 3-ring, a 3-node bank, a star, a cycle). The DOT parser returns `Left` for bad input. `enabled` allows only channel heads in Fifo mode.
- **Properties (ScalaCheck).** Generators make connected graphs, trees, rings with distinct uids, wait-for graphs and seeds. The Simulator runs each case under a seeded schedule to quiescence. Each property is an invariant from section 3. For every algorithm, the trace has no `Rejected` event and at least one `Decided` event.
- **Regression tests,** one named test for each verified bug in the review:
  - the "Increment"/"increment" mismatch
  - the Lai-Yang `snapshotTaken` reset
  - the first-marker channel records empty
  - the Bracha-Toueg In/Out mismatch
  - `Done` after all `Ack`s on the review DAG
- **BehaviorTestKit.** One generic suite for `NodeAdapter` over all 8 algorithms, with `TestInbox` channels and a `TestInbox` Controller. One `Deliver` must give the expected `Put`s and exactly one `Handled`.
- **ActorTestKit conformance.** For each algorithm and 20 seeds, the mediated Akka trace equals the Simulator trace for the same `Script`. A free run must satisfy the same invariants. Tests wait with `expectMessageType[Finished]`.
- **Golden traces.** The JSON files in `fixtures/traces` serve as both test oracles and gallery data.
- **CI.** `core-web` runs `coreJVM/test coreJS/test web/fastLinkJS`, the format check and the lint check. `akka` runs `actors/test` and the conformance suite when the secret exists. `pages.yml` builds `web` from committed traces with no token.

## 8. Milestones

| M | Scope | Demo | Exit check |
|---|---|---|---|
| M0 | Build, token-gated aggregate, both CI jobs, scalafmt/scalafix, rules, ADRs 0001–0005 | A hello actor on Scala 3.9 with Akka 2.10, and a Scala.js page | Akka `_3` resolves and runs on 3.9. CI is green with and without the token. All plugin versions are pinned. |
| M1 | Core types, DotParser, World/enabled/next, Simulator, Echo and Chang-Roberts | The CLI prints a Simulator trace | Properties pass for Echo and Chang-Roberts. `-Werror` is clean. |
| M2 | Web sandbox for Echo and Chang-Roberts: mailboxes, Choose, scrub, URL replay | A public Pages link | A shared URL replays the same run. One primary action on each screen. |
| M3 | `actors` mediated engine, channel actors, `cli record` | A recorded Akka trace in the gallery | The conformance test passes for 20 seeds. |
| M4 | Tarry, Tree, Franklin | Three more sandboxes | Properties, conformance and goldens pass for each. |
| M5 | Chandy-Lamport, Lai-Yang, Bracha-Toueg, overlays, invariant line | A snapshot cut and a deadlock verdict on screen | Conservation and fixpoint-oracle properties pass. All regression tests pass. |
| M6 | Space-time view, guided lessons, Decided vs Quiescent | A lesson with a prediction question for each algorithm | Each lesson is a fixed `RunSpec` with a golden trace. |
| M7 | Free-run engine, causal stamps, README with a GIF | A side-by-side "controlled vs real dispatcher" gallery | Free-run invariants pass. Space-time arrows pair by `EnvelopeId`. |
| M8 (optional) | `server` with Akka HTTP 10.7.6 WebSocket live mode | Local live Akka run in the UI | The live trace equals the recorded trace for the same script. |

## 9. Open questions for the user

1. **Akka token friction.** Do you accept that contributors register at account.akka.io for a token? *Default: yes. Use Akka 2.10 with the token-gated modules. Pekko 1.7.1 is the written fallback in ADR-0002.*
2. **Scala version.** Do you want 3.9.0 LTS, which depends on the M0 proof, or 3.3.x LTS? *Default: 3.9.0. Fall back to 3.3.x only if M0 fails.*
3. **Live WebSocket mode.** Is a local live Akka mode necessary, or are recorded Akka traces enough? *Default: recorded traces. Live mode stays optional M8.*
4. **Application traffic for snapshots.** Do you want bank transfers with a conservation total? *Default: yes, because the conservation total makes cut errors visible.*
5. **Algorithm variants.** Follow Fokkink strictly (for example, Chang-Roberts with passive non-initiators and a leader announcement)? *Default: yes, and document each variant in `docs/algorithms/`.*