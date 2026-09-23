# Changelog

All notable changes to Holdfast are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with
the caveat that **0.x releases carry no API stability guarantee**. Breaking
changes may land in any 0.x bump; consumers should pin to an exact version.

## [Unreleased]

### Fixed

- **`suspendAction` on a store with a `derived()` state no longer deadlocks.**
  The post-commit drain ran inside `serializer.mutex.withLock`, so the derived
  recompute's blocking `action` spun on a mutex its own call stack held —
  indefinitely, at 100% CPU, with the thread in `RUNNABLE` so no deadlock
  detector or thread dump reported it. The drain now runs after the mutex is
  released, matching what `suspendAtomic` already did. The blocking path had
  the same placement, where the recompute threw instead of spinning and
  `drainPostCommitTasks` swallowed it in `runCatching`, silently freezing the
  derived state.

- **Nested `action` works again on a store that has used `suspendAction`.**
  `AsyncSerializer` is installed permanently on first coroutine use and is not
  reentrant, but `Store.action` acquired it unconditionally — so the savepoint
  mechanism failed with a raw kotlinx `"This mutex is already locked by the
  specified owner"`, folded into an ignorable `TransactionResult.Error` that
  discarded the outer action's writes too. A nested action is already inside the
  serialized region and no longer re-acquires.

- **Commit fanout can no longer tear a transaction.** A throwing
  `Bridge.publish` or `Transformer.get` aborted the apply loop part-way, leaving
  earlier states written and later ones not. Commit is now phased — apply every
  pending write (assignment only, runs no user code), then all observers, then
  all bridge publishes, then events — with the fanout phases isolated per state
  and reported through `Store.uncaughtObserverHandler`.

- **Blocking `atomic()` is serialized against in-flight suspending work.** It
  took only each participant's `transactionLock`, never the `AsyncSerializer`,
  so a frame could install a fresh root over a `suspendAction`'s transaction and
  — because `suspendingOwner` relaxes `mutate`'s owner check — that suspending
  body then staged its writes into the frame's transaction.

- **`Transaction.commit`/`rollback` catch `Throwable`, not `Exception`.** An
  `Error` escaping previously left the transaction `Active` while the caller
  reported a rollback that never happened. `CancellationException` now
  propagates unwrapped instead of being wrapped in `TransactionException`.

- **`TransactionException` names the failure.** It now identifies the
  transaction, store, commit phase and how many states were already applied,
  replacing a bare `"Commit failed"`.

- **Observer callbacks run outside `observersLock`.** Holding it across user
  code let one slow observer block `observe` and every disposal on that state
  from all other threads. (This does not make cross-store observer writes safe:
  `action` still holds `transactionLock` across the fanout, so two stores whose
  observers write to each other still deadlock.)

- **`StoreLock` parks instead of spinning.** It looped on `tryAcquire` +
  `threadYield`, so every waiter on a contended store burned a core and sat in
  `RUNNABLE` where no profiler reports it as blocked. It now blocks on
  `kotlinx.atomicfu.locks.SynchronousMutex`, keeping its own reentrancy depth.

- **A `derived()` recompute never blocks or spins on its host store.** The
  recompute ran a blocking `action` on the store hosting the derived. For a
  derived whose source lives on another store, that happened inside the
  source's commit fanout with the source's `transactionLock` held, so a host
  held with no transaction visible yet (its serializer taken, its action not
  yet in its body) stalled the source's commit — a deadlock if that holder
  then needed the source store. And when kotlinx `Mutex.unlock` handed the
  host's serializer to a queued `suspendAction` that had not resumed yet, the
  recompute spun on it — forever on a single-threaded event loop, where that
  coroutine needed the spinning thread. The recompute now makes one
  non-blocking top-level attempt; a busy host gets it handed to its current
  holder, which runs it after releasing.

- **`derived()` recompute failures reach `Store.uncaughtObserverHandler`.** A
  throwing `compute` (or a middleware rejecting the recompute) used to vanish
  inside the post-commit drain's `runCatching`, silently freezing the derived.
  It now rolls back and goes to `Store.uncaughtObserverHandler`; the next
  source commit recomputes normally. With no handler set (the default) the
  failure is logged (see "post-commit failures are logged by default" under
  Changed). Disposing a derived also drops a recompute that was already
  queued.

