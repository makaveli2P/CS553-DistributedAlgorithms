# CS553 Distributed Algorithms

This project teaches eight distributed algorithms from Wan Fokkink, *Distributed Algorithms: An Intuitive Approach* (2nd edition):

- Snapshot: Chandy-Lamport, Lai-Yang
- Wave: Echo, Tarry, Tree
- Deadlock detection: Bracha-Toueg
- Election: Chang-Roberts, Franklin

Each algorithm is one pure Scala 3 model. The same model runs in three engines: a simulator in the browser, a mediated Akka Typed engine and a free-run Akka Typed engine. The browser visualizer shows the channels, the mailboxes and the steps of each run.

## Status

This branch is a rewrite of the college project. The original code is at the tag `college-original`.

Milestones M0 to M3 are done:

- M0: the build, the CI, the coding rules and the ADRs.
- M1: the pure model, the Simulator, Echo and Chang-Roberts.
- M2: the web sandbox for Echo and Chang-Roberts.
- M3: the mediated Akka engine, with channel actors and a Controller, and the CLI command `record`. For each algorithm, preset, channel order and 20 seeds, a test shows that the Akka trace is equal to the Simulator trace.

Milestones M4 to M7 add the other six algorithms, the space-time view, the lessons and the free-run engine. The descriptions of the algorithms are in [docs/algorithms](docs/algorithms).

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

The CS553 team wrote the college project. The test topologies come from [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim) by Prof. Mark Grechanik.
