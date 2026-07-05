# Changelog

All notable changes to Holdfast are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with
the caveat that **0.x releases carry no API stability guarantee**. Breaking
changes may land in any 0.x bump; consumers should pin to an exact version.

## [Unreleased]

### Added

- **`ProfilingMiddleware`** (`com.vynatix.holdfast.middleware`) — drop-in
  transaction profiler. Records per-transaction monotonic-clock duration,
  outcome, savepoint/`frameId` identity, and the names of the state
  properties written; streams each finished transaction as a
  `TransactionSample` via an optional `onSample` callback and aggregates
  into a `StoreProfile` — read with `profile()`, or drain atomically with
  `reset()` (zeroes the counters and returns the final snapshot, so
  periodic collection is lossless) — with per-state write counts, slowest
  sample, and total/max/average durations. Purely
  observational — its own bookkeeping never throws, so attaching it cannot
  change a transaction's outcome; when state-name attribution is illegal
  (owner-thread-confined read after a `suspendAction` thread hop) the
  sample degrades to empty `modifiedStates` instead of failing.

- **Cross-store transaction API graduated to first class.** `atomic(vararg
  stores)` gains a `policy: FramePolicy = FramePolicy.Strict` parameter and a
  written consistency contract (GUIDE §15):
  - **Enrollment enforcement** — writing to a store not enrolled in the frame
    (via `action`, `mutate`, or `update`, including through an enclosing
    action's open transaction) throws `UnenrolledStoreException` instead of
    committing independently while the frame rolls back. Enforcement covers
    the frame body only; observers reacting to the commit may still write to
    foreign stores. Opt out per call site with
    `policy = FramePolicy.AllowUnenrolled`.
  - **Inner-error escalation** — an inner `action { }` on a participant that
    returns `TransactionResult.Error` now aborts the whole frame (all
    participants roll back; the frame returns `Error` carrying the inner
    exception). Opt out with `policy = FramePolicy.TolerateInnerErrors`.
    Frame-contract violations always escalate and RETHROW out of the frame
    instead of being folded into an ignorable `Error` result.
  - **Nested lock-order verification** — a nested frame introducing a store
    whose `lockOrderKey` sorts below an already-held key throws
    `FrameLockOrderException` at entry (always-on O(1) check) instead of
    risking a latent deadlock against a concurrent frame.
  - **Frame observability** — participant roots share a new
    `Transaction.frameId`; each participant store's middleware chain now
    fires for the frame (`started` before the body, `completed` for ALL
    stores before ANY store commits — a throw rolls the whole frame back —
    and `error` on rollback). New experimental (`@ExperimentalStoreApi`)
    `FrameObserver` / `FrameObservers` surface frame-level
    started/committed/rolledBack events.
  - New exception hierarchy: `FrameContractException` ←
    `UnenrolledStoreException` / `FrameLockOrderException` /
    `FrameInteropException`.

### Fixed

- **`derived` rejects invalid sources with teaching messages (F17).** Passing
  zero sources now throws `IllegalArgumentException` ("derived requires at least
  one source state…") instead of silently producing a state that never
  recomputes; passing a `computed{}` / hand-rolled `State` (which has no
  observer fanout) throws a teaching `IllegalArgumentException` instead of a
  bare `ClassCastException` deep inside the wiring.

- **Standalone `state.update { }` is now atomic (P1-update-rmw).** Outside an
  action, the read-modify-write is wrapped in an implicit `action` so the read
  and write happen together under the store's `transactionLock`; concurrent
  standalone `update`s no longer lose increments (10,000/10,000 survive).
  Inside an owned transaction the behavior is unchanged (read-your-own-writes,
  stages into that transaction). The KDoc, which previously already claimed an
  implicit single-shot transaction wrapped the operation, is now accurate.

- **`restore` no longer commits type-mismatched values (F11).** A snapshot now
  captures each state's runtime type; `restore` validates it against the
  destination state's current type and fails the whole restore (naming the
  state, atomically, mutating nothing) instead of silently committing an
  incompatible value into a same-named state — the hazard when restoring across
  differently-shaped stores. Sibling collection/map implementations are treated
  as compatible. New `restore(snapshot, validateTypes: Boolean = true)`
  parameter opts out for intentionally polymorphic (sealed-type) states.

- **`derived` recompute failures are no longer swallowed (F10).** Previously a
  throwing recompute `compute` (or a failing recompute commit) was discarded
  silently and the derived value froze. The failure now routes to a new
  optional `onError: ((Throwable) -> Unit)? = null` parameter on `derived`, or
  — when unset — to the store's `uncaughtObserverHandler` (loud by default);
  the derived value recovers on the next source commit. The post-commit task
  drain no longer swallows task exceptions either: a failing task is routed to
  the same policy and never corrupts the parent action's `Success` result.

- **Blocking `atomic(...)` now serializes against in-flight suspending work
  (F1).** For each participant (in lock order), `atomic` acquires the store's
  `AsyncSerializer` — the same hook `:holdfast-coroutines.suspendAction`
  installs — before taking the transaction lock, and releases it after the
  per-store scope (including the post-commit drain) exits. Previously an
  `atomic` overlapping an in-flight `suspendAction` on a shared store opened
  a root that clobbered the suspending transaction's active slot, silently
  cross-contaminating the two transactions' writes. Residual (documented in
  `atomic`'s KDoc): the serializer installs lazily on a store's first-ever
  `suspendAction`; an `atomic` racing that exact first call is unserialized,
  same as `action`.
- **Nested `atomic` no longer commits shared stores prematurely.** A nested
  frame overlapping an enclosing action/frame on the same thread now opens
  savepoints: its commit merges into the enclosing scope, and the enclosing
  rollback discards nested writes. Previously the nested frame committed the
  adopted root at inner exit, silently disabling the outer rollback for that
  store.
- **`derived` recomputes queued during an `atomic` frame now run at frame
  exit.** Previously the frame never drained the post-commit queue, so
  recomputes were deferred until the next unrelated action on that store.
- **A frame's commit-failure error now names the offending store (F7).** When
  a per-store commit throws inside `atomic` (phase 5), the returned `Error`
  wraps `TransactionException("Commit failed for <Store>#<lockOrderKey> in
  frame <frameId>", …)` — previously the message was an unattributed "Commit
  failed". `TransactionResult.transaction` still points at the last
  participant root in lock order (now documented as such); correlate per-store
  outcomes via `Transaction.frameId`.

### Documentation

- Clarified frame semantics in `atomic`/`action` KDoc and GUIDE §15: inner
  errors under `Strict` never *return* their `Error` to the inner call site —
  escalation rethrows to unwind the frame (F6); and `atomic`'s
  savepoint-vs-fresh-root distinction (an INTRODUCED store does not roll back
  with the enclosing scope, and the un-policed `c.action { atomic(a, b, c) }`
  torn-commit shape) is now spelled out (F3).

### Changed

- **BREAKING (behavior): `by state { }` now registers eagerly at construction
  (P1-lazy-registration).** A new `provideDelegate` operator runs each state's
  initializer when the owning store is constructed, in declaration order,
  instead of lazily on first read. `snapshot()` and `properties` now see every
  declared state on a freshly-constructed store — closing the
  snapshot/restore-on-untouched-store surprise. Consequences: a throwing
  initializer fails at construction (not on first read), and a
  forward-referencing initializer (`val y by state { x.value }` where `x` is
  declared later) now fails at construction — reorder declarations, or use
  `computed`/`derived`. Migration in `MIGRATING.md`. (Binaries compiled before
  this change stay lazy; only code recompiled against the new artifact registers
  eagerly.)

- **The CRTP `Self` type is enforced at construction (P1-crtp).** A
  mis-parameterized store — `class Foo : Store<Bar>()` instead of
  `class Foo : Store<Foo>()` — now throws an `IllegalStateException` naming both
  types at construction, instead of degrading to a swallowed
  `ClassCastException` deep inside the DSL. Enforcement is JVM/Android-only
  (generic-superclass reflection); iOS/wasmJs are no-ops (documented). Generic
  intermediate bases whose `Self` is a type variable (e.g. `EventfulStore`) are
  skipped.

- **BREAKING (behavior): a bare `store { }` no longer permits mutation
  (P1-invoke-nonatomic).** `store { count mutate 1 }` and
  `store { count update { … } }` now throw a teaching `IllegalStateException`:
  a bare `store { }` provides the receiver context only and opens no
  transaction, so each write would commit its own one-shot transaction with
  observers firing between them. Migration (see `MIGRATING.md`): use
  `store action { … }` for mutations. Non-mutating uses inside `store { }`
  (`effect`, `bridge`, `observeFrom`, reads) are unchanged, as is a nested
  `action { }`, an in-frame write on an enrolled store, and a standalone
  `mutate`/`update` reached with the store as receiver (a store method or
  `with(store) { … }`).

- **`TimingMiddleware` now reports the transaction's real outcome and includes
  commit fanout in the elapsed time (F31).** `onTransactionCompleted` fires
  before the commit, so the success report is deferred via `Store.postCommit`
  until after the commit's observer/bridge/event fanout. The reported
  `TransactionStatus` is now truthful — `Committed`, or `Failed` if the commit
  itself threw — and metric consumers keying on the enum may see the new
  `Failed` value. The elapsed time now includes fanout, not just the body.

- **`emit()` gains the ownership check `mutate` has (P1-emit-owner).**
  `EventfulStore.emit` / `EventfulSupport.emit` from a thread that does not own
  the active transaction (and is not the in-flight suspending body) now throws
  `IllegalStateException` instead of staging an event onto another action's
  transaction — matching `mutate`'s contract. `Transaction.stagePendingEvent`'s
  owner-thread requirement is now enforced, not just documented.

- **`atomic`, `derived`, and `computed` now enforce the disposed-store
  contract (P1-disposed-gaps).** `atomic` checks every participant before
  acquiring any lock; `derived`/`computed` check at entry. Calling them on a
  disposed store throws `IllegalStateException` instead of silently
  proceeding. Adds the `@StoreInternalApi Store.internalCheckNotDisposed()`
  hook so companion modules enforce the same rule.

- **Sharper diagnostic messages for disposed-store and cross-store-state
  errors (F32).** The disposed-store `IllegalStateException` now names the
  store class (`"CounterStore is disposed — dispose() is terminal; …"`), and
  the ownership check separates its two failures: a `computed{}`/hand-rolled
  `State` passed to `mutate`/`bridge` reports "not produced by store.state { }",
  while a state owned by another store names the state property and both store
  classes. Message text only — no API change.

- **Observer/effect exceptions during commit fanout are now loud by default
  (P1-observer-swallow).** Previously a `null`
  `Store.uncaughtObserverHandler` swallowed a throwing observer silently.
  It now routes to a built-in fallback that prints the store identity and
  stack trace. Set your own handler to reroute, or assign a no-op lambda
  `{ }` to opt back into silence. The exception is still contained — other
  observers on the same commit continue to fire.

- **BREAKING (behavior): a nested `atomic` may no longer introduce an
  unenrolled store under `FramePolicy.Strict` (F2).** `verifyFrameNesting`
  now throws `UnenrolledStoreException` at entry when a nested frame enrolls
  a store that no frame in the enclosing chain enrolls and the enclosing
  policy is not `AllowUnenrolled`. An introduced store gets a fresh root
  that commits at the nested frame's exit and does NOT roll back with the
  enclosing frame — the same silent escape a bare unenrolled write would
  be, now closed for nested frames too. Migration (see `MIGRATING.md`):
  enroll the store in the outermost frame, or pass
  `policy = FramePolicy.AllowUnenrolled` on the enclosing frame to run the
  nested frame as a deliberate independent (REQUIRES_NEW-style)
  transaction.

- `atomic`'s vararg parameter is named `stores` (was pre-rename `vaults`) —
  source-compatible for positional calls; update any named-argument call
  sites.

- Completed the Vault → Store rename in the public API:
  `EventfulSupport.bindVault(...)` is now `bindStore(...)` and
  `MutableState.owningVault` is now `owningStore`. The testing harness entry
  point `vaultTest { }` (in `:holdfast-testing`) is now `storeTest { }`.

### Removed (ABI)

- **ABI hygiene (F29).** Removed accidentally-public members that were never
  intended as API: the `FileSystemKvStore` companion constants
  (`HEX_DIGITS`/`HEX_RADIX`/`TMP_PREFIX`) and `TimingMiddleware.KEY_START_MS`
  are now `private`; `MutableState`'s constructor is now `internal` (construct
  states via `store.state { }`). `StoreLock` is annotated `@StoreInternalApi`
  (it remains in the ABI but now requires opt-in to reference). Also:
  `MutableState.bridge`'s setter now enforces the disposed-store check; and the
  KDoc of `Publisher.publish(): Boolean` and `TransactionResult.Success`/`Error`
  `copy()` now document their known API-shape caveats (return-value ignored;
  forgeable copy) ahead of the pre-1.0 breaking window.

