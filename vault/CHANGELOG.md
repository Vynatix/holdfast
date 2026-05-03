# Changelog

All notable changes to the Vault library are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 2.0.0 — 2026-05-03

Coordinated 2.0 cut across `:vault`, `:vault-coroutines`, `:vault-compose`,
and `:vault-validation`. See [MIGRATING.md](../MIGRATING.md) for the
per-call-site rewrite cheatsheet.

### Added

- **`Vault.scope: CoroutineScope`** with three-tier resolution: per-call
  parameter → per-vault `override val scope` → process-default
  `Vault.defaultScope`. The settable-once `Vault.defaultScope` lazily backs
  off to a process `SupervisorJob + Dispatchers.Default` if never assigned.
  App-init pattern: `Vault.defaultScope = appScope` once at startup.
- **`Vault.bindToScope(scope)`** — replaces the bound scope reference for
  one vault. Optional. Calling on an already-bound vault rebinds.
- **`Vault.dispose()`** — terminal lifecycle. Idempotent. Clears states,
  detaches bridges, clears observers, terminates events `SharedFlow`. Does
  NOT cancel the bound scope (caller owns its lifecycle). Subsequent calls
  to scope-using or transactional APIs throw `IllegalStateException`.
- **`Eventful<E>` interface** (`val events: SharedFlow<E>` + `fun emit(event)`)
  and **`EventfulVault<Self, E>`** base class. Events stage into the active
  transaction's `pendingEvents` and emit during commit, AFTER state observer
  fanout and bridge publish. Lossless-by-default: `replay = 0`,
  `extraBufferCapacity = 16`, `BufferOverflow.SUSPEND`. Off-action `emit`
  throws `IllegalStateException`.
- **`EventfulSupport<E>`** — delegate helper for vaults that already extend
  another base and cannot extend `EventfulVault`. Same staging machinery
  exposed as a delegate field.
- **`@VaultInternalApi MutableState.applyCommittedRaw(value)`** — splits
  observer fanout out of the bridge-publish path so `:vault-coroutines`
  can interpose `SuspendingBridge.publishAwaited` between observers and
  bridges during the `suspendAction` commit phase.
- **`@VaultInternalApi Transaction.stagePendingEvent(channel, event)`** —
  the per-transaction event buffer used by `EventfulVault.emit`. Discarded
  on rollback; merged into parent on nested commit.

### Removed

- This module surface is unchanged in terms of removals; the only
  removal in the 2.0 cut lives in `:vault-coroutines`. See that module's
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
  extension exposed by `:vault-coroutines`).

---

## [Unreleased]

### Added — `:vault-validation` (new module)

- New companion artifact `com.vynatix:vault-validation` providing
  boundary-validation primitives in commonMain (Android + iOS) that integrate
  with Vault's transformer pipeline.
- Core interfaces under `com.vynatix.vault.validation`:
  `Boxed<PRIMITIVE>` (the typed wrapper carrying `val value: P`),
  `Rule<PRIMITIVE>` (`fun interface`),
  `Condition<P, R>` (`fun interface`, `run(primitive, rule: Array<out R>)`),
  `Spec<P, R, O>` (`rule: Array<out R>` + `condition` + `factory`),
  and `Validator<P, R, O>` with `infix of`, `validate`, `ofOrNull`, plus the
  combinator/builder members `allConditions()`, `anyConditions()`, and
  `createSpec(factory, condition, vararg rule)`. `Factory<P, O>` is a
  `(P) -> O` typealias.
- **`ValidatingTransformer<P, R, O>(validator)`** — a Vault `Transformer<O>`
  that re-runs `validator of value.value` on every write. Defence-in-depth
  against callers who construct a `Boxed` directly (e.g. via `data class
  copy`) and bypass `Validator.of`. A failed validation throws
  `IllegalArgumentException`, which propagates to the enclosing `action { }`
  and rolls every state mutation in the transaction back.
