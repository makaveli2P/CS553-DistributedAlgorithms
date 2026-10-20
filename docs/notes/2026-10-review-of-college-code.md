# CS553 review: verified findings

## chandy-lamport

I did a static review of the Chandy-Lamport scope. I read the algorithm, process, SnapshotUtils, the test, and the shared utility and Main files in full. I found 4 correctness bugs. Increments never apply because the code compares "Increment" with "increment". On a non-initiator, the channel of the first marker keeps recording, which breaks the snapshot. Also, the driver loop is a busy-wait on a plain var that several actors update with a non-atomic += 1, so a lost update or a hoisted read makes the loop run forever. A concurrently mutated mutable.HashMap can crash an actor, and the restart then corrupts that actor's snapshot state. Termination detection is a tight producer loop that floods unbounded mailboxes. The 2 tests check only that the process sends markers and forwards one message. Test 2's expectNoMessage() checks the testActor, which never gets a message, so the check always passes. No test checks the recorded state, the channel state, completion, or consistency. Every bug above passes the suite. For the FP design and the visualizer, the main problems are these. The actors read and write global `var`s on a singleton object. Messages use Booleans and Strings instead of an ADT. The snapshot data is mutable classes with printing inside them. There is no pure state-transition function and no event log or returned result that a visualizer can replay. Randomness is unseeded.

### [high/confirmed] Increment messages never change state: the code compares with "Increment" but the driver sends "increment"
- `src/main/scala/main/processes/ChandyLamportProcess.scala:61` (algorithm-correctness)
- Evidence: ChandyLamportAlgorithm.scala:63 `val operation = if (selectedKey == 1) "increment" else "decrement"`; ChandyLamportProcess.scala:61 `if (action.equals("Increment")) {` ... 64 `} else if (action.equals("decrement")) {`
- Failure: Any run: SendMessage("increment") goes to a process, and the process forwards PerformAction("increment"). The equals("Increment") check is false, so storedVariable does not change. The value only decreases, so every recorded local state is wrong. Line 69-70 still records the message in the channel state, so the snapshot shows 'increment' in transit with no effect on any state. The test at chandyLamportTest.scala:59-61 uses "increment" and does not check the state, so the bug passes.
- Fix: Replace the strings with a sealed ADT (`sealed trait Op; case object Inc extends Op; case object Dec extends Op`) and match on it exhaustively. Add a test that sends Inc and checks the new value.
- Verifier: ChandyLamportProcess.scala:61 compares with "Increment", but ChandyLamportAlgorithm.scala:63 and the test send "increment". The increment branch never runs, so storedVariable only decreases. Line 69-70 still records the message in the channel state. The test at chandyLamportTest.scala:59-62 does not check the state.

### [high/confirmed] Non-initiator keeps recording on the channel where the first marker arrived (Fokkink: that channel's state must be empty)
- `src/main/scala/main/processes/ChandyLamportProcess.scala:34` (algorithm-correctness)
- Evidence: ```
if (!start) {
  markersReceived += 1
}
```
The sender of the first marker is never added to `stopRecordingOn`, and line 69 records when `this.snapshotTaken && !stopRecordingOn.contains(sender)`.
- Failure: Topology 0-1-5 with initiator 0. Process 0 takes its snapshot and sends a marker to 1. Then 0 handles SendMessage and sends PerformAction("decrement") to 1, which is a post-snapshot message. Process 1 gets the marker, takes its snapshot, and waits for a marker from 5 (markersReceived=1 < 2). Then the decrement from 0 arrives. 0 is not in stopRecordingOn, so 1 records it as in transit on channel 0->1. Process 0 sent the message after its snapshot, so the global snapshot counts a message that, in the snapshot, the process never sent. The snapshot is not consistent.
- Fix: On the first marker from a non-initiator, also mark the channel from the sender as closed (stopRecordingOn += sender). Better: model the state per channel as `Map[ProcessId, ChannelState]` with `Recording(msgs)` / `Closed(msgs)`, so that the 'first channel is empty' rule follows from the types.
- Verifier: Lines 34-36 increment markersReceived on the first marker, but the code never adds the sender to stopRecordingOn. The topology that main() really uses (NetGraph_21-04-24-18-24-58.ngs.perturbed.dot) is a tree with 1-{0,5}. Akka keeps FIFO order between one pair of actors, so a message from 0 that arrives after 0's marker is a post-snapshot message. Process 1 records it on channel 0->1 until the marker from 5 arrives. Fokkink requires that the channel of the first marker is recorded as empty.