### Deprecated

- `EventfulSupport.bindVault`, `MutableState.owningVault`, and `vaultTest`
  remain as `WARNING`-level deprecated aliases delegating to the new names;
  they will be removed after one minor release.

### Added

- `TransactionResult` ergonomics: `getOrThrow()` (returns the `Success` value
  or rethrows the original `Error.exception`), `valueOrNull`, and chainable
  `onSuccess { }` / `onError { }` extensions — so fire-and-forget `action`
  callers can surface rollbacks instead of silently dropping them.

- **`KvBridge` gains an optional `onDecodeError: ((encoded, cause) -> Unit)?`
  constructor parameter (F12).** Load-on-attach decode failures are still
  dropped silently by default (state stays at its initializer, and the next
  commit overwrites the un-decodable payload) — the hook lets you observe the
  raw payload and cause at the moment of the drop so you can quarantine or
  migrate it first. The KDoc now documents both this overwrite hazard and the
  save-failure contract: a throwing `encode`/`put` surfaces the transaction as
  `TransactionResult.Error` (after the in-memory commit and observer fanout have
  already applied), not a rollback.

- Documented platform support tiers in the root and module READMEs:
  Android/JVM/iOS are supported (tests run in CI); wasmJs is **experimental** —
  the artifact is still published, but tests are disabled on wasmJs,
  `FileSystemKvStore` throws `UnsupportedOperationException`, the seedless
  `suspendDerived` overload is unusable (`runBlocking` initial seed — use the
  new `suspendDerived(..., initial = ...)` overload instead), and the platform
  is single-threaded (`currentThreadId() == 0`). Doc-only; no code changes.

