# Changelog

All notable changes to Holdfast are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with
the caveat that **0.x releases carry no API stability guarantee**. Breaking
changes may land in any 0.x bump; consumers should pin to an exact version.

## [Unreleased]

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
