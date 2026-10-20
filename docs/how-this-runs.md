# How this runs

Each algorithm is one pure `Protocol`: a set of functions that take a state and a message, and return a new state and the messages to send. Three engines run the same functions and write the same trace format:

| Engine | Where it runs | Who picks the next step | Status |
|---|---|---|---|
| Simulator | Browser and JVM | A seed, a script, or the learner | Ready |
| Mediated Akka engine | JVM (`modules/actors`) | The Controller, with a seed or a script | Ready (M3) |
| Free-run Akka engine | JVM (`modules/actors`) | The Akka dispatcher | Milestone M7 |

## The Simulator

The Simulator keeps the full state of the network in one value, the `World`. One step applies one `Choice`:

- `Deliver(id)`: give one envelope to its receiver.
- `Fire(node, action)`: let one node do a spontaneous action, for example `Wake`.
- `Drop(id)`: remove one envelope. The receiver never gets it.

`World.enabled` lists the legal choices. In `Fifo` order, only the oldest envelope of each channel is legal. In `AnyOrder`, each envelope is legal. A seed picks one choice from the list. So the same seed always gives the same run.

## The mediated Akka engine

The mediated engine runs each process as a real Akka actor. The nodes send real messages with `!`. A Controller decides when each message arrives.

```
                         +--------------+
             Release(id) |  Controller  |  Act(action)
          +--------------|              |--------------+
          |              +--------------+              |
          |                ^          ^                |
          |      Enqueued(id)          Handled(...)    |
          v                |          |                v
   +----------------+      |          |         +-------------+
   | Channel(1 -> 2)|------+          +---------|   Node 1    |
   |  [e0] [e1]     |                           +-------------+
   +----------------+                                  |
          |                                            | Put(env)
          | Deliver(env)                               v
          v                                     +----------------+
   +-------------+                              | Channel(1 -> 3)|
   |   Node 2    |                              +----------------+
   +-------------+
```

### The actors

`RunGuardian` is the root of one run. It spawns these actors as its children:

- **One node actor for each process** (`node-3`). The node holds only the refs of its outgoing channels. It never holds the ref of a peer node. For each message or action, it calls the pure `Protocol`, sends the new messages with `out(j) ! Put(env)` and tells the Controller `Handled`.
- **One channel actor for each directed edge** (`channel-3-5`). The channel keeps the envelopes in arrival order. It tells the Controller `Enqueued(id)` for each new envelope. It gives an envelope to the receiver only when the Controller sends `Release(id)`. In `Fifo` order, the channel refuses a `Release` of an envelope that is not the oldest.
- **One Controller** (`controller`). It is the observer of the run. It picks each step and writes the trace.

The full path of a node is, for example, `/user/run-echo-inputEcho-7/node-3`.

### One step

1. The Controller lists the legal choices. It uses `World.enabledOf`, the same rule as the Simulator, with the envelope ids from `Enqueued` and the actions from `Handled`.
2. The Controller picks one choice with the seed (`Simulator.seededChoice`) or with the next choice of a script.
3. For `Deliver(id)`, the Controller sends `Release(id)` to the channel, and the channel sends `Deliver(env)` to the receiver. For `Fire`, the Controller sends `Act(action)` to the node.
4. The node calls `onMessage` or `onAction`. It gives the result to `Effects.of`, the same function as the Simulator. Then it sends each new envelope to its channel and sends `Handled` to the Controller.
5. The Controller waits for `Handled` **and** for one `Enqueued` for each new envelope. Then it adds the events of the step to the trace and starts the next step.

When the stop rule of the run applies, the Controller sends `Finished(run)` to `replyTo` and stops. The guardian then stops, and Akka stops all the actors of the run.

### Why one step at a time

Akka keeps the order of the messages from one sender to one receiver. Akka does not keep an order between two different senders. In step 4, the node sends `Put` to a channel and `Handled` to the Controller. The channel then sends `Enqueued` to the Controller. So `Enqueued` can arrive before or after `Handled`.

If the Controller starts the next step too early, the list of legal choices can be incomplete, and the seed can pick a different choice. So the Controller waits for all the reports of the step. Only one step is active at any time. Thus the choices alone define the trace, and the mediated trace is equal to the Simulator trace for the same `RunSpec`. A conformance test checks this for each algorithm, each preset, both channel orders and 20 seeds.

### How a free run is different

In a free run (milestone M7), there is no Controller and there are no channel actors. The nodes send to their peers directly. The Akka dispatcher decides the order of the messages, so two runs can give two different traces. Only the order from one sender to one receiver is fixed.

| | Mediated run | Free run |
|---|---|---|
| Who picks the order | The Controller | The Akka dispatcher |
| Same seed, same trace | Yes | No |
| Messages | Real `!`, through channel actors | Real `!`, directly to the peer |
| Who knows that the network is quiet | The Controller | Nobody |

A mediated run lets you replay and study one schedule. A free run shows what a real system does.

## Decided and Quiescent

The UI shows two different facts:

- **Decided.** The algorithm itself detects the end. For example, the Echo initiator decides when all echoes arrived.
- **Quiescent.** No envelope is in a channel, no step is active and no action is legal. Only an outside observer knows this: the Simulator or the Controller. A node cannot know it without a termination detection algorithm.

## Protocol errors

When a `Protocol` function gets an illegal input, it returns `Left(ProtocolError)`. The engine writes a `Rejected` event, and the run continues. The node actor does not crash.

## Record an Akka run

The CLI runs the mediated engine and writes the trace as JSON:

```sh
sbt "cli/run record --engine mediated --alg echo --preset inputEcho --seed 7 --out echo.json"
```

`sbt goldenPromote` records each golden case to `fixtures/traces/<alg>/<preset>-akka-mediated-<seed>.json`. These commands need `AKKA_REPO_TOKEN`. The web pages do not use the recorded traces yet.