- **Documented that `distinct = true` is inert on an `EncryptingTransformer`
  state backed by a non-deterministic cipher (F30).** Dedup compares
  post-`Transformer.set` raw values (ciphertext); a secure per-value-IV cipher
  encrypts equal plaintext to different ciphertext, so dedup never fires and
  observers/bridges publish on every commit. `Cipher` / `EncryptingTransformer`
  KDoc and GUIDE §14.4 spell this out; a pinning test in `CryptoTest` locks the
  behavior. Docs + test only; no code or API change.

### Infrastructure

- **Real Maven Central publishing pipeline.** The `holdfast.publish.sonatype`
  convention plugin now applies the vanniktech `com.vanniktech.maven.publish.base`
  plugin and configures `mavenPublishing { publishToMavenCentral(); pom { … } }`,
  replacing the hand-rolled `maven-publish` repository block that pointed at the
  Central Portal upload URL (a bundle-POST endpoint `maven-publish` cannot speak).
  `.github/workflows/publish.yml`'s `publishAndReleaseToMavenCentral` task and its
  `ORG_GRADLE_PROJECT_*` env vars are now the plugin's real contract. Signing is
  wired only when a `signingInMemoryKey` is present, so
  `./gradlew publishToMavenLocal -Pholdfast.version=<v>` still succeeds UNSIGNED
  for local verification. No artifact has been published to Central yet — the
  first Central release will be 0.2.0.

## 0.1.0 — Initial public release

First public release on Maven Central as `com.vynatix:holdfast` (plus the
companion modules `:holdfast-coroutines`, `:holdfast-compose`, `:holdfast-testing`,
`:holdfast-hallmark`, `:holdfast-hallmark-coroutines`).

The library was developed internally under the name `vault` (versions 1.x
through 2.0). No prior version was published to Maven Central; the public
artifact line begins at 0.1.0 under the `holdfast` name.

The internal 1.x → 2.0 history is preserved below as design archive — it
documents the evolution of the API but does not correspond to any published
release. Reading order: this `0.1.0` entry first, then everything below as
historical context for *why* certain API choices look the way they do.

---

## Internal-only history (preserved as design archive)

The entries below describe internal versions never published to Maven Central.
They predate the rename to Holdfast and use the original `Vault` / `:vault`
names that were in effect at the time. Treat as design history, not as a
release log a consumer ever migrated against.

## 2.0.0 — 2026-05-03 (internal)

Coordinated 2.0 cut across `:holdfast`, `:holdfast-coroutines`, `:holdfast-compose`,
and `:holdfast-hallmark`. See [MIGRATING.md](../MIGRATING.md) for the
per-call-site rewrite cheatsheet.

This release is built on top of 0.4.0 — every feature documented in the
0.4.0 entry below is carried forward into 2.0.0 (`Transformer.then`,
the validation-rule additions, etc.). 2.0.0 adds the scope-ownership,
events, and `applyCommittedRaw`/`stagePendingEvent` internal hooks
described here.

### Added

