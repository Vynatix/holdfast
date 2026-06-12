# Changelog — `:holdfast-coroutines`

All notable changes to `:holdfast-coroutines` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- `asStateFlow`'s default-scope resolution now reads
  `MutableState.owningStore` (renamed from `owningVault` in `:holdfast`), and
  KDoc samples use `Store*` class names. No API or behavior change in this
  module.

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
