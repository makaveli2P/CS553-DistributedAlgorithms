# 2. Gate Akka behind a token and keep Pekko as the fallback

Date: 2026-10-20

## Status

Accepted

## Context

Akka 2.10 is available only from `https://repo.akka.io/<token>/secure`. Each user needs a free personal token from https://account.akka.io/token. The licence key `AKKA_LICENSE_KEY` also comes from https://account.akka.io. M0 did not confirm whether development runs need the key. The repository is public. Fork pull requests do not get repository secrets. A learner who opens the GitHub Pages site must not need an Akka account.

`core` and `web` have no Akka dependency.

## Decision

- `build.sbt` adds the Akka resolver only when the environment contains `AKKA_REPO_TOKEN`.
- Without the token, the root aggregate skips `actors` and `cli`. sbt prints one warning line on load that tells how to get a token.
- `application.conf` reads the licence key with `akka.license-key = ${?AKKA_LICENSE_KEY}`.
- CI has two jobs. `core-web` always runs, also on fork pull requests. `akka` runs only when the secret `AKKA_REPO_TOKEN` exists.
- Nobody commits the token or the licence key.

The fallback, if Akka becomes unusable for this project: use **Pekko 1.7.1** and **Pekko HTTP 1.4.2** (the newest 1.x releases on Maven Central on 2026-10-20). This is the only token-free stack that also covers the optional HTTP server (milestone M8). Akka 2.6.21 covers `actors` only, because no Apache-licensed Akka HTTP exists for Scala 3: the first Akka HTTP with `_3` artifacts is 10.5.0, which has the BSL licence. Nobody confirmed that Akka 2.6.21 uses the Apache 2.0 licence. Check its licence before you use it.

## Consequences

- Contributors to `actors` and `cli` must register for a token.
- Fork pull requests run only the `core-web` job.
- A change to Pekko changes the package names (`akka` to `org.apache.pekko`) and the config keys. The pure `core` does not change.