- 10 tests across `ValidatorTest` + `ValidatingTransformerTest` covering happy
  path, `ofOrNull`, `validate`, multi-spec matching, multi-rule per spec with
  both `allConditions()` and `anyConditions()`, transformer rollback on
  constructor bypass, and cross-state atomicity. Verified on Android JVM + iOS
  sim. ABI baseline at `vault-validation/api/vault-validation.klib.api`.
  Published to local Maven at `com.vynatix:vault-validation:0.2.0` (4
  publication targets: `kotlinMultiplatform`, `android`, `iosArm64`,
  `iosSimulatorArm64`).

### Changed — Packaging

- `astrid.publish.sonatype` is now applied to **all four** published modules
  (`:vault`, `:vault-coroutines`, `:vault-compose`, `:vault-validation`),
  replacing the bare `astrid.publish`. `publishToMavenLocal` continues to work
  unchanged (signing block is no-op when credentials are absent); a real release
  uses `publishAllPublicationsToSonatypeRepository` on each module.

### Documentation

- `vault/README.md` — modules table now includes `:vault-validation`;
  capabilities section gains a `CivilizingTransformer` entry; build cheatsheet
  adds `:vault-validation:allTests :vault-validation:apiCheck`.

## [0.2.0] — 2026-05-02

Additive minor release. Every item that 0.1.0 deferred ships in 0.2.0; no
breaking changes vs. 0.1.0. The cross-module integration hooks are gated by
the `@VaultInternalApi` opt-in annotation introduced in this release — companion
modules (`vault-coroutines`, `vault-compose`) `@OptIn` to reach them; application
code should not.

### Added — Core (`com.vynatix.vault`)

- **`Vault.snapshot()` / `Vault.restore(snapshot)`** — capture the raw stored
  value of every registered state into a `VaultSnapshot`; restore writes them
  back inside a single top-level `action`. Implemented via a new internal
  `Transaction.stagePendingRaw(state, rawValue)` that bypasses
  `transformer.set`, so asymmetric transformers (e.g. encryption, JSON
  codecs) round-trip losslessly. Restore of an unknown state name throws
  (caught by the wrapping action → `TransactionResult.Error`).
- **`Vault.computed { }`** — read-time derived state. Cheap, stateless, NOT
  observable; every read of `value` re-runs `compute`.
- **`Vault.derived(vararg sources, compute): Pair<State<T>, Disposable>`** —
  push-recomputed derived state. Subscribes to each source via `effect`; on
  each source commit, runs `compute` inside a fresh top-level action and
  stages the result into a backing `MutableState`. Returns the derived state
  plus a `Disposable` for explicit teardown.
- **`atomic(vararg vaults: Vault<*>, body): TransactionResult<R>`** — top-
  level cross-vault transaction primitive. Each `Vault` gains a stable
  `lockOrderKey: Long` (process-monotonic, set at construction); `atomic`
  sorts vaults by this key and acquires each `transactionLock` in global
  order — deadlock-safe across any combination. Inner `v.action {}` joins
  the atomic frame as a savepoint of `v`'s root via the existing
  parent-chain machinery.
- **`Vault.postCommit(task)`** — internal post-commit deferred-task queue,
  drained at top-level action exit. Used by `derived` to defer a recompute
  past the parent's commit fanout (avoids re-entering `pendingWrites` mid-
  iteration). Foundation for future userland post-commit hooks.

### Added — Standard library (`com.vynatix.vault.crypto`, `.bridge`)

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

### Added — `:vault-coroutines`

- **`suspend fun V.suspendAction(body: suspend V.() -> R): TransactionResult<R>`** —
  async-aware transactional body. Backed by `kotlinx.coroutines.sync.Mutex`
  installed lazily via the new `Vault.AsyncSerializer` hook. Mutually
  exclusive with blocking `Vault.action` on the same vault — blocking
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
- **`Vault.AsyncSerializer`** interface + `Vault.asyncSerializer` slot —
  external-mutex extension point for `:vault-coroutines.suspendAction`.
  When non-null, blocking `action` brackets each call with the serializer's
  `blockingAcquire` / `blockingRelease`.