- **`Store.scope: CoroutineScope`** with three-tier resolution: per-call
  parameter → per-store `override val scope` → process-default
  `Store.defaultScope`. The settable-once `Store.defaultScope` lazily backs
  off to a process `SupervisorJob + Dispatchers.Default` if never assigned.
  App-init pattern: `Store.defaultScope = appScope` once at startup.
- **`Store.bindToScope(scope)`** — replaces the bound scope reference for
  one holdfast. Optional. Calling on an already-bound vault rebinds.
- **`Store.dispose()`** — terminal lifecycle. Idempotent. Clears states,
  detaches bridges, clears observers, terminates events `SharedFlow`. Does
  NOT cancel the bound scope (caller owns its lifecycle). Subsequent calls
  to scope-using or transactional APIs throw `IllegalStateException`.
- **`Eventful<E>` interface** (`val events: SharedFlow<E>` + `fun emit(event)`)
  and **`EventfulStore<Self, E>`** base class. Events stage into the active
  transaction's `pendingEvents` and emit during commit, AFTER state observer
  fanout and bridge publish. Lossless-by-default: `replay = 0`,
  `extraBufferCapacity = 16`, `BufferOverflow.SUSPEND`. Off-action `emit`
  throws `IllegalStateException`.
- **`EventfulSupport<E>`** — delegate helper for vaults that already extend
  another base and cannot extend `EventfulStore`. Same staging machinery
  exposed as a delegate field.
- **`@VaultInternalApi MutableState.applyCommittedRaw(value)`** — splits
  observer fanout out of the bridge-publish path so `:holdfast-coroutines`
  can interpose `SuspendingBridge.publishAwaited` between observers and
  bridges during the `suspendAction` commit phase.
- **`@VaultInternalApi Transaction.stagePendingEvent(channel, event)`** —
  the per-transaction event buffer used by `EventfulStore.emit`. Discarded
  on rollback; merged into parent on nested commit.

### Removed

- This module surface is unchanged in terms of removals; the only
  removal in the 2.0 cut lives in `:holdfast-coroutines`. See that module's
  changelog.

### Changed (behavior, signature stable)

- **Commit-phase ordering is now universal**: state observers fire
  (post-`transformer.get`), then bridges publish (sync `Bridge.publish`;
  for `SuspendingBridge` under `suspendAction`, `publishAwaited` is
  awaited), then events `tryEmit` to their `MutableSharedFlow`. Subscribers
  to `events` always see "saved" after `state` observers see the new state.

### Changed (signature)

- **`MutableState<T>.observe` visibility narrowed to `internal`.**
  Migration: `(state as MutableState<T>).observe { ... }` →
  `state effect { ... }` (uses the new top-level `State<T>.effect`
  extension exposed by `:holdfast-coroutines`).

---

## [0.4.0] — 2026-05-03

Closes the deferred-features list from 0.3.0. Additive across the validation
modules; one additive change to `:holdfast` core (`Transformer.then`); one new
module (`:holdfast-hallmark-coroutines`).

### Added — `:validation`

- **9 format-regex rules** in `com.vynatix.hallmark.rules`:
  `EmailRule`, `UrlRule`, `UuidRule`, `Ipv4Rule`, `Ipv6Rule`, `E164PhoneRule`,
  `Iso8601DateRule`, `Iso8601DateTimeRule`, `IbanRule`. Each captures a
  practical pattern (NOT a strict RFC grammar) — the patterns target HTML5 /
  OWASP common usage, not edge-case correctness. Adopters needing stricter
  forms compose `MatchesRule(theirRegex)` or subclass `Rule<String>`.
- **Collection field validators** in `com.vynatix.hallmark`:
  `each(name, getter, elementValidator)` validates every element of an
  `Iterable<E>` field with indexed path notation (`["addresses", "[2]", "zip"]`);
  `forKey(name, getter, key, valueValidator)` validates a specific map key
  with quoted-key path notation (`["tags", "[\"primary\"]"]`). Both
  accumulate violations across elements.
- **`MessageResolver` + `EnglishMessageResolver` default**. `interface
  MessageResolver { fun resolve(violation: Violation, locale: String? = null): String }`.
  Adopters wire their own (Android resources, kotlinx-i18n, in-house bundle)
  to consume `Violation.code` + `Violation.args`. `HallmarkResult.resolveAll(resolver)`
  helper for batch resolution.
- **Schema export / introspection.** `Validator<IN, OUT>.describe(): ValidatorDescription`
  surfaces leaf specs/rules and composite field structure. Three sealed
  variants: `LeafDescription`, `CompositeDescription`, `OpaqueDescription`.
  Useful for OpenAPI / JSON-Schema generation and form-builder UIs.

### Added — `:holdfast` core

- **`Transformer<T>.then(other: Transformer<T>): Transformer<T>`** — composition
  primitive that lets adopters chain transformers (e.g.
  `ValidatingTransformer + EncryptingTransformer`). Set order: this then
  other; get order: other then this (round-trip-preserving). `shouldTransform`
  is logical OR.

### Added — `:holdfast-hallmark`

- **`BoxedHandle<P, O>` + `boxedHandle()` factory** — alternative to `boxed()`
  that returns a property of type `BoxedHandle` instead of bare `State<O>`.
  Bundles the underlying `state` and `validator` so call sites can
  `email.state mutate email.civilize("alice@example.com")` without naming
  the validator object externally. The original `boxed()` factory is
  unchanged.
- **`assign` infix** on `BoxedHandle<P, O>` — civilize a raw primitive and
  atomically mutate the state inside an `action { }` block:
  ```kotlin
  vault action { email assign "alice@example.com" }
  ```
  Implemented via Kotlin context parameters (`-Xcontext-parameters`,
  enabled globally in the `astrid.kmp.library` convention plugin). Throws
  `HallmarkException` and rolls back on validation failure.

### Added — `:holdfast-hallmark-coroutines` (new module)

- New companion artifact `com.vynatix:holdfast-hallmark-coroutines`.
- **`Store<V>.suspendValidateAndMutate(state, suspendValidator, primitive)`** —
  primary entry. Runs the suspend validator (which may do I/O), then mutates
  the Vault state inside a `suspendAction { }`. Atomic: validation failure
  rolls back the entire transaction.
