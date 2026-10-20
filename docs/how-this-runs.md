# How this runs

Status: stub. Milestones M3 and M7 complete this page.

This page will explain these items:

- **The Simulator.** It runs in the browser and on the JVM. It applies one `Choice` at a time to a pure `World`.
- **Channel actors.** In the mediated Akka engine, each directed edge is one `Channel(i->j)` actor. A node sends with `!` to its channel refs only.
- **The Controller.** It releases one envelope or fires one action at a time. It ends a step only when it has the `Handled` report and one `Enqueued` for each sent envelope. So the choices alone define the trace.
- **The free run.** Nodes send to each other directly. The Akka dispatcher picks the order, so the run shows real non-determinism.
- **Decided and Quiescent.** The algorithm detects `Decided` itself. Only an outside observer knows that the network is `Quiescent`.