- **`Vault.suspendingOwner`** — recognized by `mutate`'s ownership check so
  cross-thread coroutine-resume points inside a suspending body are still
  treated as in-transaction.
- **`Transaction.createForExternal(id, ownerThreadId)`** — public-but-opt-in
  factory used by `suspendAction` to manufacture a top-level transaction
  outside the blocking lock.
- **`Vault.runUnderLock(block)`** — public-but-opt-in lock-holder used by
  `atomic(...)`.
- **`Vault.lockOrderKey: Long`** — public-but-opt-in process-monotonic
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

- New `taxId` state on `AccountVault` declared with
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
  `com.vynatix.vault.bridge.InMemoryKvStore`.
- Unused `freeze`/`unfreeze`/`close` operations annotated `@Suppress("unused")`
  for surface-completeness.
- Redundant fully-qualified `com.vynatix.vault.middleware.*` /
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
  standard middleware, `KvBridge`/`Codec`/`KvStore`, `:vault-coroutines`,
  `:vault-compose`, plus a 1.1-idioms cookbook (encrypted credentials,
  one-line atomic transfer, running-total derived, undo via snapshot,
  async transactional fetch). One-page cheatsheet at the end shows the
  1.1 forms.
- Per-module READMEs unchanged (vault-coroutines/README.md already
  documented `suspendAction`).

### Verification

- 305+ tests pass on Android JVM + iOS sim across `:vault`, `:vault-coroutines`,
  `:vault-compose`.
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
- `@VaultActionDsl` `@DslMarker` on `Vault<Self>` — prevents accidental
  outer-receiver access in nested DSLs.
- `Vault.uncaughtObserverHandler: ((Throwable) -> Unit)?` — opt-in surfacing
  of commit-fire observer exceptions (default null preserves silent-swallow).

### Added — Standard library (`com.vynatix.vault.middleware`, `com.vynatix.vault.bridge`)
- `LoggingMiddleware<V>(tag, log)` — drop-in tracing of every transaction.
- `TimingMiddleware<V>(onResult)` — wall-clock duration measurements.
- `ValidationMiddleware<V>(check)` — post-body invariant check with rollback.
- `Codec<T>` interface + `StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`.
- `KvStore` interface + `InMemoryKvStore` impl.
- `KvBridge<T>(kv, key, codec)` — generic save-on-commit + load-on-attach
  bridge backed by any `KvStore`.

### Added — New modules
- **`com.vynatix:vault-coroutines`** — `State<T>.asFlow`, `asStateFlow(scope)`,
  `asEagerStateFlow`, `first(predicate)`, `awaitValue(target)`.
- **`com.vynatix:vault-compose`** — `@Composable State<T>.collectAsState()`,
  `@Composable rememberDisposable { … }`.

### Changed (BREAKING)
- `Vault.action` is now generic in the body's return type. `TransactionResult`
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
- `Vault.middlewares(...)` documentation corrected: the LAST argument is the
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
- ~~Snapshot / restore~~ → shipped as `Vault.snapshot()` / `Vault.restore()`.
- ~~Derived state~~ → shipped as `Vault.computed { }` and `Vault.derived(...) { }`.
- ~~Suspending action~~ → shipped as `:vault-coroutines.suspendAction { }`.
- ~~File-based bridge~~ → shipped as `FileSystemKvStore` over the existing
  `KvBridge`.
- ~~Sonatype / signing publication~~ → shipped as
  `astrid.publish.sonatype` convention plugin.
- ~~In-memory encryption transformer~~ (added scope) → shipped as
  `EncryptingTransformer` + `Cipher` + `XorCipher` in
  `com.vynatix.vault.crypto`.
