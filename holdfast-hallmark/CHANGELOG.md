# Changelog — `:holdfast-hallmark`

All notable changes to `:holdfast-hallmark` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`Store.boxed(validator, codec, tags, initial)` and
  `Store.boxedHandle(validator, codec, tags, initial)`**
  (`@ExperimentalStoreApi`, issue #20 R1/R3) — the boxed factories through
  `Store.state`'s experimental overload: a snapshot codec (wrap a primitive
  codec in `BoxedCodec`) and state tags. Calls that pass neither resolve to
  the stable factories. For a `StateTag.Secret` state, a validation failure
  — the initializer, a write through the `ValidatingTransformer`,
  `BoxedHandle.civilize` and `assign` — throws a `HallmarkException` whose
  violations keep their code, path and rule but read
  "`<code>` rejected the value (withheld: a Secret state)" and carry no
  arguments, where hallmark's rules may quote the value ("got 42").
  `:holdfast-hallmark-coroutines`' `suspendValidateAndMutate` withholds the
  value the same way for a Secret state (`SuspendBoxedSecretRedactionTest`).
  A `ValidatingTransformer` constructed by hand does not know its state's
  tags and keeps hallmark's messages (pinned by `BoxedSecretRedactionTest`);
  so does a validator you call yourself (`validator of primitive`).

- **`NonEmptyList<Violation>.withheld()`** (`@StoreInternalApi`, companion
  modules only) — the violations without the rejected value, as the Secret
  paths above throw them; `:holdfast-hallmark-coroutines` uses it.

- **`BoxedHandleDelegate.provideDelegate(thisRef, property)`**: `boxedHandle`
  forwards the new `StateDelegate.provideDelegate` of `:holdfast` (issue #20,
  R5), so a `val email by boxedHandle(…) { … }` state is declared when its
  store is constructed and a never-read handle's state is captured by
  `snapshot()`. `boxed(…)` returns the `state(…)` delegate itself and needs no
  change. As with any declared state, a never-read `boxed`/`boxedHandle` state
  whose initial value fails validation now makes `snapshot()` throw
  `HallmarkException`.

- `shouldBeBoxedAs` test matcher moved here from `:holdfast-testing` (package
  `com.vynatix.holdfast.hallmark`, previously
  `com.vynatix.holdfast.testing.matcher`), so `:holdfast-testing` no longer
  depends on the unpublished `com.vynatix:hallmark` artifact.

### Changed

- `shouldBeBoxedAs` withholds both values from its failure message for a
  `StateTag.Secret` state (issue #20, R3): it reads "Boxed mismatch on a
  Secret state: values withheld (from `<Wrapper>`)". A non-Secret state's
  message is unchanged.

- The module is excluded from the default build; opt in with
  `-Pholdfast.includeHallmark=true` after publishing the sibling
  [Hallmark](https://github.com/vynatix/hallmark) repo to `mavenLocal`.

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
