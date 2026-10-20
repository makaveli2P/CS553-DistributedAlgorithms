# 6. Use circe and the cats NonEmptyList in core

Date: 2026-10-20

## Status

Accepted

## Context

M1 adds the trace contract to `core`. The design selects circe 0.14 for JSON. Coding rule 16 requires `Either[NonEmptyList[E], A]` for parsed input, but the build had no `NonEmptyList` type.

circe-core depends on cats-core. So cats-core is already on the classpath of `core` on the JVM and on JavaScript.

## Decision

- Add `circe-core` and `circe-parser` 0.14.17 to `core`. `circe-parser` reads JSON text on the JVM (jawn) and on JavaScript (`JSON.parse`).
- Use `cats.data.NonEmptyList` from the cats-core that circe brings. Do not add a direct cats dependency. Do not write a local `NonEmptyList`.
- Use no other part of cats in `core`.
- Encode a 64-bit `Seed` as a JSON string, because `JSON.parse` loses the low bits of a large number.
- Add ScalaCheck 1.18.1 and scalatestplus `scalacheck-1-18` 3.2.19.0 to the `core` tests. sbt selects ScalaTest 3.2.20 over the 3.2.19 that scalatestplus asks for, with no eviction warning.

## Consequences

- The circe version also sets the cats-core version. A circe update can change cats-core.
- If a later change removes circe from `core`, it must add cats-core directly or replace `NonEmptyList`.
