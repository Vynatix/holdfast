# Changelog — `:holdfast-coroutines`

All notable changes to `:holdfast-coroutines` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`suspendAtomic` graduated to a first-class cross-store frame**, matching
  the core `atomic` contract (see `:holdfast`'s changelog and GUIDE §15):
  `policy: FramePolicy = FramePolicy.Strict` parameter, nested lock-order
  verification (`FrameLockOrderException`), per-store middleware parity, shared
  `Transaction.frameId`, and `FrameObserver` dispatch (now receiving the
  lock-ordered `participants` list, F33). Its two *behavioral* halves —
  enrollment enforcement and inner-error escalation — are breaking and are
  documented under **Changed** below. The enforcement marker follows the
  suspending body across dispatcher hops via `ThreadContextElement` on
  JVM/Android and a delegating `ContinuationInterceptor` on iOS/wasmJs (on those
  two platforms a nested `withContext(otherDispatcher)` section inside the body
  is not policed).
- **`suspendAction(name = null, body)` (F33).** A `name` becomes the resulting
  `Transaction.id` verbatim (else the lambda-derived / random-UUID fallback), so
  a suspending action has a stable, greppable id in middleware logs, the
  testing-harness timeline, and frame diagnostics. Mirrors the blocking
  `Store.action(name, body)` overload. Source-compatible: `store.suspendAction { }`
  is unchanged.
- **Blocking `action { }` on a `suspendAtomic` participant now throws
  `FrameInteropException` immediately** instead of deadlocking on the
  store's suspend mutex; the message names the working alternatives
  (`mutate`/`update` or `suspendAction`). Blocking `atomic` overlapping a
  suspending frame's participants fails the same way, at frame entry.
- **`suspendAction` on a `suspendAtomic` participant now joins the frame as
  a savepoint** (commit merges into the frame root; observers/bridges fire
  once, at frame commit) — previously it deadlocked on the already-held
  mutex despite the KDoc's savepoint claim. Its `Error` results escalate per
  the frame's `FramePolicy`.
- **`SuspendingKvBridge` now implements `Disposable`** (F15). It owns a
  long-lived drainer coroutine (plus in-flight load-on-attach jobs) that a
  `state bridge null` detach does not stop. Call `dispose()` when done: it
  closes the save channel so the drainer persists its last conflated value
  and exits (backstopped by a job cancel), cancels outstanding load jobs, and
  shuts the bridge down — `publish` then returns `false` and `observe` is a
  no-op. `dispose()` is idempotent.
- **`suspendDerived(vararg sources, initial, compute)`** (F16) — a new,
  recommended overload that seeds the derived state with an explicit `initial`
  value and launches the first compute asynchronously on `store.scope`, using
  **no `runBlocking`**. Unlike the seedless overload it runs on every target
  (including wasmJs) and never risks a single-thread-dispatcher deadlock.
  Disposing the returned handle now also **unregisters the synthetic backing
  state** (best-effort `removeState`) so it no longer leaks in the store's
  registry; this applies to both overloads.

### Fixed

- **The serializer's blocking side is now thread-reentrant (F1).** Once a
  `suspendAction` has installed a store's `MutexSerializer`, nested blocking
  entries on one thread — `action { action { } }`, an `action` inside an
  `atomic` body, or an `atomic` nested inside an `action` — re-acquire it
  instead of re-`tryLock`ing the mutex with the shared spin owner, which made
  kotlinx throw a raw `IllegalStateException`. Reentrancy is tracked by
  holder thread id (safe on single-threaded wasmJs, where every caller
  reports id 0). This is also the prerequisite for core's new
  `atomic`-acquires-the-serializer bracket (see `:holdfast`'s changelog).
- **`SuspendingKvBridge.publishAwaited` no longer swallows persistence
  failures** (F13). It emits the throwable on `errors` (existing collectors
  keep working) and then rethrows, so a failed `store.put` under
  `suspendAction`/`suspendAtomic` surfaces as `TransactionResult.Error`
  instead of a false success. The contract is ordering plus a surfaced
  error — not rollback: the in-memory commit and observer fanout have
  already applied when the bridge-publish phase fails. Fire-and-forget
  paths (sync `action { }` via the conflated drainer) still report only
  via the `errors` flow, since a rethrow inside `scope.launch` would crash
  the scope instead of reporting.

- **Nested `suspendAtomic` no longer leaks writes into the outer frame on
  failure.** Stores shared with an enclosing frame get a savepoint of the
  outer root; a failed nested frame discards only its own writes.
  Previously nested writes staged directly into the outer root and
  committed with it even when the nested frame returned `Error`.
- **`derived` recomputes queued during a `suspendAtomic` are drained after
  the store's mutex releases.** Previously the drain ran while the frame
  still held the mutex, so a recompute (a blocking `action`) could spin
  forever.
- **A `suspendAtomic` commit-failure error now names the offending store
  (F7).** A per-store `suspendingCommit` throw (e.g. a failing
  `SuspendingBridge.publishAwaited`) is wrapped as `TransactionException("Commit
  failed for <Store>#<lockOrderKey> in frame <frameId>", …)`;
  `TransactionResult.transaction` remains the last participant root in lock
  order (now documented) — correlate per-store outcomes via
  `Transaction.frameId`.

### Changed

- **Breaking (behavioral): `suspendAtomic(...)` now enforces frame enrollment
  and escalates inner errors (F8).** The suspending peer of core's F8 change
  (shared contract): writing to a store not enrolled in the frame throws
  `UnenrolledStoreException`, and an inner `suspendAction { }` (or `action { }`
  savepoint) returning `TransactionResult.Error` aborts the whole frame.
  `FrameContractException`s always rethrow out of `suspendAtomic` rather than
  folding into an ignorable `Error`. Opt out per call site with
  `policy = FramePolicy.AllowUnenrolled` and/or `FramePolicy.TolerateInnerErrors`.
  Migration: `MIGRATING.md` → "atomic / suspendAtomic frame enforcement".

- **`suspendAction`, `suspendAtomic`, and `suspendDerived` now enforce the
  disposed-store contract (P1-disposed-gaps).** `suspendAction` checks at
  entry and again after acquiring the serializer mutex (a dispose can land
  while parked); `suspendAtomic` checks every participant at entry and
  re-checks each after its mutex is acquired; `suspendDerived` checks at
  entry. Calling any of them on a disposed store throws
  `IllegalStateException`, matching the blocking `action` contract.

- **BREAKING (behavior): `suspendAction` now runs its body inside a
  single-store suspending scope (F4, F5, P1-livelock).** The body carries a
  relaxed frame marker (`AllowUnenrolled + TolerateInnerErrors` — it never
  polices writes to other stores) plus a held-stores context element,
  changing three previously broken shapes:
  - a **nested `suspendAction` on the SAME store** now joins as a savepoint
    (inner commit merges into the transaction, inner rollback discards only
    inner writes, one observer fanout at the outer commit) instead of
    self-deadlocking on the store's serializer mutex;
  - **read-your-own-writes follows the body across dispatcher hops** — after
    `withContext(Dispatchers.X)`, `state.value` sees this transaction's staged
    writes on whichever thread resumes it (JVM/Android; on iOS/wasmJs a nested
    `withContext(otherDispatcher)` section loses this, same gap as enrollment).
    The relaxation is gated on the thread-local frame marker, so concurrent
    plain readers still see only committed values;
  - a **blocking `action { }`, or a `suspendAtomic`/`atomic` enrolling this
    store, called from INSIDE the body now throws `FrameInteropException`
    immediately** (the message names the hoist: run `suspendAtomic` first and
    `suspendAction` inside it) instead of livelocking on the held serializer.
    Disjoint-store frames inside the body stay legal, subject to the global
    lock-order rule.
- **BREAKING (behavior): a nested `suspendAtomic` may no longer introduce an
  unenrolled store under `FramePolicy.Strict` (F2).** Same rule as core
  `atomic` (the check is shared `verifyFrameNesting`): introducing a store
  no enclosing frame enrolls throws `UnenrolledStoreException` at entry
  unless the enclosing policy is `AllowUnenrolled` — the introduced store's
  fresh root would commit at the nested frame's exit and would NOT roll
  back with the enclosing frame. Migration: enroll the store in the
  outermost frame, or pass `policy = FramePolicy.AllowUnenrolled` on the
  enclosing frame (see `MIGRATING.md`).
- **BREAKING: `SuspendingKvBridge` now implements `SuspendingBridge` directly
  and the nested `SuspendingKvBridge.Awaiting` class is removed** (F14).
  `suspendingBridge(...)` returns `SuspendingKvBridge<T>` (was
  `SuspendingKvBridge.Awaiting<T>`); `bridge(...)` is a `WARNING`-level
  deprecated alias returning the same type. Behavior change for former
  `bridge(...)` products: `suspendAction`'s commit phase now awaits their
  `publishAwaited` (strictly more durable; adds per-commit latency and drops
  cross-commit conflation under `suspendAction`). Sync `action { }` saves are
  unchanged: fire-and-forget through the conflated channel — which is now
  also the sync path for bridges that used to be `Awaiting` (previously a
  direct per-publish launch with no conflation). See
  [MIGRATING.md](../MIGRATING.md).

- `suspendAtomic`'s vararg parameter is named `stores` (was pre-rename
  `vaults`) — source-compatible for positional calls.

- `asStateFlow`'s default-scope resolution now reads
  `MutableState.owningStore` (renamed from `owningVault` in `:holdfast`), and
  KDoc samples use `Store*` class names. No API or behavior change in this
  module.

### Deprecated

- **`SuspendingKvStore.bridge(key, codec, scope)`** — `WARNING`-level alias of
  `suspendingBridge(...)`. The former fire-and-forget-only product no longer
  exists; both factories return the same awaited-under-`suspendAction`
  `SuspendingKvBridge`. Kept for one minor release.

### Removed (ABI)

- **ABI hygiene (F29).** The `SuspendingFileSystemKvStore` companion constants
  `HEX_DIGITS`/`HEX_RADIX`/`TMP_PREFIX` (JVM/Android and iOS) are now `private`,
  removing them from the module ABI. They were implementation details.

### Removed

- **BREAKING: the K2 `context(scope: CoroutineScope)` overloads of
  `State.asStateFlow`, `SuspendingKvStore.bridge`, and
  `SuspendingKvStore.suspendingBridge`.** Inside any coroutine body the
  implicit `CoroutineScope` receiver satisfied the context parameter, so a
  zero-scope-arg call like
  `runBlocking { state.asStateFlow(started = SharingStarted.Eagerly) }`
  silently captured the ambient scope instead of the store's — attaching an
  eager sharing job to `runBlocking` hung it forever. Only the default-param
  forms remain. Migration: pass `scope` explicitly
  (`state.asStateFlow(myScope)`, `store.bridge(key, codec, myScope)`) or
  omit it to use the owning store's scope (`asStateFlow`) /
  `Store.defaultScope` (bridge factories) — which is what the context
  overloads were resolving away from.

---

## Internal-only history (preserved as design archive)

The entries below describe internal versions that were **never published to
Maven Central**. They predate the rename to Holdfast and refer to the original
`vault` / `:vault-coroutines` module names and paths (e.g. `vault/CHANGELOG.md`)
in effect at the time. Treat them as design history — not a release log any
consumer migrated against — and do not rewrite them.

## 2.0.0 — 2026-05-03

Coordinated 2.0 cut across `:holdfast`, `:holdfast-coroutines`, `:holdfast-compose`,
and `:holdfast-hallmark`. `:holdfast-coroutines` 2.0 is a coroutine-first peer of
`:holdfast` core with full feature parity — not the thin adapter framing of 1.x.
See [MIGRATING.md](../MIGRATING.md) for the per-call-site rewrite cheatsheet.

### Added

- **`State<T>.effect`** — top-level `State<T>` extension, replacing the
  `Store<Self>` member-extension shipped in 1.x. Both prior call sites
  collapse to `state effect { ... }` without the implicit-cast leak.
- **`State<T>.asStateFlow(scope, started)`** — single hot StateFlow API.
  `started` defaults to `SharingStarted.WhileSubscribed()`; pass
  `SharingStarted.Eagerly` for the eager-publish path that replaces the
  removed `asEagerStateFlow()`. Plus a K2 context-parameter overload that
  resolves the sharing scope from `context(scope: CoroutineScope) { ... }`.
- **`SuspendingMiddlewareHooks<V>`** — opt-in interface for async middleware
  hooks (`onTransactionStartedAsync`, `onTransactionCompletedAsync`,
  `onTransactionErrorAsync`). A middleware can implement either or both
  the sync `Middleware<V>` and this; `runCatching` wraps each hook so one
  middleware's failure does not abort others.
- **`SuspendingKvStore`** interface — `suspend get / put / remove / snapshot`,
  for async backends (DataStore, SQLDelight, Realm).
- **`SuspendingBridge<T> : Bridge<T>`** — await-completion bridge.
  `suspend fun publishAwaited(value)` is the contract; the default
  `Bridge.publish(value)` launches a fire-and-forget coroutine on the
  bridge's scope.
- **`SuspendingKvStore.bridge(key, codec, scope = Store.defaultScope)`**
  (fire-and-forget) and **`SuspendingKvStore.suspendingBridge(...)`**
  (await-completion) factory functions, plus K2 context-parameter overloads
  for both. The two factories share the same store, key, and codec — caller
  picks the action type to pick the persistence guarantee.
- **`InMemorySuspendingKvStore`** — test fixture with `delay(0)` between
  operations.
- **`SuspendingFileSystemKvStore`** — `expect class` `SuspendingKvStore`
  impl on Android + JVM + iOS using `withContext(Dispatchers.IO)` for
  file ops.
- **`suspendAtomic(vararg vaults, body)`** — multi-holdfast async transaction.
  Vaults sorted by `Store.lockOrderKey`; each vault's `AsyncSerializer.Mutex`
  acquired in lock order via `withLock`. Mutually exclusive with blocking
  `atomic` and per-store `action` / `suspendAction` on the same vault.
  Commit phase wrapped in `withContext(NonCancellable)`; partial-commit
  cannot happen.
- **`suspendDerived(vararg sources, compute)`** — push-recomputed derived
  state with a suspending compute lambda. Returns
  `Pair<State<T>, Disposable>`. Recompute path uses `vault.scope.launch`
  with internal `suspendAction` to stage the result.

### Removed

- **`EagerStateFlow<T>`** and **`State<T>.asEagerStateFlow()`**.
  Migration: `state.asEagerStateFlow().also { it.dispose() }` →
  `state.asStateFlow(started = SharingStarted.Eagerly)`. Disposal is now
  handled by scope cancellation.
- **`Store<Self>.effect` member-extension**. Replaced by the top-level
  `State<T>.effect` extension; the `vault { state effect { ... } }`
  call-site form continues to compile.

### Changed (behavior, signature stable)

- **`State<T>.asFlow()` is now lossless-conflated.** Previously
  `callbackFlow { trySend(value) }` with default `BUFFERED` capacity, which
  silently dropped values past a 64-element backlog under contention. Now
  backed by `MutableSharedFlow(replay = 1, extraBufferCapacity = 0,
  onBufferOverflow = DROP_OLDEST)`. The producer never blocks; the latest
  value is always available via the replay slot. Strict improvement; no
  flag. See [MIGRATING.md](../MIGRATING.md) for the regression-watch
  concern (callers who relied on every commit being delivered to a slow
  collector were always broken; use `vault.events` for discrete event
  streams instead).
- **`vault.suspendAction { }` now invokes the `Middleware<V>` chain.**
  Previously documented as "no middleware support" (logging, timing,
  validation middleware silently no-op'd on the async path). Now: sync
  hooks always fire; middleware that implements `SuspendingMiddlewareHooks`
  additionally fires async hooks. Strict improvement; no flag. See
  [MIGRATING.md](../MIGRATING.md) for the regression-watch concern
  (middleware authors who relied on "suspendAction won't trigger me" must
  verify their hooks are idempotent and safe under the suspending path).
- **`CancellationException` from a `suspendAction` body now invokes
  `onTransactionError`.** 1.x silently no-op'd middleware on the suspending
  path; this surfaces only as a behavior change for consumers who registered
  middleware AND use `Job.cancel()` to terminate `suspendAction`s.
- **Middleware ordering is uniform across `action` and `suspendAction`.**
  Both paths use **last-registered = outermost** semantics: for
  `vault.middlewares(A, B)`, the trace is `B.started → A.started → body →
  A.completed → B.completed`. Earlier 2.0 work-in-progress had the
  suspending path inverted (first-registered outermost); that asymmetry has
  been resolved.
- **`onTransactionCompleted` throw semantics differ between sync and
  suspend.** Sync `action`'s throwing completion hook triggers rollback.
  Suspend `suspendAction`'s completion hook is wrapped in `runCatching`,
  so a throw is swallowed and the transaction still commits. Per design,
  documented as an asymmetry.
- **Events drain differently on sync vs suspend.** Sync `Transaction.commit()`
  uses `tryEmit` for events drain — a full `BufferOverflow.SUSPEND`-policy
  buffer silently drops on the sync path. The suspend path honors
  back-pressure (`emit` suspends on full buffer). Same `vault.events`
  channel, same buffer config, two different drop semantics depending on
  which action type produced the event. Mirrors the `Bridge.publish` (sync,
  no back-pressure) vs `SuspendingBridge.publishAwaited` (suspending,
  honors) duality.
- **Sync `vault.action { }` inside a `suspendAtomic` body deadlocks.** The
  design spec called for both `action` and `suspendAction` inside the body
  to become savepoints. Reality: kotlinx `Mutex` is not owner-reentrant,
  so sync `action` (which acquires the per-store `transactionLock`,
  separate from `AsyncSerializer.Mutex`) deadlocks when nested inside
  `suspendAtomic` for the same vault. Practical guidance documented in
  KDoc and [MIGRATING.md](../MIGRATING.md): inside a `suspendAtomic` body
  use `state mutate value` or `state update { ... }` directly — those
  participate in the active transaction.

### Targets

- `:holdfast-coroutines` 2.0 ships for Android + iOS + JVM via the new
  `jvmAndAndroidMain` intermediate source set. JS / Wasm / non-iOS native
  targets are deferred to a demand-driven minor release.

---

## 1.x

See `vault/CHANGELOG.md` for the unified 0.1.0 / 0.2.0 history that
preceded the per-module changelog split.