- Tests cover acceptance, rejection-with-rollback, and
  `HallmarkException` propagation.

### Documentation

- `validation/KONFORM-MIGRATION.md` (new) — 1:1 conceptual mapping from
  Konform's `Validation<T>` API to `:validation`'s surface. Drop-in for
  adopters migrating; no runtime Konform dep.
- `vault/README.md`, `vault/CHANGELOG.md`, `vault/GUIDE.md` updated.

### Verification

- 53+ tests across the validation modules; 8 modules total green on
  Android JVM + iOS sim.
- `apiCheck` clean across 8 modules; new ABI baselines committed.
- `detekt` + `ktlint` clean across all 8 modules.
- 32 GAVs at 0.4.0 published to `~/.m2/repository/com/vynatix/`.

## [0.3.0] — 2026-05-03

Validation library reshape. The 0.2.0 `:holdfast-hallmark` surface is fully
replaced and split into three modules; the rest of the Vault libraries (`:holdfast`,
`:holdfast-coroutines`, `:holdfast-compose`) carry forward at 0.3.0 with no API
changes — only the version label moves.

This is a **hard break** for `:holdfast-hallmark` consumers. Pre-1.0 SemVer
permits this. No deprecation shims. The 0.2.0 GAVs in `~/.m2` remain intact
for retrieval if you've not yet migrated.

### Added — `:validation` (new module)

Standalone KMP boundary-validation library; **no Vault dependency**.

- **`Boxed<P : Any>`** — typed wrapper interface carrying `val value: P`.
- **`Rule<PRIMITIVE>`** — abstract class with `code: String`,
  `messageTemplate: String`, abstract `validate(p): Boolean`, and
  override-able `message(p)` / `args(p)` for i18n templating.
- **`Violation`** — data class `(message, path, code, rule, args)`. Carries
  rule reference for test introspection and a free-form `args` map for i18n
  resolvers.
- **`HallmarkResult<O>`** — sealed `Success(value)` / `Failure(violations: NonEmptyList<Violation>)`.
  `getOrThrow()` throws `HallmarkException` (an `IllegalArgumentException`
  subclass that exposes `violations`); `getOrNull()` returns null on failure.
- **`NonEmptyList<T>`** — minimal in-house non-empty list. No Arrow dep.
- **`Spec<P : Any, O : Boxed<P>>`** — data class `(rules, mode: SpecMode, factory)`.
  Multi-spec validators (e.g. `NumberValidator` accepting Int OR Float) declare
  multiple specs.
- **`SpecMode { ALL, ANY }`** — combine rules within a spec.
- **`Validator<IN, OUT>`** — unified interface with `validate(value)`,
  `infix of(value)`, `ofOrNull(value)`. Both leaves and composites produce values
  of this type — composites can `field(name, getter, validator)` either form.
- **`BoxedValidator<P : Any, O : Boxed<P>>`** — abstract base for leaf
  validators. Subclass override `specs`; the base implements `validate` /
  `of` / `ofOrNull` for you.
- **`validator<T> { field(name, getter, validator) }`** — composite DSL builder
  producing `Validator<T, T>`. Conditional fields work (`if (admin) field(...)`).
  Composites compose recursively — a field may take any `Validator<IN, OUT>`,
  leaf or composite. Path tags thread automatically via
  `HallmarkResult.atPath(name)`.
- **14 prebuilt rules** in `com.vynatix.hallmark.rules`:
  - String: `NonEmptyRule`, `NonBlankRule`, `LengthInRule(IntRange)`,
    `MinLengthRule(n)`, `MaxLengthRule(n)`, `MatchesRule(Regex)`,
    `StartsWithRule(prefix)`, `EndsWithRule(suffix)`.
  - Number (`Comparable<T>`): `GtRule(n)`, `GteRule(n)`, `LtRule(n)`,
    `LteRule(n)`, `InRangeRule(range)`.
  - Collection: `NonEmptyCollectionRule<T>`, `SizeInRule<T>(IntRange)`.

  Format-specific regex rules (email, URL, UUID, IBAN) intentionally **not**
  shipped — their canonical forms are debatable and ownership is a maintenance
  trap. Adopters bring their own via `MatchesRule(myRegex)`.

- 24 tests in `:validation` covering HallmarkResult atPath/getOrThrow,
  BoxedValidator multi-rule + multi-spec + ALL/ANY accumulation, composite
  cross-field accumulation + nested path threading, and every prebuilt rule.

### Added — `:validation-coroutines` (new module)

Suspend extension. Mirrors the `:holdfast` / `:holdfast-coroutines` split.

- **`SuspendRule<PRIMITIVE>`** — `suspend` analog of `Rule<PRIMITIVE>` for
  out-of-process checks (DB unique-lookup, remote feature gate).
- **`SuspendValidator<IN, OUT>`** — `suspend` analog of `Validator<IN, OUT>`.
- **`SuspendBoxedValidator<P : Any, O : Boxed<P>>`** — abstract base for
  suspend leaves; mirrors `BoxedValidator`.
- **`SuspendSpec<P : Any, O : Boxed<P>>`** — data class with `suspend` factory.
- **`suspendValidator<T> { field(...) }`** — suspend composite DSL with two
  `field` overloads: one accepting sync `Validator<IN, OUT>`, one accepting
  `SuspendValidator<IN, OUT>`. Mix sync and suspend leaves freely.
- **`Rule<P>.asSuspend()`** / **`Validator<IN, OUT>.asSuspend()`** — lift sync
  rules / validators into suspend types when needed for explicit composition.
- 3 tests covering happy/failure paths and mixed sync+suspend composites.

### Added — `:holdfast-hallmark` (rebuilt module)

Vault adapter; tiny — just a transformer + state factory + codec.

