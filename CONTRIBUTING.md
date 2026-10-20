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
| Test the Akka modules (token) | `sbt actors/test` |
| Run the CLI (token) | `sbt cli/run` |
| Run all checks before a commit | `sbt scalafmtCheckAll "scalafixAll --check" test` |

For the web page, run these commands in `modules/web`:

| Task | Command |
|---|---|
| Install the tools | `npm install` |
| Start the development server | `npm run dev` |
| Build the static site into `modules/web/dist` | `npm run build` |

The Vite plugin calls `sbt` itself. Make sure that `sbt` and JDK 21 are on the `PATH`.

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
