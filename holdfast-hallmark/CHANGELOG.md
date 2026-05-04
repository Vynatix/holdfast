# Changelog — `:holdfast-hallmark`

All notable changes to `:holdfast-hallmark` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 2.0.0 — 2026-05-03

Coordinated 2.0 cut across `:holdfast`, `:holdfast-coroutines`, `:holdfast-compose`,
and `:holdfast-hallmark`. See [MIGRATING.md](../MIGRATING.md) for the
per-call-site rewrite cheatsheet across modules.

### Added

- Version bump only — the `:holdfast-hallmark` public surface is unchanged
  vs. the 0.2.0 introduction of the module. `Boxed<P>`, `Rule<P>`,
  `Condition<P, R>`, `Spec<P, R, O>`, `Validator<P, R, O>`, and
  `ValidatingTransformer<P, R, O>` all continue to work identically.

### Removed

- Nothing removed from the `:holdfast-hallmark` public surface.

### Changed

- No behavior changes within `:holdfast-hallmark`. The module's
  `ValidatingTransformer` integrates with `:holdfast` core's transformer
  pipeline; per-write validation runs on every `state mutate` /
  `state update` regardless of which action type (sync `action` or
  async `suspendAction`) produced the write.

### Targets

- `:holdfast-hallmark` 2.0 ships for Android + iOS + JVM, matching the
  `:holdfast` and `:holdfast-coroutines` target set. JS / Wasm / non-iOS native
  targets are deferred to a demand-driven minor release.

---

## 0.2.0

See `vault/CHANGELOG.md` for the unified 0.1.0 / 0.2.0 history that
preceded the per-module changelog split. `:holdfast-hallmark` was introduced
in 0.2.0.
