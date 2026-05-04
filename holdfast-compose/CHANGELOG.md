# Changelog — `:holdfast-compose`

All notable changes to `:holdfast-compose` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 2.0.0 — 2026-05-03

Coordinated 2.0 cut across `:holdfast`, `:holdfast-coroutines`, `:holdfast-compose`,
and `:holdfast-hallmark`. See [MIGRATING.md](../MIGRATING.md) for the
per-call-site rewrite cheatsheet across modules.

### Added

- Version bump only — the `:holdfast-compose` public surface is unchanged in
  2.0. `@Composable State<T>.collectAsState()` and
  `@Composable rememberDisposable { ... }` continue to work identically.

### Removed

- Nothing removed from the `:holdfast-compose` public surface.

### Changed (behavior, signature stable)

- `:holdfast-compose` re-exports vault state through Compose's `State<T>`. With
  `:holdfast-coroutines` 2.0's lossless-conflated `asFlow()` semantics
  (replay = 1, DROP_OLDEST), late subscribers and slow recompositions now
  always observe the latest committed value rather than potentially missing
  it under contention. See `vault-coroutines/CHANGELOG.md` and
  [MIGRATING.md](../MIGRATING.md) for the regression-watch concern.

### Targets

- `:holdfast-compose` 2.0 stays Android + iOS pending Compose Multiplatform
  1.11 desktop-target verification. The desktop target is deferred to 2.1
  to give CMP 1.11's stable channel time to land. JS / Wasm targets are
  not on the `:holdfast-compose` roadmap; they are deferred at the
  `:holdfast` core level (Threading semantics).

---

## 0.2.0

See `vault/CHANGELOG.md` for the unified 0.1.0 / 0.2.0 history that
preceded the per-module changelog split.