- **`ValidatingTransformer<P : Any, O : Boxed<P>>(validator)`** — Vault
  `Transformer<O>` that re-validates on every write. Defence-in-depth against
  constructor-bypass writes (e.g. `data class copy`). A failure throws
  `HallmarkException` and rolls the transaction back.
- **`Store.boxed(validator) { initial }`** — state factory extension; sugar
  for `state(transformer = ValidatingTransformer(v)) { v of initial() }`.
  Eliminates duplicate validator references at state declaration sites.
- **`BoxedCodec<P : Any, O : Boxed<P>>(primitiveCodec, validator)`** —
  `Codec<O>` for `KvBridge` persistence. Encodes by stripping the wrapper and
  delegating to the primitive codec; decodes by running the primitive through
  the validator.
- 5 tests covering transformer happy/rollback paths, codec round-trip for
  String- and Long-backed Boxed types, and decode-of-now-invalid-primitive
  rollback.

### Removed — `:holdfast-hallmark` 0.2.0 surface

The following are gone in 0.3.0:

- `com.vynatix.holdfast.hallmark.Civilizable` (renamed to `Boxed` in 0.2.x → moved to `com.vynatix.hallmark`)
- `com.vynatix.holdfast.hallmark.Civilizer` / `Validator<P, R, O>` (replaced by `Validator<IN, OUT>` + `BoxedValidator<P, O>` in `com.vynatix.hallmark`)
- `Variation` / `Spec<P, R, O>` (replaced by `Spec<P, O>` data class with `SpecMode`)
- `Condition<P, R>` (dropped; replaced by `SpecMode { ALL, ANY }` enum)
- `Declaration<P, O>` typealias (replaced by inline `(P) -> O`)
- `createVariation` / `createSpec` builder members (composite DSL replaces them)
- `allConditions()` / `anyConditions()` (replaced by `SpecMode`)

### Changed — Packaging

- Three new GAVs: `com.vynatix:validation:0.3.0`,
  `com.vynatix:validation-coroutines:0.3.0`, plus the rebuilt
  `com.vynatix:holdfast-hallmark:0.3.0`.
- 6 published modules total, 24 GAVs across `kotlinMultiplatform` / `android`
  / `iosArm64` / `iosSimulatorArm64` targets.
- Default `astrid.publish` version bumped 0.2.0 → 0.3.0.

### Documentation

- `vault/README.md` — modules table includes the three validation modules; the
  1.1 additions section gets a Validation 0.3.0 paragraph; build cheatsheet
  adds the new `:validation:allTests :validation:apiCheck` and
  `:validation-coroutines:allTests :validation-coroutines:apiCheck` lines.
- `vault-validation/0.3.0-DESIGN.md` (new) — captures the 15 grilled design
  decisions verbatim, with rationale.

### Verification

- `./gradlew :validation:allTests :validation-coroutines:allTests :holdfast-hallmark:allTests` — green on Android JVM + iOS sim.
- `./gradlew apiCheck` clean across all 6 modules; new ABI baselines committed.
- `./gradlew detekt ktlintCheck` clean across all 6 modules.
- `publishToMavenLocal` produces 24 GAVs at 0.3.0 in `~/.m2/repository/com/vynatix/`.

## [0.2.0] — 2026-05-02

Additive minor release. Every item that 0.1.0 deferred ships in 0.2.0; no
breaking changes vs. 0.1.0. The cross-module integration hooks are gated by
the `@VaultInternalApi` opt-in annotation introduced in this release — companion
modules (`vault-coroutines`, `vault-compose`) `@OptIn` to reach them; application
code should not.

### Added — Core (`com.vynatix.holdfast`)

- **`Store.snapshot()` / `Store.restore(snapshot)`** — capture the raw stored
  value of every registered state into a `StoreSnapshot`; restore writes them
  back inside a single top-level `action`. Implemented via a new internal
  `Transaction.stagePendingRaw(state, rawValue)` that bypasses
  `transformer.set`, so asymmetric transformers (e.g. encryption, JSON
  codecs) round-trip losslessly. Restore of an unknown state name throws
  (caught by the wrapping action → `TransactionResult.Error`).
- **`Store.computed { }`** — read-time derived state. Cheap, stateless, NOT
  observable; every read of `value` re-runs `compute`.
- **`Store.derived(vararg sources, compute): Pair<State<T>, Disposable>`** —
  push-recomputed derived state. Subscribes to each source via `effect`; on
  each source commit, runs `compute` inside a fresh top-level action and
  stages the result into a backing `MutableState`. Returns the derived state
  plus a `Disposable` for explicit teardown.
- **`atomic(vararg vaults: Store<*>, body): TransactionResult<R>`** — top-
  level cross-vault transaction primitive. Each `Vault` gains a stable
  `lockOrderKey: Long` (process-monotonic, set at construction); `atomic`
  sorts vaults by this key and acquires each `transactionLock` in global
  order — deadlock-safe across any combination. Inner `v.action {}` joins
  the atomic frame as a savepoint of `v`'s root via the existing
  parent-chain machinery.
- **`Store.postCommit(task)`** — internal post-commit deferred-task queue,
  drained at top-level action exit. Used by `derived` to defer a recompute
  past the parent's commit fanout (avoids re-entering `pendingWrites` mid-
  iteration). Foundation for future userland post-commit hooks.

### Added — Standard library (`com.vynatix.holdfast.crypto`, `.bridge`)

- **`Cipher`** interface + **`EncryptingTransformer(cipher) : Transformer<String>`** —
  encrypt-on-write, decrypt-on-read. Stored `currentValue` is ciphertext;
  `KvBridge`-persisted bytes are ciphertext; reads through `state.value`
  return plaintext. Asymmetric-rollback safe.
- **`XorCipher(seed: ByteArray)`** — KMP-pure educational `Cipher` (NOT
  production-grade — clearly documented). Production users implement
  `Cipher` over `javax.crypto` (JVM) or CryptoKit (iOS).