- **A `derived()` whose host store was disposed no longer throws into its
  source's commit.** Its subscriptions live on the source store and outlive
  the host, so a later source commit ran the recompute's blocking `action` on
  the disposed host, and its `"store disposed"` went to the source's
  `uncaughtObserverHandler`. The recompute now sees the host disposed and does
  nothing.

- **A `postCommit` from another thread can no longer be stranded.** A caller
  could read an active transaction that was just ending, then enqueue after its
  owner had cleared the slot and drained an empty queue, leaving the task queued
  until some unrelated later transaction drained it. `postCommit` re-reads the
  slot after enqueueing and drains itself if it emptied.

- **`atomic` runs its post-commit work only once the whole frame has
  unwound.** Each participant's queue drained at that participant's own unwind
  step, while the frame still held the EARLIER participants' locks with their
  finished roots installed. So an observer on a `derived` recompute that wrote
  to an earlier participant hit that finished transaction: `mutate` threw, and
  an `action` opened a savepoint of it whose writes never committed. The frame
  now drains every store whose root it opened after releasing all of them.

- **`:holdfast-testing`: closing an open `transaction(on = …)` runs the
  post-commit work queued behind it.** Rollback and a throwing body never
  drained the store's post-commit queue, and commit drained it while still
  holding the store's lock, so a `derived` recompute that fired while the
  transaction was open could be stranded. All three exits now drain after
  releasing, like a production `action`.

### Changed

- **BREAKING (commit fanout order).** Observers for every state in a
  transaction now run before any bridge publishes, where fanout previously
  interleaved per state. This is what the documented
  "observers → bridge publish → event drain" order always described, and what
  the suspending commit path already did.

- **BREAKING (bridge failures).** A throwing `Bridge.publish` now yields
  `TransactionResult.Success` with the failure reported through
  `Store.uncaughtObserverHandler`, instead of a
  `TransactionException("Commit failed")`. A bridge is external sync, not a
  transaction participant — `atomic`'s KDoc already stated that persistence
  publishes carry no crash-consistency — so a failed publish cannot undo values
  that are already committed.

- **BREAKING (`@StoreInternalApi`).** `MutableState.applyCommittedRaw` is
  replaced by `applyCommittedValue` / `fanOutToObservers` / `publishToBridge`;
  `Transaction.commitDispatching` now takes a single fanout callback receiving
  all committed writes rather than a per-write callback; `Store` gains
  `internalOwnsActiveTransaction`. Companion modules only.

