# Contributing

## Requirements

- JDK 21 (Temurin 21 is the reference). Scala 3.9 needs JDK 17 or later. Akka certifies JDK 21.
- The sbt launcher. The project pins sbt 1.12.15 in `project/build.properties`, and the launcher downloads it.
- Node.js 22 or later and npm, for the web module.
- An Akka repository token, for the modules `actors` and `cli` only.

## The Akka token

Akka 2.10 is available only from the Akka library repository, which needs a free personal token.

1. Get a token at https://account.akka.io/token.
2. Export it in your shell: `export AKKA_REPO_TOKEN=<your token>`.
3. Optional: export the licence key as `AKKA_LICENSE_KEY`. `modules/actors/src/main/resources/application.conf` reads it. The Akka account page at https://account.akka.io gives the key. M0 did not confirm whether development runs need the key.

Do not commit the token or the licence key.

Without the token, the build still works for `core` and `web`. The root aggregate skips `actors` and `cli`, and sbt prints one warning line when it loads.

## Commands

Run these commands from the repository root.

| Task | Command |
|---|---|
| Check the format and the lint rules | `sbt scalafmtCheckAll "scalafixAll --check"` |
| Format the code | `sbt scalafmtAll scalafmtSbt` |
| Test the core on the JVM and in JS | `sbt coreJVM/test coreJS/test` |
| Link the web module | `sbt web/fastLinkJS` |
| Write the golden traces again | `sbt goldenPromote` |
| Test the Akka modules (token) | `sbt actors/test` |
| Show the CLI usage (token) | `sbt cli/run` |
| Simulate a run and write its trace (token) | `sbt "cli/run simulate --alg echo --topology fixtures/topologies/inputEcho.dot --initiator 1 --seed 7 --out trace.json"` |
| Record a mediated Akka run (token) | `sbt "cli/run record --engine mediated --alg echo --preset inputEcho --seed 7 --out trace.json"` |
| Run all checks before a commit | `sbt scalafmtCheckAll "scalafixAll --check" test` |

For the web page, run these commands in `modules/web`:

| Task | Command |
|---|---|
| Install the tools | `npm install` |
| Start the development server | `npm run dev` |
| Build the static site into `modules/web/dist` | `npm run build` |

The Vite plugin calls `sbt` itself. Make sure that `sbt` and JDK 21 are on the `PATH`.

## Golden traces

A golden trace is a committed Simulator trace in `fixtures/traces/<alg>/<topology>-simulator-<seed>.json`. The list of golden cases is in `modules/core/shared/src/test/scala/cs553/core/golden/Goldens.scala`. Each case names an algorithm of the registry, a topology preset and a seed.

Each case also has a recorded trace of the mediated Akka engine in `fixtures/traces/<alg>/<topology>-akka-mediated-<seed>.json`.

`GoldenSpec` simulates each case again on the JVM and in JavaScript, and compares the result with the committed file. When a trace differs, the test gives the first line that is different. `GoldenSpec` also checks that each recorded Akka trace is equal to the Simulator trace, except for the engine field.

When a change of behavior is intended, do these steps:

1. Run `sbt goldenPromote` with `AKKA_REPO_TOKEN` set. The task writes each Simulator trace again, and then records each case with the CLI `record` command. Without the token, the task writes only the Simulator traces.
2. Read the diff of `fixtures/traces` with `git diff`.
3. Explain the diff in the commit message (coding rule 30).

Do not edit a golden trace by hand. `Goldens.cases` makes two cases (seeds 1 and 7) on the first preset of each algorithm in the registry. To get the cases of a new algorithm, add the algorithm to the registry, and then run `sbt goldenPromote`.

The build embeds `fixtures/topologies` in the core main code and `fixtures/traces` in the core test code, because core does no IO.

## CI

- `core-web` runs on each push and pull request, also from forks. It needs no token.
- `akka` runs only when the repository has the secret `AKKA_REPO_TOKEN`.
- `core-web` also builds the site with Vite, so a pull request cannot break the Pages build.
- `pages.yml` builds the web module and deploys it to GitHub Pages. It needs no token.

### Maintainer setup

Do these steps one time in the GitHub repository:

1. In GitHub, open Settings > Secrets and variables > Actions.
2. Add the secret `AKKA_REPO_TOKEN`. Without this secret, the `akka` job does not run.
3. Optional: add the secret `AKKA_LICENSE_KEY`.
4. Open Settings > Pages and set Source to "GitHub Actions". Without step 4, the deploy job of `pages.yml` fails.

## Rules

Read [docs/coding-rules.md](docs/coding-rules.md). Record each change to the stack or a module boundary as an ADR in [docs/adr](docs/adr). Write prose in ASD-STE100 Simplified Technical English.