- **`FileSystemKvStore(rootPath: String)`** — `expect class` `KvStore` impl
  for `KvBridge`. Atomic writes via tempfile + rename:
  - androidMain: `java.nio.file.Files.move(StandardCopyOption.ATOMIC_MOVE)`
  - iosMain: `NSData.writeToURL(atomically = true)`
  - URL-percent-encoded keys make any String a safe filename.

### Added — `:holdfast-coroutines`

- **`suspend fun V.suspendAction(body: suspend V.() -> R): TransactionResult<R>`** —
  async-aware transactional body. Backed by `kotlinx.coroutines.sync.Mutex`
  installed lazily via the new `Store.AsyncSerializer` hook. Mutually
  exclusive with blocking `Store.action` on the same vault — blocking
  callers `tryLock`-spin via `threadYield()`. Cancellation rolls back the
  body; commit phase wraps in `withContext(NonCancellable)` so observer
  / bridge fanout completes cleanly even if the surrounding scope cancels
  mid-commit.
  - Limitations (1.1): no middleware support (the existing chain wraps a
    non-suspending block); body should be single-threaded.

### Added — Cross-cutting

- **`@VaultInternalApi`** — opt-in annotation gating cross-module integration
  hooks. The annotation is `RequiresOptIn(level = ERROR)`; companion modules
  (`vault-coroutines`) `@file:OptIn(VaultInternalApi::class)` to reach the
  necessary internals. Application code should never opt in.
- **`Store.AsyncSerializer`** interface + `Store.asyncSerializer` slot —
  external-mutex extension point for `:holdfast-coroutines.suspendAction`.
  When non-null, blocking `action` brackets each call with the serializer's
  `blockingAcquire` / `blockingRelease`.
- **`Store.suspendingOwner`** — recognized by `mutate`'s ownership check so
  cross-thread coroutine-resume points inside a suspending body are still
  treated as in-transaction.
- **`Transaction.createForExternal(id, ownerThreadId)`** — public-but-opt-in
  factory used by `suspendAction` to manufacture a top-level transaction
  outside the blocking lock.
- **`Store.runUnderLock(block)`** — public-but-opt-in lock-holder used by
  `atomic(...)`.
- **`Store.lockOrderKey: Long`** — public-but-opt-in process-monotonic
  ordering key; primary use is `atomic`'s sorted lock acquisition.

### Added — Packaging

- **`astrid.publish.sonatype` convention plugin** — Sonatype/Central
  publication with GPG signing. Layers on top of `astrid.publish` to add:
  - `signing` plugin with key from env (`SIGNING_KEY` / `SIGNING_PASSWORD`)
    or `~/.gradle/gradle.properties` (`signing.key` / `signing.password`).
  - Sonatype Central staging repo (`publishToSonatype` task).
  - Pre-flight steps (group claim, GPG key, credentials) documented in the
    plugin's KDoc — manual one-time setup.
  - Smoke verification: `publishToMavenLocal` produces `.asc` signature
    files alongside artifacts when signing credentials are present.
- Default published version bumped: **0.1.0 → 0.2.0**.

### Changed

- **Default `org.gradle.jvmargs`** in `gradle.properties` bumped from
  `-Xmx2048M` to `-Xmx4096M` with `-XX:MaxMetaspaceSize=1024M` to handle
  the larger multi-module build comfortably (vault + vault-coroutines +
  vault-compose all at once).
- **`Transaction.commit`** behavior preserved with explicit comment that
  pending writes remain readable via `findPendingValue` during the
  iteration so observer callbacks reading sibling states still see the
  about-to-be-committed values (read-your-own-writes during fanout) — no
  semantic change vs. 0.1.0, just clarified.

### BankingDemo updates

- New `taxId` state on `AccountHoldfast` declared with
  `state(EncryptingTransformer(XorCipher(seed)))` — exercises the new
  encryption transformer.
- `transferTo` rewritten using `atomic(this, other) { … }`. The
  hand-rolled compensation path (re-credit on credit-side failure) is
  gone — `atomic` rolls back both vaults together.
- Six new focused 1.1 feature tests appended to `class BankingDemo`:
  - `encryptingTransformerProtectsTaxIdAtRest` — KvBridge persists
    ciphertext, reads return plaintext.
  - `fileSystemKvStorePersistsBalanceAcrossSimulatedRestart` — disk
    round-trip across two vault sessions.
  - `snapshotAndRestoreRoundTripsAccountStateIncludingEncryptedFields` —
    encrypted state survives snapshot/restore (raw round-trip means no
    double-encrypt).
  - `derivedNetDebitsRecomputesOnLedgerCommits` — push-recomputed running
    total fires its own observers.
  - `crossVaultAtomicTransferRollsBackBothVaultsOnFailure` and
    `crossVaultAtomicTransferSucceedsAtomically` — end-to-end atomic.
- Local `InMemoryKvStore` + `BalancePersistenceBridge` private fixtures
  removed — superseded by the stdlib `KvBridge(kv, key, codec)` over
  `com.vynatix.holdfast.bridge.InMemoryKvStore`.
- Unused `freeze`/`unfreeze`/`close` operations annotated `@Suppress("unused")`
  for surface-completeness.
- Redundant fully-qualified `com.vynatix.holdfast.middleware.*` /
  `.bridge.*` references in `stdlibShowcase` replaced with imports.
- BankingDemo now: 9 `@Test`s, ~14 ms on JVM.

### Documentation

- **README.md**: new "Major capabilities" section split into 1.0 surface
  and 1.1 additions; standard-library table grew with `FileSystemKvStore`,
  `Cipher`/`EncryptingTransformer`, `XorCipher`; concurrency model documents
  `atomic`'s `lockOrderKey` and `suspendAction`'s `AsyncSerializer` mutex.