- **BREAKING (behavior): a `derived()` whose sources live on its own store
  recomputes once per source commit, not once per changed source.** Each
  source's observer queued its own recompute, so a commit touching N sources
  ran `compute` N times and committed (and fanned out) the derived N times.
  The recompute is now one task per derived, and `Store.postCommit`
  deduplicates queued tasks by identity, so middleware and observers on such a
  derived see one recompute transaction per source commit. The dedup needs a
  transaction active on the derived's store: for sources on another store,
  while the derived's store is idle, the recompute still runs inline from each
  changed source's observer — once per changed source — until cross-store
  settling lands (issue #20).

- **BREAKING (behavior): a `derived()` recompute can land after the
  committing `action` returns, on another thread.** The post-commit drain used
  to recompute with a blocking `action`, so the derived reflected the caller's
  commit by the time `action` returned, and its middleware, observers and
  bridge publish ran on the committing thread. The recompute now makes one
  non-blocking attempt; if the derived's store is busy, it is handed to that
  store's holder and runs on the holder's thread after the holder releases.
  This applies even when the sources are on the same store — for example when
  another thread's action or a queued `suspendAction` takes the store between
  the commit and the drain. So `derived.value` can briefly lag its sources,
  then converges. For a read-your-writes value, read the sources or use
  `computed`.

- **BREAKING (source): a `Store` subclass property named `clock` no longer
  compiles.** It collides with the new `Store.clock` ("'clock' hides member of
  supertype 'Store' and needs an 'override' modifier"), whatever its type and
  visibility, including `val clock by state { … }`. Rename it, or `override` it
  as a getter with `@OptIn(ExperimentalStoreApi::class)`. Inside a `Store`
  subclass's body (initializers, `state { … }` lambdas, member functions) and
  inside a lambda with a store receiver (`store.action { … }`, `store { … }`),
  an unqualified `clock` that resolved to a companion-object, enclosing-class or
  top-level declaration now resolves to `Store.clock`: a compile error without
  the opt-in, a silent switch with it. See
  [MIGRATING.md](../MIGRATING.md#source-break-storeclock-030).

- **BREAKING (behavior): writing into a transaction that has already applied
  is refused instead of silently lost (issue #20).** A commit applies its
  writes, then notifies observers, bridges and event collectors while its
  transaction is still the store's active one. An observer that wrote back
  into the store it observes during that fanout staged into the finished
  transaction, and the write was silently lost: `mutate`/`update` and `emit`
  (on `EventfulStore` and `EventfulSupport`) landed in buffers nobody applies
  again, and a nested `action` or `atomic` opened a savepoint that merged into
  them. Now `mutate`, `update` and `emit` throw an `IllegalStateException`
  that names the store and state and lists the fixes (write in the action
  itself, derive the value, or run a follow-up action as an `action` on
  another thread, or launched on `Store.scope` with a dispatcher that does not
  run it inline — on `Dispatchers.Unconfined` or an immediate main dispatcher
  the launched action runs inside the fanout and is refused too); thrown out
  of an observer it reaches `uncaughtObserverHandler`. A nested `action` or
  `atomic` returns `TransactionResult.Error` carrying it, without running its
  body or middleware — the observer must check that result (e.g.
  `getOrThrow()`); ignored, the write is still dropped without a log line.
  The same holds for a participant of an `atomic` frame that already
  committed while a later one fans out — whether its entry is a root of its
  own or a savepoint of an enclosing action on the same thread, which used to
  accept a nested `action`/`atomic`/`emit` and lose it — for a participant
  already rolled back while the frame's error hooks run, and inside
  `suspendAction` and `suspendAtomic` commits — their observers, bridge
  publishes, event collectors resumed inline and frame observers, across
  thread hops (see `:holdfast-coroutines`' changelog for the two remaining
  gaps). Another thread's `action`/`atomic` is not affected: it waits for the
  store and commits on its own, and so does its bare `mutate`/`update` during
  a blocking commit. While a `suspendAction`/`suspendAtomic` holds the store,
  though, a bare `mutate`/`update` from any thread stages into its
  transaction (the suspending body may resume on any thread): before the
  apply pass it joins the transaction, after it throws this error — with a
  message saying a suspending transaction holds the store — until the
  suspending call returns; write from other threads through `action { }`.
  That check and the stage are atomic with the apply pass, so such a write is
  applied with the commit or refused, never lost in between. A write into a
  transaction committed or rolled back by hand now reports this error with
  its status ("has already applied its writes (status: Committed)", or "has
  already been rolled back (status: RolledBack)") where `mutate` used to
  report "Cannot mutate state on a Committed/RolledBack transaction", and a
  nested `action` there, which used to open a savepoint of the finished
  transaction and return `Success`, returns it as an `Error`. Writes to
  another store whose transaction has not applied still commit, as before. See
  [MIGRATING.md](../MIGRATING.md#behavior-change-writes-from-an-observer-into-its-own-committing-store-040).

- **BREAKING (behavior): post-commit failures are logged by default.** With no
  `Store.uncaughtObserverHandler` set, a throwing observer callback, fanout
  `Transformer.get`, `Bridge.publish` or `derived` recompute used to be dropped
  without a trace. It is now logged: a line naming the store and pointing at
  `uncaughtObserverHandler`, then the stack trace — on standard error on JVM
  and Android, on standard output on iOS and wasmJs. The commit still stands and
  the remaining observers still run. Set a handler to route these failures into
  your own logging, or `{ }` to silence them. See
  [MIGRATING.md](../MIGRATING.md#behavior-change-post-commit-failures-are-logged-by-default-040).

### Added

- **`Store.internalReportUncaughtFailure(error)`** (`@StoreInternalApi`) — the
  one reporting path for post-commit failures: `uncaughtObserverHandler` when
  set, the default log otherwise. `:holdfast-coroutines` reports its suspending
  bridge-publish failures through it. Companion modules only.

- **`FanoutMarkers`** (`@StoreInternalApi`) — a thread-local marker naming the
  transactions (roots, or a nested frame's savepoints) whose suspending commit
  the current thread is running.
  `:holdfast-coroutines` installs it around the commit phase of
  `suspendAction`/`suspendAtomic` and keeps it coherent across dispatch, so a
  blocking `action`/`atomic` from inside that commit is recognised as nested
  and refused instead of waiting for the serializer the commit holds.
  Companion modules only.

- **`Store.clock` and `Store.bindClock(clock)`** (`@ExperimentalStoreApi`,
  issue #20 R10) — time as an input. Store code reads `clock.now()` instead of
  `Clock.System.now()`, so a timestamp stamped in an action, or an initial value
  computed from the time, is deterministic under a fixed test clock. `clock`
  resolves like `scope`: a subclass getter override, then the clock bound with
  `bindClock`, then `Clock.System`. `bindClock(null)` unbinds; `bindClock` throws
  on a disposed store, while reading `clock` never throws. State initializers
  read it lazily, at the state's first read, so bind before that read. The
  library's own timestamps (`Transaction.endTime`, `TimingMiddleware`,
  `ProfilingMiddleware`) keep using the system clocks. `Store.internalBoundClock`
  (`@StoreInternalApi`) exposes the raw binding for the test harness.

- **`:holdfast-testing`: `storeTest { }` teardown restores the clock binding of
  every tracked store.** Tracking a store (with `track`, or by an
  auto-registering extension such as `store.read { }`; a bare
  `store.action { }` resolves to the `Store` member and does not track)
  remembers its `bindClock` binding. Teardown puts it back, also when the body
  failed, and again once the test's un-joined child coroutines have finished.
  A clock bound after tracking therefore does not leak into the next test
  through a singleton store. Bind after tracking: a clock bound before the store
  is first tracked counts as the pre-test binding and is kept, and a store the
  test never tracks is not restored. Work in `backgroundScope` or on scopes
  outside the test is not waited for, so join it before the body ends. A store
  disposed during the test is skipped.

- **`Store.AsyncSerializer.tryBlockingAcquire()`** (`@StoreInternalApi`) — a
  non-blocking acquire for the store's non-blocking paths (the `derived`
  recompute hand-off). It has a default that delegates to `blockingAcquire()`,
  so existing serializers keep compiling, but that default blocks; a serializer
  a `derived` state can meet should override it.

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

- **Nested `atomic` no longer commits shared stores prematurely.** A nested
  frame overlapping an enclosing action/frame on the same thread now opens
  savepoints: its commit merges into the enclosing scope, and the enclosing
  rollback discards nested writes. Previously the nested frame committed the
  adopted root at inner exit, silently disabling the outer rollback for that
  store.
- **`derived` recomputes queued during an `atomic` frame now run at frame
  exit.** Previously the frame never drained the post-commit queue, so
  recomputes were deferred until the next unrelated action on that store.

### Changed

- `atomic`'s vararg parameter is named `stores` (was pre-rename `vaults`) —
  source-compatible for positional calls; update any named-argument call
  sites.

- Completed the Vault → Store rename in the public API:
  `EventfulSupport.bindVault(...)` is now `bindStore(...)` and
  `MutableState.owningVault` is now `owningStore`. The testing harness entry
  point `vaultTest { }` (in `:holdfast-testing`) is now `storeTest { }`.

### Deprecated

- `EventfulSupport.bindVault`, `MutableState.owningVault`, and `vaultTest`
  remain as `WARNING`-level deprecated aliases delegating to the new names;
  they will be removed after one minor release.

### Added

- `TransactionResult` ergonomics: `getOrThrow()` (returns the `Success` value
  or rethrows the original `Error.exception`), `valueOrNull`, and chainable
  `onSuccess { }` / `onError { }` extensions — so fire-and-forget `action`
  callers can surface rollbacks instead of silently dropping them.

- Documented platform support tiers in the root and module READMEs:
  Android/JVM/iOS are supported (tests run in CI); wasmJs is **experimental** —
  the artifact is still published, but tests are disabled on wasmJs,
  `FileSystemKvStore` throws `UnsupportedOperationException`, `suspendDerived`
  is unusable (`runBlocking` initial seed), and the platform is single-threaded
  (`currentThreadId() == 0`). Doc-only; no code changes.

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
