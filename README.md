# Distributed Algorithms

This project teaches eight distributed algorithms from Wan Fokkink, *Distributed Algorithms: An Intuitive Approach* (2nd edition):

- Snapshot: Chandy-Lamport, Lai-Yang
- Wave: Echo, Tarry, Tree
- Deadlock detection: Bracha-Toueg
- Election: Chang-Roberts, Franklin

Each algorithm is one pure Scala 3 model. The same model runs in three engines: a simulator in the browser, a mediated Akka Typed engine and a free-run Akka Typed engine. The browser visualizer shows the channels, the mailboxes and the steps of each run.

## Features

- Eight algorithms: Chandy-Lamport, Lai-Yang, Echo, Tarry, Tree, Bracha-Toueg, Chang-Roberts and Franklin.
- A guided lesson for each algorithm, with a question that the learner answers by clicking a node.
- A space-time view that shows each node as a line and each message as an arrow.
- A sandbox in the browser, with mailboxes, overlays, a timeline and an inspector.
- Two real Akka Typed engines: a mediated engine and a free-run engine. The sandbox replays recorded runs of both.

**Live site:** https://makaveli2p.github.io/CS553-DistributedAlgorithms/

This branch is a rewrite of the college project. The original code is at the tag `college-original`. The descriptions of the algorithms are in [docs/algorithms](docs/algorithms).

## Modules

| Module | Contents | Needs the Akka token |
|---|---|---|
| `modules/core` | The pure model, for the JVM and for JS | No |
| `modules/web` | The Laminar visualizer, built with Vite | No |
| `modules/actors` | The Akka Typed engines | Yes |
| `modules/cli` | The command line: it simulates a run and records a mediated Akka run | Yes |

## How to run

You need JDK 21, sbt and Node.js. [CONTRIBUTING.md](CONTRIBUTING.md) gives the full setup, which includes the Akka token.

```sh
sbt coreJVM/test coreJS/test          # test the core
sbt "cli/run record --engine free --alg echo --preset inputEcho --out echo-free.json"   # record a free-run Akka run
cd modules/web && npm install && npm run dev   # open the web page
sbt "cli/run simulate --alg chang-roberts --ring 6 --seed 7"   # print a trace (needs AKKA_REPO_TOKEN)
sbt "cli/run record --engine mediated --alg echo --preset inputEcho --seed 7 --out echo.json"   # record an Akka run
```

## Documents

- [docs/coding-rules.md](docs/coding-rules.md): the coding rules
- [docs/adr](docs/adr): the architecture decisions
- [docs/how-this-runs.md](docs/how-this-runs.md): how the engines run
- [docs/algorithms](docs/algorithms): one page for each algorithm
- `fixtures/topologies`: the topologies from the college project

## Credits

The college team wrote the college project. The test topologies come from [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim) by Prof. Mark Grechanik.