- **GUIDE.md**: API reference signatures refreshed to 1.1 (generic `action<R>`,
  `update`, `observeFrom`, `bridge null`, `state(distinct)`, sealed interface
  `TransactionResult<out R>`, `Transaction.modifiedStates`, `endTime: Long?`,
  `uncaughtObserverHandler`, `lockOrderKey`). New Section 14 "The 1.1 Surface"
  with 10 sub-sections covering snapshot/restore, computed/derived,
  `atomic(...)`, `EncryptingTransformer`/`Cipher`, `FileSystemKvStore`,
  standard middleware, `KvBridge`/`Codec`/`KvStore`, `:holdfast-coroutines`,
  `:holdfast-compose`, plus a 1.1-idioms cookbook (encrypted credentials,
  one-line atomic transfer, running-total derived, undo via snapshot,
  async transactional fetch). One-page cheatsheet at the end shows the
  1.1 forms.
- Per-module READMEs unchanged (vault-coroutines/README.md already
  documented `suspendAction`).

### Verification

- 305+ tests pass on Android JVM + iOS sim across `:holdfast`, `:holdfast-coroutines`,
  `:holdfast-compose`.
- `apiCheck` clean for all three modules; `.api` baselines refreshed.
- `detekt` + `ktlint` clean.
- `:android:assembleDebug` succeeds against the new APIs.
- `publishToMavenLocal` produces `com.vynatix:0.2.0` artifacts for all
  three modules.

---

## [0.1.0] — 2026-05-02

First versioned release. The library is **not yet 1.0** — APIs may evolve based
on early-adopter feedback. Binary compatibility is tracked via
`binary-compatibility-validator` from this release forward; future ABI breaks
will appear as diffs in `vault/api/*.api`.

### Added — Core
- `State<T>.update { … }` — read-modify-write convenience.
- `State<T>.observeFrom(Observable<T>)` — inbound-only push subscription.
- Generic `action<R>(body): TransactionResult<R>` — body's value carried in
  `TransactionResult.Success.value`.
- `state(distinct = true)` — opt-in same-value dedup for observers and bridges.
- `Transaction.modifiedStates: Set<State<*>>` — owner-thread-only read view of
  pending-write keys, for audit middleware and userland undo.
- `@VaultActionDsl` `@DslMarker` on `Store<Self>` — prevents accidental
  outer-receiver access in nested DSLs.
- `Store.uncaughtObserverHandler: ((Throwable) -> Unit)?` — opt-in surfacing
  of commit-fire observer exceptions (default null preserves silent-swallow).

### Added — Standard library (`com.vynatix.holdfast.middleware`, `com.vynatix.holdfast.bridge`)
- `LoggingMiddleware<V>(tag, log)` — drop-in tracing of every transaction.
- `TimingMiddleware<V>(onResult)` — wall-clock duration measurements.
- `ValidationMiddleware<V>(check)` — post-body invariant check with rollback.
- `Codec<T>` interface + `StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`.
- `KvStore` interface + `InMemoryKvStore` impl.
- `KvBridge<T>(kv, key, codec)` — generic save-on-commit + load-on-attach
  bridge backed by any `KvStore`.

### Added — New modules
- **`com.vynatix:holdfast-coroutines`** — `State<T>.asFlow`, `asStateFlow(scope)`,
  `asEagerStateFlow`, `first(predicate)`, `awaitValue(target)`.
- **`com.vynatix:holdfast-compose`** — `@Composable State<T>.collectAsState()`,
  `@Composable rememberDisposable { … }`.

### Changed (BREAKING)
- `Store.action` is now generic in the body's return type. `TransactionResult`
  is a `sealed interface TransactionResult<out R>`:
  - `Success<R>(transaction, value: R)`
  - `Error(exception, transaction): TransactionResult<Nothing>`

  Migration:
  - `assertIs<TransactionResult.Success>(r)` → `assertIs<TransactionResult.Success<*>>(r)`
    (or `<Unit>` where the body returns Unit).
  - `vault action { … }: TransactionResult` → `: TransactionResult<R>` (R inferred
    from the body, or `Unit` for void bodies — usually inferred automatically).
- `infix State<T>.bridge(b)` now accepts `Bridge<T>?` (null detaches). Existing
  non-null callers compile unchanged.
- `Transaction.endTime` is now `Long?` (epoch millis) instead of `String?`.
- `Store.middlewares(...)` documentation corrected: the LAST argument is the
  outermost middleware. Place logging/audit middleware last so `onTransactionError`
  sees inner middlewares' failures.
- The `UUID` and `Timestamp` classes are removed in favor of `kotlin.uuid.Uuid`
  and `kotlinx.time.Clock`.

### Fixed
- `MutableState.bridge` setter no longer leaks the previous bridge's inbound
  observer registration on swap or null-set.
- `getMutableState` ownership check is now O(1) via `MutableState.owningVault`,
  down from O(N) over `_properties.values`.
- `removeState` / `clearStates` now dispose observers and bridges silently;
  removing a state with pending writes in an active transaction throws
  `IllegalStateException` instead of silently orphaning them.

### Documentation
- Full KDoc on every public type and member.
- `GUIDE.md` (1100+ lines): mental model, decision charts, feature
  differentiation tables, cookbook, concurrency model, API reference.
- `BankingDemo.kt`: single-file end-to-end exercise of every public API.
- README per module.
- Dokka HTML generation per module.

### Deferred to 0.2.0 *(all shipped — see entry above)*
- ~~Cross-vault atomic actions~~ → shipped as `atomic(vararg vaults) { … }`.
- ~~Snapshot / restore~~ → shipped as `Store.snapshot()` / `Store.restore()`.
- ~~Derived state~~ → shipped as `Store.computed { }` and `Store.derived(...) { }`.
- ~~Suspending action~~ → shipped as `:holdfast-coroutines.suspendAction { }`.
- ~~File-based bridge~~ → shipped as `FileSystemKvStore` over the existing
  `KvBridge`.
- ~~Sonatype / signing publication~~ → shipped as
  `astrid.publish.sonatype` convention plugin.
- ~~In-memory encryption transformer~~ (added scope) → shipped as
  `EncryptingTransformer` + `Cipher` + `XorCipher` in
  `com.vynatix.holdfast.crypto`.