### [high/plausible] Termination detection is a busy-wait on a plain var that several actor threads update with a non-atomic += 1
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:53` (concurrency)
- Evidence: Algorithm.scala:18 `var numberOfProcessesCompletingSnapshot = 0`; :53 `while (numberOfProcessesCompletingSnapshot < processRecord.map.size) {`; Process.scala:52 `numberOfProcessesCompletingSnapshot += 1` (imported from the singleton at Process.scala:5)
- Failure: Example: process5 and process9 are leaves that complete on the dispatcher threads at the same time. Both read 7 and both write 8, so one update is lost. The counter then never reaches 13, and the while loop in main() never ends, so the menu hangs. Separately, the field is not volatile and the loop body never synchronizes. The JIT is therefore free to hoist the read, which also gives an infinite loop.
- Fix: Remove the shared counter. Each process sends a `SnapshotDone(id, localState, channelStates)` message to a collector actor (or replies through ask/Future). The collector completes a Promise[GlobalSnapshot] when it has all N results, and main awaits that Future with a timeout.
- Verifier: The code is as described: Process.scala:52 does an unsynchronized `+=` on the object var, and Algorithm.scala:53 spins on it without volatile. A lost update needs two processes to complete in the same few nanoseconds. That is possible, because the 5 leaves (5, 11, 12, 9, 13) complete right after their marker arrives, but it is not common. The loop body calls `!`, which does volatile/CAS operations, so the JIT hoist is unlikely in practice. The JMM does not guarantee the read, however. If a lost update occurs, main() hangs forever, so the severity stays high, not critical.

### [high/plausible] Several actors mutate a non-thread-safe mutable.HashMap at the same time; a lost entry crashes the actor and the restart resets its snapshot state
- `src/main/scala/main/processes/ChandyLamportProcess.scala:31` (concurrency)
- Evidence: Process.scala:31 `systemSnapshot.systemSnapshot += (id -> new ProcessSnapshotData(storedVariable, ListBuffer()))`; :70 `systemSnapshot.systemSnapshot(id).messageQueue += ...`; SnapshotUtils.scala:16 `val systemSnapshot: mutable.Map[Int, ProcessSnapshotData] = mutable.Map.empty`; :24 `systemSnapshot(processId)`
- Failure: Markers reach several processes at about the same time, and they insert into the same HashMap on different threads. A concurrent resize can drop one entry. A later `systemSnapshot(id)` (line 70 or SnapshotUtils:24) then throws NoSuchElementException. Default supervision restarts the actor from Props with snapshotTaken=false, markersReceived=0 and storedVariable=0. The next marker counts as a first marker: the actor takes a new snapshot of 0, sends duplicate markers to all neighbors, and never reaches the correct marker count. Then numberOfProcessesCompletingSnapshot is wrong and the run hangs, or it double-counts.
- Fix: Keep the snapshot data inside each actor as immutable values. Send the data to a collector actor. Do not let actors share mutable structures.
- Verifier: Several actor threads insert into the shared mutable.Map (SnapshotUtils.scala:16) at Process.scala:31, and printSnapshotData reads it on other threads. With 13 processes, the default HashMap capacity of 16 and load factor 0.75 causes a resize at the 12th insert, so a race during the resize is possible. A lost entry gives NoSuchElementException at line 70 or SnapshotUtils:24. The default supervisor then restarts the actor with reset vars, which corrupts the marker counting. The failure needs a race, so the verdict is plausible.

### [high/confirmed] The only snapshot test checks marker fan-out; there is no test for the recorded state, the channel state, completion or consistency
- `src/test/scala/chandyLamportTest.scala:38` (test-quality)
- Evidence: ```
process0 ! InitiateSnapshot(true)
process1.expectMsg(InitiateSnapshot(false))
process2.expectMsg(InitiateSnapshot(false))
```
This is the whole snapshot coverage. The probes never return markers, so process0 never completes.
- Failure: chandy-lamport-1 (increment ignored), chandy-lamport-2 (first channel recorded), chandy-lamport-3 (lost counter update), the wrong recorded value, and a missing completion all pass this suite. ChandyLamportAlgorithm.main() is not tested. init() does not reset numberOfProcessesCompletingSnapshot or snapshotTaken, so the tests also depend on global state across cases.
- Fix: Extract a pure `step(state, event): (state, effects)` and test it with property-based tests. Run random seeded schedules on random connected graphs, then check the textbook consistency property: every message recorded in a channel state was sent before the sender's snapshot and received after the receiver's snapshot, and a conservation invariant holds (use token transfers instead of inc/dec).
- Verifier: The suite has only two tests: marker fan-out (lines 38-41) and a forward. No test covers the recorded value, the channel state, completion, consistency or main(). init() (Algorithm.scala:21-24) does not reset numberOfProcessesCompletingSnapshot or snapshotTaken. Bugs 1 and 2 pass this suite.

### [high/confirmed] Actors read and write global null-initialized vars on a singleton object (hidden shared mutable state)
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:15` (fp-design)
- Evidence: ```
var systemSnapshot: SystemSnapshot = null
var processRecord: ProcessRecord = null
var snapshotTaken = false
var numberOfProcessesCompletingSnapshot = 0
```
Process.scala:5 `import main.algorithms.ChandyLamportAlgorithm.{numberOfProcessesCompletingSnapshot, processRecord, systemSnapshot}`
- Failure: A process actor dereferences `processRecord` on each message. If the actor runs before main()/init() (for example, a test builds a ChandyLamportProcess without init()), then `processRecord.map` throws NullPointerException. Each test's init() replaces the global, so actors that are still alive from an earlier test then send to the new test's map. Two runs at the same time (for example, a visualizer next to a test) corrupt each other.
- Fix: Pass the dependencies explicitly. Give each process its neighbor ActorRefs (or typed ActorRef[Msg]) through Props or a setup message, plus a reply-to collector ref. Remove the object-level vars. Use Akka Typed Behaviors with the state as immutable parameters (`def active(s: State): Behavior[Msg]`).
- Verifier: Algorithm.scala:15-18 declares null-initialized global vars, and Process.scala:5 imports them. The actors dereference processRecord and systemSnapshot on each message. The current tests call init() before they send a message, so the NPE does not happen in the suite today. The hidden shared state and the cross-test coupling are real, and they block a visualizer that runs several algorithms in one process.

### [medium/plausible] The driver is a tight unthrottled loop that floods unbounded mailboxes, so markers wait behind a backlog that keeps growing
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:53` (concurrency)
- Evidence: ```
while (numberOfProcessesCompletingSnapshot < processRecord.map.size) {
  val selectedKey = Random.nextInt(3)
  ...
  sender ! SendMessage(operation)
```
No delay and no bound. Each SendMessage also causes one PerformAction.
- Failure: The main thread sends millions of SendMessage per second to 13 actors with default unbounded mailboxes. Markers are FIFO behind this backlog. If the consumers fall behind, snapshot completion is delayed without limit and the heap grows until OutOfMemoryError. The run length and the recorded channel contents also depend on JIT and scheduler speed, so the results are not reproducible.
- Fix: Make the workload a finite, seeded script (for example, a List of N events with the snapshot start at step k). Use a scheduler or a pure simulator to deliver the events, and do not use a spin loop.
- Verifier: Lines 53-65 are an unthrottled spin that sends SendMessage until completion, and each SendMessage causes one PerformAction. Markers wait in FIFO order behind the backlog, and the run is not reproducible. Unbounded growth or OOM depends on whether the producer is faster than the dispatcher threads. That is likely on few cores, but it is not certain.

### [medium/confirmed] expectNoMessage() checks the testActor, which never receives a message, so the check is vacuous
- `src/test/scala/chandyLamportTest.scala:62` (test-quality)
- Evidence: ```
process0 ! SendMessage("increment")
process1.expectMsgAnyOf(PerformAction("increment"))
expectNoMessage()
```
- Failure: The process never replies to the implicit sender (the testActor), so expectNoMessage() always passes. If process0 sent 2 PerformActions to process1, or a PerformAction with the wrong body handled anywhere, the test would still pass, because the check looks at the wrong probe. `expectMsgAnyOf` with one argument is only expectMsg. The test also never checks the storedVariable change, so chandy-lamport-1 passes.
- Fix: Call process1.expectNoMessage() to check that there is exactly one forward. Add a test that sends PerformAction to a process and then checks its state, through a query message or a pure step function.
- Verifier: chandyLamportTest.scala:62 `expectNoMessage()` checks the implicit testActor, and ChandyLamportProcess never replies to it, so the check always passes. expectMsgAnyOf with one argument is the same as expectMsg. The test never checks the storedVariable change. This is a real gap, but it is a test-quality issue, so I lowered the severity to medium.

### [medium/confirmed] Boolean and String message protocol: the marker and the snapshot start share one Boolean case class, and operations are strings
- `src/main/scala/main/utility/MessageTypes.scala:29` (fp-design)
- Evidence: `case class InitiateSnapshot(start : Boolean) extends MessageTypes`; `case class SendMessage(messageBody: String)`; `case class PerformAction(messageBody: String)`; the trait `MessageTypes` is not sealed.
- Failure: The string protocol caused chandy-lamport-1, and the compiler cannot catch it. Process.scala:61-67 drops an unknown action string without a warning, but still records it in the channel state. `start` is Boolean-blind: `InitiateSnapshot(false)` means 'marker'. The protocol is not sealed, so the receive cannot be checked for exhaustiveness.
- Fix: Define a sealed protocol per algorithm: `sealed trait ClMsg; case object StartSnapshot; final case class Marker(from: Pid); final case class App(from: Pid, op: Op); final case class Tick(...)`. Use Akka Typed so that the compiler checks exhaustiveness.
- Verifier: MessageTypes.scala:6 `trait MessageTypes` is not sealed. Line 29 `InitiateSnapshot(start: Boolean)` uses false to mean marker. Lines 30-31 use String bodies. The string protocol directly causes chandy-lamport-1, and lines 61-67 drop an unknown action but still record it at line 69-70.

### [medium/confirmed] The snapshot model uses mutable classes that hold ActorRefs and print inside the data; no result is returned that a visualizer could use
- `src/main/scala/main/utility/SnapshotUtils.scala:11` (fp-design)
- Evidence: `class MessageRecord(val process: ActorRef, val messageBody: String) {}`; `class ProcessSnapshotData(val storedInteger: Int, val messageQueue: ListBuffer[MessageRecord]) {}`; `printSnapshotData` calls `log.info`; ChandyLamportAlgorithm.main() returns Unit.
- Failure: The snapshot exists only as log lines. A visualizer cannot get the global snapshot or the event timeline, which is needed to show markers moving and channel recording. The classes are not case classes, so they have no equality, and the tests cannot compare snapshots. Line 31 prints the ActorRef, which is a full path, instead of a process id. There is no separator between records, so the output runs together.
- Fix: Use `final case class LocalSnapshot(pid: Pid, value: Int, channels: Map[Pid, Vector[Op]])` and `final case class GlobalSnapshot(locals: Map[Pid, LocalSnapshot])`. Return `Future[RunResult(snapshot, events: Vector[TraceEvent])]`. Keep the formatting as a separate pure `show` function.
- Verifier: SnapshotUtils.scala:11-13 uses plain classes, not case classes, with ActorRef and ListBuffer fields. The output is log lines only, and main() returns Unit. Line 31 prints the ActorRef path, and the records have no separator. The snapshot model gives a visualizer no data that it can use.

### [medium/confirmed] The process state is spread across mutable vars toggled with `!snapshotTaken`; there is no pure transition function, so the logic is untestable and cannot be replayed
- `src/main/scala/main/processes/ChandyLamportProcess.scala:11` (fp-design)
- Evidence: `class ChandyLamportProcess(val id: Int, val neighbors: List[Int], var snapshotTaken: Boolean)`; `private var storedVariable = 0;` `private var markersReceived = 0;` `private val stopRecordingOn = ListBuffer[ActorRef]()`; line 32 `snapshotTaken = !snapshotTaken`, line 48 `snapshotTaken = !snapshotTaken`
- Failure: The flag at line 48 means 'in progress', not 'taken', and the toggle depends on the earlier value. Any extra marker (for example, duplicate neighbors from a dot file with both a->b and b->a) flips the meaning. The constructor parameter is always false. The counting with markersReceived lets a duplicate marker on the same channel count as a different channel. Because the logic is inside receive, a test can see it only through message probes, and a visualizer cannot step through it.
- Fix: Model the state as an ADT: `Idle(value)` | `Recording(value, snapshotValue, open: Set[Pid], recorded: Map[Pid, Vector[Op]])` | `Done(...)`. Write `def step(s: State, e: Event): (State, List[Effect])` as a pure function. The actor (or a deterministic simulator for the visualizer) only interprets the Effects. Close channels by removing from a `Set[Pid]`, not with a counter.
- Verifier: The vars and the toggles at lines 32 and 48 exist as described, and the logic is not testable as a pure step function. One detail of the failure scenario is wrong: readTopology emits both directions, so with a->b and b->a both nodes list each other twice. The marker sent count and the marker expected count then still match, and this topology has no duplicate edges. The design finding stands, but that example does not cause a failure.

### [low/confirmed] Unseeded global Random in the driver and the process makes the runs impossible to reproduce
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:55` (fp-design)
- Evidence: Algorithm.scala:55 `val selectedKey = Random.nextInt(3)`, :58, :61; Process.scala:56 `neighbors(Random.nextInt(neighbors.length))`
- Failure: A failing schedule cannot be replayed. A visualizer cannot give a 'replay this run' or step backward. Tests that use main() would be flaky. If a process has an empty neighbor list (possible when it is built directly, as in the tests), `Random.nextInt(0)` throws IllegalArgumentException on SendMessage.
- Fix: Pass a seed or a pure RNG (for example, scala.util.Random(seed) owned by the simulator, or a cats-effect Random) and make the schedule an explicit value. Use NonEmptyList for neighbors, or handle the empty case.
- Verifier: Algorithm.scala:55/58/61 and Process.scala:56 use the unseeded global scala.util.Random, so a run cannot be replayed. Random.nextInt(0) throws on an empty neighbor list. Only a directly built actor can have an empty list, because the topology file cannot give one.

### [low/confirmed] The code reads the topology file twice, never closes either Source, and never uses the first result
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:38` (other)
- Evidence: `val topologyLines = Source.fromFile(filename).getLines().toList` (never used); TopologyReader.scala:16 opens the file again with `Source.fromFile(filename)` and also does not close it.
- Failure: Each run from the menu leaks 2 file handles. In a long-lived visualizer server, repeated runs exhaust the file descriptors. A missing file throws FileNotFoundException out of main(). Main.scala:67 catches it only as a generic printStackTrace.
- Fix: Delete line 38. In TopologyReader, use `scala.util.Using(Source.fromFile(f))(_.getLines().toList)` and return `Either[ParseError, Graph]`.
- Verifier: Algorithm.scala:38 reads the file and never uses topologyLines. Neither that Source nor the Source at TopologyReader.scala:16 is closed, so each menu run leaks file handles.

### [low/plausible] Arbitrary shutdown timeout; Await.ready throws TimeoutException, and the user sees it as a generic error
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:74` (concurrency)
- Evidence: `Await.ready(system.whenTerminated, ((1.7 * processRecord.map.size)).seconds)`
- Failure: If a large backlog from chandy-lamport-5 keeps the dispatcher busy, termination can take longer than 1.7*N seconds. Await.ready then throws TimeoutException, Main.scala:67-69 prints a stack trace and 'An error occurred', and the snapshot is lost. The magic number 1.7 has no basis.
- Fix: Give the timeout a name in config. Await the snapshot result Future, not the termination. Use CoordinatedShutdown.
- Verifier: The magic timeout at line 74 exists, and Await.ready throws TimeoutException when the time runs out. But system.terminate() stops each actor after its current message and sends the remaining mailbox to dead letters, which Akka logs only up to a limit. A large backlog therefore does not delay the termination much, and the timeout is unlikely to happen in practice.

## lai-yang

I did a static review of the Lai-Yang snapshot code: LaiYangAlgorithm.scala, LaiYangProcess.scala, SnapshotUtils.scala, laiYangTest.scala, and the shared MessageTypes, ProcessRecord, TopologyReader and Main files. The implementation does not produce a consistent global snapshot.

Algorithm bugs:
- A process that receives a true-tagged (post-snapshot) message applies the message to its state first. It then queues the snapshot to itself, which is asynchronous.
- A process resets its snapshotTaken flag to false when it completes. After that, it sends false-tagged messages again, so the receivers record post-snapshot messages as pre-snapshot messages or as channel state.
- The same reset lets the process take a second snapshot and count its completion twice.
- The "Increment" / "increment" case mismatch means that no increment is ever applied.
- The printed snapshot leaves out the message that closes the last channel.

Concurrency bugs:
- All actors share global mutable state with no synchronization: the completion counter and the snapshot HashMap.
- The main thread busy-waits on a non-volatile var while it floods the mailboxes with messages. The result is lost updates, possible infinite loops, and possible NoSuchElementException crashes.

Test bugs: the two tests check only that control messages with count 0 go out. They give no snapshot state, no channel state, no completion and no non-zero counts, so most of the defects above still pass. Test 2 also uses a duplicate id and an asymmetric topology.

FP-design problems: global singletons with null and var, mutable maps that the global object and the actors share by alias, stringly-typed operations, a Boolean flag that selects the message variant, a -1 sentinel, output only through log side effects, and no seeded randomness. Because of these, the algorithm cannot be tested and cannot drive a visualizer. Restructure it as a pure state machine.

### [critical/confirmed] True-tagged message is applied before the local snapshot, and the snapshot is deferred through an async self-message
- `src/main/scala/main/processes/LaiYangProcess.scala:87` (algorithm-correctness)
- Evidence: Lines 87-93 change storedVariable first (`storedVariable += 1` / `storedVariable -= 1`). Only after that, lines 114-116 run: `} else if (tag && !this.snapshotTaken && this.incomingChannelsRecorded < neighbors.size) { self ! InitiateSnapshotWithMessageCount(-1, start = true) }`
- Failure: Fokkink 3.2 says: when q receives a true message before q takes its snapshot, q must take the local snapshot BEFORE q processes that message. Topology p-q. p takes a snapshot and sends decrement(true) to q. q applies the decrement and only then enqueues the initiate message, so q's recorded state contains the effect of a post-snapshot message. Because the initiate message is queued behind other mailbox traffic, q can also process more SendMessage commands with snapshotTaken=false in between. q then sends false-tagged messages that causally follow a post-snapshot event, and q's neighbors record them as pre-snapshot. The cut is inconsistent: the sum of states and channel contents does not match any reachable configuration.
- Fix: In the true-tag branch, take the snapshot synchronously in the same handler: record state, send the control messages, and set snapshotTaken. Then apply the message. Put the snapshot code in one function that both handlers call, and remove the self-send.
- Verifier: LaiYangProcess.scala:87-93 change storedVariable before the tag check. Line 116 then enqueues the snapshot with `self ! InitiateSnapshotWithMessageCount(-1, start = true)`. That self-message goes to the back of the mailbox, behind the SendMessage flood from LaiYangAlgorithm.scala:80-90. Those SendMessage commands run with snapshotTaken=false and send false-tagged messages that are counted as pre-snapshot (line 79). A decrement shows the problem. An increment does not, but only because of lai-yang-4. This breaks Fokkink's rule that the snapshot comes before the true message.

### [critical/confirmed] snapshotTaken is reset to false on completion, so later messages carry tag false again and the process can snapshot again
- `src/main/scala/main/processes/LaiYangProcess.scala:65` (algorithm-correctness)
- Evidence: Line 65 and line 106: `snapshotTaken = !snapshotTaken` when `incomingChannelsRecorded == neighbors.size`. Line 80 tags messages with this flag: `receiver ! PerformActionWithTagPayload(str, snapshotTaken)`. Line 79 increments preSnapshotMessagesSent again.
- Failure: In Lai-Yang, the tag means 'sent after the sender's snapshot', and the tag is permanent. Example: p completes first and sends m tagged false to q, but q has not taken a snapshot yet. q counts m as pre-snapshot and includes m in its local state, but m was sent after p's snapshot. The result is an orphan message and an inconsistent cut. Second example: q already took a snapshot and q's channel from p is closed (counter 0). m then decrements the counter to -1, and line 112 records m as in-transit channel state on a channel that is already closed. Third example: any later InitiateSnapshotWithMessageCount, such as a queued self-init from lai-yang-1, finds `!this.snapshotTaken` true at line 56. The process then overwrites systemSnapshot(id), sends a second round of control messages, and increments numberOfProcessesCompletingSnapshot again (see lai-yang-3).
- Fix: Keep two separate states: 'taken' (permanent, drives the tag) and 'completed'. Never clear the tag after the snapshot. Model the process phase as a sealed ADT (Idle | Recording(pending) | Done).
- Verifier: Lines 65 and 106 run `snapshotTaken = !snapshotTaken` when the process completes. Line 79 counts later messages as pre-snapshot again, and line 80 tags them false. The main loop keeps sending until every process completes, so a process that completes early does send false messages to neighbors that are still recording, or that have no snapshot yet. Those messages become orphans or make the counters go wrong. The third example, a re-snapshot through a queued self-init, needs a specific interleaving (see lai-yang-3). A true message that arrives after completion does not re-trigger the snapshot, because line 114 requires incomingChannelsRecorded < neighbors.size.

### [high/plausible] Completion check runs on every control/initiate message after completion, so the process counts completion twice and the main loop exits early
- `src/main/scala/main/processes/LaiYangProcess.scala:64` (algorithm-correctness)
- Evidence: Line 64: `if (incomingChannelsRecorded == neighbors.size) { snapshotTaken = !snapshotTaken ... numberOfProcessesCompletingSnapshot += 1 }`. This line is not guarded by a 'not yet completed' flag. Line 114 can enqueue several self-inits, because `!this.snapshotTaken` stays false until the first self-init is processed.
- Failure: q receives two true messages before q processes its first self-init, so two InitiateSnapshotWithMessageCount(-1,true) messages are queued. Alternatively, q completes and then a queued self-init arrives. Either way, line 56 re-snapshots q because the flag was toggled back, and line 64 is still true. numberOfProcessesCompletingSnapshot is incremented a second time for q. The guard `numberOfProcessesCompletingSnapshot < processRecord.map.size` (LaiYangAlgorithm.scala:80) then stops the loop and terminates the system while other processes are still recording. Those processes never print a snapshot. The duplicate control messages can also take a neighbor's channel counter back to 0, so incomingChannelsRecorded is incremented twice for one channel, and the neighbor completes before it records its other channels.
- Fix: Add an explicit Done state. Ignore initiate messages when the state is Recording or Done. Report completion exactly once, as a message to a coordinator actor, not as a shared counter.
- Verifier: Line 64 has no 'already completed' guard. Line 114 can enqueue two self-inits if two true messages arrive before the first self-init runs. The double count needs this order: the second self-init runs after the process completes, and the completion needs all control messages to arrive between the two self-inits. The flood traffic makes this possible, but it needs that specific interleaving. If it happens, line 56 re-snapshots the process, line 69 counts it a second time, and LaiYangAlgorithm.scala:80 can stop the system too early.

### [high/confirmed] "Increment" vs "increment" case mismatch: increments are never applied
- `src/main/scala/main/processes/LaiYangProcess.scala:87` (algorithm-correctness)
- Evidence: LaiYangProcess.scala:87 `if (action.equals("Increment"))`. LaiYangAlgorithm.scala:88 sends `val operation = if (selectedKey == 1) "increment" else "decrement"`.
- Failure: Every increment message falls through both branches and is silently ignored. Only decrements change storedVariable, so every recorded local state is <= 0. A conservation check (sum of states + in-transit increments - decrements) can never hold. Nothing reports the problem, because the code has no default branch and does not log unknown actions.
- Fix: Replace the strings with an ADT: `sealed trait Op; case object Inc extends Op; case object Dec extends Op`. Match exhaustively so the compiler finds this class of bug.
- Verifier: LaiYangProcess.scala:87 compares the action with "Increment". LaiYangAlgorithm.scala:88 and laiYangTest.scala:88 send "increment". No branch matches, so the process drops every increment and logs nothing.

### [high/plausible] Unsynchronized global counter is incremented from many actor threads and busy-read by the main thread
- `src/main/scala/main/processes/LaiYangProcess.scala:69` (concurrency)
- Evidence: LaiYangProcess.scala:69 and line 110: `numberOfProcessesCompletingSnapshot += 1`. This is an imported `var` from `object LaiYangAlgorithm` (LaiYangAlgorithm.scala:19). LaiYangAlgorithm.scala:80: `while (numberOfProcessesCompletingSnapshot < processRecord.map.size) {`
- Failure: Two actors complete at the same time on different dispatcher threads. Both read 4 and both write 5, so one update is lost. The counter never reaches map.size and the main thread spins forever. The field is also not volatile, so the JIT can hoist the read out of the spin loop, and the main thread then never sees the updates. The result is a hang even without a lost update.
- Fix: Remove the shared counter. Each process sends a SnapshotDone(id, data) message to a collector actor. The caller waits on a Future or Promise (ask, or typed ActorSystem with a result Promise) with a timeout.
- Verifier: The data race is real. Lines 69 and 110 increment the plain `var` at LaiYangAlgorithm.scala:19 from dispatcher threads, and LaiYangAlgorithm.scala:80 reads it in a spin loop. A lost update needs two processes to complete at the same moment, which is possible because completions cluster. The JIT hoist claim is weak: each loop iteration does an actor tell, which goes through mailbox atomics and gives memory barriers in practice. The lost-update hang remains possible, but it needs exact timing.

### [high/confirmed] Test 1 asserts only that an initiator sends control count 0, and never checks recorded state, channel state or completion
- `src/test/scala/laiYangTest.scala:53` (test-quality)
- Evidence: Lines 53-56: `process0 ! InitiateSnapshotWithMessageCount(-1, true)`, then `process1.expectMsg(InitiateSnapshotWithMessageCount(0, false))` and `process2.expectMsg(InitiateSnapshotWithMessageCount(0, false))`
- Failure: No basic messages are sent, so every count is trivially 0. An implementation that hard-codes 0 in the control message passes. So does an implementation that records the wrong storedVariable, one with the Increment typo (lai-yang-4), one that never records channel state, the print-ordering bug (lai-yang-7), and the flag reset (lai-yang-2). The test never reads LaiYangAlgorithm.systemSnapshot and never checks numberOfProcessesCompletingSnapshot. It cannot find any of the critical bugs.
- Fix: Add property-based tests for the pure transition function. Over random topologies and seeded schedules, assert conservation (sum of local states + sum of channel contents = initial total + effect of basic events) and the consistent-cut property (no message received in the snapshot that was sent after the sender's snapshot). Also add a focused test with counts > 0 and in-transit messages.
- Verifier: laiYangTest.scala:53-56 send only an initiate message and expect two InitiateSnapshotWithMessageCount(0,false) messages. The test sends no basic messages, so every count is 0. The test never reads systemSnapshot, the recorded value, the channel state or the completion counter. None of the critical bugs can make this test fail.

### [high/confirmed] Test 2 fakes state through a constructor flag, uses a duplicate id and an asymmetric topology, and asserts only a trivial count
- `src/test/scala/laiYangTest.scala:62` (test-quality)
- Evidence: Line 62 `new LaiYangProcess(0, List(1), true)`, line 63 `new LaiYangProcess(0, List(2), false)` (both have id 0, and process1 does not list process0 as a neighbor), line 90 `process2.expectMsg(InitiateSnapshotWithMessageCount(0, start = false))`
- Failure: The test passes with the lai-yang-1 bug (the message is applied before the snapshot, and the snapshot is deferred), because it only checks that a control message eventually reaches process2. It never checks that the snapshot of process1 excludes the triggering increment. Because of the Increment typo, the increment is a no-op, so nothing would show the difference. The duplicate id 0 makes process1 write its snapshot under process0's key. The asymmetric topology hides the lai-yang-9 crash path because the message is true-tagged. Setting `snapshotTaken = true` through the constructor tests a state that the real protocol cannot reach (a 'taken' process that never sent controls).
- Fix: Drive both processes through the real protocol (initiate p, then p sends). Use distinct ids and a symmetric topology. Assert that q's recorded value excludes the post-snapshot message, and that q's channel state is empty.
- Verifier: Lines 62-63 give both processes id 0 and give process1 the neighbors List(2). process0 starts with snapshotTaken=true through the constructor. Line 88 sends "increment", which is a no-op because of lai-yang-4. Line 90 checks only that a 0-count control message reaches the probe, so the test passes with lai-yang-1. One sub-claim is wrong: the asymmetric neighbor list does not hide a crash, because the received map contains process0 at line 81.

### [high/confirmed] Global mutable singletons with null and var, and actor state that the global object shares by alias
- `src/main/scala/main/algorithms/LaiYangAlgorithm.scala:16` (fp-design)
- Evidence: Lines 16-19: `var systemSnapshot: SystemSnapshot = null`, `var processRecord: ProcessRecord = null`, `var snapshotTaken = false`, `var numberOfProcessesCompletingSnapshot = 0`. Lines 22-25: global `mutable.Map[ActorRef, mutable.Map[ActorRef, Int]]`. LaiYangProcess.scala:29-30 aliases these maps into actor fields, and lines 79/97 then mutate the global object.
- Failure: The actor's private counters are the same objects as the global maps, so actor encapsulation is broken and the state can be read or written from outside the actor. If you run option 6 twice from Main's menu, the stale MessageCountStore entries from the old ActorSystem stay in memory. If a run throws before line 94, the counters are not reset. This design is the root cause of lai-yang-5, lai-yang-6 and lai-yang-12. It also prevents a visualizer from observing the state: the only output is log lines.
- Fix: Model the algorithm as a pure state machine: `final case class LYState(taken: Boolean, value: Int, sent: Map[Id,Int], pending: Map[Id,Int], recorded: Map[Id,Vector[Op]], phase: Phase)` and `def step(s: LYState, ev: Event): (LYState, List[Effect])`. Run it in a deterministic simulator that records a trace, so the visualizer can replay the trace. Keep an optional thin Akka Typed adapter (`Behaviors.receive` with immutable state).
- Verifier: LaiYangAlgorithm.scala:16-25 hold `null` vars and global mutable maps. LaiYangProcess.scala:29-30 alias the per-actor counter maps from the global MessageCountStore, so the actor's mutations at lines 53/79/97 change global state. Main.scala:43 calls LaiYangAlgorithm.main(), and a second run leaves stale entries keyed by old ActorRefs. That is a memory leak, not a key collision. This design is the root cause of lai-yang-5, lai-yang-6 and lai-yang-12, and it blocks a visualizer, which matters for the user's goal.

### [medium/plausible] Shared non-thread-safe mutable.HashMap is written concurrently by all actors, and this can cause a NoSuchElementException crash
- `src/main/scala/main/processes/LaiYangProcess.scala:59` (concurrency)
- Evidence: Line 59: `systemSnapshot.systemSnapshot += (id -> new ProcessSnapshotData(storedVariable, ListBuffer()))`. SnapshotUtils.scala:16 `val systemSnapshot: mutable.Map[Int, ProcessSnapshotData] = mutable.Map.empty`. Line 112 reads it back: `systemSnapshot.systemSnapshot(id).messageQueue += ...`, and SnapshotUtils.scala:24 calls `systemSnapshot(processId)`.
- Failure: Several processes take a snapshot at the same time. They do this often, because control messages fan out to all of them. Concurrent puts into a scala.collection.mutable.HashMap can lose entries or corrupt the table during a resize. A later `systemSnapshot(id)` at line 112 or in printSnapshotData then throws NoSuchElementException. The default supervisor restarts the actor, and the restart loses all of its counters. That process never completes, and the main loop (line 80) never exits.
- Fix: Keep each process's snapshot data inside its own actor state. Send that data to a collector actor in the completion message. Do not share mutable global maps.
- Verifier: Line 59 writes into the shared mutable.Map at SnapshotUtils.scala:16 from many actor threads, often at the same time because the control messages fan out. A lost entry during a resize would make line 112 or SnapshotUtils.scala:24 throw. The actor would then restart and lose its counters. The code is as described, but the corruption needs concurrent puts during a resize, so it happens only sometimes. Medium severity is better than high.

### [medium/confirmed] Snapshot is printed before the last in-transit message is recorded, so the output leaves out that message
- `src/main/scala/main/processes/LaiYangProcess.scala:105` (algorithm-correctness)
- Evidence: Lines 105-111 run `systemSnapshot.printSnapshotData(id)` when the last channel closes. Only after that, line 112 runs: `systemSnapshot.systemSnapshot(id).messageQueue += new MessageRecord(sender, action)`
- Failure: q took a snapshot. p's control message said n=1, and the one false message m from p arrives after the snapshot. m closes the last open channel, so the completion block prints the snapshot without m. m is then appended after the print. The reported channel state is missing m, and the reported snapshot is not the true global state.
- Fix: Record the message first, and then check for completion. Better: return the completed snapshot as a value from a pure transition function instead of printing it.
- Verifier: Inside the `if (this.snapshotTaken)` block, lines 105-111 print and count completion when the last channel closes. Line 112 appends the message only after that, and line 112 still runs, because the block was entered before line 106 toggled the flag. The false message that closes the last channel is in-transit channel state, and the printed snapshot leaves it out.

### [medium/confirmed] Unbounded busy loop floods mailboxes, termination depends on the racy counter, and the snapshot may never start
- `src/main/scala/main/algorithms/LaiYangAlgorithm.scala:80` (concurrency)
- Evidence: Lines 80-91: `while (numberOfProcessesCompletingSnapshot < processRecord.map.size) { val selectedKey = Random.nextInt(3) ... sender ! SendMessage(operation) }`. The process actors mix in UnboundedStash and UnboundedMessageQueueSemantics.
- Failure: The main thread sends SendMessage commands as fast as the CPU allows, with no throttling. That can be millions per second into unbounded mailboxes. Control messages queue behind large backlogs, completion lags, and heap use grows until an OutOfMemoryError. Each lost update or double count (lai-yang-3, lai-yang-5) gives either an infinite loop or a premature system.terminate(). The run uses the unseeded global Random, so you cannot reproduce a run. After the loop, `Await.ready(system.whenTerminated, ((1.7 * processRecord.map.size)).seconds)` (line 99) throws a TimeoutException if the shutdown is slow, and the reset at lines 94-95 has already run.
- Fix: Generate a finite, seeded schedule of N basic events and one initiation event. Wait for the collector's result Future. For the visualizer, drive the algorithm with a deterministic simulated scheduler (pure state machine + event queue) instead of real threads.
- Verifier: Lines 80-91 are an unthrottled loop that sends to unbounded mailboxes until the racy counter reaches map.size. The global Random has no seed. Line 99 calls Await.ready, which throws a TimeoutException if the shutdown takes too long. The reset at lines 94-95 runs before line 99. One part of the title is wrong: the snapshot does start, because selectedKey==0 has a 1/3 chance on each iteration. Only the flood, termination and reproducibility claims hold.

### [medium/confirmed] Weak message protocol: a Boolean selects the message variant, a -1 sentinel, string operations, and the payload field name says the opposite of the tag
- `src/main/scala/main/utility/MessageTypes.scala:33` (fp-design)
- Evidence: `case class InitiateSnapshotWithMessageCount(preSnapshotMessageCount: Int, start: Boolean)` (start=true means a local trigger and the count is ignored. The caller passes the -1 sentinel at LaiYangAlgorithm.scala:85 and LaiYangProcess.scala:116). `case class PerformActionWithTagPayload(str: String, snapshotTaken: Boolean)`. SnapshotUtils.scala:11-13 uses plain non-case classes that contain mutable ListBuffer.
- Failure: A caller can build InitiateSnapshotWithMessageCount(5, true), and the code silently drops the 5. A misspelled operation string compiles and is ignored (lai-yang-4). Snapshot data is a plain class with a mutable ListBuffer: it has no structural equality, so tests cannot compare an expected snapshot with an actual snapshot. The printSnapshotData loop also puts no separator between records (SnapshotUtils.scala:31).
- Fix: Split the protocol into `Initiate` and `Marker(count: Int)` (or `Control(count)`), and `Basic(op: Op, postSnapshot: Boolean)`. Make the snapshot result an immutable case class (`LocalSnapshot(value: Int, channels: Map[Id, Vector[Op]])`) with structural equality. Remove the unused UnboundedStash and UnboundedMessageQueueSemantics mixins, the unused `topologyLines` read (LaiYangAlgorithm.scala:43), and the unclosed Source.
- Verifier: MessageTypes.scala:33-34 have `InitiateSnapshotWithMessageCount(preSnapshotMessageCount: Int, start: Boolean)`, which the callers use with the -1 sentinel, and `PerformActionWithTagPayload(str: String, snapshotTaken: Boolean)`. SnapshotUtils.scala:11-13 are plain classes that hold a ListBuffer. SnapshotUtils.scala:31 appends records without a separator. LaiYangAlgorithm.scala:43 reads topologyLines, never uses them, and never closes the Source. LaiYangProcess.scala:14 mixes in UnboundedStash without using it. All of these are in the code as described.

### [low/plausible] Unchecked Map.apply lookups crash the actor for a sender that is not a neighbor or a topology that is not symmetric
- `src/main/scala/main/processes/LaiYangProcess.scala:48` (crash)
- Evidence: Line 48 `preSnapshotMessagesToReceive(sender)`, line 97 `preSnapshotMessagesToReceive(sender) - 1`, line 60 `preSnapshotMessagesSent(processRecord.map(neighbor))`, line 29 `LaiYangAlgorithm.MessageCountStore.preSnapshotMessagesSent(self)`
- Failure: A false-tagged message or control message that comes from an actor that is not in the receiver's neighbor map throws NoSuchElementException. Examples: the test's asymmetric topology (test 2 gives process1 the neighbors List(2), but process1 receives from process0), or a TestKit testActor. If init() runs before MessageCountStore is populated for self (any test or caller that forgets the setup), the first message crashes the actor. The supervisor restarts it with fresh state and the snapshot silently fails.
- Fix: Pass the neighbor refs and the zeroed counters to the actor at construction (Props arguments or typed Behavior parameters). Use a NeighborId type and `getOrElse` / validated input, or build the topology so that only valid neighbors can send.
- Verifier: The unchecked Map.apply calls are present (lines 29, 48, 60, 97). The failure scenarios do not hold for the real callers. TopologyReader.readTopology (TopologyReader.scala:25) adds both directions of every edge, so the main topology is symmetric. In test 2, process1's received map includes process0 (laiYangTest.scala:81), so the test has no hidden crash path. The crash needs a caller that sends from a non-neighbor or skips the setup, so this is only a robustness problem.

### [low/confirmed] Tests mutate global singleton state that accumulates across tests, and LaiYangAlgorithm.main is never tested
- `src/test/scala/laiYangTest.scala:41` (test-quality)
- Evidence: Lines 37/71 `LaiYangAlgorithm.init(processRecord)`, lines 41/47/73/79 `LaiYangAlgorithm.MessageCountStore.preSnapshotMessagesSent ++= Map(...)` (never cleared). The process actors from test 1 stay alive in the shared ActorSystem.
- Failure: Each test appends entries to global maps and replaces the global processRecord and systemSnapshot. A test that is added later, runs in another order, or runs in parallel sees stale entries and replaced references. For example, a test 1 actor that is still processing after test 2 calls init() sends to test 2's processRecord. The orchestration in main() (topology wiring, termination) has no coverage at all.
- Fix: Remove the global state so that each test builds an isolated topology value. Test main's logic through a pure `run(topology, schedule): Snapshot` function.
- Verifier: The tests call init() and `++=` on global maps that nothing clears (lines 37/41/47/71/73/79). The actors from test 1 stay alive, and nothing tests LaiYangAlgorithm.main. With the current two tests, the map keys are distinct ActorRefs and test 1's actor is idle after its assertions, so nothing fails now. The cross-test failure is hypothetical, so the severity is lowered to low.

## echo

I reviewed the echo algorithm by reading the code only. Nothing was compiled or run. On a simple connected graph with no duplicate edges, EchoProcess follows the textbook steps. The code has four main problems.
- Termination has a race. The run can finish before the Terminator is registered in the shared mutable map. When that happens, EchoTerminate is lost, Await.ready throws after 30 s, and the ActorSystem keeps running.
- A duplicate or self-loop edge makes the run hang. The forward-on-first-wave branch never checks whether the process has already heard from every neighbour, so the initiator never decides.
- A process finds out who sent a wave by parsing the actor name. A wave from any actor whose name is not a number makes the process crash and restart, which throws away its state.
- An empty topology crashes the program on .head.
The algorithm gives no result: the spanning tree (parent pointers) and the message trace are never reported, so neither a visualizer nor a test can see them. The only test runs on a 3-node star, which has no cycles. It checks only that EchoTerminate eventually arrives, so many broken versions would still pass. For FP design, the main problems are the shared mutable ProcessRecord, mutable vars instead of context.become/typed Behaviors, no pure step function, a random unseeded initiator, and a messages trait that is not sealed.

### [high/confirmed] The only test uses a 3-node star with no cycles and checks only that EchoTerminate eventually arrives
- `src/test/scala/EchoTest.scala:32` (test-quality)
- Evidence: L32-34: `new EchoProcess(1, List(2, 3), initiator = true, ...)`, `new EchoProcess(2, List(1), ...)`, `new EchoProcess(3, List(1), ...)`
L47: `terminatorProbe.expectMsg(EchoTerminate())`
- Failure: The topology is a tree, and every non-initiator is a leaf. So the test always takes the leaf special case (`neighbors.size == 1`) and never runs the forwarding path, cross waves on non-tree edges, or the `received == neighbors.size` path for a non-initiator. These broken versions would all pass:
(a) the initiator decides on its first returned wave (`received >= 1`);
(b) a non-initiator sends to its parent on the first non-parent wave, without waiting for all neighbours;
(c) the decision is sent several times (there is no expectNoMessage);
(d) the duplicate-edge hang in echo-3.
The test does not check the parent pointers, the message count (2|E| = 4), or that only the initiator decides. It does not use inputEcho.dot, TopologyReader or EchoAlgorithm.main, so echo-1, echo-2 and echo-5 are not covered either.
- Fix: Test a pure step function `step(state, Wave(from)) => (state', outgoing)` with property-based tests on random connected graphs. Check that exactly one decision occurs, that exactly 2|E| messages are sent, that the parents form a spanning tree rooted at the initiator, and that the initiator decides last. Keep one actor integration test on a graph with cycles (for example inputEcho.dot) that asserts on the EchoResult, plus expectNoMessage after the decision.
- Verifier: The test uses a 3-node star, so both non-initiators take the neighbors.size==1 branch. Mutants (a) received>=1 and (b) an early send to the parent would both pass, because expectMsg passes on the first EchoTerminate. The test has no expectNoMessage, no tree or message-count check, and it does not cover TopologyReader or EchoAlgorithm.main.

### [medium/plausible] Algorithm starts before the Terminator is registered; unsynchronized write to a shared mutable.Map
- `src/main/scala/main/algorithms/EchoAlgorithm.scala:51` (concurrency)
- Evidence: L51: `processRecord.map.get(randomInitiatorId).foreach(_ ! AllProcessesCreated)`
L54-55: `val terminator = system.actorOf(Props(new Terminator(system)), "terminator")`
`processRecord.map += (-1 -> terminator)`
EchoProcess.scala L58: `processRecord.map.get(-1).foreach(_ ! EchoTerminate())`
ProcessRecord.scala L11: `val map = mutable.Map.empty[Int, ActorRef]`
- Failure: The initiator gets AllProcessesCreated, and on a small graph the wave can finish on dispatcher threads before the main thread runs L54-55. The initiator then looks up key -1, gets None, and the foreach does nothing. It still calls context.stop(self), so no EchoTerminate is ever sent. The write on the main thread at L55 also races with reads of the same non-thread-safe HashMap on actor threads, with no happens-before edge between them, so a read can miss the entry even after it is written. In both cases system.terminate() is never called.
- Fix: Create the Terminator (or the result collector) before any process exists and pass its ActorRef into each process through the constructor or an init message. Better: remove ProcessRecord. Give each actor an immutable Map[Int, ActorRef] of its neighbours in an Init message after all actors exist, and send the decision to a replyTo ActorRef.
- Verifier: The code is as quoted. L51 sends AllProcessesCreated before L54-55 create the Terminator and write key -1 into the shared non-thread-safe mutable.Map, and that write has no happens-before edge to the actor reads. On the shipped inputEcho.dot (5 connected nodes, 7 edges), the wave needs about 14 hops, and each hop logs, before the initiator reads key -1. The main thread needs only one actorOf call and one map put. The lost-termination case therefore needs an unusual scheduling delay. A HashMap resize is also not triggered at 6 entries. The race is real, but it is unlikely in practice.

### [medium/confirmed] Await.ready timeout throws, and the ActorSystem is never terminated
- `src/main/scala/main/algorithms/EchoAlgorithm.scala:58` (concurrency)
- Evidence: L58: `Await.ready(system.whenTerminated, 30.seconds)`
Main.scala L66-68: `case e: Exception => e.printStackTrace()`
- Failure: Await.ready throws TimeoutException after 30 s, not 'times out quietly' as the comment says. This happens on any run where termination is lost (echo-1, echo-3). Main catches the exception, prints a stack trace and shows the menu again. The ActorSystem 'EchoAlgorithmSystem' keeps its non-daemon threads alive, so each failed run leaks a system, and choosing 9 to exit does not stop the JVM.
- Fix: Wrap the run in try/finally and call system.terminate() in the finally block. Better: return a Future[EchoResult] (or IO) that completes when the initiator decides, and await that with a timeout that the caller handles explicitly.
- Verifier: Await.ready throws TimeoutException on timeout. Main.scala L67-68 catches it and prints the stack trace, and nothing calls system.terminate(). Akka dispatcher threads are non-daemon by default, and no 'daemonic' setting is in the config, so the JVM does not exit after choice 9. The failure occurs only after another bug loses termination, so medium fits better than high.

### [medium/plausible] First-wave branch never checks for completion; duplicate edges or a self-loop make the run hang
- `src/main/scala/main/processes/EchoProcess.scala:40` (algorithm-correctness)
- Evidence: L36-51:
`if (parent.isEmpty && !initiator) {`
`  parent = Some(senderId)`
`  if (neighbors.size > 1) { neighbors.foreach { neighbor => if (neighbor != senderId) ... } }`
`  else { processRecord.map.get(senderId).foreach(_ ! EchoWave()) }`
`} else if (received == neighbors.size) {`
TopologyReader.scala L25: `List((from, to), (to, from))` (adds both directions for every line and does not remove duplicates)
- Failure: In Fokkink's algorithm, a non-initiator sends to its parent as soon as it has a message from every neighbour, and that can happen on the first message. The code covers this only with the special case `neighbors.size == 1`. Take a .dot file that has both "1" -> "2" and "2" -> "1", which is common in generated NetGraph files. TopologyReader then gives 1 -> [2,2] and 2 -> [1,1]. Initiator 1 sends two waves to 2. On the first wave, process 2 sets parent=1, takes the size>1 branch and forwards to nobody. On the second wave, received=2 equals size, so process 2 sends exactly one wave to 1. Process 1 then has received=1 out of 2, so it never decides, and the run times out (echo-2). A self-loop such as "3" -> "3" gives a similar wrong count.
- Fix: Remove duplicates from neighbours (use a Set[Int]) and ignore self-loops in the reader. In the process, after handling the first wave, apply the same rule as for every other wave: `if (received == neighbors.size) sendToParent`. This removes the leaf special case.
- Verifier: TopologyReader L25 adds both directions and does not remove duplicates, and the first-wave branch has no completion check when neighbors.size > 1. The hang scenario with duplicate edges is correct. But the configured file (application.conf L132 -> inputEcho.dot) has no reverse or duplicate edges and no self-loops. Without duplicates, the size==1 special case is behaviourally equivalent to the textbook rule, so the shipped run is correct. The failure needs a different input file.

### [medium/confirmed] The decision outputs nothing: the spanning tree, parent pointers and message trace are never reported
- `src/main/scala/main/processes/EchoProcess.scala:56` (algorithm-correctness)
- Evidence: L56-59: `case None =>`
`  log.info(s"$self (initiator) received Wave from all neighbors, deciding")`
`  processRecord.map.get(-1).foreach(_ ! EchoTerminate())`
`  context.stop(self)`
MessageTypes.scala L27: `case class EchoTerminate() extends MessageTypes`
- Failure: The useful output of the echo algorithm is the decision plus the spanning tree that the parent pointers define. Here `parent` stays private in each actor, which is then stopped. EchoTerminate carries no data and only shuts down the system. A visualizer has nothing to draw except log lines. A test cannot check the tree, which nodes took part, or the count of 2|E| messages. Non-initiators are also never stopped, and they never report that they finished.
- Fix: Model the run as events, for example `Sent(from, to)`, `Received(from, to)`, `ParentSet(node, parent)` and `Decided(initiator)`. Each process sends its events to an observer ActorRef. Return `EchoResult(tree: Map[Int, Int], messages: Vector[Event])`. The visualizer can replay this result, and tests can assert on it.
- Verifier: parent stays private to each actor, and EchoTerminate() carries no data. Nothing reports the spanning tree or the message trace. This is a real gap for the visualizer goal and for test assertions. One detail is overstated: system.terminate() stops the non-initiators, so they are stopped, but they never report.

### [medium/confirmed] Shared mutable ProcessRecord and mutable vars inside the actor, with no pure core
- `src/main/scala/main/processes/EchoProcess.scala:15` (fp-design)
- Evidence: L15: `class EchoProcess(val id: Int, val neighbors: List[Int], val initiator: Boolean, val processRecord: ProcessRecord)`
L18-19: `var received: Int = 0`, `var parent: Option[Int] = None`
ProcessRecord.scala L11: `val map = mutable.Map.empty[Int, ActorRef]`
- Failure: Every actor holds a reference to the same mutable map, which the main thread also writes to. This breaks actor isolation and causes echo-1. The protocol logic, the logging and the sending are all mixed together inside receive. So the algorithm cannot be tested, replayed step by step for a visualizer, or reused outside Akka. The `if (neighbors.size > 1)` special case (echo-3) is the kind of bug that a total, pure transition function would make clear.
- Fix: Add `final case class EchoState(parent: Option[Int], received: Int)` and a pure `def onWave(cfg: NodeCfg, s: EchoState, from: Int): (EchoState, List[Out])`, where `Out` is `Send(to)` or `Decide`. Run it from an Akka Typed `Behavior` that calls itself recursively with the new state (or from a deterministic in-memory scheduler for the visualizer). Pass the neighbour ActorRefs in an immutable Init message.
- Verifier: The shared ProcessRecord mutable.Map, the vars at L18-19, and protocol logic mixed with logging and sends inside receive are all present as quoted. This is a valid FP and testability finding.

### [low/plausible] Sender identity comes from parsing the actor name; a NumberFormatException restarts the actor and throws away its state
- `src/main/scala/main/processes/EchoProcess.scala:32` (crash)
- Evidence: L32: `val senderId = sender().path.name.toInt`
MessageTypes.scala L26: `case class EchoWave() extends MessageTypes`
- Failure: Any EchoWave from an actor whose name is not a number fails here. Examples are a TestKit testActor ('testActor-1'), a TestProbe, deadLetters (a send from outside an actor with no implicit sender), or a future visualizer actor that injects or relays waves. The default supervisor then restarts the EchoProcess, which resets `received=0` and `parent=None`. Waves it already received are lost, and the initiator never decides. The protocol also depends on the actor path naming scheme, so a run cannot use names like 'p1' and two runs cannot share one system.
- Fix: Put the sender id in the message: `final case class Wave(from: Int)`. Or use a typed protocol that carries `replyTo: ActorRef[Wave]`. Do not get protocol data from sender().path.
- Verifier: L32 parses sender().path.name.toInt, and a NumberFormatException does restart the actor and reset its vars. All current senders of EchoWave are EchoProcess actors named by their numeric id. This includes the test, where the TestKit testActor sends only AllProcessesCreated, never EchoWave. No current caller crashes, so this is a latent design hazard.

### [low/confirmed] Empty or edge-less topology crashes on .head; the file handle is never closed
- `src/main/scala/main/algorithms/EchoAlgorithm.scala:39` (crash)
- Evidence: L37-39: `val allProcessIds = processConfig.keys.toList`
`val randomInitiatorId = Random.shuffle(allProcessIds).head`
TopologyReader.scala L16: `Source.fromFile(filename).getLines().toList` (Source is never closed)
TopologyReader.scala L19: `.filter(line => line.contains("->"))`
- Failure: A .dot file with no '->' lines produces an empty map. One case is a valid graph that has a single node. Another is a file where the edge arrow uses a different spacing or syntax that the substring filter misses. `.head` then throws NoSuchElementException. A missing file throws FileNotFoundException. Both errors happen after ActorSystem(...) is created at L26, so the system leaks (echo-2). The Source is also never closed.
- Fix: Parse into Either[TopologyError, Graph] using `Using(Source.fromFile(f))`. Validate that the graph is non-empty and connected before creating the ActorSystem. Pick the initiator with headOption, or let the caller pass it.
- Verifier: Random.shuffle(Nil).head throws NoSuchElementException for a file with no '->' lines (for example, a single-node graph). Source.fromFile is never closed. Both errors occur after ActorSystem creation at L26, so the system leaks. One part of the claim is wrong: the substring filter does accept any spacing around '->', because split and trim handle it. The failure needs bad or degenerate input, so the severity is low.

### [low/confirmed] The designated initiator node "0" is silently dropped; the initiator is random and unseeded
- `src/main/resources/inputEcho.dot:3` (algorithm-correctness)
- Evidence: inputEcho.dot L1: `digraph "Net Graph with 6 nodes" {`
L3: `"0" ["color"="red","label"=<<b>Init</b>>,"fontcolor"="#1020d0"]`
EchoAlgorithm.scala L39: `Random.shuffle(allProcessIds).head`
- Failure: The file marks node 0 as the initiator and calls itself a 6-node graph. Node 0 has no edges, and TopologyReader keeps only '->' lines, so the run uses 5 nodes and ignores the Init marker. The code picks the initiator with unseeded Random, so two runs on the same file build different spanning trees. Neither a test nor a visualizer can reproduce a run.
- Fix: Read node declarations and the initiator attribute from the file, or take the initiator and an RNG seed as parameters. Report isolated nodes as an error, because echo assumes a connected undirected graph.
- Verifier: inputEcho.dot L3 declares the isolated node "0" with an Init label. The reader keeps only '->' lines, so the run has 5 nodes, and L39 uses unseeded Random to pick the initiator. The echo result is still correct on the 5-node connected component, and the Init label is probably a NetGraph generator artefact. The real impact is non-reproducibility, which matters for a visualizer, so the severity is low.

### [low/confirmed] The test sends the companion object of a case class, which hides a message-type mismatch
- `src/test/scala/EchoTest.scala:44` (test-quality)
- Evidence: EchoTest.scala L44: `process1 ! AllProcessesCreated`
EchoProcess.scala L25: `case AllProcessesCreated =>`
MessageTypes.scala L21: `case class AllProcessesCreated() extends MessageTypes`
- Failure: AllProcessesCreated is a case class with an empty parameter list. Both the senders and the receive pattern use the companion object, not an instance, so the code works only because they make the same mistake. If anyone sends `AllProcessesCreated()`, which is the natural form for a case class, the message goes unhandled, the initiator never starts, and the run times out. The compiler gives no error. Terminator's `case TerminateSystem =>` has the same problem with the case class `TerminateSystem()`.
- Fix: Declare it as `case object AllProcessesCreated` (or `Start`) inside a sealed protocol trait, so that the compiler checks exhaustiveness.
- Verifier: MessageTypes L21 declares 'case class AllProcessesCreated()'. EchoAlgorithm L51, EchoTest L44 and the EchoProcess L25 pattern all use the companion object, so they match each other only by accident. A send of AllProcessesCreated() would go unhandled with no compiler error. Terminator L15 'case TerminateSystem =>' versus 'case class TerminateSystem()' has the same problem. The current code works, so the issue is fragility.

### [low/confirmed] The algorithm object extends the message marker trait; the protocol is not sealed and is shared by every algorithm
- `src/main/scala/main/algorithms/EchoAlgorithm.scala:16` (fp-design)
- Evidence: L16: `object EchoAlgorithm extends MessageTypes {`
MessageTypes.scala L6: `trait MessageTypes` (not sealed; it holds the messages for all 8 algorithms)
L24: `def main(): Unit = {`
- Failure: The comment says EchoAlgorithm extends MessageTypes to get access to the message types. It gives no such access: it makes the EchoAlgorithm object itself a value of the message type, so it can be sent as a message by mistake. All the algorithms' messages live in one open trait, so receive blocks are never checked for missing cases. Nothing stops an actor from receiving another algorithm's messages. main() returns Unit and runs every side effect inline (system creation, file I/O, randomness, blocking await), so it cannot be composed with a visualizer or tested.
- Fix: Give each algorithm its own `sealed trait EchoMsg` in its own package. Make the entry point `def run(graph: Graph, initiator: Int): Future[EchoResult]` (or an effect type), and keep the menu and the Await at the edge of the program.
- Verifier: L16 'object EchoAlgorithm extends MessageTypes', and the trait at MessageTypes L6 is unsealed and shared by all algorithms. Extending the trait gives no access to anything and makes the object a message value. main(): Unit runs all side effects inline. This is a valid low-severity design finding.

## tarry

Static review of Tarry's algorithm (TarrysAlgorithm.scala, TarryProcess.scala, TarryTest.scala, inputTarry.dot, plus ProcessRecord, Terminator, TopologyReader, MessageTypes, Main). On a connected graph with one correctly flagged initiator, the traversal rules in TarryProcess.receive give the correct textbook result. Rule 1 (no channel used twice) holds, rule 2 (the parent channel only as the last choice) holds, and the termination check matches Fokkink: the initiator holds the token and has sent through all its channels. The defects are elsewhere. The initiator records a false parent, so a spanning tree that a visualizer derives from the parent field is wrong. The 'Init' marker in the DOT file is ignored, and the initiator is random with no seed. The code can represent illegal states: the initiator flag and the InitiateTarry message are two sources of truth, a missing actor reference loses the token, and the -1 sentinel stands for both 'no parent' and 'terminator'. Termination falls back on a 30 s Await that throws and leaks the ActorSystem. The algorithm produces no result, only log lines, so a visualizer has nothing to consume. The three tests assert only that one TerminateTarry arrives. An implementation that terminates on the first token return passes all three tests. An implementation that only visits direct neighbours also passes all three. FP design: all state is in mutable vars and a shared mutable map, the transition logic is mixed into the Akka plumbing, and no pure step function exists to test or replay.

### [high/confirmed] All three tests assert only that one TerminateTarry arrives; broken implementations pass them
- `src/test/scala/TarryTest.scala:47` (test-quality)
- Evidence: Each test ends with the same single assertion: `terminatorProbe.expectMsg(TerminateTarry)` (lines 47, 69, 92). No test checks the visit order, the message count (2 x edges), the parent of each node, whether every node got the token, or whether each channel carried the token exactly once in each direction.
- Failure: Mutant A: the initiator sends TerminateTarry on the first token return. Star test: 1->2, 2->1, terminate: pass. Line test: 1->2->3->2->1, terminate: pass. Ring test: 1->2->3->4->1, terminate: pass, but the correct run continues 1->4->3->2->1, so the mutant stops after 4 of 8 messages. Mutant B: a non-initiator returns the token to its parent at once and never explores. Line test 1-2-3: 1->2, 2->1, 1 has sent to all neighbours, terminate: pass, and node 3 never gets the token. Ring: 1->2->1->4->1: pass. Mutant C: a non-initiator picks the parent first (this breaks rule 2). It also passes all three tests. The suite cannot tell a correct Tarry from these mutants.
- Fix: Extract a pure step function (see tarry-9) and test it with properties on generated connected graphs: each directed channel is used exactly once, the run has 2|E| messages, every node is visited, the parent pointers form a spanning tree rooted at the initiator, and the initiator decides exactly once, as the last event. Keep one actor integration test that collects the event trace through a probe and checks the trace, not only the final message.
- Verifier: TarryTest.scala lines 47, 69 and 92 hold the only assertions: terminatorProbe.expectMsg(TerminateTarry). The traces check out. Mutant A (terminate on the first return) passes the star (1->2->1), line and ring tests. Mutant B (return to the parent at once) passes the line test, and node 3 never gets the token. No test checks visit order, message count or parent pointers.

### [medium/plausible] Termination relies on a 30 s Await that throws TimeoutException and leaks the ActorSystem
- `src/main/scala/main/algorithms/TarrysAlgorithm.scala:58` (crash)
- Evidence: `Await.ready(system.whenTerminated, 30.seconds)
    log.info("Algorithm terminated")`. Only the TerminateTarry message stops the system (Terminator line 25-26). Nothing calls system.terminate() on the timeout path.
- Failure: Any run where the token is lost: an isolated initiator (line 31 of TarryProcess does nothing when neighbors is empty), a missing actor reference (tarry-5), the loop in tarry-3, or a disconnected graph where the token cannot return. After 30 s, Await.ready throws java.util.concurrent.TimeoutException. Main.scala line 67 catches it and prints the stack trace, but the 'TarrysAlgorithm' ActorSystem stays alive with its non-daemon threads. When the user chooses 9 to exit, the JVM does not exit. A run on a large graph with verbose logging that takes more than 30 s also fails, although it is correct.
- Fix: Return the result as a Future (or a typed ask) that completes when the initiator decides, and terminate the system in a finally / onComplete block on both paths. Make the timeout a parameter, and report the timeout as a Left or failed result value, not an exception.
- Verifier: Await.ready at TarrysAlgorithm.scala:58 throws TimeoutException. Main.scala:67 catches it, and nothing calls system.terminate(), so the non-daemon ActorSystem stays alive and option 9 does not exit the JVM. Some triggers in the finding are wrong. TopologyReader only produces nodes that have edges, so an isolated initiator cannot come from a file. A disconnected graph terminates normally inside the initiator's component, so the token is not lost. The shipped inputTarry.dot is connected. The real triggers are a misuse (tarry-3/5) or a run slower than 30 s.

### [medium/confirmed] The DOT 'Init' marker is ignored and the initiator is a random choice with no seed
- `src/main/resources/inputTarry.dot:3` (other)
- Evidence: inputTarry.dot line 3 declares `"0" ["color"="red","label"=<<b>Init</b>>,...]`. TopologyReader keeps only lines that contain '->' (`.filter(line => line.contains("->"))`), so node 0 and the marker are dropped. TarrysAlgorithm line 37 then does `val randomInitiatorId = Random.shuffle(allProcessIds).head`. The graph name says 'Net Graph with 6 nodes', but 7 nodes are declared.
- Failure: The author marked node 0 as the initiator, but node 0 has no edges and never takes part, and every run starts from a different random node. Runs cannot be reproduced, a visualizer cannot replay the same run, and a bug report cannot name the run that failed. If the file has no edges, `Random.shuffle(Nil).head` throws NoSuchElementException ('head of empty list').
- Fix: Parse node declarations and attributes, take the initiator from the file or a CLI argument, and fall back to a seeded Random that the caller passes in. Return a validation error (Either) for an empty or disconnected graph. Fix the DOT file so the initiator is a connected node.
- Verifier: inputTarry.dot line 3 declares node "0" with the Init label and no edges. TopologyReader line 19 drops every line that has no '->', so node 0 never exists. TarrysAlgorithm line 37 picks the initiator with an unseeded Random.shuffle(...).head, which throws on an empty list. Runs cannot be reproduced, and that matters for a replay visualizer.

### [medium/confirmed] The algorithm produces no result value: only log lines, plus dead counters
- `src/main/scala/main/processes/TarryProcess.scala:20` (fp-design)
- Evidence: `var parent: Int = -1`, `var tokensSent: Int = 0`, `var tokensReceived: Int = 0`, `var sent: mutable.Set[Int]`. tokensSent and tokensReceived are incremented (lines 39, 80) but no code reads them. TarrysAlgorithm.main returns Unit, and the only observable output is log.info text.
- Failure: The user's visualizer needs the traversal order, the parent tree and the channel use over time. None of these leave the actors, so the visualizer would have to parse log strings. The tests cannot check correctness for the same reason (tarry-1).
- Fix: Define an event ADT (TokenSent(from, to, step), ParentSet(node, parent), Decided(initiator)) and have the run return or stream a List/Stream of events plus the final parent map. The visualizer replays the events and the tests assert on them. Remove the unused counters.
- Verifier: No code reads tokensSent or tokensReceived (grep finds only the increments at lines 39 and 80). main() returns Unit, and the only output is log text. So the visualizer and the tests get no traversal trace or parent tree.

### [medium/confirmed] The transition logic is mixed into actor side effects; no pure, testable step function exists
- `src/main/scala/main/processes/TarryProcess.scala:37` (fp-design)
- Evidence: The `case TarryProbe(sid) =>` branch mutates vars, chooses the next hop and sends messages through `forward`, which itself mutates `tokensSent` and `sent` and does the map lookup. The 'parent' and 'terminator' identities both use the magic Int -1 (`var parent: Int = -1` and `processRecord.map(-1)`).
- Failure: You cannot run the algorithm without an ActorSystem, cannot replay it step by step for a visualizer, and cannot check it with property tests. A topology with a node id -1 collides with the terminator key in ProcessRecord and with the 'no parent' sentinel.
- Fix: Write `final case class NodeState(id: Int, neighbours: List[Int], parent: Option[Int], sent: Set[Int])` and a pure function `def onToken(s: NodeState, from: Int, isInitiator: Boolean): (NodeState, Action)` with `sealed trait Action; case class SendTo(n: Int); case object Decide`. A deterministic simulator (a fold over a queue of in-flight messages) and the Akka adapter both call it. In the actor, use context.become(running(state)) or Akka Typed Behaviors in place of vars.
- Verifier: The TarryProbe branch mixes var mutation, next-hop choice and sends through forward(). The value -1 is both the 'no parent' sentinel (line 20/41) and the terminator key (line 51; TarrysAlgorithm line 51), so a node with id -1 collides with both. This is a valid FP-design finding for the user's goals.

### [low/confirmed] The initiator sets a parent, so the spanning tree that the parent field gives is wrong
- `src/main/scala/main/processes/TarryProcess.scala:41` (algorithm-correctness)
- Evidence: `if (parent == -1) {
        // Set the sender as the parent if not already set
        parent = sid
      }` runs for every process, including the initiator. In Fokkink the initiator has no parent, and the parent pointers of the non-initiators form the spanning tree.
- Failure: Line topology 1-2-3, initiator 1: the token returns from 2, and process 1 sets parent = 2 while process 2 has parent = 1. The parent relation now has the cycle 1<->2, so it is not a tree. A visualizer or a test that draws the tree from the parent fields shows a wrong graph. Traversal still terminates only because the filter at line 58 and forward(parent) at line 65 send to the same node.
- Fix: Model parent as Option[Int]. Give the initiator a distinct state (for example a Role ADT: Initiator | NonInitiator(parent: Option[Int])) and never assign a parent to the initiator. Expose the final parent as part of the result.
- Verifier: Line 41 has no initiator guard, so the initiator sets parent = sid when the token first comes back. Line topology 1-2-3 gives the parent cycle 1<->2, as the finding says. Traversal stays valid: the line 58 filter only delays the send to the parent, and line 65 sends to it last. The parent field is never exposed or read outside the actor, so nothing can observe a wrong tree today. The defect is latent, so the severity is lowered to low.

### [low/plausible] The initiator flag and the InitiateTarry message are two sources of truth; a mismatch loops the token forever
- `src/main/scala/main/processes/TarryProcess.scala:29` (algorithm-correctness)
- Evidence: The constructor takes `val initiator: Boolean`, but any process accepts `case InitiateTarry =>` and starts the traversal. The termination branch at lines 48-55 consults only the flag: `if (initiator) { ... ! TerminateTarry } else { forward(parent) }`.
- Failure: Send InitiateTarry to a process built with initiator = false (an easy slip in a test or a visualizer 'start here' button). Example: P1 (initiator=false, neighbours [2]) and P2 (neighbours [1]). P1 sends to 2. P2 sets parent=1 and forwards to 1. P1 sets parent=2; all its neighbours are in sent and it is not the initiator, so it calls forward(2). P2 has sent to all and calls forward(1). The token goes back and forth forever, rule 1 breaks on every hop, and the run ends only when the 30 s Await expires. Conversely, two processes with initiator = true are also representable.
- Fix: Make the initiator a decision of the start message only (for example Start becomes the initiator role through context.become or a typed Behavior), or remove the flag and derive the role from the presence of a parent. Make the illegal combination unrepresentable.
- Verifier: I traced the scenario: P1 (initiator=false, [2]) and P2 ([1]) pass the token back and forth forever through lines 54 and 65. Line 29 accepts InitiateTarry no matter what the flag is. But the production caller (TarrysAlgorithm line 43/55) and the tests always send InitiateTarry only to the process that has initiator=true. The loop needs misuse, so this is a design smell (two sources of truth), not a current bug.

### [low/plausible] A missing actor reference silently drops the token, so the run hangs
- `src/main/scala/main/processes/TarryProcess.scala:82` (concurrency)
- Evidence: `case None =>
        log.error(s"$self Actor reference not found for process $neighborId")` - the token is not sent, and nothing is recorded in sent or reported. Also `processRecord.map(-1) ! TerminateTarry` (line 51) throws NoSuchElementException if the terminator is not registered; the default supervisor then restarts the actor and resets parent and sent.
- Failure: A neighbour id that is in a neighbour list but not in ProcessRecord: a DOT node whose id is not an Int crashes earlier, but a test or visualizer that builds the record by hand and forgets a node, or that registers the record entries after the start message, loses the token. The traversal stops with one log line, and the run hangs until the 30 s timeout of tarry-4.
- Fix: Resolve all neighbour references before the run starts (fail fast with a validated topology: ids exist, graph connected, initiator exists). Pass the ActorRefs into each process at construction or in a typed Init message, not through a shared map that the actor reads at message time.
- Verifier: Lines 82-83 do log and drop the token, and processRecord.map(-1) at line 51 throws if no terminator is registered. But TopologyReader builds the lists symmetrically, so every neighbour id is also a key, and TarrysAlgorithm and every test register all refs before the first send. The failure needs a hand-built record that is incomplete.

### [low/plausible] All actors share one unsynchronised mutable.Map that the main thread and the test thread write
- `src/main/scala/main/algorithms/TarrysAlgorithm.scala:46` (concurrency)
- Evidence: `processRecord.map += (id.toInt -> processActor)` in TarrysAlgorithm, `processRecord.map ++= Map(1 -> process1, ...)` in TarryTest line 37, and ProcessRecord is `val map = mutable.Map.empty[Int, ActorRef]`. Each TarryProcess reads `processRecord.map.get(neighborId)` from a dispatcher thread.
- Failure: The current code is safe only by accident: all writes happen before the first `!` and Akka's send gives the happens-before edge. Any change that writes after the start, for example a visualizer that adds nodes live or the test that registers the terminator probe after sending InitiateTarry, gives a data race on a non-thread-safe HashMap. A reader can then see a stale or corrupted map, which leads to the lost token of tarry-5.
- Fix: Remove ProcessRecord. Give each process an immutable Map[Int, ActorRef] of its own neighbours (and the reply-to ref for the result) at construction or in an init message.
- Verifier: The code is as quoted: an unsynchronised mutable.Map that dispatcher threads read. The finding itself says the current code is safe, because all writes happen before the first `!`, and the send gives the happens-before edge (this holds in the tests too: lines 37/41 come before line 44). The race exists only if the code changes in the future.

### [low/confirmed] InitiateTarry is an empty case class but the code sends and matches its companion object
- `src/main/scala/main/utility/MessageTypes.scala:16` (fp-design)
- Evidence: `case class InitiateTarry() extends MessageTypes`. The callers send the companion: `processRecord.map.get(randomInitiatorId).foreach(_ ! InitiateTarry)` (TarrysAlgorithm line 55) and `process1 ! InitiateTarry` (TarryTest line 44). The receiver matches `case InitiateTarry =>` (TarryProcess line 29), which compares by equality with the companion object, a Function0.
- Failure: The code works only because both sides use the companion by mistake. A caller that writes the natural `InitiateTarry()` sends an instance that `case InitiateTarry =>` does not match. The message goes to unhandled/dead letters, nothing starts, and the run times out (tarry-4).
- Fix: Use `case object InitiateTarry` (as TerminateTarry already is), and put all Tarry messages in a sealed trait so the compiler checks that the match is exhaustive.
- Verifier: MessageTypes.scala:16 is `case class InitiateTarry()`. TarrysAlgorithm:55 and TarryTest:44 send the companion object, and TarryProcess:29 matches the companion by equality. A caller that sends InitiateTarry() does not match and goes to unhandled. The code works today only because both sides use the companion.

### [low/confirmed] The termination check tests 'sent to all', not 'received through all'; the log text claims the second
- `src/main/scala/main/processes/TarryProcess.scala:50` (algorithm-correctness)
- Evidence: `if (neighbors.forall(sent.contains)) { if (initiator) { log.info(s"$self received token through all channels, traversal completed")`. The code never tracks the channels it received through.
- Failure: Under the two Tarry rules the two conditions are the same when the initiator holds the token, so the result is correct today. If a change breaks rule 1 or rule 2 (for example the mutants in tarry-1), the initiator still reports 'received through all channels' although some channels were never used inward. The log, and any visualizer built on it, then hides the bug.
- Fix: Track the received channels as a Set too and assert the invariant (received == sent == neighbours) at the decision, or record it as part of the Decided event so the tests can check it.
- Verifier: Line 46 checks neighbors.forall(sent.contains), and the line 50 log claims 'received token through all channels'. The code never tracks the channels it received through. The result is correct under the Tarry rules, as the finding says, so the problem is only a misleading log and an invariant that nothing checks.

### [low/confirmed] Neighbour lists can contain duplicates and the graph is never checked for connectivity
- `src/main/scala/main/utility/TopologyReader.scala:25` (algorithm-correctness)
- Evidence: `List((from, to), (to, from))` for every '->' line, then `.groupBy(_._1).mapValues(_.map(_._2).toList)`. No dedup and no connectivity check. TarryProcess assumes a connected undirected graph with simple neighbour lists.
- Failure: A DOT file that has both `"1" -> "2"` and `"2" -> "1"` gives process 1 the neighbour list [2, 2]. The run still terminates because sent is a Set, but a visualizer and an expected-message-count test (2|E|) disagree with the graph. A disconnected graph terminates with only the initiator's component visited, and nothing reports it.
- Fix: Build an undirected graph as Map[Int, Set[Int]], parse ids to Int once in the reader, and validate connectivity before the run (return Either[TopologyError, Graph]).
- Verifier: TopologyReader lines 25 and 30-31 emit both directions for each '->' line and do no dedup, so both "1"->"2" and "2"->"1" give [2,2]. No connectivity check exists, and a disconnected graph silently visits only the initiator's component. The shipped file has no duplicate edges, so this needs other input.

## tree

Static review of the tree algorithm: src/main/scala/main/algorithms/TreeAlgorithm.scala, src/main/scala/main/processes/TreeProcess.scala and src/test/scala/TreeTest.scala, plus the shared utilities they use (MessageTypes, ProcessRecord, Terminator, TopologyReader, Main). I could not compile or run anything. The default input file (NetGraph_21-04-24-18-24-58.ngs.perturbed.dot) is a valid tree with 13 nodes and 12 edges, so the happy path usually works.

The main correctness defects:
1. The start signal and the protocol message are the same message (`Wave`), and the process does not record that it already sent its wave. A leaf that gets its neighbor's wave before its own start signal decides twice, and the neighbor never decides. The textbook says exactly two neighboring processes decide.
2. The Terminator stops the whole ActorSystem on the first `TreeTerminate`. The second decision and the Info broadcast are cut off.
3. The code checks the input only for cycles. A forest, an isolated node, or a file that lists one edge in both directions passes the check. The run then gives wrong deciders or hangs until `Await.ready` throws `TimeoutException`.

Test quality is poor. The one actor test passes if any single `TreeTerminate` arrives within 3 seconds. It does not check which processes decide, how many decide, the parent tree, or the Info delivery. It also never runs TreeAlgorithm.main. The other two tests check TopologyReader.hasCycle, not the tree algorithm.

FP design: mutable `var` state, a shared mutable `ProcessRecord` map, a reverse lookup of the sender's ID by `ActorRef`, and empty case classes used through their companion objects. There is no pure (state, message) => (state, outputs) step function, and a visualizer needs one to replay runs.

### [high/confirmed] The only actor test passes if any single TreeTerminate arrives within 3 seconds
- `src/test/scala/TreeTest.scala:57` (test-quality)
- Evidence: `terminatorProbe.expectMsg(TreeTerminate)` is the only assertion in the test at lines 20-58. It has no expectNoMessage, no check of which processes decided, and no check of parents or Info delivery.
- Failure: Each of these broken versions still passes: a leaf that decides as soon as it is kicked; the double decision of tree-1 (one decider twice, neighbor never); deciders that are not adjacent; Info never forwarded (lines 33-44 deleted); a wrong parent selection. Any code path that sends one TreeTerminate to processRecord.map(-1) is green.
- Fix: Run the algorithm on several trees (line, star, 2-node, 1-node, the default file) and collect events. Assert that exactly two processes decide, that they are neighbors, that each decides once, that the parent pointers form a spanning tree directed toward the decider edge, and that every non-decider receives Info exactly once. Add expectNoMessage after the run. Better: property-test the pure step function under random schedules.
- Verifier: TreeTest.scala:57 `terminatorProbe.expectMsg(TreeTerminate)` is the only assertion in the actor test. It does not check which processes decide, adjacency, parent pointers, Info delivery or the count of decisions. Any implementation that sends one TreeTerminate to map(-1) within 3 s passes. The test also uses ImplicitSender, so the kicks come from testActor (id -1), the same path as production.

### [medium/plausible] Start signal and protocol wave are the same message; a late start makes a leaf decide twice and its neighbor never decide
- `src/main/scala/main/processes/TreeProcess.scala:27` (algorithm-correctness)
- Evidence: TreeAlgorithm.scala:47 `leafNodes.foreach(id => processRecord.map(id.toInt) ! Wave)` and TreeProcess.scala:27-31 `case Wave => val senderId = getSender(context.sender()); received += (senderId -> true) ... sendWave()`. No state records that the wave to the parent was sent. sendWave runs again on every Wave.
- Failure: Path 0-1-2 (leaves 0 and 2). The main thread kicks 0. Process 0 sends a wave to 1, so 1 has only 2 left unreceived. Process 1 sends a wave to 2 and sets parent=2. Process 2 receives this wave before the main thread enqueues its kick. Process 2 now has no unreceived neighbors, so it decides and sends Info to 1 (parent is None). The kick then arrives from deadLetters (id -1). Process 2 decides again and sends Info to 1 again. Process 1 never receives a wave from 2, so it never decides. Result: one process decides twice, its neighbor never decides, and Info goes twice through 1's subtree. The textbook says exactly two neighbors decide, once each. The window grows with more leaves, for example the 5 leaves in the default topology.
- Fix: Use a separate `Start` message for initiators, or start leaves in preStart. Carry the sender ID in the message (`Wave(from: Int)`). Keep an explicit phase (Collecting / SentTo(parent) / Decided) and ignore or reject a Start or Wave that is not valid for the current phase.
- Verifier: The code is as quoted. Kicks and protocol waves are both `Wave`, and sendWave has no phase guard. A leaf that gets a Wave from its neighbor before its own kick decides alone (no unreceived neighbors), sends Info to that neighbor, and the neighbor never decides. The late kick then comes from deadLetters/testActor, getSender returns -1, `received += (-1 -> true)` leaves unreceived empty, and the leaf decides a second time. But the main thread enqueues all kicks in one tight loop at TreeAlgorithm.scala:47. For the default topology (NetGraph_21-04-24-18-24-58.ngs.perturbed.dot), a leaf such as 13 receives a wave only after 3 gets waves from both 7 and 8, so a chain of several actor hops must finish before the main thread's next `!`. The bug is real, but it needs an unusual schedule.

### [medium/confirmed] System terminates on the first decision, so the second decision and the Info broadcast are cut off
- `src/main/scala/main/utility/Terminator.scala:28` (concurrency)
- Evidence: Terminator.scala:28-29 `case TreeTerminate => system.terminate()`. TreeProcess.scala:46-48 `case Decide => ... processRecord.map(-1) ! TreeTerminate`. TreeProcess.scala:66-71 sends `self ! Decide` and then the Info messages.
- Failure: In any tree, two processes decide. The first Decide makes the Terminator call system.terminate(). The actors stop while the Info messages are still in flight. In deep trees, most processes never receive Info, and the second decider can stop before it processes its own Decide. A visualizer that records the run sees an incomplete and nondeterministic trace.
- Fix: Detect termination when both deciders reported and every non-decider acknowledged Info (or reached a terminal phase). Count the expected reports (2 decides plus N-2 Info receipts) in the coordinator. Then terminate.
- Verifier: Terminator.scala:28-29 calls system.terminate() on the first TreeTerminate. TreeProcess.scala:66-71 sends `self ! Decide` before the Info sends, and the first Decide reaches the Terminator while Info is still in flight. The second decider and the Info broadcast race with system shutdown. The run is truncated and nondeterministic. The output is only log lines, so the impact is an incomplete trace, not a wrong decision. Medium, not high.

### [medium/confirmed] Algorithm logic is mixed with actor I/O and mutable vars; no pure step function exists to test or replay for a visualizer
- `src/main/scala/main/processes/TreeProcess.scala:56` (fp-design)
- Evidence: `def sendWave(): Unit = { val unreceivedNeighbors = neighbors.filterNot(received) ... processRecord.map(neighbor) ! Wave; parent = Some(neighbor) ... self ! Decide ...}`. The state is `var received`, `var parent`. The only output is log lines.
- Failure: You cannot unit-test the decision rule without starting actors. You cannot record or replay a run step by step, and the visualizer needs that. A run produces no structured event trace (who sent what, when, parent edges, deciders), only free-text logs that interleave between threads.
- Fix: Extract `def step(s: TreeState, m: TreeMsg): (TreeState, List[Effect])`, where Effect = Send(to, msg) | Decide | SetParent(p). Test it with plain property tests. Make the actor a thin interpreter, and also make a deterministic simulator that drives the step function under a chosen schedule and emits the event list to the visualizer.
- Verifier: TreeProcess.scala:18-19 hold state in vars. sendWave (56-74) mixes the decision rule with `!` sends and logging. The only output is slf4j log lines. No pure transition function or structured event trace exists, and the visualizer goal needs one. This is accurate design feedback.

### [medium/confirmed] The test copies the topology and wiring by hand, so TreeAlgorithm.main and the input path are never tested
- `src/test/scala/TreeTest.scala:24` (test-quality)
- Evidence: Lines 24-54 build 13 actors, populate ProcessRecord, and send `process5 ! Wave` ... `process13 ! Wave` by hand. TreeAlgorithm's leaf detection (`neighbors.size == 1`, line 46), its TopologyReader use and its Terminator are not exercised.
- Failure: A regression in TreeAlgorithm.scala:46 (for example, kicking every node or the wrong nodes) or in readTopology (duplicated neighbors, tree-3) leaves this test green. The test also keeps an outdated copy of the topology that can drift from the .dot file.
- Fix: Separate wiring from running: a pure `buildNetwork(Graph): Either[Err, Network]` plus a `run(network): Future[Trace]`. Test both on the same parsed graph that production uses.
- Verifier: TreeTest.scala:24-54 hand-builds the 13 actors and the kicks. TreeAlgorithm.main, readTopology, leaf detection (line 46) and the Terminator are never exercised. Today the hand copy matches the .dot file, but nothing ties the two together.

### [low/confirmed] Only cycles are rejected; disconnected forests, isolated nodes and duplicated edges pass the check
- `src/main/scala/main/algorithms/TreeAlgorithm.scala:29` (algorithm-correctness)
- Evidence: TreeAlgorithm.scala:29 `if (TopologyReader.hasCycle(processConfig))` is the only validation. TopologyReader.scala:19 `.filter(line => line.contains("->"))` drops node-only lines. TopologyReader.scala:25 emits `List((from, to), (to, from))` with no dedup. TopologyReader.scala:55 `if (neighbor == parent)` skips every copy of a duplicated parent edge.
- Failure: (a) Forest {0-1, 2-3}: hasCycle is false. Each component has its own two deciders, and the first TreeTerminate kills the system. That is four 'decisions' for one wave. (b) A file with both `"0" -> "1"` and `"1" -> "0"`: each node gets neighbors List(1,1) / List(0,0). hasCycle misses the multi-edge, and `neighbors.size == 1` at line 46 finds no leaves. Nothing starts, and `Await.ready(system.whenTerminated, 30.seconds)` throws TimeoutException after 30 s with the ActorSystem still running. (c) A single isolated node (the textbook says it decides at once): the reader never creates the node, so the map is empty and the run times out.
- Fix: Parse into an undirected graph type with deduplicated edges (Set[(Int,Int)] normalized). Keep isolated nodes. Validate that the graph is a tree with a pure function (connected and |E| = |V|-1) that returns Either[TopologyError, Tree]. Handle the 1-node case.
- Verifier: (a) is true: hasCycle on the forest {0-1, 2-3} returns false, the run produces two decider pairs, and the first TreeTerminate stops the system. (c) is true: a node-only line has no `->`, so line 19 drops it. A 1-node graph gives an empty map, no leaves, and a 30 s timeout. (b) is WRONG: for `0->1` plus `1->0`, node 0 gets neighbors List(1,1). dfs(0,"") visits 1 and returns, then sees the second `1` already visited and returns true. hasCycle reports a CYCLE, and the algorithm aborts with the 'contains a cycle' error. There is no hang and no timeout, only a misleading error. The default input file is a valid tree, so only hand-made files trigger these cases.

### [low/confirmed] Await.ready throws TimeoutException and leaves the ActorSystem running
- `src/main/scala/main/algorithms/TreeAlgorithm.scala:49` (crash)
- Evidence: `Await.ready(system.whenTerminated, 30.seconds)`. Main.scala:67-69 catches `case e: Exception => e.printStackTrace()`.
- Failure: In any run where no TreeTerminate arrives (tree-3 b/c), or where the run takes more than 30 s, Await.ready throws scala.concurrent.TimeoutException. Main prints a stack trace and shows the menu again. The old ActorSystem 'TreeAlgorithm' and its threads keep running. Each later attempt leaks another system.
- Fix: Use Await.result inside Try, or a Future with a timeout. On timeout, call system.terminate() in a finally block and return a typed failure result (Either) to the caller.
- Verifier: TreeAlgorithm.scala:49 `Await.ready(system.whenTerminated, 30.seconds)` throws TimeoutException when no TreeTerminate arrives. Main.scala catches `case e: Exception => e.printStackTrace()`, and nothing terminates the ActorSystem. A new ActorSystem("TreeAlgorithm") starts on each retry, so the systems leak. The trigger needs a bad input (the forest/1-node cases of tree-3) or the tree-1 race, so the severity is low.

### [low/confirmed] Sender identity comes from a reverse lookup in a shared mutable map; unknown senders silently become id -1
- `src/main/scala/main/processes/TreeProcess.scala:82` (concurrency)
- Evidence: `processRecord.map.find(_._2 == actorRef).map(_._1).getOrElse(-1)`. ProcessRecord.scala:11 `val map = mutable.Map.empty[Int, ActorRef]` is shared by all actors and the main thread. TreeAlgorithm.scala:37-38 writes to the map while the actors already exist.
- Failure: An unrecognized sender (deadLetters from the main thread, the TestKit testActor, or a restarted or forwarded ref) does not cause an error. The process records `received += (-1 -> true)` and continues with corrupted state. Each lookup is an O(N) scan on every message. The non-thread-safe HashMap is safe here only because the writes happen before the first `!` (which gives a happens-before edge). Any later write, for example a dynamic topology for the visualizer, is a data race.
- Fix: Put the sender ID in the message (`Wave(from: Int)`, `Info(from: Int)`). Give each actor an immutable Map[Int, ActorRef] of its neighbors, through the constructor or one `Init(neighbors)` message. Remove the global ProcessRecord.
- Verifier: TreeProcess.scala:83 does an O(N) reverse lookup in the shared mutable map with `.getOrElse(-1)`. The quoted code is exact. The data-race part does not occur today: all map writes (TreeAlgorithm.scala:38, 43; TreeTest.scala:39-47) happen before the first `!`. Constructors do not read the map. The -1 fallback is the mechanism behind the tree-1 double decision, but alone it is harmless, because -1 is never in `neighbors`. This is a valid design finding, at low severity.

### [low/refuted] Unchecked Map.apply on neighbor ids; with default supervision, a crash restarts the actor and resets its state
- `src/main/scala/main/processes/TreeProcess.scala:61` (crash)
- Evidence: `processRecord.map(neighbor) ! Wave` (also lines 40, 41, 48, 60, 63, 69, 70). State is held in `var received` / `var parent` (lines 18-19).
- Failure: A process lists a neighbor whose actor is not registered, for example a typo in the topology or a lookup before registration. The process throws NoSuchElementException. The default supervisor restarts it, and `received` resets to all-false and `parent` to None, with no error shown to the user. The process then waits forever for waves that already arrived, and the run times out (tree-4).
- Fix: Resolve all neighbor refs once at construction time and fail fast before the start. Model state immutably with context.become(behavior(state)), or use Akka Typed Behaviors, so that a restart is a deliberate decision.
- Verifier: readTopology emits both (from,to) and (to,from) for each edge, so every neighbor id is also a key. TreeAlgorithm.scala:36-38 creates and registers an actor for every key before any message goes out (line 47). A typo in the topology creates a new node. It cannot leave a dangling neighbor. Neither the production path nor the test path can make processRecord.map(neighbor) throw. The restart-resets-state point is true in general, but the stated failure cannot happen.

### [low/confirmed] Empty case classes are sent and matched through their companion objects; an instance `Wave()` would go to dead letters without an error
- `src/main/scala/main/utility/MessageTypes.scala:20` (fp-design)
- Evidence: `case class Wave() extends MessageTypes`, `case class Info()`, `case class Decide()`. The code sends `! Wave` / `! Info` / `self ! Decide` and matches `case Wave =>` (TreeProcess.scala:27, 33, 46). `trait MessageTypes` is not sealed, and `object TreeAlgorithm extends MessageTypes` (TreeAlgorithm.scala:15).
- Failure: The code works only because every site uses the companion object (type Wave.type, which is a Function0). A contributor who writes the natural `ref ! Wave()` gets an unhandled message that goes to deadLetters with no error. The algorithm then hangs until the 30 s timeout. The unsealed trait and untyped `Receive` give no exhaustiveness check, and the algorithm object itself is a 'message type'.
- Fix: Define a sealed trait TreeMsg with `final case class Wave(from: Int)`, `final case class Info(from: Int)`, and `case object Start`. Use Akka Typed `Behavior[TreeMsg]` so that the compiler checks the protocol. Do not extend the message trait from TreeAlgorithm.
- Verifier: MessageTypes.scala:20,22,23 define `case class Wave()`, `Info()` and `Decide()`. All send and match sites use the companion objects (`! Wave`, `case Wave =>`). These sites are consistent, so the code works today. `ref ! Wave()` would not match, and Akka classic publishes it as an UnhandledMessage on the event stream (not deadLetters). The algorithm would then hang. The trait is not sealed, and `object TreeAlgorithm extends MessageTypes` is real (TreeAlgorithm.scala:15). This is a valid FP/typing smell, and the failure is hypothetical.

### [low/confirmed] Two of the three 'TreeProcess' tests test TopologyReader.hasCycle and skip the cases that break the tree algorithm
- `src/test/scala/TreeTest.scala:60` (test-quality)
- Evidence: `"detect a cycle in the topology" in { ... TopologyReader.hasCycle(processConfig) shouldBe true }` and `"not detect a cycle in a valid topology"` (line 71) are under `"TreeProcess" should`.
- Failure: The tests do not cover forests, duplicated or bidirectional edges, self-loops, isolated nodes or the empty graph. These are the inputs that cause tree-3. hasCycle on a forest returns false, and the suite gives no signal that a forest is not a valid tree input.
- Fix: Move these tests to a TopologyReaderSpec. Add a tree-validation function and table-driven or property tests for forest, multi-edge, self-loop, 1-node and empty inputs.
- Verifier: TreeTest.scala:60-89 test only TopologyReader.hasCycle on a 4-cycle and on the default tree, under the heading "TreeProcess" should. The tests do not cover forest, multi-edge, self-loop, isolated-node or empty inputs.

### [low/confirmed] The topology reader leaks the file handle and does imperative parsing with no error channel
- `src/main/scala/main/utility/TopologyReader.scala:16` (fp-design)
- Evidence: `val topologyLines = Source.fromFile(filename).getLines().toList` (the Source is never closed). Line 31 `.mapValues(_.map(_._2).toList)` (deprecated in 2.13). TreeAlgorithm.scala:37 `id.toInt` / `neighbors.map(_.toInt)` throw NumberFormatException on non-numeric DOT ids. hasCycle uses a mutable Set and non-local `return` inside foreach (lines 42, 59, 62).
- Failure: A DOT file with node names like "A" makes TreeAlgorithm throw NumberFormatException, and Main catches it generically. Each run leaks one open file descriptor. The non-local return inside foreach depends on exceptions to exit early, and the recursion depth is linear in path length (stack overflow on very long paths).
- Fix: Use scala.util.Using(Source.fromFile(f))(...). Parse to Either[ParseError, Graph[Int]] with the ID conversion inside the parser. Write cycle and connectivity checks as pure folds (union-find, or BFS over immutable sets).
- Verifier: TopologyReader.scala:16 never closes the Source, and line 31 `.mapValues` is deprecated in 2.13. hasCycle uses a mutable Set and a non-local `return` inside foreach (lines 59, 62), and the recursion is unbounded. One correction: on a non-numeric id, the NumberFormatException comes from TreeAlgorithm.scala:38 on the main thread. The `Props(new ...)` at line 37 is by-name, so it fails inside the actor instead. Main catches the main-thread exception in its first branch `case _: NumberFormatException` and prints the misleading text 'Invalid input. Please enter a valid integer.', not a generic stack trace. The ActorSystem also leaks in that case.

## bracha-toueg

Static review of the Bracha-Toueg implementation. BrachaTouegProcess.scala breaks the textbook algorithm in four ways. (1) A node with no outgoing requests sends DONE after its first ACK, not after all ACKs. Its grant is then not complete, so the initiator can report a false deadlock on the deadlock-free scenario. (2) grant() can run twice: once when GRANTs arrive before NOTIFY, and again from notify. The second GRANT reduces an in-neighbour's request count twice, which can give a false "No Deadlock found". (3) The GRANT handler sends no ACK when requests is already 0, so the granter never finishes. (4) An initiator with no edges never decides, and Await.ready then throws a TimeoutException. The verdict only goes to a debug log line and the initiator sends TerminateSystem again on every later ACK. The deadlock scenario in application.conf has In lists that do not match the Out lists. That scenario gives the right verdict only because no node ever grants. The 4 tests check only the first messages that one actor sends. None of them checks the verdict, the GRANT/ACK handling, or the multi-ACK DONE rule. The last test sends TerminateSystem, which shuts down the shared TestKit ActorSystem. For FP design, the process is a mutable-var actor that reads a shared mutable map. Its messages are case-class companions, and its result exists only as log output. Nothing in it is a pure state machine that a visualizer could replay. Note: the code at lines 101 and 117 is processRecord.map(sid), not map.get(..).get; it throws the same NoSuchElementException.

### [high/plausible] Node with empty Out sends DONE after the first ACK, before its grant is complete
- `src/main/scala/main/processes/BrachaTouegProcess.scala:78` (algorithm-correctness)
- Evidence: case Ack =>
      ack -= 1
      ...
      sendAck()
      sendDone()
...
private def sendDone() {
    if (done == 0) {   // no check of ack == 0
- Failure: In Fokkink, notify(u) calls grant(u) when requests_u = 0, and grant(u) awaits an ACK from every w in In_u. So u sends DONE to its parent only after all those ACKs arrive. Here a node with out = [] has done = 0 from the start, so the first Ack calls sendDone and sends Done. Example from DeadlockFreeTestData: node 3 (Out=[], In=[0,2]) grants to 0 and 2. Node 0 has requests 2, so it ACKs at once, and node 3 sends Done to 0. Node 2 does not ACK yet, because 2 is still waiting for 1's grant to finish. Meanwhile the Done chain 1->2->3 (already notified)->2->1->0 completes. Node 0 then reaches done == 0 before Grant(1) arrives, with requests = 1 and free = false. It logs "Process is Deadlocked!!" on a topology that has no deadlock.
- Fix: Track the grant phase separately. Send DONE to the parent only when done == 0 AND (grant was not started inside notify OR all ACKs arrived). The simplest correct rule for N-out-of-N is to require done == 0 && (out.nonEmpty || ack == 0).
- Verifier: The code is as quoted. done = out.size = 0 for an empty Out. The Ack handler (lines 78-82) calls sendDone() with no check of ack == 0, so the node sends Done after the first Ack. Fokkink waits for every ACK first. The reported failure on DeadlockFreeTestData does NOT happen, because Akka keeps message order between one pair of actors. 3 sends Grant(3) to 2 before Done(3) to 2. So 2 sends Grant(2) to 1 before Done(2), and 1 sends Grant(1) to 0 before Done(1). Node 0 therefore always gets Grant(1) before its last Done. The bug does give a wrong verdict on a different topology with an unusual interleaving: 0->W, 0->X, W->Y, W->Z, X->Y, Y->Z (a DAG with no deadlock; Z has Out=[] and In=[W,Y]). Z acks nothing itself, W acks Z at once (requests 2->1), and Z sends an early Done to W. Notify(X) reaches Y before Grant(Z), so Y sends Done to X before Grant(Y). Node 0 then gets Done(X) and Done(W) before Grant(X) and logs Deadlocked. The correct ACK wait would delay Z's Done until 0 has acked X.

### [high/plausible] grant() can run twice, so a node receives two GRANTs from one neighbour and becomes free too early
- `src/main/scala/main/processes/BrachaTouegProcess.scala:104` (algorithm-correctness)
- Evidence: private def notifyProc(): Unit = {
    notifyFlag = true
    ...
    if (requests == 0) {
      ...
      grant()
    }
...
        if (requests == 0) {
          ...
          grant()
- Failure: A node can receive all its GRANTs before its first NOTIFY, because NOTIFY and GRANT come from different senders and Akka gives no ordering across senders. Topology: 0 (initiator) Out=[2,1,3]; 1 Out=[2], In=[0]; 2 Out=[], In=[0,1]; 3 Out=[4], 4 Out=[3] (a cycle, so 3 never grants). Node 2 receives Notify(0) and sends Grant(2) to 1 before Notify(0) reaches 1. Node 1: requests -> 0, grant() sends Grant(1) to 0. Then Notify(0) arrives at 1 and notifyProc sees requests == 0, so it calls grant() again and sends a second Grant(1) to 0. Node 0 (requests 3) counts Grant(2), Grant(1) and Grant(1), sets free = true and logs "No Deadlock found". Node 0 is in fact deadlocked, because it waits on node 3.
- Fix: Guard grant with the free flag: call grant only if !free. Better, keep a Set of granters and decrement requests only once per distinct sender.
- Verifier: The code is as described. Grant (lines 57-62) calls grant() when requests reaches 0 and does not check notifyFlag. Then notifyProc (lines 104-106) calls grant() again because requests == 0, and nothing guards it with free. Grant(2) and Notify(0) reach node 1 from different senders, so Akka does not order them, and the race in the reported topology is realistic. Node 0 then counts Grant(1) twice, reaches requests 0, sets free = true and logs "No Deadlock found", but node 0 waits on the 3<->4 cycle. The topology is not in application.conf, and the result needs that interleaving, so the verdict is plausible.

### [high/confirmed] Tests check only the first outgoing messages and never check the deadlock verdict
- `src/test/scala/brachaTouegTest.scala:18` (test-quality)
- Evidence: process2.expectMsg(Notify(1))
process3.expectMsg(Notify(1))
...
process2.expectMsg(Grant(1))
...
process1.expectMsg(Done)
- Failure: The 4 tests check: Notify after Initiate (lines 40-41); Notify after Notify (68-69); Grant to In when Out is empty (94-95); Done after a single Ack (117-123). No test sends a Grant to a node with requests > 0. No test checks requests decrement, Ack, deferred Ack, Done to an already-notified sender, or the initiator's free/deadlocked verdict. No test runs a whole topology or the two scenarios. An implementation with bracha-toueg-1, -2, -3, -4 or -5 passes all 4 tests. An implementation that always logs "No Deadlock found" also passes, and so does one that never sends Ack.
- Fix: Make the verdict observable: a reply to a probe, or a pure step function that returns the outputs. Add whole-topology tests that assert the verdict for the two config scenarios, a cycle, an empty-edge initiator and the early-grant race topology from bracha-toueg-2. Add unit tests for Grant -> Ack, deferred Ack and the duplicate Grant.
- Verifier: brachaTouegTest.scala has 4 tests, and they check only Notify after Initiate (40-41), Notify after Notify (68-69), Grant when Out is empty (94-95) and Done after one Ack (117-123). No test checks Grant->Ack, a decrement, a deferred Ack, Done to an already-notified sender, or the verdict, which exists only as logger.debug. No test runs a whole topology. Implementations that have bugs 1, 2, 4 or 5, or that always log "No Deadlock found", pass all 4 tests.

### [medium/plausible] Initiator with no Out and no In never decides; Await.ready throws and the ActorSystem leaks
- `src/main/scala/main/algorithms/BrachaTouegAlgorithm.scala:53` (crash)
- Evidence: Await.ready(system.whenTerminated, 30.seconds)
...
(process) var done = out.size ... sendDone() is called only from the Done and Ack handlers
- Failure: Configure initiator = 0 with Node 0 Out=[] In=[]. Initiate calls notifyProc, which calls grant(). grant() sends nothing, so no Done or Ack ever arrives and sendDone is never called. The verdict "No Deadlock found" is never reached. After 30 s, Await.ready throws java.util.concurrent.TimeoutException. scenario2WithDeadlock never runs, and the first ActorSystem is never terminated. Main catches the exception, prints a stack trace and returns to the menu with live actor threads. The same timeout happens for every hang in bracha-toueg-1 to bracha-toueg-3.
- Fix: Call the completion check at the end of notifyProc as well, so an empty Out with an empty In decides at once. Make the run return a Future[Verdict]. Terminate the system in a finally block, or use Await.result with explicit failure handling.
- Verifier: This is confirmed by tracing. With Out=[] and In=[], notifyProc calls grant(), which sends nothing. sendDone runs only from the Done handler (line 74) and the Ack handler (line 82), so the initiator never decides. Await.ready (line 53) throws TimeoutException after 30 s. Main.scala lines 67-69 catch the exception and print the stack trace. The system is never terminated and scenario2 does not run. Neither config topology has this shape: it needs an edited config. The cascade claim that bracha-toueg-3 causes a timeout does not hold.

### [medium/confirmed] Last test can never detect the early-DONE bug and terminates the shared TestKit ActorSystem
- `src/test/scala/brachaTouegTest.scala:105` (test-quality)
- Evidence: system.actorOf(Props(new BrachaTouegProcess(2, system, List(), List(1), initiator = true, processRecord)))
val terminator = system.actorOf(Props(new Terminator(system)))
...
process2 ! Ack
process1.expectMsg(Done)
- Failure: In = List(1) has only one element, so "Done after the first Ack" and "Done after all Acks" act the same. The test cannot detect bracha-toueg-1. With In = List(1, 3) and one Ack, expectNoMessage would expose the bug. Because process2 is initiator = true, sendDone also sends TerminateSystem to a Terminator that holds the TestKit's own system, and system.terminate() runs while the suite is active. Every test added after this one, or a reorder, then fails with a terminated system. Tests 1 and 2 also set initiator = true on a node that only receives Notify, and they leave the actors running.
- Fix: Use In with two or more nodes and assert expectNoMessage until the last Ack. Inject a TestProbe in place of Terminator so that no test can stop the shared system. Use initiator = false where the node is not the initiator.
- Verifier: Test 4 (line 105) uses In=List(1), so it cannot tell "Done after the first Ack" from "Done after all Acks". Its node has initiator = true, so sendDone sends TerminateSystem to a real Terminator(system), and Terminator.scala line 17 calls system.terminate() on the shared TestKit system. The suite does not fail now only because this test is the last one in declaration order. Tests 1-3 also set initiator = true on nodes and leave the actors running.

### [medium/confirmed] Algorithm logic is mutable actor state with side effects; there is no pure model that a visualizer could replay
- `src/main/scala/main/processes/BrachaTouegProcess.scala:23` (fp-design)
- Evidence: class BrachaTouegProcess(val id: Int, val system: ActorSystem, val out: List[Integer], val in: List[Integer], val initiator: Boolean, val processRecord: ProcessRecord) extends Actor {
  var notifyFlag = false
  var free = false
  var requests = out.size
  ...
  var neighborNotify = mutable.Stack[Int]()
- Failure: The process keeps six vars and two mutable stacks, uses java.lang.Integer lists, and takes an unused ActorSystem. It sends through a shared mutable.Map (ProcessRecord) that the main thread fills after the actors exist. Every message handler both changes state and sends messages, so you cannot unit-test a transition, record a trace, or step a run forward and back for a visualizer. The verdict exists only as a logger.debug line (lines 121-123), mixed with println output (lines 60, 133). No caller or test can read it.
- Fix: Model each node as an immutable case class NodeState and write step(state, msg): (NodeState, List[Outgoing]) as a pure function. Model the verdict as an ADT (Deadlocked | Free). Keep a thin actor (or a pure scheduler in tests and in the visualizer) that applies step and records each event. The visualizer can then replay that event log, with deterministic and random delivery schedules.
- Verifier: Lines 23-30 show the mutable vars, the mutable.Stack fields, List[Integer], an unused system parameter and the shared mutable.Map ProcessRecord. Every handler mixes state changes and sends. The verdict exists only as logger.debug (lines 121-123), with println at lines 60 and 133. No caller can read the verdict, and nobody can replay a run. This finding is accurate and relevant to the user's goal of a visualizer.

### [low/refuted] GRANT that arrives when requests is already 0 gets no ACK, so the granter waits forever
- `src/main/scala/main/processes/BrachaTouegProcess.scala:57` (algorithm-correctness)
- Evidence: case Grant(nid) =>
      ...
      if (requests > 0) {
        ...
        sendAck()
      }
      // no else branch: no Ack sent
- Failure: In Fokkink, upon GRANT from w the node always sends ACK to w; the condition requests_u > 0 guards only the decrement. Here an extra GRANT (for example the duplicate from bracha-toueg-2, or any duplicate delivery) is dropped without an Ack. The granter's ack counter never reaches 0. So it never ACKs its own granter, and, if its Out is empty, it never sends DONE. The DONE/ACK chain stalls, the initiator never decides, and Await.ready(system.whenTerminated, 30.seconds) throws a TimeoutException.
- Fix: Always reply with ACK: if requests > 0, decrement and possibly start grant, and defer the ACK only while that grant is in progress. Otherwise send Ack to nid at once.
- Verifier: The missing else branch is real, and it differs from Fokkink. But the stall does not follow. A Grant that arrives with requests == 0 can only come from a duplicate grant (bracha-toueg-2), because each Out neighbour grants once and requests starts at out.size. The granter that sent the duplicate counts ack = in.size, which is one ACK per In node, not one per Grant message. The first Grant already brought it an Ack, so ack reaches 0 and sendAck/sendDone run. Dropping the duplicate's Ack stops ack from going negative and does not cause a hang. No realistic input triggers the described stall.

### [low/plausible] Initiator logs the verdict and sends TerminateSystem again on every later ACK
- `src/main/scala/main/processes/BrachaTouegProcess.scala:119` (concurrency)
- Evidence: case Ack =>
      ...
      sendDone()
...
      if (initiator) {
        if (free) { logger.debug("No Deadlock found") } else { logger.debug("Process is Deadlocked!!") }
        processRecord.map(-1) ! TerminateSystem
      }
- Failure: The initiator has Out non-empty and In non-empty, and it becomes free. Its Done count reaches 0 and it decides. Then each ACK for its own grant calls sendDone() again. done is still 0, so the initiator logs the verdict again and sends another TerminateSystem. The log shows the verdict several times, and the extra messages go to a Terminator that is stopping, which gives dead-letter noise. With bracha-toueg-1, the initiator with an empty Out decides on the first ACK and then decides again on each later ACK.
- Fix: Add a decided/terminated flag, or move to a terminal behavior (context.become) after the decision. Emit the verdict once, as a message or a Promise.
- Verifier: The Ack handler calls sendDone() every time, and the initiator branch (lines 119-126) has no decided flag. So each later Ack logs the verdict again and sends another TerminateSystem. In both config scenarios, initiator 0 has In=[], so it never grants and never receives an Ack, and the bug does not occur. It needs an initiator with a non-empty In that becomes free. The result is only duplicate log lines plus extra terminate() calls or dead letters, because terminate() is idempotent. The severity is low, not medium.

### [low/confirmed] DeadlockTestData In lists do not match the Out lists
- `src/main/resources/application.conf:20` (other)
- Evidence: Node = 0 ... In = []   (but Node 3 has Out= [0])
Node = 2 ... Out= [3]  In = []   (but Node 1 has Out= [2])
- Failure: An edge u->w (w in Out_u) requires u in In_w. Here 1->2 has no 1 in In_2, and 3->0 has no 3 in In_0. The scenario gives the correct "Deadlocked" verdict only because no node ever has requests = 0, so no GRANT is ever sent. Edit the data so that one node grants: that node then sends GRANTs only to its listed In nodes. The requesters that are missing from that list never get the GRANT and stay blocked, so the verdict is wrong. createTestData has no validation.
- Fix: Derive In from Out (In_w = {u | w in Out_u}) instead of storing both. Alternatively, validate the topology in createTestData and reject inconsistent input.
- Verifier: application.conf lines 8-27 show that Node 0 has In=[] although Node 3 has Out=[0], and Node 2 has In=[] although Node 1 has Out=[2]. createTestData (BrachaTouegAlgorithm.scala lines 89-95) does not validate the data. The current verdict is still correct, because every node has a non-empty Out, so no node ever grants. This is a latent data defect, so the severity is low.

### [low/plausible] Unchecked map lookups crash the actor, and the restart silently resets all algorithm state
- `src/main/scala/main/processes/BrachaTouegProcess.scala:51` (crash)
- Evidence: processRecord.map.get(nid).get ! Done      // line 51
processRecord.map.get(nid).get ! Ack       // line 64
processRecord.map.get(sid).get ! Grant(id) // line 92
processRecord.map(sid) ! Done              // line 117
- Failure: Send Notify from an id that is not in the record, as test 2 does with Notify(0). Node 0 is pushed onto neighborNotify, and sendDone later throws NoSuchElementException at line 117. The default supervisor restarts the actor, which re-runs the constructor. notifyFlag, requests, done and ack go back to their initial values, and the pending Done to the parent is lost. The algorithm then hangs or double-counts, and the only sign is an error log line.
- Fix: Give each process its neighbours as ActorRefs (Map[Int, ActorRef], built once and immutable) when it starts. Reject unknown senders explicitly instead of throwing.
- Verifier: The quoted lookups are at lines 51, 64, 92, 117 and 136, and the default supervisor restart would reset the vars. The test-2 scenario does not throw: process1 has out=[2,3] TestProbes that never send Done, so done never reaches 0 and line 117 never runs. In production, createTestData registers every id from the config, and the terminator (-1) is put into the map before Initiate is sent. So no realistic run crashes. This is a robustness and design issue, not a bug that occurs now.

### [low/confirmed] Parameterless messages are case classes but the code sends and matches their companion objects
- `src/main/scala/main/utility/MessageTypes.scala:8` (fp-design)
- Evidence: case class Initiate() extends MessageTypes
case class Done() extends MessageTypes
case class Ack() extends MessageTypes
...
processRecord.map(sid) ! Done   /  case Done =>
- Failure: The code works only because the sender and the receiver both use the companion object (Done.type, which is a Function0), not an instance. A future change that sends Done() fails to match case Done. The message is then unhandled and goes to dead letters, and the algorithm hangs with no compile error. MessageTypes is also not sealed, so the compiler cannot report the missing cases in receive.
- Fix: Declare a sealed trait BrachaTouegMsg per algorithm, with case objects Initiate/Done/Ack and case classes Notify(from)/Grant(from). Use Akka Typed Behavior[BrachaTouegMsg] so that the compiler checks the message types.
- Verifier: MessageTypes.scala lines 6-14 declare a trait that is not sealed and empty-parameter case classes (Initiate(), Done(), Ack(), TerminateSystem()). The process and the Terminator send and match the companion objects (for example `! Done` and `case Done =>`). This works today only by convention, and a later `Done()` would silently fall through to unhandled.

### [low/confirmed] scenario1 and scenario2 are copy-paste; run returns Unit and only N-out-of-N is supported
- `src/main/scala/main/algorithms/BrachaTouegAlgorithm.scala:27` (fp-design)
- Evidence: def scenario1NoDeadlock(): Unit = { ... ApplicationProperties.deadlockFreeData ... }
def scenario2WithDeadlock(): Unit = { ... ApplicationProperties.deadlockData ... }
(process) var requests = out.size
- Failure: The two scenarios differ only in the config key, so a fix to one is easy to miss in the other. Neither returns the verdict, so no caller (Main, a test or a visualizer) can use the result. requests = out.size hard-codes the N-out-of-N model, but Fokkink's Bracha-Toueg allows an n-out-of-m request (requests_u = n_u). The OR model and k-out-of-m deadlocks cannot be expressed. Procedure syntax def main() { is deprecated in 2.13.
- Fix: Write one def run(topology: Topology, initiator: Int): Future[Verdict]. Parse the config into an immutable Topology with a per-node requestCount. Call run from both scenarios and from the tests.
- Verifier: scenario1NoDeadlock (lines 27-55) and scenario2WithDeadlock (lines 58-85) are the same code except for the config key. Both return Unit. requests = out.size (process line 26) hard-codes the N-out-of-N model. `def main() {` at line 19 uses procedure syntax, which is deprecated in Scala 2.13.

## chang-roberts

I read the whole Chang-Roberts implementation, which is `/Users/dhruv/Desktop/CS553-DistributedAlgorithms/src/main/scala/main/algorithms/ChangRobertsAlgorithm.scala` and `/Users/dhruv/Desktop/CS553-DistributedAlgorithms/src/main/scala/main/processes/ChangRobertsProcess.scala`. I also read the shared files it uses: Terminator, ProcessRecord, MessageTypes, ApplicationProperties, Main and the application.conf ring. No test for this algorithm exists.

The bugs that give wrong results:
- **chang-roberts-1:** Initiate does not check the state of the process. With the shipped config, the max node can send its UID two times, so the leader is elected two times and the system is terminated two times.
- **chang-roberts-2:** The branch that forwards a larger UID does not mark the process as participant, and announceLeader resets the leader to non-participant. Together, a late smaller bid can start a full second election.
- **chang-roberts-3:** The code implements the 'participant/substitute' variant, not Fokkink's rule. Thus a non-initiator with the max UID wins, and Fokkink's winner (the max initiator) does not.

The runtime bugs:
- **chang-roberts-4:** The Terminator (key -1) is in the pool of possible initiators. In about 4% of runs, nobody starts the election. Await.ready then throws TimeoutException after 30 s and the ActorSystem leaks.
- **chang-roberts-5:** The code does not validate the ring, the neighbor ids or the uniqueness of the UIDs. The result is a hang after an exception in the actor, or two leaders.

The design problems:
- **chang-roberts-6:** Messages are zero-arg case classes that the code sends and matches as companion objects.
- **chang-roberts-7:** The result goes only to a Unit return and an unread private var. Thus a test or a visualizer cannot see the leader or the message trace, and the unseeded Random makes runs impossible to reproduce.
- **chang-roberts-8:** All actors share one mutable map.
- **chang-roberts-9:** No test exists, and the current design makes the algorithm untestable.

The fix I recommend is a pure step function `(NodeState, Msg) => (NodeState, List[Send])`. A deterministic simulator runs it and produces an event trace for the visualizer and for property tests. The Akka actor becomes a thin, typed shell that calls the step function.

I did a static review only. No JDK or sbt is installed, so I compiled and ran nothing.

### [high/confirmed] The Terminator (key -1) is put in the map before initiator selection, so it can be chosen as the only initiator; then the election never starts and Await throws after 30 s
- `src/main/scala/main/algorithms/ChangRobertsAlgorithm.scala:50` (crash)
- Evidence: record.map.put(-1, terminator)
...
val numNodes = record.map.size
val numInitiators = Random.nextInt(numNodes) + 1
val randomInitiators = Random.shuffle(record.map.keys.toList).take(numInitiators)
randomInitiators.foreach { initiatorId =>
  record.map(initiatorId) ! Initiate
}
...
Await.ready(system.whenTerminated, 30.seconds)
- Failure: With 4 processes, numNodes is 5. numInitiators == 1 has probability 1/5, and the shuffle then puts -1 first with probability 1/5. So in 4% of runs the only Initiate goes to the Terminator, which has no Initiate case (an unhandled message, silently dropped). No process starts. Await.ready throws TimeoutException after 30 s. The ActorSystem is never terminated, so its threads leak. Main catches the exception and prints a stack trace. In other runs, -1 uses up one initiator slot and the initiator count is off by one.
- Fix: Choose initiators only from the process ids, before you add the terminator, or keep the terminator out of the process map. Make the initiator set an explicit input (or use a seeded RNG). Handle the timeout: use Await.result in a Try, call system.terminate() on failure, and return an Either/Try result.
- Verifier: Line 50 puts -1 -> terminator in the map before line 55 computes numNodes = record.map.size (5 with the shipped 4-node config). Line 61 shuffles all keys, -1 included. When the count is 1 and -1 comes first (probability 1/5 * 1/5 = 4%), the only Initiate goes to the Terminator. Terminator.scala has no Initiate case, so the message is unhandled. No election starts. Await.ready at line 69 throws TimeoutException after 30 s, and nothing calls system.terminate(). Main.scala:67-69 catches the exception and prints a stack trace. In other runs the terminator takes up one of the initiator slots. The finding is correct as reported.

### [high/confirmed] No test exists for Chang-Roberts, and the current design makes the algorithm untestable
- `src/main/scala/main/algorithms/ChangRobertsAlgorithm.scala:27` (test-quality)
- Evidence: There is no src/test file that references ChangRoberts. `def main(): Unit = { runAlgorithm() }` reads global ApplicationProperties.changRobertsData, uses unseeded Random, and returns Unit.
- Failure: Bugs chang-roberts-1 through chang-roberts-5 (double election, double termination, wrong winner vs. Fokkink, a 4% chance of no initiator, two leaders with duplicate UIDs) all ship undetected. Even a smoke test only checks that the run 'did not throw', and that passes when the wrong node wins.
- Fix: After the pure-core refactor, add property tests (ScalaCheck). For any valid ring, any non-empty initiator set and any interleaving: exactly one leader; the leader is the max-UID initiator (Fokkink variant); every node learns the same leader; the number of messages is at most n(n+1)/2 + n. Add example tests for the interleavings in chang-roberts-1 and chang-roberts-2.
- Verifier: src/test/scala holds only brachaTouegTest, chandyLamportTest, EchoTest, franklinTest, laiYangTest, TarryTest and TreeTest. None of them references ChangRoberts. main()/runAlgorithm read global config, use unseeded Random and return Unit, so a test cannot assert on the result. Given the user's goals, high is appropriate.

### [medium/plausible] The Initiate handler ignores the state of the process, so the leader UID circulates two times and the leader is elected two times
- `src/main/scala/main/processes/ChangRobertsProcess.scala:34` (algorithm-correctness)
- Evidence: case Initiate =>
      logger.info(s"Initiated by process $self")
      participant = true
      processRecord.map(neighbor) ! ElectionMessage(uid)
- Failure: The shipped config is ring 0(10)->1(20)->2(5)->3(15)->0. The random initiators are {0,1}. Main sends Initiate to node 0 first. Node 0 sends ElectionMessage(10) to node 1. Node 1 can get that message before its own Initiate, because the two messages come from different senders and Akka gives no order between them. Node 1 runs the branch `10 < 20 && !participant`, sets participant = true and sends ElectionMessage(20). Then Initiate arrives, and node 1 sends ElectionMessage(20) again. Two copies of 20 go around the ring. Node 1 runs announceLeader() two times, two ElectedMessage(20) rounds go around the ring, and node 1 sends TerminateSystem two times. The same bug applies when a process already forwarded a larger UID and then gets Initiate: it starts a stale bid. Fokkink says such an initiator is already passive and must not send its ID.
- Fix: Make Initiate do nothing when the process is already a participant or is already passive (for example: `case Initiate if state == Idle =>`). Model the state as an ADT (Idle | Candidate | Relay | Leader) and do not use a mutable Boolean.
- Verifier: The code matches the quote. ChangRobertsProcess.scala:34-42 sets participant = true and sends ElectionMessage(uid) with no check of state. The double bid needs a race. Main sends Initiate to every initiator in a tight loop (ChangRobertsAlgorithm.scala:64-66), so node 0 must handle Initiate and send ElectionMessage(10) before the main thread puts Initiate in node 1's mailbox. Akka gives no order between different senders, so this can happen, but it is unusual. If Initiate arrives first, node 1 drops 10 at line 63 and nothing goes wrong. The effect is real: two copies of 20 go around the ring, node 1 elects itself two times and sends TerminateSystem two times. The second TerminateSystem goes to dead letters after the system terminates, so the visible damage is extra messages and duplicate log lines, not a crash.

### [medium/plausible] Forwarding a larger UID does not set participant, and announceLeader resets the leader to non-participant, so a late smaller bid starts a second election
- `src/main/scala/main/processes/ChangRobertsProcess.scala:53` (algorithm-correctness)
- Evidence: if (senderUid > uid) {
        ...
        processRecord.map(neighbor) ! ElectionMessage(senderUid)
      } else if (senderUid < uid && !participant) {
        participant = true
        ...
        processRecord.map(neighbor) ! ElectionMessage(uid)
...
  private def announceLeader(): Unit = {
    participant = false
- Failure: Use the ring M(9) -> Q(1) -> P(5) -> M with initiators {M, Q}. M sends 9. Q gets 9 before its Initiate and forwards it, and Q stays non-participant. Then Q's Initiate sends 1 (see chang-roberts-1). P forwards 9 and stays non-participant (line 53 does not mark P). Then P gets 1, takes the `!participant` branch and sends 5. M gets 9 back, is elected, and announceLeader sets participant = false. Then M gets 5 and takes the `5 < 9 && !participant` branch, so M sends ElectionMessage(9) again. A second full election runs, M is elected again and TerminateSystem is sent again. In the participant variant of Chang-Roberts, a process that forwards a larger UID must become a participant. Fokkink also makes an active process passive when it forwards a larger ID. Without that mark, the message complexity also grows past the textbook bound, because every non-marked relay can later inject its own UID.
- Fix: Set the relay state (participant = true / passive) in the `senderUid > uid` branch. Do not reset the leader to non-participant in announceLeader. Put the leader in a terminal Leader state that ignores ElectionMessage.
- Verifier: The code matches the quote. Line 53-56 forwards a larger UID and does not set participant, and announceLeader at line 99 sets participant = false. I traced the given ring M(9)->Q(1)->P(5)->M. Per-pair FIFO holds, and M takes the `5 < 9 && !participant` branch after its election, so it starts a second election. But the scenario needs Q to get 9 before its own Initiate, which is the same race as in chang-roberts-1. I found no way to reach this without that race. A smaller bid can arrive at a relay after a larger one only if an initiator sends its UID after the larger UID passed it. So it is a real deviation from the participant variant, but it shows only under an unusual interleaving.

### [medium/confirmed] runAlgorithm returns Unit and the result is only in a private var; a test or visualizer cannot observe the leader or the message trace
- `src/main/scala/main/algorithms/ChangRobertsAlgorithm.scala:32` (fp-design)
- Evidence: def runAlgorithm(): Unit = { ... Await.ready(system.whenTerminated, 30.seconds) ... }
// ChangRobertsProcess.scala:22
var electedLeader: Option[Int] = None
- Failure: No code reads electedLeader. The only output is log lines. A test cannot assert which UID won, how many messages were sent, or that exactly one leader exists. A visualizer has no event stream to render. Also, initiator selection uses unseeded scala.util.Random, so you cannot reproduce a run.
- Fix: Separate a pure core from the runtime: `def step(state: NodeState, msg: Msg): (NodeState, List[Send])`. Write a deterministic simulator that folds over a scheduler (seeded or explicit interleaving) and returns `RunResult(leader, trace: Vector[Event])`. The Akka actor becomes a thin shell that calls step. The visualizer replays the trace.
- Verifier: runAlgorithm returns Unit (line 32). The code writes electedLeader (ChangRobertsProcess.scala:22, 69, 79) and never reads it. Initiator selection uses unseeded scala.util.Random (lines 58, 61). So no caller can get the leader, a trace or a reproducible run. This blocks both tests and the visualizer.

### [low/confirmed] The code implements the 'participant/substitute' variant, not Fokkink's Chang-Roberts, so the elected leader is not the initiator with the largest ID
- `src/main/scala/main/processes/ChangRobertsProcess.scala:57` (algorithm-correctness)
- Evidence: } else if (senderUid < uid && !participant) {
        participant = true
        logger.info(s"$self replacing UID in the message ...")
        processRecord.map(neighbor) ! ElectionMessage(uid)
- Failure: In Fokkink (Section 9.1), non-initiators are passive and forward every message, and the initiator with the largest ID becomes the leader. Here a non-initiator that gets a smaller UID replaces it with its own UID and joins the race. Take the shipped config with only node 2 (UID 5) as initiator. Fokkink elects 5. This code elects 20, which is a non-initiator, and it uses more messages. A visualizer that claims to show 'Chang-Roberts (Fokkink)' would show the wrong rule and the wrong winner.
- Fix: Implement the textbook rule: an initiator is Active, a non-initiator is Passive and only relays. An Active process purges q<p, becomes Passive and relays q>p, and becomes leader on q==p. If you also want the participant variant, give it a separate, explicitly named strategy.
- Verifier: Lines 57-62 make a non-initiator replace a smaller UID with its own UID. With only node 2 (UID 5) as initiator on the shipped ring, node 3 replaces 5 with 15, node 1 replaces 15 with 20, and node 1 (UID 20, not an initiator) wins. Fokkink's version would elect 5. The finding is correct as a deviation from the textbook. I lowered the severity because the code is the well-known Chang-Roberts variant where every process joins, and it still elects exactly one leader (the largest UID). It matters mostly for labels in a visualizer.

### [low/plausible] The topology and UIDs are not validated: a bad Neighbor id causes an exception in the actor and a hang, and duplicate UIDs give two leaders
- `src/main/scala/main/algorithms/ChangRobertsAlgorithm.scala:76` (crash)
- Evidence: val id = nodeData.getInt("Node")
val uid = nodeData.getInt("UID")
val neighbor = nodeData.getInt("Neighbor")
...
processRecord.map(neighbor) ! ElectionMessage(uid)   // ChangRobertsProcess.scala:42,56,62,83,103
- Failure: Example 1: Neighbor = 7 (no such node). processRecord.map(7) throws NoSuchElementException in receive. The default supervisor restarts the actor, the message is lost, and the run hangs until the 30 s TimeoutException. Example 2: Neighbor = -1 sends ElectionMessage to the Terminator, which drops it. Example 3: two nodes have the same UID. Each one sees `senderUid == uid` for its own bid and elects itself, so the result has two leaders. Example 4: the config is not a single cycle (for example 0->1, 1->0, 2->3, 3->2). Two leaders are elected, and the first ElectedMessage that returns terminates the system.
- Fix: Parse the config into a validated `Ring` value (Either[ConfigError, Ring]). Check that the UIDs are unique, that every neighbor exists, and that the successor function forms one cycle that covers all nodes. Give each actor its successor ActorRef directly, not an id lookup in a shared map.
- Verifier: The code reads Node, UID and Neighbor and does no validation (ChangRobertsAlgorithm.scala:78-80). The process looks up processRecord.map(neighbor) with apply, so a missing id throws NoSuchElementException. Duplicate UIDs make a second node see senderUid == uid and elect itself. But the shipped application.conf:61-86 holds a valid 4-node ring with unique UIDs. Every failure here needs a config that the user writes badly. It is a real robustness gap, but only for bad input.

### [low/plausible] Zero-arg case classes are sent and matched as companion objects; if code sends Initiate() or TerminateSystem(), the message is silently unhandled
- `src/main/scala/main/processes/ChangRobertsProcess.scala:34` (fp-design)
- Evidence: MessageTypes.scala:8  case class Initiate() extends MessageTypes
MessageTypes.scala:14 case class TerminateSystem() extends MessageTypes
ChangRobertsAlgorithm.scala:65  record.map(initiatorId) ! Initiate
ChangRobertsProcess.scala:34    case Initiate =>
ChangRobertsProcess.scala:92    processRecord.map(-1) ! TerminateSystem
Terminator.scala:15             case TerminateSystem =>
- Failure: The code works only because the sender and the receiver both use the companion object (a Function0), not a message instance. If a refactor or a test sends `Initiate()`, `case Initiate =>` does not match it. The actor drops the message as unhandled, the election never starts, and the run hangs for 30 s. The compiler gives no error, because `receive` is untyped (Any => Unit).
- Fix: Use `case object Initiate` / `case object TerminateSystem`. Better: define a sealed ChangRobertsMsg ADT and use Akka Typed Behavior[ChangRobertsMsg], so that the compiler checks the message protocol and the match exhaustivity.
- Verifier: MessageTypes.scala:8 and :14 declare `case class Initiate()` and `case class TerminateSystem()`. The sender and the receiver both use the companion object (ChangRobertsAlgorithm.scala:65, ChangRobertsProcess.scala:34/92, Terminator.scala:15), so the code works today. A grep shows that no code sends `Initiate()` or `TerminateSystem()`. The failure happens only if a future change sends an instance. This is a real design smell, but it causes no bug now.

### [low/plausible] A shared, non-thread-safe mutable.Map is captured by every actor and mutated by the main thread
- `src/main/scala/main/utility/ProcessRecord.scala:11` (concurrency)
- Evidence: class ProcessRecord {
  val map = mutable.Map.empty[Int, ActorRef]
}
// ChangRobertsAlgorithm.scala:83,86,50
system.actorOf(Props(new ChangRobertsProcess(id, uid, neighbor, system, record)), ...)
record.map.put(id, process)
record.map.put(-1, terminator)
- Failure: Today the code is safe only by accident: all puts happen before the first `!`, and the Akka send happens-before rule publishes them. If any actor sends during construction or preStart, or if the main thread mutates the map later (as Terminator's TerminateProcess pattern invites), actors read a HashMap while it is being resized. The result is a missing key, NoSuchElementException, or a corrupt read. Each actor can also see and message every other node, which defeats the ring model (a node must know only its successor).
- Fix: Give each actor only an immutable `next: ActorRef` (send it in an Init message after all actors are spawned), plus a reply-to ref for the result. Remove ProcessRecord.
- Verifier: ProcessRecord.scala:11 is a shared mutable.Map. The current code does all puts (lines 50 and 86) before the first send at line 65, and the actor-send happens-before rule publishes them, so no race occurs today. The hazard needs a future code change. The point that every node can reach every other node, which breaks the ring model, is a valid design remark.

### [low/confirmed] Copy-pasted, wrong Scaladoc and dead members
- `src/main/scala/main/processes/ChangRobertsProcess.scala:8` (other)
- Evidence: * Actor representing a process in the Echo algorithm.
 * @param neighbor      The list of neighboring process IDs.
...
val system: ActorSystem   // never used
override def preStart(): Unit = { super.preStart() }   // no-op
- Failure: A reader is told this is the Echo algorithm and that `neighbor` is a list (it is a single Int successor id). The `system` parameter is unused, and it tempts code to call system.terminate() from inside a node.
- Fix: Fix the doc. Remove the unused `system` parameter and the no-op preStart.
- Verifier: ChangRobertsProcess.scala:8 says "Actor representing a process in the Echo algorithm", and line 11 calls neighbor "The list of neighboring process IDs", but neighbor is an Int. The class never uses the `system` constructor parameter. preStart at lines 25-27 only calls super. All of these are correct as reported.

## franklin

Franklin review (static only; nothing compiled or run). For the shipped 5-node ring with unique non-negative ids, the code most likely elects the correct leader (4). It does this by a different algorithm from the textbook one. A central FranklinOrchestrator runs the rounds. It collects one Checkpoint from each active process, tells the losers to go passive, waits for their Acks, then starts the next round. In Fokkink's Franklin, each process exchanges round-tagged ids with its active neighbours, goes passive on its own, and finds out it is the leader when its own id comes back to it. Here the processes never decide their own state, never learn who the leader is, and the run produces no result value. Real defects: the passiveProcess list is never cleared, so every round sends TurnPassive again to all processes that are already passive and the round barrier works only by accident. Any None.get on a bad neighbour id crashes an actor. Akka then restarts it, which resets isActive to true and loses the in-flight message, so the round never finishes. The run waits for at most 30 s; on timeout Await.ready throws a TimeoutException and the ActorSystem is never terminated. There is no check that the ring config is consistent, and ids collide with the reserved keys -1/-2 and the -1 sentinel. The two tests check only that Initiate is broadcast and that the two id requests are sent. No test checks the compare logic, passive forwarding, multiple rounds, leader detection or the elected leader, so almost any broken election still passes. FP-design problems: mutable vars, a mutable map shared between all actors, messages declared as case classes with () but sent as their companion objects, and no event stream or return value that a visualizer could use.

### [high/confirmed] Central orchestrator replaces Franklin's distributed round logic; processes never decide state or learn the leader
- `src/main/scala/main/utility/FranklinOrchestrator.scala:26` (algorithm-correctness)
- Evidence: FranklinProcess.compareIds: `val stayActive: Boolean = max(max(id, leftId), rightId) == id` then `processRecord.map.get(-2).get ! Checkpoint(id, stayActive, leftId == id)`; only the orchestrator acts on it: `processRecord.map.get(proId).get ! TurnPassive` and `processRecord.map.get(-1).get ! TerminateSystem`.
- Failure: In Fokkink's Franklin, an active process with max(q,r) > p goes passive itself, and one with max(q,r) < p starts the next round itself. Messages carry a round number, so there is no global barrier, and the process that gets its own id back becomes the leader. Here a global coordinator gathers n Checkpoints, holds a barrier (Checkpoint -> TurnPassive -> Ack -> Initiate) and decides alone. No process ever sets a 'leader' or 'elected' state, and the leader id goes only to a log line (`log.info(s"Process $id is the Leader...")`). Remove the orchestrator and nothing runs. The message pattern is also not Franklin's: request/response through passive chains gives about 4 messages per active process per round instead of 2, plus 2-3 coordinator messages for each process. A visualizer that shows this run would show a centralized protocol, not Franklin's.
- Fix: Implement the textbook algorithm. Each active process sends Id(round, id) both ways. A passive process forwards Id messages unchanged. An active process keeps per-round buffers for its left and right ids (to cope with messages from round r+1 that arrive early) and decides passive, next round or leader by itself. Once elected, the leader sends a Leader(id) announcement around the ring. Delete FranklinOrchestrator. Use a separate observer only to detect termination or record events.
- Verifier: The code matches the quote. FranklinProcess.compareIds (lines 84-86) only sends Checkpoint to map(-2). FranklinOrchestrator lines 42-54 decide TurnPassive and termination. The orchestrator also holds a global barrier: it waits for all Checkpoints and then all Acks before the next Initiate. No process stores a leader state; only the log line on line 43 names the leader. This is a centralized, synchronous-round variant and not Fokkink's Franklin, which is asynchronous with round-tagged messages. For the visualizer goal, high is the correct severity.

### [high/confirmed] Tests check only message fan-out; no test checks election correctness, leader, forwarding or rounds
- `src/test/scala/franklinTest.scala:21` (test-quality)
- Evidence: Test 1: `orchestrator ! Initiate; process2.expectMsg(Initiate); process3.expectMsg(Initiate)`. Test 2: `Process ! Initiate; left.expectMsg(LeftIdRequest); right.expectMsg(RightIdRequest)`. Nothing else.
- Failure: All of these broken implementations pass both tests: compareIds that keeps the minimum (`min` in place of `max`); `isLeader` always false, so the run never terminates; passive processes that do not forward (lines 37-39, 53-54 deleted); responses forwarded the wrong way; the stale passiveProcess bug (franklin-2); an orchestrator that never ends a round. Nothing runs FranklinAlgorithm.createTestData or a full ring, and nothing checks which id is elected. The suite reuses the system name `"BrachaTouegProcessSpec"` (copy-paste), the test name says `"should should receive"`, and the actors created in the tests are never stopped.
- Fix: First, extract the decision logic as a pure function, e.g. `def step(state: NodeState, msg: Msg): (NodeState, List[Out])`. Unit-test it with table cases: own id larger, smaller, own id returned. Then add property-based tests with ScalaCheck: for a random permutation of distinct ids on a ring of 1..50 nodes, the elected leader is ids.max, exactly one node is the leader, and the run terminates. Add one end-to-end test with akka-testkit that waits for an observer's ElectionResult(leader) and asserts that value.
- Verifier: franklinTest.scala contains only the two quoted tests (lines 21-46 and 49-68). They check Initiate fan-out and the first two requests. No test checks compareIds, forwarding, rounds, Checkpoint, TurnPassive or the elected leader. Swapping max for min would pass both tests. The system name `BrachaTouegProcessSpec` (line 9) and the test name 'should should receive' are as quoted. The tests check almost nothing.

### [medium/plausible] Option.get on neighbour lookup crashes the actor; restart resets isActive=true and loses the message, so the run hangs
- `src/main/scala/main/processes/FranklinProcess.scala:28` (crash)
- Evidence: `processRecord.map.get(leftNeighbor).get ! LeftIdRequest` (also lines 29, 38, 47, 54, 64, 86; orchestrator lines 44, 53, 76; FranklinAlgorithm line 38 `record.map.get(-2).get ! Initiate`).
- Failure: Change the config so that node 4 has `rightNeighbor: 5`, a typo. The Initiate to process 4 throws NoSuchElementException(None.get). The default supervisor restarts the actor, which drops the message and rebuilds the class with `isActive = true`. Process 4 never sends a Checkpoint, so `roundProcessCount == processes.size` never holds. Main blocks for 30 s and then Await.ready throws TimeoutException (see franklin-5). A worse case: a passive process that gets restarted for any reason becomes active again. It then answers LeftIdRequest/RightIdRequest with its own id and takes part in the comparisons, which can elect the wrong leader or produce extra Checkpoints.
- Fix: Resolve the neighbour ActorRefs once, before the start. Pass them in as constructor or init-message parameters (typed `ActorRef[Msg]`), and validate the topology in a pure function that returns `Either[TopologyError, Ring]`. Do not call `.get` on an Option.
- Verifier: The .get calls are present at the cited lines. A missing neighbour throws NoSuchElementException. The default supervisor restarts the actor, which drops the message and resets isActive=true. The run then hangs, and Await.ready throws TimeoutException after 30 s, which Main lines 67-69 catch. But the failure needs a malformed config. The shipped ring 0..4 in application.conf (lines 89-123) is consistent. The only source of exceptions in the actor is .get itself, so 'a passive process restarted for any reason' cannot happen on valid input. Plausible, medium.

### [medium/plausible] No validation of ring topology; inconsistent left/right links route responses to the wrong process
- `src/main/scala/main/algorithms/FranklinAlgorithm.scala:46` (algorithm-correctness)
- Evidence: `val leftNeighbour = nodeData.getInt("leftNeighbor"); val rightNeighbour = nodeData.getInt("rightNeighbor")` with no check that a.right == b iff b.left == a, that the graph is one cycle, or that ids are unique/non-negative.
- Failure: Responses go back along the opposite direction: a LeftIdResponse is forwarded to `rightNeighbor`, and a RightIdResponse to `leftNeighbor`. Take a config where 1.right=2 but 2.left=0. When 1 is passive, a LeftIdRequest that 2 forwards goes to 0, but the response that 1 forwards goes to 2. An active process that never sent the request can get a LeftIdResponse and set `leftId` to that value. It can then send two Checkpoints in one round, or the real requester waits forever. The orchestrator counts Checkpoints, not distinct ids, so the round can end too early with a wrong passive set. Duplicate ids crash at `name = "process" + id` (InvalidActorNameException). An id of -1 or -2 overwrites the Terminator or the Orchestrator in the map (`record.map.put(-1, terminator)`). An id of -1 can also never be stored, because `leftId != -1` is the 'not received' sentinel, so compareIds never fires.
- Fix: Parse the config into a validated `Ring` value: a cyclic Vector of distinct ids from which each node's left and right are derived, so they cannot disagree. Keep actor addresses in a separate map and not in the same keyspace as the process ids. Replace the -1 sentinel with Option[Int].
- Verifier: FranklinAlgorithm lines 48-53 read ids and neighbours with no validation. The forwarding directions are as described: LeftIdResponse goes to rightNeighbor (line 54) and RightIdResponse goes to leftNeighbor (line 64). So inconsistent links misroute responses. A duplicate id gives a duplicate actor name and an exception. An id of -1 or -2 is overwritten by lines 57 and 61, and -1 also collides with the sentinel on line 81. All of these need a bad config. The shipped config is a consistent ring, so plausible.

### [medium/confirmed] 30 s Await.ready used as termination; on timeout it throws and leaks a running ActorSystem
- `src/main/scala/main/algorithms/FranklinAlgorithm.scala:40` (concurrency)
- Evidence: `Await.ready(system.whenTerminated, 30.seconds)`; `logger.info("Terminating the algorithm")`; no `system.terminate()` in a finally block.
- Failure: Any hang (franklin-3 or franklin-4, or an empty `nodes: []` list, where initiateRound sends nothing and no Checkpoint ever arrives) makes Await.ready throw java.util.concurrent.TimeoutException after 30 s. Main's catch prints the stack trace and shows the menu again, but 'FranklinSystem' and all of its actors keep running. Every retry from the menu starts another live ActorSystem. A large ring that is correct but needs more than 30 s fails in the same way. main returns Unit, so even a successful run gives the caller no leader.
- Fix: Return the outcome as a value, e.g. `def run(ring: Ring): Future[ElectionResult]` completed by the leader announcement. The caller decides on a timeout and always calls `system.terminate()` in a finally block or with `transformWith`.
- Verifier: Line 40 is `Await.ready(system.whenTerminated, 30.seconds)`, and nothing calls system.terminate() on the timeout path. TimeoutException extends Exception, so Main lines 67-69 catch it, print the stack trace and show the menu again, and the ActorSystem keeps running. An empty nodes list makes processes empty, initiateRound sends nothing, and the run hangs. main returns Unit. Each part of the finding holds.

### [medium/confirmed] Messages declared as `case class X()` but sent and matched as companion objects; untyped protocol
- `src/main/scala/main/utility/MessageTypes.scala:8` (fp-design)
- Evidence: `case class Initiate() extends MessageTypes`, `case class Ack()`, `case class TerminateSystem()`; Franklin code sends `! Initiate`, `! Ack`, `! TerminateSystem` and matches `case Initiate =>`, `case Ack =>`.
- Failure: The value sent is the companion object (type `Initiate.type`, a Function0), not an `Initiate` instance. The protocol works only because every sender and receiver uses the companion in the same way. If one call site sends `Initiate()`, it falls through every `case Initiate =>` silently, the message goes to unhandled, and the round hangs with no error at compile time or at run time. All 8 algorithms share one untyped `MessageTypes` trait and classic `Actor` with `Receive = PartialFunction[Any, Unit]`, so the compiler cannot report a message that a Franklin actor does not handle.
- Fix: Define one sealed protocol per algorithm, e.g. `sealed trait FranklinMsg; case object Start extends FranklinMsg; final case class Id(round: Int, id: Int, from: Direction) extends FranklinMsg`. Use Akka Typed `Behavior[FranklinMsg]` so that exhaustiveness and ActorRef message types are checked at compile time.
- Verifier: MessageTypes.scala declares `case class Initiate()`, `Ack()` and `TerminateSystem()` (lines 8, 12, 14). The Franklin code sends and matches the companion objects (FranklinAlgorithm line 38, orchestrator lines 21, 44, 59, 76, process lines 24, 75, Terminator line 15). This works today because every call site is consistent, and a grep found no Franklin call site that sends `Initiate()`. The risk of a silent unhandled message is real but latent. It is a valid fp-design finding, medium.

### [medium/confirmed] Mutable vars with sentinel values; protocol logic tied to actor plumbing; no observable event stream for a visualizer
- `src/main/scala/main/processes/FranklinProcess.scala:12` (fp-design)
- Evidence: `var isActive = true`, `var leftId: Int = -1`, `var rightId: Int = -1`; orchestrator `var processes`, `var roundProcessCount`, `var round`, `var passiveProcess`, `var passiveProcessCount`; logging via `akka.event.slf4j.Logger` is the only output.
- Failure: The election logic cannot be tested or replayed without actors (see franklin-7). The -1 sentinel conflates 'not received' with a legal id (franklin-4). The visualizer goal needs a deterministic, replayable trace of (round, node, message, state change). Here the only trace is unordered log lines from several threads, and there is no seedable scheduler, so the visualizer cannot step through or replay a run.
- Fix: Split the code into three layers: (1) pure Franklin state machine `NodeState` (Active(round, left: Option[Int], right: Option[Int]) | Passive | Leader) with `step`; (2) a pure simulator that runs the state machine on a ring with a seedable message scheduler and emits `List[Event]`; (3) a thin Akka Typed adapter that runs the same `step` and publishes Events to an observer. The visualizer reads the Event stream from (2) or (3).
- Verifier: FranklinProcess lines 12-14 and orchestrator lines 7-12 use mutable vars as quoted, with -1 sentinels. The only output is slf4j logging. No pure step function and no event stream exist. Both the testability goal and the visualizer goal need these changes.

### [low/confirmed] passiveProcess is never cleared, so every round sends TurnPassive again to all earlier passive processes
- `src/main/scala/main/utility/FranklinOrchestrator.scala:34` (algorithm-correctness)
- Evidence: `passiveProcess = id :: passiveProcess` (line 34) and `passiveProcess.foreach(proId => { processes = processes.filterNot(_ == proId); processRecord.map.get(proId).get ! TurnPassive })` (lines 51-54); initiateRound resets `roundProcessCount` and `passiveProcessCount` but not `passiveProcess`.
- Failure: Take ring 0..4 (shipped config). Round 0: the local minima 0,1,2,3 lose, so passiveProcess=[3,2,1,0] and 4 TurnPassive/Ack pairs are sent. Round 1: process 4 is the only active process and wins, so the run ends. With a larger ring, e.g. ids [5,1,4,2,6,3,7,0], round 1 has new losers, and passiveProcess=[new..., old...]. The orchestrator sends TurnPassive to every old passive process again and waits for all of their Acks (`passiveProcessCount == passiveProcess.size`). Coordinator traffic grows by O(n) per round, which gives O(n log n) wasted messages. The barrier is correct only because the extra Acks happen to equal the extra list entries. A rewrite that clears the list in initiateRound but counts Acks differently (or the reverse) deadlocks. The `if (passiveProcess.isEmpty) initiateRound()` branch (line 47) is unreachable after round 0. A visualizer would draw TurnPassive storms on nodes that are already passive.
- Fix: Model round state as an immutable value, e.g. `case class RoundState(round: Int, pending: Set[Int], losers: Set[Int])`. Build a fresh one for each round with context.become, and count Acks against the losers of this round only. Better: remove this barrier altogether (see franklin-1).
- Verifier: Line 34 prepends and is never cleared. initiateRound (lines 69-72) resets only the counters. Old passive processes answer every TurnPassive with Ack (FranklinProcess lines 71-75), and passiveProcessCount restarts at 0 each round, so Ack count == passiveProcess.size still holds. The barrier is correct. The cost is redundant messages only. The claim that the line 47 branch is unreachable after round 0 is correct: with 2 or more active distinct ids, the minimum active always loses, so the list is never empty. Also, on the shipped 5-node config the run ends in round 1, so the bug never shows there. The finding is wasted traffic and a fragile design, not a wrong result, so low.

### [low/plausible] Non-thread-safe mutable.Map is shared by all actors and the main thread
- `src/main/scala/main/utility/ProcessRecord.scala:11` (concurrency)
- Evidence: `val map = mutable.Map.empty[Int, ActorRef]`, written in `createTestData` (`record.map.put(id, Process)`) while actors already run, and read from every actor's receive (`processRecord.map.get(...)`). The test also writes after creating the actor: `system.actorOf(Props(new FranklinProcess(0, 2, 3, system, processRecord)))` then `processRecord.map += (2 -> left.ref)`.
- Failure: This is correct today only by accident. The actor threads see the map writes because of a transitive happens-before chain through the `Initiate` sends. If an actor reads the map in preStart, or if anything mutates the map after the start (as a visualizer that adds or removes nodes would), a read can see a HashMap that is half updated. It can then return None, which leads to the franklin-3 crash, or loop. Closing over `record` inside `Props(new FranklinProcess(...))` also breaks Akka's rule against closing over mutable state in Props.
- Fix: Give each actor an immutable view of its neighbours: constructor parameters, or an `Init(left: ActorRef, right: ActorRef, observer: ActorRef)` message. Do not share a mutable registry. Use `Props(classOf[...], args)` or typed `Behaviors.setup`.
- Verifier: ProcessRecord.map is a shared, non-thread-safe mutable.Map. In main, every put (lines 53, 57, 61) happens before the Initiate send on line 38. In the test, the puts on lines 58-59 happen before `Process ! Initiate`. No actor reads the map before it gets a message, so the mailbox send gives happens-before, and there is no race today. The finding admits this. The Akka Props warning is about closing over an enclosing actor's `this`, and these Props are built in an object method or a test, so that part is overstated. This is a design risk only, so low.

### [low/confirmed] Dead code and leftover parameters
- `src/main/scala/main/processes/FranklinProcess.scala:17` (other)
- Evidence: `override def preStart(): Unit = { super.preStart() }` in both FranklinProcess and FranklinOrchestrator; constructor params `val system: ActorSystem` unused in FranklinProcess and FranklinOrchestrator; `LeftIdResponse(id: Option[Int])` is always `Some(id)`, so `idOpt.getOrElse(-1)` is dead.
- Failure: No runtime failure. Passing ActorSystem into actors invites misuse (system.actorOf or system.terminate from inside an actor). The Option wrapper that is always Some hides the real 'not yet received' state, which lives in the -1 sentinel.
- Fix: Delete the empty overrides and the unused `system` parameters. Make the response payload a plain Int, and store the 'not yet received' state as Option in the node state.
- Verifier: Both actors have the empty preStart override (process lines 17-19, orchestrator lines 15-17). The `system` parameter is unused in FranklinProcess and FranklinOrchestrator. LeftIdResponse and RightIdResponse are always built with Some(id) (lines 36, 45), so getOrElse(-1) on lines 56 and 66 is dead code. Low.

## infra

I did a static review of the shared infrastructure: Main.scala, the seven files in utility/, application.conf, build.sbt, project/build.properties and .gitignore. I also read the launchers and processes that call this code where I had to confirm a failure path. I did not compile or run anything.

The worst problems:
1. TopologyReader always turns every directed edge into two undirected edges. It keeps duplicate neighbours and self loops. It silently drops isolated nodes, chained edges and some malformed lines, and a line that ends in ';' crashes the run with a misleading menu error. If the file has both a->b and b->a, every count-based wave algorithm (Echo, Tarry, Tree) waits for a message that never comes.
2. Two pieces of global mutable state are shared across actor threads with no synchronisation. One is the numberOfProcessesCompletingSnapshot counter. The other is the SystemSnapshot mutable.Map in SnapshotUtils. Because of the counter, the main thread can spin forever in Chandy-Lamport and Lai-Yang.
3. Lifecycle: every launcher blocks on Await with a fixed timeout. When the timeout fires, the ActorSystem is never terminated, and its non-daemon threads keep the JVM alive after the user picks Exit. The menu loop also spins forever at end of input (Ctrl-D or piped input). An ExceptionInInitializerError from ApplicationProperties gets past `case e: Exception` and crashes the program.
4. Design: messages are untyped, not sealed, and are sent as companion objects of empty case classes. ProcessRecord is a global mutable id->ActorRef address book. FranklinOrchestrator is a central round barrier, but Fokkink's Franklin algorithm is decentralised. Process ids are both addresses and UIDs.
5. Build: four of the declared dependencies are never imported: akka-actor-typed, akka-stream-typed, akka-http and akka-http-spray-json. But akka-actor-typed is the only thing that brings in akka-slf4j, which all 18 files that use akka.event.slf4j.Logger need. So if you just remove the unused dependencies, the build breaks. Some versions are known to be vulnerable:
   - Scala 2.13.8: CVE-2022-36944.
   - logback 1.0.9 (from 2012): CVE-2017-5929 and later CVEs.
   - akka-http 10.2.4: CVE-2021-42697.

   config 1.2.1 is a pointless pin. Some artifacts use %% and others hard-code _2.13. There are no scalacOptions, and the build has no visualizer seam at all.

Akka 2.6.x is still Apache-2.0. Do not upgrade to Akka 2.7 or later, which uses the BSL licence. Move to Apache Pekko 1.x instead, ideally with typed actors and a structured trace-event stream, which the visualizer will need.

### [critical/confirmed] Global counter shared across threads: lost updates and a busy-spin with no visibility guarantee
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:53` (concurrency)
- Evidence: var numberOfProcessesCompletingSnapshot = 0   // object field, line 18
while (numberOfProcessesCompletingSnapshot < processRecord.map.size) { ... sender ! SendMessage(operation) }
// ChandyLamportProcess.scala:52
numberOfProcessesCompletingSnapshot += 1
- Failure: Several actors on different dispatcher threads run a non-atomic += on a plain var, so two of them can finish at the same time and one increment is lost. The variable is not volatile, so the JIT can also hoist the read out of the loop. In both cases the main thread spins forever. While it spins, it sends millions of SendMessage messages to unbounded mailboxes and runs out of memory. LaiYangAlgorithm.scala:19 with LaiYangProcess.scala:69/110 has the same bug.
- Fix: Remove the global vars. Let each process send a SnapshotDone(localState, channelState) message to a collector actor, and use ask, a Promise or the typed watchWith pattern to finish. Drive application traffic from a scheduler, not a busy loop on the caller thread.
- Verifier: ChandyLamportAlgorithm.scala:18 has a plain object var. ChandyLamportProcess.scala:52 runs numberOfProcessesCompletingSnapshot += 1 from actor threads (the var is imported, so this assigns to the object field). The main thread spins in the while at line 53 and sends SendMessage on every iteration. A lost update from two concurrent completions makes the loop never exit, and unbounded mailboxes then grow. LaiYangProcess.scala:69/110 against LaiYangAlgorithm.scala:19/80 is the same. The JIT-hoisting part is less likely, because ! has memory fences, but the lost update alone is enough.

### [high/confirmed] SystemSnapshot is a non-thread-safe mutable.Map that all actors write to concurrently
- `src/main/scala/main/utility/SnapshotUtils.scala:16` (concurrency)
- Evidence: val systemSnapshot: mutable.Map[Int, ProcessSnapshotData] = mutable.Map.empty
// ChandyLamportProcess.scala:31
systemSnapshot.systemSnapshot += (id -> new ProcessSnapshotData(storedVariable, ListBuffer()))
- Failure: N actors on different threads run += on the same mutable.HashMap. A resize that happens while another put is in progress can lose entries. A later systemSnapshot(id) at SnapshotUtils.scala:24 or ChandyLamportProcess.scala:70 then throws NoSuchElementException and the actor restarts, which resets its local state. The mutable ListBuffer in ProcessSnapshotData is also shared. printSnapshotData has no separator between records (line 31). The 'var log' is mutable for no reason.
- Fix: Use immutable case classes: final case class LocalSnapshot(id, value, inTransit: Vector[ChannelMsg]). Let each actor send its LocalSnapshot to a collector, which folds them into an immutable GlobalSnapshot. The visualizer can then use that object directly.
- Verifier: SnapshotUtils.scala:16 is a plain mutable.Map. ChandyLamportProcess.scala:31 and LaiYangProcess.scala:59 write to it from concurrently running actors, and ChandyLamportProcess.scala:70 and SnapshotUtils.scala:24 read it with apply. Concurrent HashMap puts can lose entries, and the reads then throw. SnapshotUtils.scala:31 has no separator between records, and line 18 has 'var log'.

### [high/confirmed] The menu loop spins forever at end of input
- `src/main/scala/main/Main.scala:61` (crash)
- Evidence: choice = StdIn.readInt()
...
case e: Exception =>
  e.printStackTrace()
  logger.error("An error occurred. Please try again.")
} while (choice != 9)
- Failure: The user presses Ctrl-D, or runs `echo 2 | sbt run`, or runs it in CI. StdIn.readInt throws java.io.EOFException ('Console has reached end of input'). The generic handler catches it, choice is never 9, and the loop prints stack traces forever.
- Fix: Read with Option(StdIn.readLine()) and treat None as exit. Parse with toIntOption into a sealed MenuChoice ADT, and write the loop as a tail-recursive function, not a var with do/while (Scala 3 removes do/while).
- Verifier: Main.scala:61 calls StdIn.readInt. At end of input, readLine returns null and Scala 2.13 throws EOFException('Console has reached end of input'). Line 67 catches it, choice stays at its old value (not 9), and the do/while at line 71 loops forever and prints stack traces.

### [high/confirmed] An Await timeout leaks a live ActorSystem; the JVM does not exit after option 9
- `src/main/scala/main/Main.scala:67` (crash)
- Evidence: Main.scala:67  case e: Exception => e.printStackTrace()
FranklinAlgorithm.scala:40  Await.ready(system.whenTerminated, 30.seconds)
TreeAlgorithm.scala:49 / EchoAlgorithm.scala:58 / TarrysAlgorithm.scala:58 / BrachaTouegAlgorithm.scala:53 (same pattern)
- Failure: An algorithm does not send its terminate message, for example the Bracha-Toueg deadlock case or the duplicate-edge hang in infra-1. Await.ready throws TimeoutException after 30 s, and no code calls system.terminate(). Main catches the exception and shows the menu again while the old system keeps logging into the console. Akka dispatcher threads are non-daemon (akka.daemonic=off), so when the user picks 9 the JVM hangs and does not exit. Each extra timeout leaks one more system.
- Fix: Give each run one resource-safe wrapper, for example def runSystem[A](name)(f: ActorSystem => Future[A]): Try[A] that always calls terminate in a finally block. Have each algorithm return a Future[Result] that completes when it decides, and do not depend only on whenTerminated.
- Verifier: Await.ready throws TimeoutException on expiry. FranklinAlgorithm.scala:40, TarrysAlgorithm.scala:58, EchoAlgorithm.scala:58, TreeAlgorithm.scala:49 and BrachaTouegAlgorithm.scala:53/83 never call system.terminate() on that path. Main.scala:67 swallows the exception, and the live ActorSystem with non-daemon threads stops the JVM from exiting after choice 9. The snapshot launchers do call terminate.

### [medium/plausible] readTopology keeps duplicate neighbours and self loops, so count-based waves hang
- `src/main/scala/main/utility/TopologyReader.scala:25` (algorithm-correctness)
- Evidence: List((from, to), (to, from))
...
.groupBy(_._1)
.mapValues(_.map(_._2).toList)
- Failure: NetGameSim often writes both "1" -> "2" and "2" -> "1". Then node 1 gets neighbours List(2, 2) and node 2 gets List(1, 1). EchoProcess and TreeProcess compare the number of received messages with neighbors.size, so they wait for a second message from the same neighbour that never comes. TarryProcess thinks it has two channels to node 2. The algorithm never decides, and Await times out after 30 s. A self loop "3" -> "3" gives node 3 the neighbours List(3, 3), so node 3 sends messages to itself.
- Fix: Parse into a Graph ADT whose edges are a Set[(NodeId, NodeId)], with parallel edges removed. Reject or explicitly allow self loops. Build the neighbours as Map[NodeId, Set[NodeId]].
- Verifier: TopologyReader.scala:25 does emit (from,to),(to,from) with no dedup, so a file with both a->b and b->a gives List(b,b). But none of the DOT files in use (inputTarry.dot, inputEcho.dot, NetGraph_21-04-24...perturbed.dot) has a reverse edge, a duplicate or a self loop. Only the unused graph_50_nodes.dot has duplicates (13-46, 25-45). LaiYangAlgorithm.scala:51 already calls .distinct. The hang needs an input that the app does not load today.

### [medium/plausible] Hand-rolled DOT parser drops isolated nodes and chained edges; ';' crashes the run
- `src/main/scala/main/utility/TopologyReader.scala:21` (crash)
- Evidence: .filter(line => line.contains("->"))
...
if (parts.length == 2) { ... } else { List.empty }
val to = parts(1).split("\\[")(0).trim.replaceAll("\"", "")
- Failure: (a) inputTarry.dot and inputEcho.dot declare node "0" with the label 'Init', but node 0 has no edges, so the parser drops it. The process that the file marks as the initiator does not exist. (b) A chained edge "a" -> "b" -> "c" gives 3 parts, and the parser drops the whole line. (c) A standard DOT line "1" -> "2"; gives to = "2;". The callers then call id.toInt and get a NumberFormatException, and Main.scala:65 reports it as 'Invalid input. Please enter a valid integer.' (d) A commented-out line // "1" -> "2" still counts as an edge. (e) An undirected 'graph { a -- b }' file gives an empty map, so zero actors start and the run waits 30 s.
- Fix: Use a real DOT parser, or write a small parser-combinator or fastparse grammar. Return Either[ParseError, Graph], and include node statements in the result.
- Verifier: The code is as quoted. (a) is true: node 0 in inputTarry.dot and inputEcho.dot is dropped. But the launchers pick a random initiator and ignore the Init label, so no crash results. (b)-(e) are real parser gaps, and a ';' does give an NFE on the main thread through id.toInt, which Main.scala:65 mislabels. But no shipped file has ';', a chained edge, a comment or 'graph --'. 'High crash' is overstated.

### [medium/plausible] ProcessRecord is a global mutable id->ActorRef address book with magic ids -1 and -2
- `src/main/scala/main/utility/ProcessRecord.scala:11` (concurrency)
- Evidence: class ProcessRecord {
  val map = mutable.Map.empty[Int, ActorRef]
}
- Failure: Actors resolve neighbours at run time with map.get(id).get. They reach the terminator at -1 and the orchestrator at -2, for example FranklinOrchestrator.scala:44 'processRecord.map.get(-1).get ! TerminateSystem'. A missing id throws NoSuchElementException inside the actor, and the actor restarts silently. Because the snapshot launchers keep the record in a global var, two test suites that run in parallel (sbt runs suites in parallel in one JVM by default) overwrite each other's record through ChandyLamportAlgorithm.init. Correctness also depends on the main thread finishing all writes before the first '!', which is a fragile happens-before chain.
- Fix: Pass each process its neighbours as an immutable Map[NodeId, ActorRef[Msg]] in a constructor or an Init message. Pass the terminator or collector as an explicit ActorRef. Remove the -1 and -2 sentinels.
- Verifier: ProcessRecord.scala:11 is a shared mutable Map, and the code uses the -1 and -2 sentinels with .get, for example FranklinOrchestrator.scala:44/53/76. That design criticism holds. The parallel-suite clash is not reachable now: only chandyLamportTest calls ChandyLamportAlgorithm.init, and laiYangTest uses the separate LaiYangAlgorithm object. Tests inside one suite run sequentially. In EchoAlgorithm.scala:51-55 the initiator gets its first message before the terminator is put in the map, which does show the fragile ordering.

### [medium/confirmed] Error handling catches the wrong things: config errors crash the program, algorithm errors are mislabelled
- `src/main/scala/main/Main.scala:65` (crash)
- Evidence: case _: NumberFormatException =>
  logger.warn("Invalid input. Please enter a valid integer.")
case e: Exception =>
- Failure: (a) A key is missing from application.conf. ApplicationProperties is an eager object, so its first access throws ExceptionInInitializerError. That is an Error, not an Exception, so it escapes the catch and the program crashes. Even if something caught it, every later access gives NoClassDefFoundError. (b) A DOT id like "2;" gives a NumberFormatException from inside the algorithm, and the menu tells the user that their menu choice was invalid. The try block also covers runAlgorithm, not only the readInt call.
- Fix: Parse the input in its own step, separate from running the algorithm. Load the config into a case class with a validated loader (pureconfig, or Config wrapped in Try or Either) once at start-up, and report errors with their source.
- Verifier: ApplicationProperties is an eager object. A missing key throws ExceptionInInitializerError, which is an Error that Main.scala:64-69 does not catch, and later accesses throw NoClassDefFoundError. The try block wraps runAlgorithm, so an NFE from id.toInt inside a launcher is reported as 'Invalid input. Please enter a valid integer.'

### [medium/confirmed] One untyped, unsealed protocol for all algorithms; empty case classes are sent as companion objects
- `src/main/scala/main/utility/MessageTypes.scala:6` (fp-design)
- Evidence: trait MessageTypes
case class Initiate() extends MessageTypes
case class TerminateSystem() extends MessageTypes
// sent as: processRecord.map(-1) ! TerminateSystem   (companion object)
// matched as: case TerminateSystem =>  (Terminator.scala:15)
// but Echo uses instances: EchoTerminate()  (Terminator.scala:31)
- Failure: The code sends and matches the companion object, which is a Function0 value, not an instance of the case class. Suppose a developer writes `terminator ! TerminateSystem()`, which looks like the correct form. Terminator.scala:15 does not match it, the message goes unhandled with no warning, and the system waits for the 30 s timeout. The trait is not sealed, so the compiler cannot check that a match covers every case, and classic Actor.receive accepts Any. All eight algorithms share one bag of messages, for example Ack, Done and Initiate are reused by both Bracha-Toueg and Franklin.
- Fix: Give each algorithm its own sealed trait, for example sealed trait EchoMsg, with case objects for messages that have no payload. Move to Akka Typed or Pekko Typed Behavior[EchoMsg], so the compiler rejects messages that the actor cannot handle.
- Verifier: MessageTypes.scala:6 is an unsealed trait, and empty case classes are sent and matched as companion objects (for example Initiate, Wave, AllProcessesCreated and TerminateSystem at Terminator.scala:15), while Echo uses instances. Today the senders and matchers agree, so nothing fails yet. The hazard (a send of X() that does not match) is latent but real, and Ack, Done and Initiate are shared across algorithms.

### [medium/confirmed] Four unused Akka modules; akka-slf4j comes in only through akka-actor-typed
- `build.sbt:11` (build-config)
- Evidence: "com.typesafe.akka" % "akka-actor-typed_2.13" % "2.6.18",
"com.typesafe.akka" % "akka-stream-typed_2.13" % "2.6.18",
"com.typesafe.akka" % "akka-http_2.13" % "10.2.4",
"com.typesafe.akka" % "akka-http-spray-json_2.13" % "10.2.4",
- Failure: No source file imports akka.actor.typed, akka.stream, akka.http or spray.json. But 18 files import akka.event.slf4j.Logger, which is in akka-slf4j, and only akka-actor-typed brings that module in. If someone removes the 'unused' typed dependency, all 18 files fail to compile. akka-http 10.2.4 is affected by CVE-2021-42697 (DoS on deeply nested User-Agent headers, fixed in 10.2.7).
- Fix: Declare akka-slf4j (or pekko-slf4j) explicitly, or better, use context.log or ActorLogging. Remove akka-http, spray-json and akka-stream-typed until the visualizer back end needs them. If it does, add them with %% at a patched version.
- Verifier: No source file imports akka.actor.typed, akka.stream, akka.http or spray.json. 19 files (not 18) import akka.event.slf4j.Logger, and akka-slf4j comes in only transitively through akka-actor-typed (and akka-stream-typed). akka-http 10.2.4 is before the 10.2.7 fix for CVE-2021-42697, but akka-http is unused, so no attack surface exists. Medium, not high.

### [medium/confirmed] Eager global config object loads every algorithm's keys and fails as one unit
- `src/main/scala/main/utility/ApplicationProperties.scala:11` (fp-design)
- Evidence: object ApplicationProperties {
  private val app = ConfigFactory.load()
  val deadlockData: Config = app.getConfig("BrachaToueg.TestData.DeadlockTestData")
  ...
  val franklinData: List[Config] = app.getConfigList("FranklinAlgorithm.TestData.nodes").asScala.toList
- Failure: Suppose one key is wrong, for example a renamed SnapshotAlgorithm.InputFile. Then every algorithm fails, including those that do not use the key, with ExceptionInInitializerError on the first access and NoClassDefFoundError after that (see infra-11). The values are raw Config objects, so key-name errors appear only deep inside the launchers. Tests cannot pass a different config.
- Fix: Use a case class per algorithm and load it with pureconfig or a small Either-returning reader. Pass the config as a parameter, not through a global object.
- Verifier: ApplicationProperties.scala:10-19 eagerly reads every algorithm's keys in one object initializer. One bad key breaks every menu option with ExceptionInInitializerError and then NoClassDefFoundError, and a test cannot pass a different config.

### [medium/confirmed] There is no seam for a visualizer: algorithms are blocking Unit side effects that only write logs
- `src/main/scala/main/Main.scala:24` (fp-design)
- Evidence: def runAlgorithm(choice: Int): Unit = { choice match { case 1 => ... BrachaTouegAlgorithm.main() ... } }
- Failure: Each algorithm's main() blocks the caller thread for up to 30 s. It returns Unit, and it shows progress only as slf4j strings. A visualizer cannot get the sequence of send, receive and state-change events, a final Result, or a deterministic replay, because actor scheduling and scala.util.Random (TarrysAlgorithm, ChandyLamportAlgorithm) are not seeded. Also, Main is in the default package and 'object Main extends App' relies on DelayedInit, which Scala 3 drops.
- Fix: Define a pure core for each algorithm, for example def step(state, msg): (state, List[Outgoing]), and a TraceEvent ADT (Send, Deliver, StateChange, Decide) with logical timestamps. Run it through a deterministic simulator with a seeded scheduler, or through an actor runtime that sends TraceEvents to a sink. Return Result plus Vector[TraceEvent], and let the CLI and the visualizer read that output. Use a package and a plain def main(args).
- Verifier: Main.scala:24 runAlgorithm returns Unit and calls blocking main() methods that only log. Random is unseeded (TarrysAlgorithm.scala:37, EchoAlgorithm.scala:39, ChandyLamportAlgorithm.scala:55). Main is in the default package and uses 'extends App'. No trace or result seam exists for a visualizer. This is an accurate architectural gap for the user's stated goal.

### [low/plausible] readTopology always makes edges undirected; directed graphs cannot be expressed
- `src/main/scala/main/utility/TopologyReader.scala:25` (algorithm-correctness)
- Evidence: List((from, to), (to, from))
- Failure: Every input file is a 'digraph'. Chandy-Lamport (Fokkink ch. 3) is defined on directed FIFO channels, and a wait-for graph is directed. Every algorithm gets a symmetric graph, so a process waits for markers on channels that do not exist in the input. The DOT direction is lost with no warning. A visualizer that draws the input digraph shows edges that are not the ones the algorithm runs on.
- Fix: Keep the edge direction in the parsed model, for example Graph(directed: Boolean, edges). Let each algorithm request undirected(g) explicitly.
- Verifier: The symmetrisation is real, and it does drop DOT direction. But the stated failure does not happen. ChandyLamportProcess sends markers to all neighbours and waits for neighbors.size markers, which matches the undirected model, so no process waits on a channel that does not exist. Tarry, Echo and Tree need undirected graphs, and the input digraphs are not strongly connected, so a directed reading would break the snapshots. Bracha-Toueg does not use TopologyReader. This is a modelling and visualizer concern, not a correctness bug.

### [low/confirmed] Source is never closed, the path depends on the working directory, and the result is stringly typed
- `src/main/scala/main/utility/TopologyReader.scala:16` (other)
- Evidence: val topologyLines = Source.fromFile(filename).getLines().toList   // and application.conf: InputFile = "src/main/resources/inputTarry.dot"
- Failure: Each menu run leaks a file handle. If you run the packaged jar, or start sbt from another directory, you get a FileNotFoundException because the path is relative to the working directory. The return type Map[String, List[String]] makes every caller call .toInt, and the menu misreports the NumberFormatException that results.
- Fix: Use scala.util.Using(Source.fromResource(...)) or a classpath resource. Return Either[Error, Graph[NodeId]], where NodeId is a value class or opaque type.
- Verifier: TopologyReader.scala:16 Source.fromFile is never closed. ChandyLamportAlgorithm.scala:38 and LaiYangAlgorithm.scala:43 also open an unused Source that leaks. The application.conf paths are relative to the working directory, and the Map[String, List[String]] forces .toInt everywhere. The leak is one handle per menu run in a short-lived CLI, so low.

### [low/confirmed] hasCycle: non-local return in a closure, mutable set, unsafe apply, misses parallel edges
- `src/main/scala/main/utility/TopologyReader.scala:54` (fp-design)
- Evidence: val visited = collection.mutable.Set[String]()
...
processConfig(node).foreach { neighbor =>
  if (neighbor == parent) { } else if (visited.contains(neighbor)) { return true }
- Failure: (a) A map with a neighbour that is not a key throws NoSuchElementException (TreeTest builds maps by hand). (b) The parent check skips every edge to the parent, so two parallel edges a-b are not reported as a cycle. (c) A path of about 10k nodes overflows the stack. (d) 'return' inside foreach throws NonLocalReturnControl, and Scala 3 deprecates this.
- Fix: Write a pure fold or tailrec DFS over the Graph ADT. Use the edge identity, not the parent node, to skip the tree edge.
- Verifier: TopologyReader.scala:42-62 has a mutable Set, an unsafe processConfig(node) apply, a non-local return inside foreach, and a parent-node skip that cannot see parallel edges. The issues are real but minor. TreeTest does not call hasCycle; only TreeAlgorithm.scala:29 does, with a map from readTopology where every neighbour is also a key.

### [low/confirmed] Terminator: one message per algorithm, a dead TerminateProcess branch, a redundant system parameter
- `src/main/scala/main/utility/Terminator.scala:19` (other)
- Evidence: class Terminator(val system: ActorSystem) extends Actor {
  case TerminateProcess(processRecord: ProcessRecord) => processRecord.map.foreach { case (id, actorRef) => if (id != -1) system.stop(actorRef) }
  case TerminateTarry => system.terminate()
  case TreeTerminate => system.terminate()
  case EchoTerminate() => system.terminate()
- Failure: No code in src/main sends TerminateProcess. If some code did, it would stop the Franklin orchestrator at -2 but not the terminator, and it would never terminate the system. The Tarry, Tree and Echo messages do the same thing, so each new algorithm needs another case. context.system is already available, so the constructor parameter is redundant.
- Fix: Remove the Terminator. Let each algorithm complete a Promise or reply to its launcher with a typed Result, and let one lifecycle wrapper terminate the system (see infra-10).
- Verifier: No code in src/main sends TerminateProcess (grep). Its handler at Terminator.scala:19-24 skips only -1, so it would stop the -2 orchestrator and never terminate the system. There are three per-algorithm terminate cases that do the same thing, and the constructor takes a system parameter that context.system already gives.

### [low/confirmed] Franklin orchestrator: central synchronous barrier, leader flag taken from the last checkpoint, stale passive list
- `src/main/scala/main/utility/FranklinOrchestrator.scala:42` (algorithm-correctness)
- Evidence: case Checkpoint(id, isActive, isLeader) =>
  roundProcessCount += 1
  ...
  if (roundProcessCount == processes.size) {
    if (isLeader) { ... TerminateSystem }
    else { if (passiveProcess.isEmpty) initiateRound() else passiveProcess.foreach(...TurnPassive) }
// passiveProcess is never reset in initiateRound()
- Failure: (a) Fokkink's Franklin is a decentralised, asynchronous election in which messages carry round numbers. Here a central actor makes every round a global barrier, so the visualizer shows a protocol that is not the textbook one. (b) The orchestrator reads isLeader only from the checkpoint that arrives last. A leader flag in any earlier checkpoint of the same round is ignored. (c) The orchestrator never clears passiveProcess, so in every round after the first it sends TurnPassive again to processes that are already passive, waits for their Acks, and never uses the passiveProcess.isEmpty fast path. (d) Checkpoints carry no round number, so a late checkpoint counts toward the wrong round. (e) The 'system' parameter is unused, and the code uses Option.get on map lookups.
- Fix: Implement Franklin without an orchestrator. Each active process sends (id, round) both ways, and passive processes relay the messages. Keep the round in each message and stash messages that arrive early. If a barrier is wanted as a visual 'step' mode, put it in a separate simulation scheduler, not in the algorithm.
- Verifier: (a) The central barrier and (c) the passiveProcess list that is never cleared (FranklinOrchestrator.scala:11, not reset in initiateRound) are as described, and (e) system is unused and the code uses .get. But (b) is refuted: passive processes are removed from 'processes', and leftId==id holds only when one active process remains, so the leader's checkpoint is the only one in its round. (d) is refuted: the next round starts only after all checkpoints and acks, so no checkpoint can arrive late. (c) is wasteful but harmless, because each extra TurnPassive gets an Ack and the count still matches passiveProcess.size.

### [low/confirmed] Vulnerable or outdated versions: logback 1.0.9, Scala 2.13.8, config 1.2.1, Akka 2.6.18
- `build.sbt:15` (build-config)
- Evidence: "ch.qos.logback" % "logback-classic" % "1.0.9",
"com.typesafe" % "config" % "1.2.1",
ThisBuild / scalaVersion := "2.13.8"
- Failure: (a) logback 1.0.9 (2012) is affected by CVE-2017-5929 (deserialization RCE, fixed in 1.1.11), CVE-2023-6378 and CVE-2023-6481 (DoS), and CVE-2024-12798 and CVE-2024-12801. It was built against slf4j 1.7.2, while Akka brings slf4j 1.7.3x. (b) Scala 2.13.0 to 2.13.8 is affected by CVE-2022-36944 (LazyList deserialization gadget), which 2.13.9 fixes. (c) config 1.2.1 has no effect, because Akka 2.6.18 needs config 1.4.x and sbt evicts 1.2.1, but the pin misleads the reader. (d) Akka 2.6.18 is not the last 2.6 patch (2.6.21), and it is end of life. Akka 2.7 and later uses the Business Source License, which requires a commercial licence above a revenue threshold.
- Fix: Move to Scala 2.13.15+ or Scala 3 LTS. Move to Apache Pekko 1.x (Apache-2.0, API-compatible with Akka 2.6) instead of Akka 2.7+. Use logback 1.5.x and remove the explicit config pin.
- Verifier: build.sbt:15/16/4 has the versions as stated. The logback CVEs (2017-5929, 2023-6378) need the socket receiver or server, and the Scala CVE-2022-36944 needs deserialization of untrusted data. This offline CLI uses neither. config 1.2.1 is evicted by Akka's 1.4.x. Akka 2.6 is EOL, and moving to Pekko is a sound recommendation, but the security severity is low here.

### [low/confirmed] Mixed %% and hard-coded _2.13 suffixes; no compiler flags; stray import
- `build.sbt:10` (build-config)
- Evidence: import scala.collection.Seq
...
"com.typesafe.akka" %% "akka-actor" % "2.6.18",
"com.typesafe.akka" % "akka-actor-typed_2.13" % "2.6.18",
- Failure: If you change scalaVersion to 3.x or cross-build, akka-actor resolves to _3 but akka-actor-typed stays _2.13. sbt then fails with 'Modules were resolved with conflicting cross-version suffixes', or two binary versions get onto the classpath. The build has no scalacOptions (-deprecation, -Xlint, -Wunused, -Xfatal-warnings), so the compiler stays silent about deprecated mapValues (TopologyReader.scala:31), procedure syntax (ChandyLamportAlgorithm.scala:21/26) and unused imports. sbt reads 'import scala.collection.Seq' in build.sbt for no reason. No Test / parallelExecution setting exists, but the suites share global singletons.
- Fix: Use %% everywhere and keep the versions in vals. Add sbt-tpolecat or an explicit list of strict scalacOptions. Remove the import. Add scalafmt and scalafix.
- Verifier: build.sbt:10 vs 11-14 mixes %% with hard-coded _2.13. There are no scalacOptions, and build.sbt:1 has a stray 'import scala.collection.Seq'. These matter only on a cross-build or a Scala 3 move, so low.

### [low/confirmed] .gitignore lets machine-specific IDE and BSP files into git
- `.gitignore:1` (build-config)
- Evidence: *.class
.idea
target/
.DS_Store
- Failure: The repo tracks .bsp/sbt.json, which contains a former author's absolute paths ('/Users/aakashdasgupta/Library/Application Support/JetBrains/...sbt-launch.jar'). IntelliJ or Metals BSP import on any other machine reads this file and fails. The ignore list also omits project/target, project/project, .bsp, .metals, .bloop, .vscode and metals.sbt.
- Fix: Add .bsp/, .metals/, .bloop/, .vscode/, project/project/, project/target/ and metals.sbt to .gitignore, and remove .bsp/sbt.json from git with git rm --cached.
- Verifier: git ls-files lists .bsp/sbt.json, and that file contains /Users/aakashdasgupta/... paths. .gitignore has only 4 entries and omits .bsp, .metals, .bloop and project/target. IDEs usually offer to regenerate the file, so low.

### [low/confirmed] Bracha-Toueg deadlock data has In lists that contradict the Out lists
- `src/main/resources/application.conf:13` (build-config)
- Evidence: { Node = 0  Out= [1,3]  In = [] },
{ Node = 1  Out= [2]    In = [0] },
{ Node = 2  Out= [3]    In = [] },
{ Node = 3  Out= [0]    In = [0,2] }
- Failure: The Out lists give the edges 1->2 and 3->0. So node 2 should have In=[1], and node 0 should have In=[3]. Bracha-Toueg sends notify along Out edges and grant along In edges. Node 2 thinks that no process waits for it, and node 0 thinks the same, so the run explores a different wait-for graph from the one the file describes. The result 'deadlocked' is correct only by chance, and a visualizer that draws this data draws a graph that is not consistent. The data has no N-out-of-M request count, so only the AND model is possible.
- Fix: Store only the directed wait-for edges, plus an optional per-node request count k, and compute In from them. Validate the data when it is loaded.
- Verifier: application.conf:9-27 is inconsistent: 3 Out=[0] needs 0 In=[3], and 1 Out=[2] needs 2 In=[1]. But BrachaTouegProcess uses 'in' only in grant() and for the ack count, and in this data no node has requests==0, so no grants are sent. The inconsistency cannot change the result, and 'deadlocked' is correct for the real cycle 0->3->0, not 'by chance'. It is a data and visualizer problem, so low.

### [low/plausible] Test data sits in the main config; Franklin ids are the ring positions in sorted order
- `src/main/resources/application.conf:91` (test-quality)
- Evidence: FranklinAlgorithm = { TestData = { nodes: [ { id: 0, leftNeighbor: 4, rightNeighbor: 1 }, { id: 1, ... }, ... { id: 4, leftNeighbor: 3, rightNeighbor: 0 } ] } }
- Failure: The ids go 0,1,2,3,4 around the ring, so only node 4 is a local maximum, and Franklin ends after a single round. The stale-passive-list bug and the relay of messages through passive nodes in infra-14 never run. The id is also the actor address, so the data cannot express arbitrary UIDs, which Chang-Roberts can. Production code loads this 'TestData' section, the tests use it too, and two files are not referenced by any config: graph_50_nodes.dot and NetGraph_30-03-24-18-54-55.ngs.dot.
- Fix: Separate the node identity from the UID. Move the fixtures to src/test/resources, and generate rings with random UID permutations, for example with ScalaCheck. Remove the resource files that nothing uses, or reference them.
- Verifier: The ids 0..4 are in ring order (application.conf:91-117) and the data lives in the main config. graph_50_nodes.dot and NetGraph_30-03... are not referenced by any config. But the claims are partly wrong. The tests do not use ApplicationProperties (grep finds nothing in src/test). The relay through passive nodes does run: in round 1, node 4's LeftIdRequest passes through 3,2,1,0 and the response comes back the same way, so the run takes 2 rounds, not 1.

## tests

Static review of all 7 suites in src/test/scala (19 test cases). No test checks a final algorithm result. 15 of 19 cases check only the first fan-out of messages from one actor, or only that one terminal message arrives. The other 4 are 2 TopologyReader.hasCycle unit checks and 2 Chandy-Lamport/Lai-Yang control-message checks. None of these cases fails for the main bugs that exist in the code.

Per-test verdict (A = what the test asserts; B = does it still pass if the algorithm is broken):
- brachaTouegTest 'Notify when Initiated' (L20): A = the first Notify(1) goes to 2 and 3. B = yes; the grant, ack and done phases and the verdict are not checked.
- brachaTouegTest 'Notify when receives Notify' (L47): A = the same fan-out. It uses initiator=true and an unregistered sender 0. B = yes.
- brachaTouegTest 'Grant when no outgoing request' (L73): A = Grant(1) goes to 2 and 3. B = yes; it does not see that this initiator never decides.
- brachaTouegTest 'Done after acks' (L99): A = Grant then Done. B = yes for 'Done after the FIRST Ack' and for 'Done before Ack'. This test also terminates the shared ActorSystem.
- chandyLamportTest 'Marker on initiate' (L22): A = InitiateSnapshot(false) goes to both neighbours. B = yes; the recorded state and channel state are not checked.
- chandyLamportTest 'SendMessage' (L45): A = the probe gets PerformAction, and expectNoMessage() runs on testActor, so that check is vacuous. B = yes; the 'Increment'/'increment' bug is hidden.
- laiYangTest 'control message' (L24): A = (0,false) goes to both neighbours, and all counts are zero. B = yes for any bug in the counting logic.
- laiYangTest 'tag true' (L59): A = the probe gets a control message. The setup is invalid (duplicate id 0, asymmetric neighbours). B = yes; it does not see that the post-snapshot message is put into the snapshot state.
- franklinTest x2 (L21, L49): A = the Initiate fan-out and the Left/RightIdRequest fan-out. B = yes; leader election never runs.
- EchoTest (L28): A = EchoTerminate arrives on a depth-1 star. B = yes for bugs at inner (non-leaf) nodes. The test depends on actor names that are numbers.
- TarryTest x3 (L28, L50, L72): A = TerminateTarry arrives once. B = yes for duplicate or skipped channel traversals.
- TreeTest wave (L20): A = one TreeTerminate. B = yes when only one process decides or when the two deciders are not neighbours.
- TreeTest hasCycle x2 (L60, L71): these are real assertions, but they miss the false positive from duplicate edges and the crash on a missing key.

Untested algorithms: Chang-Roberts has no test (the README lists ChangRobertsTest.scala, but that file does not exist). Also untested: TopologyReader.readTopology, Terminator, ApplicationProperties, and all *Algorithm.main drivers.
Untested properties:
- Safety: exactly one leader (Chang-Roberts, Franklin), leader = max id, snapshot consistency (Chandy-Lamport, Lai-Yang), the Bracha-Toueg deadlock verdict, exactly two adjacent deciders (Tree), each channel traversed once in each direction (Tarry), a valid spanning tree (Echo).
- Liveness: termination of any complete run on any topology from resources/.

Root cause: the code writes each result only to the log, to global mutable objects, or as system.terminate(). Thus tests can only watch probe mailboxes. The visualizer also needs these results, so the same change helps both goals: an event trace or a pure step function that returns the result as a value.

Build risk (outside test scope, cannot confirm without sbt): build.sbt does not declare akka-slf4j, but every process imports akka.event.slf4j.Logger. If that is correct, no test compiles.

Files: /Users/dhruv/Desktop/CS553-DistributedAlgorithms/src/test/scala/*.scala, /Users/dhruv/Desktop/CS553-DistributedAlgorithms/build.sbt, /Users/dhruv/Desktop/CS553-DistributedAlgorithms/README.md.

### [high/confirmed] Chang-Roberts has no test; README lists a ChangRobertsTest.scala that does not exist
- `README.md:55` (test-quality)
- Evidence: README.md:55 `├── ChangRobertsTest.scala`; src/test/scala contains only brachaTouegTest, chandyLamportTest, EchoTest, franklinTest, laiYangTest, TarryTest, TreeTest. ChangRobertsProcess.scala:53-71 election logic and :74-93 ElectedMessage handling have zero coverage.
- Failure: Ring from application.conf (UIDs 10,20,5,15) with all 4 processes initiating: ChangRobertsProcess.scala:57 `else if (senderUid < uid && !participant)` and line 85-92 mean only the leader sends TerminateSystem; nothing checks that exactly one process sets electedLeader=Some(20) or that every process learns the leader. Any regression (e.g. swapping > for <, electing min UID) ships green.
- Fix: Add a ChangRobertsSpec that builds a ring of real actors with a TestProbe at -1, starts 1..n random initiators, and asserts exactly one ElectedMessage originator with the max UID and that every process ends with electedLeader == max. Remove the stale README line or add the file.
- Verifier: README.md:55 lists `├── ChangRobertsTest.scala`. src/test/scala has no such file, so no test covers ChangRobertsProcess.scala at all. The election logic at lines 53-71 and the ElectedMessage handling at 74-93 are as quoted. A regression such as electing the minimum UID would pass the suite.

### [high/confirmed] No Bracha-Toueg test checks the deadlock verdict; the verdict is only logged
- `src/test/scala/brachaTouegTest.scala:18` (test-quality)
- Evidence: BrachaTouegProcess.scala:120-125 `if (free) { logger.debug("No Deadlock found") } else { logger.debug("Process is Deadlocked!!") } processRecord.map(-1) ! TerminateSystem` -- the verdict is not part of any message. The 4 tests (lines 20,47,73,99) use 1-2 actors with probes; none runs ApplicationProperties.deadlockData / deadlockFreeData.
- Failure: Invert the condition to `if (!free)` or never set `free = true` in grant(): all 4 tests still pass, because none of them reaches the decision and the decision is invisible (debug log + identical TerminateSystem in both branches).
- Fix: Make the initiator send a result message (e.g. `Verdict(deadlocked: Boolean)`) to the -1 actor, then add end-to-end tests over the two application.conf graphs asserting Verdict(false) and Verdict(true). Better: extract the algorithm as a pure state machine `(State, Msg) => (State, List[Out])` and property-test it against a reference reduction of the wait-for graph.
- Verifier: BrachaTouegProcess.scala:120-125 only logs the verdict, and both branches send the same `TerminateSystem`. No test runs the application.conf deadlock graphs. One detail is wrong: test 4 does reach sendDone (it is an initiator and gets an Ack). The verdict is still invisible there, so inverting `free` passes.

### [high/confirmed] 'Done after acks' test uses a single in-edge and no expectNoMessage, so it cannot detect Done being sent after the first Ack
- `src/test/scala/brachaTouegTest.scala:117` (test-quality)
- Evidence: Test: `new BrachaTouegProcess(2, system, List(), List(1), ...)` ... `process1.expectMsg(Grant(2))` / `process2 ! Ack` / `process1.expectMsg(Done)`. Code BrachaTouegProcess.scala:78-82 `case Ack => ack -= 1 ... sendAck(); sendDone()` and sendDone (112-113) only checks `done == 0`, never `ack == 0`.
- Failure: Process 2 with out=[] in=[1,3] receives Notify(1): it grants to 1 and 3. Ack from 3 arrives first -> ack=1, sendDone sees done==0 and sends Done to 1 while the grant to 1 is still unacknowledged (Fokkink: notify awaits grant completion before done). With in=List(1) the first Ack is also the last, so the test passes. The test also never calls `process1.expectNoMessage()` before sending Ack, so an implementation that sends Done immediately after Grant would also pass.
- Fix: Use in=List(1,3) with two probes, assert `process1.expectNoMessage()` after the first Ack and `expectMsg(Done)` only after the second. Fix sendDone to require `ack == 0` when the process granted.
- Verifier: sendDone (lines 112-127) checks only `done == 0`, never `ack == 0`. With out=[] and in=[1,3], the first Ack makes the process send Done to 1 while a grant is still unacknowledged. The test uses in=List(1), so the first Ack is also the last. The test also never calls expectNoMessage between Grant and Ack, so it cannot detect a Done that comes too early.

### [high/confirmed] No Chandy-Lamport test inspects the snapshot; consistency of recorded channel state is untested
- `src/test/scala/chandyLamportTest.scala:38` (test-quality)
- Evidence: Test 1 asserts only `process1.expectMsg(InitiateSnapshot(false)); process2.expectMsg(InitiateSnapshot(false))`. The recorded data lives in the global `ChandyLamportAlgorithm.systemSnapshot` and is only logged (SnapshotUtils.scala:35). ChandyLamportProcess.scala:28-38: on the FIRST marker the code increments markersReceived but never adds `sender` to `stopRecordingOn`.
- Failure: Triangle 0-1-2, 0 initiates. Process 1 receives 0's marker first: records state, does not stop recording on 0->1. Process 0 then sends PerformAction to 1 (post-snapshot). Line 69 `if (this.snapshotTaken && !stopRecordingOn.contains(sender))` records it as in-transit on 0->1, although 0's recorded state predates sending it: the global snapshot is inconsistent (Fokkink: channel of the first marker is recorded empty). Test 1 passes.
- Fix: Expose the snapshot as data (each process sends `LocalSnapshot(id, state, channelStates)` to a collector/probe). Add tests: (a) first-marker channel is empty; (b) messages sent before the sender's marker and received after the receiver's snapshot are recorded; (c) a property test over random message interleavings that sum(recorded states) + in-transit increments equals the conserved total.
- Verifier: On the first marker, lines 28-38 increment markersReceived but do not add sender to stopRecordingOn. Akka keeps message order between one pair of actors, so a PerformAction that 0 sends after its marker arrives after the marker. Line 69 then records it as channel state while the process waits for markers from its other neighbours (degree >= 2, for example a triangle). The cut is inconsistent. No test reads systemSnapshot.

### [high/confirmed] Lai-Yang 'tag true' test has an invalid setup and cannot detect that the post-snapshot message is put into the recorded state
- `src/test/scala/laiYangTest.scala:63` (test-quality)
- Evidence: `process0 = ... LaiYangProcess(0, List(1), true)`; `process1 = ... LaiYangProcess(0, List(2), false)` (id 0 duplicated, neighbour lists asymmetric: process1 does not list 0). Only assertion: `process2.expectMsg(InitiateSnapshotWithMessageCount(0, start = false))`. Code LaiYangProcess.scala:87-93 applies the action first, then line 116 `self ! InitiateSnapshotWithMessageCount(-1, start = true)` takes the snapshot later, asynchronously.
- Failure: Fokkink: a process receiving a true-tagged message must take its snapshot BEFORE processing it. Here process1 updates storedVariable, then enqueues a self-message behind any other queued messages, so its snapshot includes the post-snapshot message (and possibly more). The test passes because it never reads systemSnapshot and because "increment" is a no-op (line 87 checks "Increment"), so storedVariable stays 0 either way. With duplicate id 0, process1's snapshot overwrites key 0 in systemSnapshot; process0 claims snapshotTaken=true without an entry, so any false-tagged message to it would throw at line 112 `systemSnapshot.systemSnapshot(id)`.
- Fix: Use distinct ids and symmetric neighbour lists. Assert on the recorded state: send a true-tagged Increment to an unsnapshotted process and assert recorded storedInteger == 0 and the message is not in any channel state. Fix the process to snapshot synchronously before applying the message.
- Verifier: The setup is as quoted: id 0 is duplicated and process1's neighbours are List(2). Lines 87-93 apply the action, and line 116 then queues a self-message, so the snapshot happens later. This is not the Fokkink rule, which takes the snapshot before the true-tagged message is processed. The test only checks the control message to process2. One sub-claim is wrong: process1 writes key 0 into systemSnapshot, so line 112 would find that entry and would not throw.

### [medium/confirmed] Grant test stops after the fan-out and misses that an initiator with out=[] never decides when in=[] and decides on the first Ack otherwise
- `src/test/scala/brachaTouegTest.scala:90` (test-quality)
- Evidence: Test: out=List(), in=List(2,3), `process1 ! Initiate`, then only `process2.expectMsg(Grant(1)); process3.expectMsg(Grant(1))`. Code: notifyProc (97-107) calls grant() but never sendDone(); sendDone is only reached from Done/Ack (70-82).
- Failure: Initiator with out=[] and in=[] (isolated, trivially not deadlocked): Initiate -> notifyProc -> grant() sends nothing -> no Ack ever arrives -> TerminateSystem never sent -> run hangs until the 30 s Await in BrachaTouegAlgorithm. With in=[2,3], the initiator decides 'free' on the first Ack before the grant completes. Neither case is tested.
- Fix: Extend the test: register a -1 probe, reply Ack from both probes, and assert the verdict arrives only after the second Ack; add a case with in=List() asserting a verdict arrives at all.
- Verifier: notifyProc (97-107) calls grant() but never sendDone(). sendDone runs only from Done or Ack. An isolated initiator (out=[] and in=[]) therefore never sends TerminateSystem, and BrachaTouegAlgorithm.scala:53 waits 30 seconds. With in=[2,3], the first Ack gives ack=1, sendDone sees done==0, and the initiator decides too early. The test stops after the Grant fan-out, so it detects neither case.

### [medium/confirmed] Chandy-Lamport SendMessage test's expectNoMessage checks testActor, not the probe, and the test hides the 'Increment' vs 'increment' bug
- `src/test/scala/chandyLamportTest.scala:62` (test-quality)
- Evidence: `process1.expectMsgAnyOf(PerformAction("increment"))` then `expectNoMessage()` (on testActor via ImplicitSender; process0 never messages testActor, so this is vacuous). ChandyLamportProcess.scala:61 `if (action.equals("Increment"))` while the driver (ChandyLamportAlgorithm.scala:63) and the test send "increment".
- Failure: process0 sends the PerformAction to process1 twice, or to every neighbour: test passes (only the probe's first message is read; the no-message check is on the wrong actor). Separately, in a real run every 'increment' is a no-op, so storedVariable only decreases and snapshots record wrong values; no test reads storedVariable or the recorded state.
- Fix: Call `process1.expectNoMessage()` on the probe. Add a test that sends PerformAction("increment") to a real process, then triggers a snapshot and asserts the recorded storedInteger == 1. Replace String actions with a sealed ADT (`Increment`/`Decrement`) so the case mismatch cannot compile.
- Verifier: Line 62 `expectNoMessage()` runs on testActor, not on the probe process1. process0 never sends to testActor, so the check is vacuous. ChandyLamportProcess.scala:61 compares with "Increment", but the driver and the test send "increment", so every increment does nothing. The test only checks fan-out, so it cannot detect this. Severity drops to medium because the case-mismatch bug is in main code and belongs to another scope.

### [medium/confirmed] Lai-Yang control-message test uses all-zero counters and pokes global mutable state that is never reset
- `src/test/scala/laiYangTest.scala:41` (test-quality)
- Evidence: `LaiYangAlgorithm.MessageCountStore.preSnapshotMessagesSent ++= Map(process0 -> mutable.Map(process1.ref -> 0, process2.ref -> 0), ...)` and assertion `process1.expectMsg(InitiateSnapshotWithMessageCount(0, false))`. MessageCountStore is a global `object` (LaiYangAlgorithm.scala:22-25) and is never cleared between tests or suites.
- Failure: Change LaiYangProcess.scala:60 to always send 0, or drop the increment at line 79: the test still passes because every count is 0. Test 2 appends to the same global maps, so stale entries from test 1 stay; a future test that reuses an ActorRef key or runs main() concurrently (sbt runs suites in parallel in one JVM) reads wrong counts.
- Fix: Send k pre-snapshot messages first and assert the control message carries k; assert the receiver stops recording only after k false-tagged messages. Move the counters into actor state (constructor parameters) instead of a global object so tests need no white-box setup.
- Verifier: Every counter is 0, so the test passes if line 60 always sends 0 or if the increment at line 79 is removed. MessageCountStore is a global object that no code clears. The stale entries do no harm today because their ActorRef keys are distinct. The isolation hazard is real, but it is only a future risk.

### [medium/confirmed] Franklin tests check only request fan-out; leader election, Checkpoint logic and orchestrator rounds are untested
- `src/test/scala/franklinTest.scala:44` (test-quality)
- Evidence: Test 1 asserts `process2.expectMsg(Initiate); process3.expectMsg(Initiate)`; test 2 asserts `left.expectMsg(LeftIdRequest); right.expectMsg(RightIdRequest)`. Untested: FranklinProcess.compareIds (79-91), passive forwarding (37-68), FranklinOrchestrator Checkpoint/Ack (26-65). Suite is also named `ActorSystem("BrachaTouegProcessSpec")` (copy-paste).
- Failure: FranklinOrchestrator.scala:42 decides leadership from the `isLeader` flag of whichever Checkpoint arrives last, and `passiveProcess` (line 11) is never cleared between rounds, so earlier passive processes get TurnPassive again each round. Changing `max(...) == id` to `min(...) == id` at FranklinProcess.scala:84 (electing the minimum) passes both tests. Test 2's process has no -2 entry, so compareIds would crash with `None.get` once responses arrive; the test never sends responses.
- Fix: Add an end-to-end test on the 5-node ring from application.conf with a probe at -1, asserting the leader is max id and exactly one leader. Unit-test compareIds by feeding LeftIdResponse/RightIdResponse and asserting the Checkpoint sent to a -2 probe. Test passive forwarding with a chain of passive processes.
- Verifier: Both tests assert only request fan-out. FranklinOrchestrator.scala:42 uses the isLeader flag of the last Checkpoint, and no code ever clears passiveProcess (line 11). If line 84 changed max to min, the tests would still pass. The process in test 2 has no -2 entry, so `.get` would throw if responses arrived. The test never sends any responses. The suite also reuses the name "BrachaTouegProcessSpec".

### [medium/confirmed] Echo test uses a depth-1 star, so the inner-node branch is never run, and it depends on actor names that are numbers
- `src/test/scala/EchoTest.scala:32` (test-quality)
- Evidence: `EchoProcess(1, List(2, 3), initiator = true, ...), "1"`, leaves 2 and 3 have one neighbour. Only assertion `terminatorProbe.expectMsg(EchoTerminate())`. EchoProcess.scala:32 `val senderId = sender().path.name.toInt`; branch lines 40-46 (`if (neighbors.size > 1)`) is unreachable in this topology.
- Failure: Break lines 40-46 (e.g. send the wave back to the parent instead of to the other neighbours): test still passes. A cycle topology (inputEcho.dot has cycles 1-2-5, 1-4-5) is never run. No check that only one EchoTerminate is sent (no expectNoMessage), that parents form a spanning tree, or that 2E messages are exchanged. Renaming actors to "process1" (the convention used by every other driver) throws NumberFormatException inside the actor.
- Fix: Add tests on inputEcho.dot and on a ring and a line, assert exactly one decide and parent tree validity (emit `ParentChosen(id, parent)` events to a probe). Carry the sender id in the message (`EchoWave(from: Int)`) instead of parsing the actor path.
- Verifier: In the star topology, leaves 2 and 3 have one neighbour each, so the branch at lines 40-46 (`neighbors.size > 1`) never runs. The test asserts one EchoTerminate and never calls expectNoMessage. Line 32 parses `sender().path.name.toInt`. EchoAlgorithm.scala:45 also uses numeric actor names, so the dependence on the actor name is real but has no effect today.

### [medium/confirmed] Tarry tests assert only that TerminateTarry arrives once; the traversal property is never checked
- `src/test/scala/TarryTest.scala:47` (test-quality)
- Evidence: All three tests end with `terminatorProbe.expectMsg(TerminateTarry)`; no expectNoMessage, no inspection of token route. TarryProcess.scala:41-44 also lets the initiator set `parent = sid` on the first returning token. Import `TarryProbe` is unused.
- Failure: A bug that sends the token twice through one channel, skips a channel, or decides before all channels are used would still pass, as long as the initiator eventually sends one TerminateTarry. inputTarry.dot (cycles 1-2-5-3) is not tested; only two trees and a 4-ring are. Duplicate TerminateTarry messages are not detected.
- Fix: Emit `TokenSent(from, to)` events to a probe and assert each undirected edge is traversed exactly once per direction (2E events), parent edges are used last, and exactly one TerminateTarry is sent. Add inputTarry.dot and a complete graph.
- Verifier: All three tests only expect TerminateTarry, and none calls expectNoMessage or checks the token route. TarryProcess.scala:41-44 sets parent on the initiator when the token first returns. The filter at line 58 (`n != parent`) can then make the initiator skip a channel. The tests would not detect that. TarryProbe is imported but not used.

### [medium/confirmed] Tree wave test expects one TreeTerminate; it does not check that exactly two adjacent processes decide
- `src/test/scala/TreeTest.scala:57` (test-quality)
- Evidence: `terminatorProbe.expectMsg(TreeTerminate)` only. TreeProcess.scala:46-48 sends TreeTerminate on every Decide; leaves are triggered by `process5 ! Wave` from testActor, which works only because TreeProcess.getSender (82-84) maps the unknown sender to -1.
- Failure: Fokkink: in the tree algorithm exactly two neighbouring processes decide. If every process decided, or a single process decided, or two non-adjacent processes decided, the test still passes because it reads only the first TreeTerminate. Sending Wave to a non-leaf from testActor would also be treated as a real neighbour message.
- Fix: Make Decide carry the id (`TreeTerminate(id)`), collect all messages with `receiveWhile`/`expectMsgAllOf`, assert exactly two deciders that are neighbours, and `expectNoMessage` afterwards. Use an explicit `Start` message for leaves instead of a fake Wave.
- Verifier: The test reads only the first TreeTerminate. The leaves receive Wave from testActor, and getSender (82-84) maps that sender to -1. Then filterNot(received) still leaves the real neighbour as the only one not heard from, so the wave starts. The test does not check that exactly two adjacent processes decide.

### [medium/confirmed] hasCycle tests miss the duplicate-edge false positive, missing-key crash and disconnected forests; readTopology is untested
- `src/test/scala/TreeTest.scala:68` (test-quality)
- Evidence: Only two hand-built maps with no duplicate neighbours. TopologyReader.scala:28 emits `List((from, to), (to, from))` per line, so a dot file containing both "a" -> "b" and "b" -> "a" yields a: [b, b]. TopologyReader.scala:54 `processConfig(node).foreach` and 57 `else if (visited.contains(neighbor)) return true`.
- Failure: Tree a-b listed in both directions: dfs(a) visits b, then sees the second b, which is visited and is not the parent -> returns true, so TreeAlgorithm.main refuses a valid tree. A neighbour that is not a key throws NoSuchElementException. A disconnected acyclic graph returns false and the Tree algorithm runs on a forest (each component decides). None of these cases is tested.
- Fix: Add cases: duplicate edges, missing key, forest (and add an `isConnected` check), self-loop. Add readTopology tests on small inline dot strings (refactor it to take lines, not a filename) asserting deduplicated, symmetric adjacency.
- Verifier: One line number is wrong: the List((from,to),(to,from)) pair is at TopologyReader.scala:25, not 28. Lines 54 and 57 are correct. If a dot file has a->b and b->a, readTopology gives a:[b,b], and the second b makes hasCycle return true for a valid tree. readTopology output is always symmetric, so the missing-key crash cannot happen in production, only with hand-built maps. The tests cover neither case, and no test covers a forest.

### [medium/confirmed] Algorithm outcomes are side effects (logs, global vars, system.terminate), so tests can only assert mailbox fan-out
- `src/test/scala/chandyLamportTest.scala:36` (fp-design)
- Evidence: Test calls `ChandyLamportAlgorithm.init(processRecord)` to set global `var processRecord`/`var systemSnapshot` (ChandyLamportAlgorithm.scala:15-24); results are printed via `log.info` (SnapshotUtils.scala:35); BrachaToueg verdict is a debug log (BrachaTouegProcess.scala:121-123); Franklin leader is a log line (FranklinOrchestrator.scala:43); drivers busy-loop on `while (numberOfProcessesCompletingSnapshot < ...)` (ChandyLamportAlgorithm.scala:53) on an unsynchronized global var.
- Failure: Every test in the suite is limited to 'actor X sent message Y next'. No test can assert 'one leader', 'consistent cut' or 'deadlocked' without parsing logs. The same gap blocks the visualizer, which needs an ordered event stream and a final result. The driver loops cannot be tested at all (non-deterministic Random, data race on the counter, no return value).
- Fix: Model each algorithm as a pure transition function `step(state, event): (state, List[Effect])` with a sealed message ADT. Run it under a deterministic scheduler in tests (property-based with ScalaCheck over random delivery orders) and under Akka Typed in production. Emit a `Trace` of events that both the tests and the visualizer consume. Return the result (`Leader(id)`, `Snapshot`, `Verdict`) as a value instead of logging it.
- Verifier: All cited lines are correct. ChandyLamportAlgorithm.scala:15-24 holds global vars. SnapshotUtils.scala:35 only logs, BrachaTouegProcess.scala:121-123 logs the verdict, and FranklinOrchestrator.scala:43 logs the leader. ChandyLamportAlgorithm.scala:53 busy-waits on an unsynchronized var that actors change. Algorithm results are side effects, so tests can only check message fan-out.

### [low/confirmed] Test 4 marks the process under test as initiator and wires a real Terminator, so it shuts down the shared TestKit ActorSystem
- `src/test/scala/brachaTouegTest.scala:105` (test-quality)
- Evidence: `new BrachaTouegProcess(2, system, List(), List(1), initiator = true, processRecord)` and `val terminator = system.actorOf(Props(new Terminator(system)))`; on Ack, BrachaTouegProcess.scala:119-125 sends TerminateSystem and Terminator.scala:15-17 calls `system.terminate()`.
- Failure: The scenario under test (a process that receives Notify from 1) is a non-initiator, but initiator=true makes it terminate the suite's ActorSystem. Today it is the last test so it is hidden; adding any test after it, or running with a randomized order, fails with 'cannot create children while terminating or terminated' / probes timing out.
- Fix: Set initiator=false and register a TestProbe as -1 (assert it receives nothing). Never inject a Terminator that calls system.terminate() into a TestKit system; use one ActorSystem per test or a probe.
- Verifier: Line 105 uses initiator = true and line 106 wires a real Terminator. On Ack, sendDone sends TerminateSystem and Terminator.scala:15-17 calls `system.terminate()` on the shared TestKit system. ScalaTest runs tests in declaration order and this is the last test, so nothing fails today. The problem is latent, so severity drops to low.

### [low/confirmed] Notify tests assert only the first fan-out, use an impossible setup, and leave the 'already notified -> Done' branch untested
- `src/test/scala/brachaTouegTest.scala:64` (test-quality)
- Evidence: Test 2: `new BrachaTouegProcess(1, ..., initiator = true, ...)` then `process1 ! Notify(0)` where id 0 is not in processRecord. Branch BrachaTouegProcess.scala:50-51 `else { processRecord.map.get(nid).get ! Done }` is never exercised by any test.
- Failure: Delete the else branch or change it to send Ack: all tests pass. A process receiving Notify(0) would later crash in sendDone at `processRecord.map(sid)` (key 0 missing) if any Done arrived; the test never gets that far. Tests 1 and 2 also pass if notifyProc sends Notify but then does nothing else.
- Fix: Use initiator=false for the receiving process, register sender 0 as a probe, send a second Notify and assert the probe gets Done; assert Done to the notifier only after all out-neighbours reply Done.
- Verifier: Test 2 sends `Notify(0)`, and id 0 is not in processRecord. No test runs the else branch at line 51 (`processRecord.map.get(nid).get ! Done`). If done ever reached 0, the pushed id 0 would make `processRecord.map(sid)` throw. The tests stop before that point.

### [low/refuted] akka-slf4j is not declared but every process imports akka.event.slf4j.Logger; the test suite may not compile (unverified)
- `build.sbt:9` (build-config)
- Evidence: build.sbt dependencies: akka-actor, akka-actor-typed, akka-stream-typed, akka-http, akka-http-spray-json, logback-classic, config, akka-testkit, scalatest -- no `akka-slf4j`. Sources: `import akka.event.slf4j.Logger` in BrachaTouegProcess.scala:4, ChandyLamportProcess.scala:4, EchoProcess.scala:4, etc.
- Failure: `sbt test` fails at compile with 'object slf4j is not a member of package akka.event' for every process class. In that case no test in src/test/scala ever ran in this state. (Static judgement only; no sbt/JDK to confirm. The object is in the akka-slf4j artifact, and none of the declared modules pulls it in transitively to my knowledge.)
- Fix: Add `"com.typesafe.akka" %% "akka-slf4j" % "2.6.18"` (or switch to `context.log`/ActorLogging), use `%%` consistently instead of hardcoded `_2.13`, align logback to a 1.2.x version, and verify with `sbt test` in CI.
- Verifier: In Akka 2.6, akka-actor-typed depends on akka-slf4j (the typed `context.log` is an org.slf4j.Logger), and build.sbt:11 declares akka-actor-typed_2.13 2.6.18. akka.event.slf4j.Logger therefore comes in transitively, and the import compiles. The missing direct dependency is a small hygiene issue, not a compile failure.

### [low/confirmed] One shared ActorSystem per suite with leftover actors and global objects; no per-test isolation
- `src/test/scala/laiYangTest.scala:12` (test-quality)
- Evidence: `class laiYangTest extends TestKit(ActorSystem("SnapshotTest"))` (same name in chandyLamportTest.scala:10; franklinTest.scala:9 reuses "BrachaTouegProcessSpec"). Actors from each test (e.g. `process0_test1`) stay alive for the rest of the suite; `LaiYangAlgorithm.init` replaces the global `systemSnapshot` that those older actors still read.
- Failure: A leftover actor from test 1 that receives a late message writes into test 2's systemSnapshot. sbt runs suites in parallel in one JVM by default (Test / parallelExecution = true), so any future test that calls LaiYangAlgorithm.main() or ChandyLamportAlgorithm.main() would race with these suites on the same global vars and the MessageCountStore. Test names read 'X should should ...' because of the duplicated word.
- Fix: Create a fresh ActorSystem (or ActorTestKit for typed) per test, stop actors in a fixture, remove global mutable objects, and give systems unique names. Drop the redundant 'should' in test names.
- Verifier: Each suite shares one TestKit ActorSystem, and the names are reused ("SnapshotTest" twice, "BrachaTouegProcessSpec" in franklinTest). Actors from earlier tests stay alive. LaiYangAlgorithm.init and ChandyLamportAlgorithm.init replace the global systemSnapshot that those actors read. The test names say "should should". No test fails today.

## fp-design

Current architecture (static read of all 23 files under src/main/scala):
1. Main.scala is an `App` with a `var choice` do-while menu. It calls one of 8 `XAlgorithm.main(): Unit` objects.
2. Each XAlgorithm object starts a new classic `ActorSystem`. It reads topology from application.conf or a .dot file through `TopologyReader`, which returns `Map[String, List[String]]`.
3. Each object creates one untyped classic `Actor` for each node (`XProcess`). It puts the ActorRef into a shared `ProcessRecord`, which is a `mutable.Map[Int, ActorRef]`. The same map holds magic IDs: -1 for the Terminator and -2 for the FranklinOrchestrator.
4. Each actor keeps its algorithm state in `var` fields and mutable collections. The logic sends to neighbours through `processRecord.map(id) ! msg` and matches on `Any`.
5. All messages are case classes and objects that extend one unsealed `trait MessageTypes`. The 8 algorithms share some of them (`Initiate`, `Ack`, `Done`).
6. The algorithms report results only through slf4j `log.info`/`debug` and `println`. No algorithm returns a value or records an event trace.
7. A Terminator actor ends the run when it receives one of 4 duplicated "terminate" messages. The caller then blocks in `Await.ready(system.whenTerminated, 30.seconds)` and ignores a timeout.
8. Chandy-Lamport and Lai-Yang keep global mutable `var`s in their companion objects. Actor threads write these vars, and the main thread busy-loops on them while it sends random traffic.
9. Franklin uses a central orchestrator actor as a global barrier for each round. This is not the textbook decentralised algorithm.
10. The code has no pure step function, no seeded randomness and no event log. This makes the algorithms hard to test, impossible to replay deterministically, and not ready for a visualizer.

Target design (recommended): one `trait Protocol[S, M]` with `init(node, topology): (S, List[Send[M]])` and `step(node, S, from, M): (S, List[Send[M]], List[Event])`. Add one shared deterministic simulator with a seeded scheduler that produces `Vector[TraceEvent]`. Keep one optional Akka Typed runtime adapter for each protocol. The visualizer then consumes the trace.

### [high/confirmed] Algorithm logic is fused with the Akka runtime: there is no pure (state, message) => (state, outgoing) step function in any of the 8 algorithms
- `src/main/scala/main/processes/BrachaTouegProcess.scala:23` (fp-design)
- Evidence: class BrachaTouegProcess(val id: Int, val system: ActorSystem, val out: List[Integer], val in: List[Integer], val initiator: Boolean, val processRecord: ProcessRecord) extends Actor {
  var notifyFlag = false
  ...
    case Notify(nid) => ... processRecord.map.get(nid).get ! Done
The same pattern is in EchoProcess.scala:24-62, TarryProcess.scala:28-68, TreeProcess.scala:26-49, ChangRobertsProcess.scala:32-94, FranklinProcess.scala:22-76, ChandyLamportProcess.scala:24-73 and LaiYangProcess.scala:37-118.
- Failure: A visualizer must show the state of each node after each message delivery. Each transition here mutates `var`s and sends with `!` inside the handler, so the only way to observe a step is to run a real ActorSystem and scrape the logs. The visualizer cannot step forward or back, cannot pause between deliveries, and cannot let the user choose which in-flight message to deliver next. Unit tests also need TestKit and timing (`expectNoMessage`) where a plain function call would be enough.
- Fix: For each algorithm, define `final case class NodeState(...)`, a `sealed trait Msg`, and `def step(self: NodeId, s: NodeState, from: NodeId, m: Msg): (NodeState, List[Send], List[Event])`, with `Send(to: NodeId, msg: Msg)`. Put the 8 algorithms behind one `trait Protocol { type S; type M; def init(...); def step(...) }`. Write one runtime-agnostic simulator, and add an Akka Typed adapter only if real concurrency is still wanted.
- Verifier: The quoted code is real. BrachaTouegProcess.scala:23-85 and the other 7 process classes mutate `var`s and call `!` inside `receive`. None of them has a pure step function. This is an architecture gap for the visualizer goal, not a runtime defect, so high fits better than critical.

### [high/confirmed] Global mutable vars in algorithm objects are written by actor threads and busy-polled by the main thread (data race, possible infinite loop)
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:15` (concurrency)
- Evidence: object ChandyLamportAlgorithm {
  var systemSnapshot: SystemSnapshot = null
  var processRecord: ProcessRecord = null
  var snapshotTaken = false
  var numberOfProcessesCompletingSnapshot = 0
...
    while (numberOfProcessesCompletingSnapshot < processRecord.map.size) {
ChandyLamportProcess.scala:52: `numberOfProcessesCompletingSnapshot += 1`. LaiYangAlgorithm.scala:16-19 and :80 and LaiYangProcess.scala:69,110 repeat the pattern.
- Failure: Several actors run on different dispatcher threads and complete their snapshot at the same time. Each one does a read-modify-write `+= 1` on a non-volatile `Int`, so increments get lost (two actors read 5 and both write 6). The counter then never reaches `processRecord.map.size`. The main thread spins forever and floods the mailboxes with `SendMessage` until OutOfMemoryError. Because the field is not `@volatile`, the JIT can also hoist the read out of the `while` loop, so the loop never terminates even without lost increments. `systemSnapshot.systemSnapshot` (a `mutable.Map`) also receives concurrent `+=` calls from many actors (ChandyLamportProcess.scala:31), which can corrupt the HashMap.
- Fix: Remove all `var`s from the companion objects. Have each process send `SnapshotComplete(id, localState, channelState)` to a collector, or better, return it as an `Event` from the pure step function. Make the simulator decide termination when the trace contains N completion events. Replace the busy loop with a scripted, seeded schedule of application events.
- Verifier: ChandyLamportAlgorithm.scala:15-18 and :53 spin on the non-volatile `numberOfProcessesCompletingSnapshot`. ChandyLamportProcess.scala:52 does an unsynchronised `+= 1` on it from actor threads. LaiYangProcess.scala:69,110 does the same, and ChandyLamportProcess.scala:31 does concurrent `+=` into the shared mutable.Map. Lost updates are a real data race, and the loop floods mailboxes until the count is reached. JIT hoisting is unlikely, because the loop body calls `!` (a CAS on the mailbox queue) and Random. The lost-increment hang needs two completions at the same moment, so high is better than critical.

### [high/confirmed] Unseeded global Random drives initiator choice, message targets and scheduling, so runs cannot be replayed
- `src/main/scala/main/algorithms/ChangRobertsAlgorithm.scala:58` (fp-design)
- Evidence: val numInitiators = Random.nextInt(numNodes) + 1
val randomInitiators = Random.shuffle(record.map.keys.toList).take(numInitiators)
The same pattern is in EchoAlgorithm.scala:39, TarrysAlgorithm.scala:37, ChandyLamportAlgorithm.scala:55-61, LaiYangAlgorithm.scala:81-87, ChandyLamportProcess.scala:56 and LaiYangProcess.scala:78.
- Failure: A user sees an interesting interleaving in the visualizer (for example, a Chandy-Lamport marker that overtakes an application message) and wants to replay it or share it. Nothing captures the seed, and the Akka dispatcher adds its own thread nondeterminism on top. The same input therefore gives a different trace on each run, and a failing test cannot be reproduced. Note also that `numNodes = record.map.size` includes the Terminator at key -1 (line 50 before line 55). The code can then pick -1 as an "initiator" and send `Initiate` to the Terminator, which does not handle it.
- Fix: Pass a seed (or a `scala.util.Random(seed)` instance, or a pure `RNG` state in the State/StateT style) into the simulator. Make the scheduler choose which pending message to deliver next from a seeded or user-driven policy. Store the seed in the trace header so that the visualizer can replay it exactly.
- Verifier: Unseeded Random is used at every cited site. The secondary bug is real and concrete. ChangRobertsAlgorithm.scala:50 puts the Terminator at -1 before :55 `numNodes = record.map.size` and :61 shuffles `record.map.keys`. With 4 nodes, the code can select -1 as the only initiator (about 1/5 x 1/5 = 4%). Terminator has no `Initiate` case, so no election starts and the run waits 30 s.

### [high/confirmed] Algorithm results go only to logs and println; main() returns Unit and no event trace exists
- `src/main/scala/main/processes/BrachaTouegProcess.scala:120` (fp-design)
- Evidence: if (initiator) {
  if (free) {
    logger.debug("No Deadlock found")
  } else {
    logger.debug("Process is Deadlocked!!")
  }
Line 60: `println("total request 0 at " + id + " executing grant procedures")`. SnapshotUtils.scala:35 has `log.info(sb.toString())`. Every `XAlgorithm.main()` is `Unit`.
- Failure: The verdict of the deadlock detector exists only as a DEBUG log line. Nothing returns it, so no test can assert "deadlock = true" for the deadlock configuration. Tests can only assert on intermediate messages. A visualizer has no structured stream of (step, from, to, message, stateBefore, stateAfter) to render. It would have to parse free-form logs that also mix `println` and slf4j output.
- Fix: Have every algorithm return a typed result (`DeadlockResult(initiatorFree: Boolean)`, `Elected(leader: NodeId)`, `Snapshot(Map[NodeId, LocalState], Map[Channel, List[Msg]])`) and a `Vector[TraceEvent]` with `TraceEvent = Delivered(step, from, to, msg) | StateChanged(node, before, after) | Decided(node, ...)`. Keep logging as an optional interpreter of the trace.
- Verifier: BrachaTouegProcess.scala:120-125 writes the verdict only to `logger.debug`, and :60 and :133 use println. Every `main()` returns Unit. No test can assert the deadlock outcome. This directly blocks both of the user's goals: meaningful tests and a trace for the visualizer.

### [medium/plausible] Shared mutable.Map of ActorRefs is the addressing layer, mutated concurrently and overloaded with magic IDs -1/-2
- `src/main/scala/main/utility/ProcessRecord.scala:11` (fp-design)
- Evidence: class ProcessRecord {
  val map = mutable.Map.empty[Int, ActorRef]
}
EchoAlgorithm.scala:51-55:
    processRecord.map.get(randomInitiatorId).foreach(_ ! AllProcessesCreated)
    val terminator = system.actorOf(Props(new Terminator(system)), "terminator")
    processRecord.map += (-1 -> terminator)
FranklinAlgorithm.scala:61 `record.map.put(-2, orchestrator)`
- Failure: In EchoAlgorithm the main thread starts the wave (line 51) and only then inserts the terminator (line 55). On a small graph the initiator can finish before line 55 runs. `processRecord.map.get(-1).foreach(_ ! EchoTerminate())` then finds None and silently does nothing, so the run hangs for the full 30 s timeout. Actor threads also read the unsynchronised `mutable.HashMap` while the main thread writes to it, which is undefined under the JMM. ID -1 can also collide with a real node: a .dot file with a node "-1" would overwrite the Terminator.
- Fix: Build an immutable `Map[NodeId, ActorRef[Msg]]` (or nothing at all, in the pure simulator) before any message is sent, and pass it in the constructor or a `Start(neighbours)` message. Do not store control actors in the node address map. Pass them as separate typed references (`replyTo: ActorRef[Result]`).
- Verifier: EchoAlgorithm.scala:51 sends AllProcessesCreated before :54-55 inserts -1. The insert has no happens-before edge to the actors. EchoProcess.scala:58 uses `get(-1).foreach`, so a miss is silent and the run waits the full 30 s. The race needs the wave to finish before the main thread's next `actorOf` call, which is possible on small graphs but not usual. The '-1' node collision needs an unusual .dot file. Tarry, Tree, ChangRoberts and BT insert -1 before the start message, so only Echo has the race.

### [medium/confirmed] Unsealed single message trait, untyped classic actors, and parenthesised case classes matched as companion objects
- `src/main/scala/main/utility/MessageTypes.scala:6` (fp-design)
- Evidence: trait MessageTypes

case class Initiate() extends MessageTypes
...
case class Ack() extends MessageTypes
case class TerminateSystem() extends MessageTypes
...
case class Wave() extends MessageTypes
case class Info() extends MessageTypes
case class Decide() extends MessageTypes
The senders use `! Initiate` and the handlers use `case Initiate =>` (BrachaTouegProcess.scala:40, FranklinProcess.scala:24). Terminator.scala:15 uses `case TerminateSystem =>`. build.sbt:11 pulls in akka-actor-typed, but no file uses it.
- Failure: `! Initiate` sends the companion object `Initiate.type`, not an `Initiate` instance. The handler `case Initiate =>` matches only that companion object. A contributor who writes the natural `! Initiate()` sends a real instance. No case matches it, so the message goes to `unhandled` (dead letters) and the algorithm hangs silently. For example, `processRecord.map(-1) ! TerminateSystem()` never stops the system. Because `receive` is `PartialFunction[Any, Unit]` and the trait is not sealed, the compiler gives no exhaustiveness check and no type error. The protocols of the algorithms also mix: Franklin and Bracha-Toueg both use the same `Ack`/`Initiate`, so a misrouted message is accepted silently.
- Fix: Define one `sealed trait` for each algorithm (`sealed trait EchoMsg; case object Wave extends EchoMsg`). Use `case object` for messages without payload. Move to Akka Typed `Behavior[EchoMsg]`, or keep the protocol pure and have no actor at all. Remove `object EchoAlgorithm extends MessageTypes` and the similar Tarry and Tree objects, which make no sense.
- Verifier: MessageTypes.scala:6 is an unsealed trait. The payload-free messages are `case class X()`. No file imports akka typed. In the current code, every sender uses `! X` and every handler uses `case X`, both on the companion object, so nothing misroutes today. Echo uses `EchoWave()` on both sides. The failure needs a future edit, so it is a real design hazard but not a live bug. Medium.

### [medium/confirmed] Stringly-typed application operations: "increment" is sent but "Increment" is compared, so the snapshot state never increases
- `src/main/scala/main/processes/ChandyLamportProcess.scala:61` (fp-design)
- Evidence: ChandyLamportAlgorithm.scala:63 `val operation = if (selectedKey == 1) "increment" else "decrement"`
ChandyLamportProcess.scala:61 `if (action.equals("Increment")) {`
LaiYangProcess.scala:87 `if (action.equals("Increment")) {`
- Failure: Every "increment" message falls through both branches without effect, while every "decrement" message applies. `storedVariable` therefore only decreases (0, -1, -2, ...). The recorded snapshot values are meaningless as a consistency check: the sum of local states and in-channel messages cannot be checked against the total of operations sent. A visualizer that shows node values would show wrong data.
- Fix: Use `sealed trait Op; case object Inc extends Op; case object Dec extends Op` with `def apply(op: Op, v: Int): Int = op match { case Inc => v + 1; case Dec => v - 1 }`. With an exhaustive match the compiler rejects this class of bug.
- Verifier: ChandyLamportAlgorithm.scala:63 and LaiYangAlgorithm.scala:88 send "increment". ChandyLamportProcess.scala:61 and LaiYangProcess.scala:87 compare with "Increment". The tests also send "increment" (chandyLamportTest.scala:59, laiYangTest.scala:88), so increments are no-ops everywhere. This is a real bug. The snapshot protocol itself is unaffected, and the decrements stay consistent, so medium is more accurate than high.

### [medium/plausible] Stringly-typed node IDs and identity derived from actor names or reverse map scans
- `src/main/scala/main/utility/TopologyReader.scala:15` (fp-design)
- Evidence: def readTopology(filename: String): Map[String, List[String]] = {
The callers then call `.toInt` everywhere (TarrysAlgorithm.scala:35,44, TreeAlgorithm.scala:37, ChandyLamportAlgorithm.scala:46). EchoProcess.scala:32 has `val senderId = sender().path.name.toInt`, and TreeProcess.scala:83 has `processRecord.map.find(_._2 == actorRef).map(_._1).getOrElse(-1)`.
- Failure: Take a .dot file with a node named "A", or a NetGraph node "0.5". `"A".toInt` throws NumberFormatException during setup, and Main reports only "An error occurred". In Echo, a test that sends `EchoWave()` from a TestProbe (whose name is "testProbe-1") crashes the actor at `.toInt`. In Tree, a message from any unregistered sender maps to -1. The node then records `received += (-1 -> true)`, the neighbour count never completes, and the run hangs. Each Tree message also does an O(n) scan of the map.
- Fix: Introduce `final case class NodeId(value: Int) extends AnyVal` (or an opaque type) and a `final case class Topology(nodes: Set[NodeId], edges: Set[Edge])`. Make the parser return `Either[ParseError, Topology]`. Carry the sender ID inside every message, or let the runtime pass `from: NodeId` to `step`. Do not derive identity from `ActorRef` or `path.name`.
- Verifier: The String map and the `.toInt` calls exist. EchoProcess.scala:32 uses `path.name.toInt`, and TreeProcess.scala:83 uses an O(n) reverse scan. All configured .dot files (inputTarry, inputEcho, NetGraph perturbed) use integer IDs, and no test sends EchoWave from a probe, so the crashes are hypothetical. The Tree hang claim is wrong. TreeAlgorithm.scala:47 already sends Wave from the main thread (sender is deadLetters, so the ID is -1). The extra `-1 -> true` entry is harmless, because `neighbors.filterNot(received)` iterates only over real neighbours.

### [medium/confirmed] Lai-Yang actors take their per-channel counters from a global mutable object, keyed by ActorRef, through lazy init
- `src/main/scala/main/processes/LaiYangProcess.scala:29` (fp-design)
- Evidence: def init(): Unit = {
  this.preSnapshotMessagesSent = LaiYangAlgorithm.MessageCountStore.preSnapshotMessagesSent(self)
  this.preSnapshotMessagesToReceive = LaiYangAlgorithm.MessageCountStore.preSnapshotMessagesReceived(self)
}
LaiYangAlgorithm.scala:22-25:
  object MessageCountStore {
    val preSnapshotMessagesSent: mutable.Map[ActorRef, mutable.Map[ActorRef, Int]] = mutable.Map.empty
- Failure: `MessageCountStore` is a singleton that persists across runs and tests. Each run adds maps keyed by new ActorRefs, so the store grows forever. A test that creates `LaiYangProcess` without filling the store gets NoSuchElementException on the first message (`MessageCountStore.preSnapshotMessagesSent(self)`), and the actor restarts. `isInitialized` is reset on restart, but the supervisor drops the message. Keys are `ActorRef`, so the counters cannot be shown or replayed by node ID.
- Fix: Store the counters in the node state as `Map[NodeId, Int]`, initialised from the neighbour list in the pure `init`. Remove `MessageCountStore`, `isInitialized` and the three duplicated lazy-init blocks (lines 42-45, 74-77, 82-85). Remove the `UnboundedStash`/`UnboundedMessageQueueSemantics` mixins, because no code calls `stash()`.
- Verifier: LaiYangAlgorithm.scala:22-25 is a global store keyed by ActorRef that nothing clears. LaiYangProcess.scala:29-30 calls `mutable.Map.apply(self)`, which throws NoSuchElementException when the store has no entry, and the actor restarts and drops the message. The tests must fill the global store by hand (laiYangTest.scala:41-51,73-83). No code calls `stash()`, so the UnboundedStash mixin is unused.

### [medium/confirmed] Franklin uses a central orchestrator with mutable round counters as a global barrier, which is not the textbook algorithm
- `src/main/scala/main/utility/FranklinOrchestrator.scala:7` (fp-design)
- Evidence: class FranklinOrchestrator(var processes: List[Int], val system: ActorSystem, val processRecord: ProcessRecord) extends Actor {
  var roundProcessCount = 0
  var round: Int = -1
  var passiveProcess: List[Int] = List()
...
        if (isLeader) {
          processRecord.map.get(-1).get ! TerminateSystem
- Failure: In Fokkink section 9.1, Franklin's algorithm is decentralised: active nodes exchange IDs with their nearest active neighbours, and round numbers tag the messages so that rounds can overlap. Here a coordinator collects a checkpoint from every node, so the algorithm is in fact centralised and the message counts and causality that a visualizer shows are wrong. Line 42 uses the `isLeader` flag of the last checkpoint to arrive only. If the leader's checkpoint is not the last of its round, the algorithm misses the leader and sends `TurnPassive` to the other node instead. `passiveProcess` is also never cleared between rounds (initiateRound does not reset it), so later rounds send `TurnPassive` again to nodes that were removed earlier.
- Fix: Implement Franklin as a pure per-node protocol with `final case class FMsg(id: NodeId, round: Int, dir: Dir)`, as in the textbook (relay nodes forward, active nodes compare in round r). Detect the leader when a node receives its own ID. Remove the orchestrator.
- Verifier: Franklin is centralised through FranklinOrchestrator with a global round barrier, unlike Fokkink's decentralised algorithm. That part holds. The 'misses the leader' scenario is refuted. `leftId == id` can be true only when one active process remains, and then processes.size == 1, so the last checkpoint is the leader's checkpoint. The orchestrator never clears `passiveProcess` (initiateRound, lines 69-77), so it sends TurnPassive again to nodes that are already passive. Those nodes still send Ack and the counts match, so the effect is only wasted messages.

### [medium/confirmed] Duplicated bootstrap code across all 8 algorithms and inside single files
- `src/main/scala/main/algorithms/BrachaTouegAlgorithm.scala:27` (fp-design)
- Evidence: `scenario1NoDeadlock()` (lines 27-55) and `scenario2WithDeadlock()` (lines 58-85) are identical except for `ApplicationProperties.deadlockFreeData` vs `deadlockData`. ChandyLamportAlgorithm.main (26-76) and LaiYangAlgorithm.main (33-101) share the same busy-loop driver. Every algorithm repeats `ActorSystem(...)`, `new ProcessRecord`, `system.actorOf(Props(new Terminator(system)))`, `record.map.put(-1, terminator)`, `Await.ready(system.whenTerminated, 30.seconds)`.
- Failure: A fix (for example, the race in which the Terminator is registered after the start, fp-design-3) must be applied 8 times, and it was in fact applied inconsistently. Tarry and Bracha-Toueg register the Terminator before Initiate, but Echo registers it after. Also, both Bracha-Toueg scenarios create an ActorSystem with the same name "BrachaTouegSystem". If scenario 1 times out, its system is never terminated, so two live systems coexist and leak threads.
- Fix: Write one generic `Simulator.run[P <: Protocol](p, topology, initiators, seed): RunResult[P]` and one Akka adapter. A scenario then becomes a pure value `Scenario(topology, initiators)`. Remove the ProcessRecord/Terminator boilerplate.
- Verifier: BrachaTouegAlgorithm.scala:27-55 and :58-85 are identical except for the config. The bootstrap is repeated in all 8 algorithms. Echo registers the Terminator after the start message, and the others register it before. Both BT scenarios use the name "BrachaTouegSystem", and `Await.ready` with a 30 s timeout never terminates a system that hangs, so a timed-out system leaks threads.

### [medium/confirmed] Four algorithm-specific terminate messages with identical effect; termination is a shared side effect, not a protocol outcome
- `src/main/scala/main/utility/Terminator.scala:15` (fp-design)
- Evidence: case TerminateSystem =>
  println("Terminating system")
  system.terminate()
...
case TerminateTarry =>
  system.terminate()
case TreeTerminate =>
  system.terminate()
case EchoTerminate() =>
  system.terminate()
- Failure: In the Tree algorithm the two deciding nodes each send `Decide` to themselves and then `TreeTerminate` (TreeProcess.scala:48). The first one terminates the system while the other node's `Info` messages are still in flight, so the trace is cut short arbitrarily. In Bracha-Toueg the termination comes from inside `sendDone`, which `Ack` also calls (line 82). Termination can therefore start before the grant/ack phase is complete, and the result is cut off without notice. `TerminateProcess` is never sent anywhere.
- Fix: Let the protocol emit a `Decided(node, result)` event. Let the simulator stop when the network is quiescent (no pending messages) or a decision event occurs, and return the full trace. Remove Terminator and its 4 messages.
- Verifier: Terminator.scala:15-32 has 4 terminate cases with the same effect. Nothing sends TerminateProcess. In the Tree algorithm, two nodes decide, and TreeProcess.scala:48 terminates on the first Decide while Info messages are still in flight. BrachaTouegProcess.scala:82 calls sendDone from Ack, and sendDone does not wait for the grant/ack phase. The initiator can therefore terminate before grants finish, and each Ack after done==0 sends TerminateSystem again.

### [low/confirmed] Pervasive var and mutable collections inside actors, with sentinel values instead of Option/ADT states
- `src/main/scala/main/processes/TarryProcess.scala:20` (fp-design)
- Evidence: var parent: Int = -1 // The parent process ID
var tokensSent: Int = 0
var tokensReceived: Int = 0
var sent: mutable.Set[Int] = mutable.Set.empty
BrachaTouegProcess.scala:29-30 has `var neighborNotify = mutable.Stack[Int]()` (a var that holds a mutable collection). FranklinProcess.scala:13-14 has `var leftId: Int = -1`, and ChandyLamportProcess.scala:11 has a constructor `var snapshotTaken`.
- Failure: In Tarry, node 0 can be a real node and also a parent. Because the code uses -1 as "no parent" in an Int, an inverted ID convention or a node named -1 makes `parent == -1` ambiguous. In Franklin, `leftId != -1 && rightId != -1` treats a real ID of -1 as "not received yet", so the node never sends its Checkpoint. The phases are implicit in boolean flags (`notifyFlag`, `free`, `snapshotTaken` toggled with `!snapshotTaken`), so states that should not exist can occur, for example `snapshotTaken` flipped back to false after completion and then re-triggered by a late marker. `tokensSent` and `tokensReceived` are written but never read.
- Fix: Model each node as an immutable case class with an ADT phase, for example `sealed trait TarryPhase; case object Idle; final case class Visiting(parent: Option[NodeId], unsent: List[NodeId]); case object Done`. In Akka, carry the state with `context.become(running(state))` or typed `Behaviors.receiveMessage` that returns a new behaviour. Delete the unused counters.
- Verifier: The vars, mutable collections, -1 sentinels and the unused tokensSent/tokensReceived (written at TarryProcess.scala:39,80, never read) exist. The failure scenarios need a node with ID -1, which no config has. The 'late marker re-trigger' does not happen with distinct neighbours, because completion requires markers from all neighbours. This is a style and design smell, so low.

### [low/plausible] Channel identity is ActorRef/sender(), not an explicit channel (from, to); snapshot data holds ActorRefs in mutable ListBuffers
- `src/main/scala/main/processes/ChandyLamportProcess.scala:43` (fp-design)
- Evidence: stopRecordingOn += sender
...
if (this.snapshotTaken && !stopRecordingOn.contains(sender)) {
  systemSnapshot.systemSnapshot(id).messageQueue += new MessageRecord(sender, action)
SnapshotUtils.scala:11-13:
class MessageRecord(val process: ActorRef, val messageBody: String) {}
class ProcessSnapshotData(val storedInteger: Int, val messageQueue: ListBuffer[MessageRecord]) {}
- Failure: In the test (chandyLamportTest.scala:59) and in Main, `SendMessage` comes from `deadLetters` or the test actor, and `PerformAction` comes from the neighbour actor. Any message sent without an implicit sender (`ActorRef.noSender`) is recorded with `deadLetters` as its channel. Chandy-Lamport needs per-channel recording (Fokkink section 3.1), but the snapshot has no channel key, only a flat queue of ActorRefs. `MessageRecord` and `ProcessSnapshotData` are plain classes, not case classes, so they have no structural equality: a test cannot compare a snapshot with an expected value. They also cannot be serialised for a visualizer.
- Fix: Use `final case class Channel(from: NodeId, to: NodeId)` and `final case class LocalSnapshot(value: Int, channels: Map[Channel, Vector[Op]])`, with immutable case classes throughout. Pass `from: NodeId` explicitly to the step function.
- Verifier: MessageRecord and ProcessSnapshotData are plain classes with no structural equality, and InitiateSnapshot doubles as the marker. That part holds. The deadLetters scenario does not happen in real runs: PerformAction is always sent from inside a process actor (ChandyLamportProcess.scala:56), so `sender` is the neighbour. Each record does carry its sender, so per-channel information exists, only flattened. The reviewer missed a real related bug. In the first-marker branch (lines 28-38), the code does not add the marker's sender to `stopRecordingOn`. Later post-snapshot messages on that channel are then wrongly recorded as channel state.

### [low/confirmed] Side-effecting I/O with leaked file handles inside algorithm setup; unused reads
- `src/main/scala/main/algorithms/ChandyLamportAlgorithm.scala:38` (fp-design)
- Evidence: val topologyLines = Source.fromFile(filename).getLines().toList
(the result is never used; LaiYangAlgorithm.scala:43 is the same). TopologyReader.scala:16 `val topologyLines = Source.fromFile(filename).getLines().toList` (it never calls close).
- Failure: Each menu run of Chandy-Lamport or Lai-Yang opens the file twice and never closes it. Repeated runs in one JVM leak file descriptors. A missing file throws FileNotFoundException deep inside setup, and the generic catch in Main.scala:67 reports it only as "An error occurred". File I/O also sits inside the algorithm object, so the algorithms cannot run in a browser or with an in-memory topology for a visualizer.
- Fix: Use `scala.util.Using(Source.fromFile(f))(_.getLines().toList)` at the edge (the CLI layer), and make the parser a pure `parseDot(text: String): Either[ParseError, Topology]`. Delete the unused `topologyLines`.
- Verifier: ChandyLamportAlgorithm.scala:38 and LaiYangAlgorithm.scala:43 read `topologyLines` and never use them. TopologyReader.scala:16 never closes its Source. The handle leak is real but small in a menu CLI.

### [low/confirmed] hasCycle uses a mutable Set, non-local return from a lambda, a "" sentinel and a partial Map apply
- `src/main/scala/main/utility/TopologyReader.scala:41` (fp-design)
- Evidence: val visited = collection.mutable.Set[String]()
...
  processConfig(node).foreach { neighbor =>
    ...
      return true
...
  if (!visited.contains(node)) dfs(node, "")
Line 31: `.mapValues(_.map(_._2).toList)` (deprecated in 2.13).
- Failure: The `return` inside `foreach` works by throwing `NonLocalReturnControl`. This is deprecated in Scala 3 and is caught silently by any `catch { case _: Throwable }` around it. A node whose name is "" would be skipped as a parent by mistake. A duplicate edge ("1" -> "2" written twice) produces neighbour list [2, 2]. The `neighbor == parent` skip then hides the duplicate in one direction only, and Tree's `neighbors.size == 1` leaf test (TreeAlgorithm.scala:46) misclassifies the node. Lai-Yang calls `.distinct`, but Tree, Echo and Tarry do not.
- Fix: Use a pure recursive DFS with an immutable `Set` and a tail-recursive or fold-based traversal, `parent: Option[NodeId]`, and a `Topology` that stores edges as a `Set` (which removes duplicates). Use `.view.mapValues(...).toMap`, or better a dedicated graph type.
- Verifier: The mutable Set, the non-local return inside foreach (deprecated in Scala 3) and the deprecated mapValues exist. Take a duplicate edge 1->2 written twice: readTopology gives 1:[2,2] and 2:[1,1]. dfs from 1 visits 2, returns, then finds 2 already visited and returns true, so Tree rejects a valid tree as cyclic. The configured files have no duplicates.

### [low/confirmed] Imperative CLI loop with var, do-while and swallowed exceptions
- `src/main/scala/main/Main.scala:57` (fp-design)
- Evidence: var choice = 0
do {
  displayMenu()
  try {
    choice = StdIn.readInt()
    runAlgorithm(choice)
    ...
  } catch {
    case e: Exception =>
      e.printStackTrace()
      logger.error("An error occurred. Please try again.")
  }
} while (choice != 9)
- Failure: If stdin reaches EOF (for example, piped input or a closed terminal), `StdIn.readInt()` throws `EOFException`. The generic catch handles it, `choice` keeps its old value (not 9), and the loop runs forever and prints the menu and stack traces. `do-while` is also removed in Scala 3, which blocks a later migration.
- Fix: Use a tail-recursive `@tailrec def loop(): Unit` that reads `Option(StdIn.readLine())` and stops on None. Map the input to an `Algorithm` ADT, or replace the menu with CLI arguments (`run echo --topology f.dot --seed 42 --trace out.json`) that feed the visualizer.
- Verifier: Main.scala:57-71 is as quoted. At EOF, `StdIn.readInt` throws java.io.EOFException. The generic catch handles it, `choice` stays at its old value, and the loop runs forever and prints stack traces. Scala 3 removes do-while.

### [low/plausible] Unused and mismatched dependencies: typed Akka and akka-http not used, hard-coded _2.13 suffixes, ancient logback/config
- `build.sbt:10` (build-config)
- Evidence: "com.typesafe.akka" %% "akka-actor" % "2.6.18",
"com.typesafe.akka" % "akka-actor-typed_2.13" % "2.6.18",
"com.typesafe.akka" % "akka-stream-typed_2.13" % "2.6.18",
"com.typesafe.akka" % "akka-http_2.13" % "10.2.4",
...
"ch.qos.logback" % "logback-classic" % "1.0.9",
"com.typesafe" % "config" % "1.2.1",
- Failure: No source file imports akka typed, stream or http, so these only add weight. The hard-coded `_2.13` suffixes break if `scalaVersion` changes to 3.x. `config` 1.2.1 is older than the version that Akka 2.6.18 needs (1.4.x), so sbt evicts it, which makes the declaration misleading. logback 1.0.9 (2012) predates the slf4j 1.7 bindings that Akka's slf4j logger uses, and it has known CVEs. `import scala.collection.Seq` on line 1 is unnecessary. Akka 2.6 is also the last Apache-licensed line (later Akka uses BSL), which is relevant if the code is to be published.
- Fix: Remove the unused modules and use `%%` throughout. For an FP rewrite, consider dropping Akka from the core and keeping the pure protocol plus simulator with no dependencies. Optionally add cats-effect or fs2 for the runtime, and Scala.js for an in-browser visualizer that reuses the same protocol code. Add scalacheck for property tests on the pure step functions.
- Verifier: The hard-coded _2.13 suffixes, the unused stream/http modules and the evicted config 1.2.1 are real. The fix is partly wrong. Every file imports `akka.event.slf4j.Logger`, which comes from akka-slf4j, and akka-slf4j is not declared. It arrives transitively through akka-actor-typed, so removing typed as 'unused' would break compilation. The slf4j-binding claim for logback 1.0.9 is also doubtful: 1.0.9 uses the StaticLoggerBinder that slf4j 1.7 still supports. The CVE claim (CVE-2017-5929) holds.

### [low/confirmed] Side effects in actor constructors, empty preStart overrides, unused constructor dependencies
- `src/main/scala/main/processes/TreeProcess.scala:21` (fp-design)
- Evidence: TreeProcess.scala:21 `log.info(s"[$self] initialized with neighbors: $neighbors")` (in the constructor body).
BrachaTouegProcess.scala:34-36, ChangRobertsProcess.scala:25-27, FranklinProcess.scala:17-19, FranklinOrchestrator.scala:15-17: `override def preStart(): Unit = { super.preStart() }`.
An unused `val system: ActorSystem` parameter is in BrachaTouegProcess:23, ChangRobertsProcess:14, FranklinProcess:10 and FranklinOrchestrator:7.
- Failure: An actor that takes the whole `ActorSystem` can call `system.terminate()` or create top-level actors from inside algorithm logic. The constructor signature hides this capability, and tests cannot replace it. The constructor log runs again on each supervisor restart, which duplicates log lines that a log-scraping visualizer would count as events. `Props(new X(...))` inside an object also creates closures, which Akka warns against.
- Fix: Remove the empty overrides and the `system` parameters. Pass only explicit capabilities, such as a typed `replyTo` reference. In the target design, a node needs only its `NodeId` and neighbour set, and the runtime owns all effects.
- Verifier: TreeProcess.scala:21 logs in the constructor. The cited classes have empty preStart overrides. The `system` parameters in BrachaTouegProcess, ChangRobertsProcess, FranklinProcess and FranklinOrchestrator are unused. The Props-closure warning applies to Props created inside another actor, not inside a top-level object, so that sub-claim is weak.

### [low/confirmed] Copy-paste documentation and inconsistent naming hide what each component does
- `src/main/scala/main/processes/ChangRobertsProcess.scala:7` (fp-design)
- Evidence: /**
 * Actor representing a process in the Echo algorithm.
 *
 * @param neighbor      The list of neighboring process IDs.
(This is ChangRobertsProcess, and `neighbor` is a single Int.) Message names mix conventions: `TerminateTarry`, `TreeTerminate`, `EchoTerminate()`, `InitiateTarry()`. The `InitiateSnapshot(start: Boolean)` message is also the marker: `InitiateSnapshot(false)` means "marker".
- Failure: `InitiateSnapshot(false)` overloads one message for two concepts (local initiation and marker reception) behind a Boolean. A reader or visualizer cannot label marker edges separately from initiation without decoding the flag. A wrong flag value also silently changes the marker count (ChandyLamportProcess.scala:34-36).
- Fix: Use distinct ADT cases: `case object StartSnapshot` (external) and `case object Marker` (channel message). Use consistent naming for each algorithm module (`echo.Msg`, `echo.State`, `echo.Protocol`). Correct the Scaladoc.
- Verifier: ChangRobertsProcess.scala:7-13 has a Scaladoc that says 'Echo algorithm' and describes `neighbor` as a list. The naming is inconsistent (TerminateTarry, TreeTerminate, EchoTerminate()). InitiateSnapshot(Boolean) serves as both the initiation message and the marker (ChandyLamportProcess.scala:25-38).

# Architecture plan

# Recommendation: rewrite the CS553 algorithms as a pure simulator with a visualizer

## 1. Verdict: rewrite. Do not refactor.

- The main code is small: about 1,600 lines in 23 files. A rewrite costs little.
- None of the 8 algorithms has a pure core. In each algorithm, the logic lives in the `var` fields of an Akka classic actor. The actors share global mutable state (`ProcessRecord`, the companion-object `var`s, `SystemSnapshot`). A refactor would change every line, so it gives no saving.
- At least five algorithms have defects in their state machines: Chandy-Lamport, Lai-Yang, Bracha-Toueg, Chang-Roberts and Franklin. Franklin is not the textbook algorithm (`FranklinOrchestrator` is a central barrier).
- The tests assert only the first mailbox fan-out (tests-16), so they give no safety net for a refactor.
- Keep the knowledge, not the code: the algorithm list, the topologies and the README descriptions.

## 2. Target stack

| Concern | Choice | Reason |
|---|---|---|
| Language | Scala 3.3.x LTS | `enum` ADTs, opaque types and `given`s make the protocol types small. The LTS release gets long-term support. |
| Concurrency | No actors in the core | A deterministic simulator replaces Akka. Akka 2.7+ uses the BSL licence. If you want a "real concurrency" demo later, add an optional Apache Pekko Typed adapter. |
| Effects | Plain pure Scala | A step function is `(S, M) => (S, List[Send])`. cats-effect and ZIO add nothing to this. Add one only if a server becomes necessary. |
| Build | sbt 1.10 with `crossProject(JVMPlatform, JSPlatform)` | The same `core` compiles to the JVM (tests, CLI) and to JS (the browser). |
| Frontend | Scala.js 1.17+ with Laminar 17 | The simulator runs in the browser and needs no backend. The trace is an in-memory value, so a JSON contract between two languages becomes unnecessary. Add a JSON trace export (upickle) for golden files. |
| Tests | munit 1.x + munit-scalacheck + ScalaCheck 1.18 | Property tests over random topologies and random seeds. |
| Hygiene | scalafmt, `-Wunused:all -Werror`, GitHub Actions CI | — |

Do not use a JVM backend with a TS/D3 frontend. That choice adds a second language and a serialization contract, and it gives no benefit.

## 3. Core design

```scala
opaque type NodeId = Int
final case class Edge(from: NodeId, to: NodeId)
final case class Topology private (nodes: Set[NodeId], edges: Set[Edge]):
  def out(n: NodeId): Set[NodeId]; def in(n: NodeId): Set[NodeId]
object Topology:
  def undirected(es: Iterable[(Int, Int)]): Either[TopologyError, Topology]
  def ring(ids: List[Int], directed: Boolean): Either[TopologyError, Topology]
  def fromDot(src: String): Either[TopologyError, Topology]   // no file I/O here

final case class Send[M](to: NodeId, msg: M)
final case class Step[S, M](state: S, out: List[Send[M]], decision: Option[Decision] = None)

trait Protocol[S, M]:
  def name: String
  def init(self: NodeId, ctx: NodeCtx): Step[S, M]          // ctx: neighbours, initiator flag, params
  def receive(self: NodeId, s: S, from: NodeId, m: M): Step[S, M]

enum TraceEvent[+S, +M]:
  case Sent(t: Long, from: NodeId, to: NodeId, msg: M)
  case Delivered(t: Long, from: NodeId, to: NodeId, msg: M)
  case StateChanged(t: Long, node: NodeId, before: S, after: S)
  case Decided(t: Long, node: NodeId, d: Decision)

final case class SimConfig(seed: Long, fifo: Boolean, maxSteps: Int)
enum Outcome { case Quiescent, StepLimit }
final case class Run[S, M](trace: Vector[TraceEvent[S, M]], last: Map[NodeId, S], outcome: Outcome)

def simulate[S, M](p: Protocol[S, M], t: Topology, cfg: SimConfig): Run[S, M]
```

- Each algorithm has its own sealed message `enum`, for example `enum TarryMsg { case Token }`. No string operations, no Boolean variant flags, no `-1` sentinels.
- The simulator state is the node states plus `Map[Edge, Queue[M]]`. A seeded `scala.util.Random` selects the next channel to deliver from. With `fifo = false`, it can select any message, so Lai-Yang gets the non-FIFO channels that it must tolerate.
- The protocol code makes no random choices. The config supplies the initiator choice and the application traffic (both seeded).
- For the snapshot algorithms, use a "bank transfer" application in place of "increment": the nodes send money, and the total is constant. This gives a conservation invariant to test.
- The visualizer consumes only `Run` and a `given Render[S]` / `Render[M]` (a label and a colour). `Run` is the single contract.

## 4. Test strategy

Generators: connected graphs (a random spanning tree plus extra edges), random trees, rings with random distinct ids, wait-for graphs and seeds.

| Algorithm | Properties |
|---|---|
| Simulator | The same seed gives the same trace. Each `Delivered` has an earlier `Sent`. FIFO mode keeps the order on each channel. |
| Tarry | The token crosses each edge once in each direction (2\|E\| messages). The parents form a spanning tree with the initiator as root. Only the initiator decides, and it decides once. |
| Echo | 2\|E\| messages. The parents form a spanning tree. Only the initiator decides, and it decides once. |
| Tree | On any tree, exactly two adjacent nodes decide, and the run sends exactly \|V\| messages. |
| Chang-Roberts, Franklin | Exactly one leader, and its id is the maximum id among the initiators. All nodes learn the leader. Franklin uses ≤ ⌈log₂ n⌉ + 1 rounds. |
| Chandy-Lamport (FIFO), Lai-Yang (non-FIFO) | Each node records once. The cut is consistent: no recorded receive has a send after the cut. The recorded balances plus the recorded channel contents equal the initial total. |
| Bracha-Toueg | The verdict equals a sequential reduction of the wait-for graph (grant to free nodes until nothing changes). Test both N-out-of-N and k-out-of-n. |

Also add one named regression test for each bug that the review found, for example the `"Increment"` vs `"increment"` mismatch. Add golden JSON traces for the existing DOT inputs.

## 5. Migration plan

1. **M0 – Skeleton.** Make a new branch and a cross-built sbt project. Add CI, scalafmt and strict compiler flags.
2. **M1 – Core.** Write `Topology`, a `fromDot` that returns `Either`, `simulate`, `TraceEvent` and the simulator properties.
3. **M2 – Waves.** Write Tarry, Echo and Tree with their properties.
4. **M3 – Elections.** Write Chang-Roberts (directed ring) and textbook Franklin (bidirectional ring, no orchestrator).
5. **M4 – Snapshots.** Write Chandy-Lamport and Lai-Yang with bank traffic.
6. **M5 – Deadlock.** Write Bracha-Toueg on a directed wait-for graph.
7. **M6 – Visualizer.** Build it with Laminar: a graph view (a ring or circle layout, or d3-force through a facade), animated in-flight messages, a step/play/scrub timeline, a seed input, a node-state panel and a decision highlight.
8. **M7 (optional).** Add a Pekko Typed adapter that runs the same `Protocol`. Add a differential test that checks that its trace is a valid schedule.

**Keep:** the 8-algorithm list. Keep `inputEcho.dot`, `inputTarry.dot`, `graph_50_nodes.dot` and the `NetGraph_*.dot` files as test fixtures. Keep the `application.conf` topologies after you convert them to DOT or Scala fixtures, and fix the Bracha-Toueg In/Out mismatch (infra-19) at the same time. Keep the README algorithm text.

**Delete:** all `*Process.scala` and `*Algorithm.scala` files, `ProcessRecord`, `Terminator`, `FranklinOrchestrator`, `SnapshotUtils`, `MessageTypes`, `ApplicationProperties`, `TopologyReader`, the `Main` menu loop, all current tests, all Akka and akka-http dependencies, and logback 1.0.9.

Source reviewed: /Users/dhruv/Desktop/CS553-DistributedAlgorithms (no files changed).