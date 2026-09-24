# Changelog — `:holdfast-coroutines`

All notable changes to `:holdfast-coroutines` are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Hydration lifecycle** (experimental, issue #20, R8; plan PR 13, decision
  D19): `val hydration = hydrator { base { … }; refresh { store -> … } adopt
  { fetched -> … } }` gives a store its one `Hydrator<V>`, attached through
  core's `StoreAttachment` slot (a second `hydrator { }` on the store throws;
  `hydratorOrNull()` finds it). New surface — experimental, and per the
  roadmap's principle 6 it soaks for two minors before it can stabilize.
  - `hydrate(scope = store.scope)` on `Hydration.Detached` runs `base { }` in
    ONE transaction (id `HydrationSeed`) that also moves the phase to
    `Seeded` and marks a refresh in flight (R8's guard guarantee); only once
    that transaction has committed is `refresh { }` launched on `scope`, with
    `CoroutineStart.ATOMIC`, so a seed a middleware rolls back launches
    nothing and a hydration marked in flight always gets its refresh. What
    it fetched is adopted in one transaction (id `HydrationAdopt`) whose
    savepoint (id `Adopt`) runs `adopt { }`, then the phase moves to
    `Hydrated`; a refresh that throws (a cancelled `scope` included) moves it
    to `Failed(cause)` (id `HydrationFailure`). Middleware sees every one.
  - Idempotent: `hydrate()` while `Seeded` or `Hydrated` does nothing. Single
    flight: every decision reads the phase and commits the next while the
    hydration gate holds the store's serializer — only for that decision's
    transaction — so concurrent calls seed and fetch once, and fifty calls on
    a `Failed` hydration retry once: the retry (id `HydrationRetry`) moves
    back to `Seeded` in the deciding transaction and never re-runs
    `base { }`. The gate takes the store politely — `tryLock`, then a yield
    and delays doubling from 1 ms to 32 ms, in coroutine time — and never
    queues on the store's mutex, so it never spins and never reads the
    store's clock; it drains the store's post-commit queue once it releases
    the store, inside one settle scope.
  - `hydrate()` throws `IllegalStateException` inside an action, an `atomic`
    frame, a `suspendAction` or a `suspendAtomic` of any store — body or
    commit, and a child coroutine of the body — where it would wait for
    itself or commit its seed outside the enclosing transaction.
  - `adopt { }` may write only `StateTag.Remote` states (and evict entries of
    `Remote` keyed families): anything else — directly, in a nested action,
    through `restore` or `reset()` — fails the adoption, naming the states,
    rolls it back whole and moves the phase to `Failed` in the same
    transaction; `removeState`/`clearStates` throw inside it.
  - Back to `Detached` only through `invalidate()` (id
    `HydrationInvalidate`), `stageInvalidate()` inside an action, and the
    store's `reset()`, which detaches inside its transaction (plan deviation
    8). A refresh in flight then is cancelled and its result discarded, even
    one that ignores cancellation; a sterile `restore` does not detach.
  - `Hydrator.state` is a read-only, observable `State<Hydration>` of the
    store (`Detached`, `Seeded`, `Hydrated`, `Failed(cause: Throwable)`),
    usable as a `derivedState` source — a health flag over several stores'
    hydrations needs no cross-store frame — that no snapshot, restore or reset
    captures or writes, and that every store write entrypoint refuses.
    `current`, `awaitSettled()` (waits until no refresh is in flight), and
    `hydrateEach(vararg)` until issue #21's `hydrateAll()`. After `dispose()`
    the entrypoints throw, a refresh in flight is cancelled and never adopted,
    and `state` keeps its last value.
  - Deviations from #20's sketch (plan deviation 8): `hydrate { }:
    State<Hydration>` became `hydrator { }: Hydrator<V>`, `Failed(cause: Any)`
    became `Failed(cause: Throwable)`, `reset()` detaches too, and
    `hydrateAll()` waits for #21. The persisted overlay is the next PR.
  `HydrationLifecycleTest`, `HydrationInvalidateTest`,
  `HydrationAdoptPolicyTest`, `HydrationHealthFlagTest`, `HydrationClockTest`,
  `HydrationDisposeTest` and `DisposedEntrypointTest` (common, so iOS runs
  them too), and `HydrationSingleFlightTest` and the watchdogged
  `HydrationSerializerContractTest` (JVM and Android host) pin it. iOS
  unverified until a macOS run.

- **Keyed-state evictions under `suspendAction`/`suspendAtomic`** (issue #20,
  R7; see `:holdfast`'s changelog): `KeyedState.evict`/`evictAll` stage like
  `mutate`, so inside a suspending body they stage into its transaction and
  commit or roll back with it, and from its commit's fanout (observers,
  bridge publishes, event collectors, across thread hops) they are deferred
  until the suspending call has released the store — then run by a
  transaction that never waits for the store, so the drain in the call's
  `finally` never spins waiting for the coroutine it just handed the
  store's mutex to (`KeyedDeferredEvictionTest`). Inside a suspending body
  only a write to an entry cancels its staged eviction, never a `get`:
  another coroutine on the body's thread would look like the body. Another thread's
  eviction while a suspending call holds the store joins its transaction
  before it applies and is refused after — the foreign-thread staging gap
  that bare `mutate` has, pinned by `KeyedEvictSuspendGapTest` until the
  planned "body is running" marker closes it.

- **`suspendAction` and `suspendAtomic` are settle entries** (issue #20, R9;
  see `:holdfast`'s changelog): the `derivedState`/`merged` states whose
  sources one of them — and everything nested in it: `suspendAction`s,
  blocking `action`s, frames — changes recompute once, when the outermost
  entry has released every store, on whatever thread it ends on. Its settle
  scope travels with the coroutine (`SettleAmbientContext.kt`: a
  `ThreadContextElement` on JVM/Android, a bracketing interceptor on
  iOS/wasmJs, as for the frame marker), so a commit that fans out on another
  thread than the one that opened the entry still queues into it, and a
  parked entry's scope never leaks into another coroutine sharing its
  thread. `SuspendSettleCommonTest` (common, so iOS runs it too),
  `SuspendSettleTest`, `SuspendAtomicHopDerivationTest`,
  `SettleScopeInterceptedTest` (the iOS/wasmJs carrier, on the JVM) and
  `SharedThreadIdentityTest` (the wasmJs one-thread model, on the JVM) pin
  it.
  On iOS/wasmJs a nested `withContext(dispatcher)` inside the entry replaces
  the interceptor: a commit in that section does not see the entry's scope —
  a blocking entry opened there settles on its own, and a nested suspending
  one joins the entry but its commit falls back to the per-commit routing —
  so the derived states it changes recompute after that commit, once per
  commit rather than once per entry.

- **Core's derived states work with every adapter** (issue #20, R6):
  `asFlow`, `asStateFlow` (whose default scope is the scope of the store the
  derived state was created on), `first` and `awaitValue` accept a
  `derivedState`/`merged` state, and so does `suspendDerived` as a source.
  `MergedSuspendTest` pins the flows over a merged state, that an adoption
  committed by `suspendAction` recomputes it once, that a `suspendAction`
  or `suspendAtomic` commit on a source's store recomputes a derived state on
  another store once, before the suspending call returns, and that a
  `suspendAction` parked on the recomputing thread (as under `runBlocking`
  or on Android's main thread) neither delays a recompute nor leaks its
  pending writes into it, and that a store disposed from its own commit's
  fanout launches no `suspendDerived` recompute (core's `dispose()` now
  drains the queued launch instead of dropping it, and the launch checks
  the store first). `DerivedStateSourceRoutingTest` (JVM and Android host)
  pins the same once-after-the-commit recompute for a suspending commit
  that fans out on another thread than the one that opened it.

- **`suspendDerived` inherits the `Secret` tag of its sources** (issue #20,
  R3, through `:holdfast`'s `registerDerivedBackingState`): a suspending
  derived with a `StateTag.Secret` state among its sources is `Secret`
  itself, so its value is withheld from encoded snapshots, renders and test
  timelines like its source's. `SecretRedactionSuspendTest` pins that no built-in
  middleware writes a `Secret` value under `suspendAction` or
  `suspendAtomic`.

- **`suspendAtomic` graduated to a first-class cross-store frame**, matching
  the core `atomic` contract (see `:holdfast`'s changelog and GUIDE §15):
  `policy: FramePolicy = FramePolicy.Strict` parameter, enrollment
  enforcement (`UnenrolledStoreException`), inner-error escalation, nested
  lock-order verification (`FrameLockOrderException`), per-store middleware
  parity, shared `Transaction.frameId`, and `FrameObserver` dispatch. The
  enforcement marker follows the suspending body across dispatcher hops via
  `ThreadContextElement` on JVM/Android and a delegating
  `ContinuationInterceptor` on iOS/wasmJs (on those two platforms a nested
  `withContext(otherDispatcher)` section inside the body is not policed).
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

### Fixed

- **A blocking `action`/`atomic` on a later `suspendAtomic` participant, from
  an earlier one's commit, no longer waits forever** (issue #20, R9). The
  frame still held that participant's serializer and had not committed it
  yet, so the call was not recognised as nested and waited for the frame.
  Every participant now applies before any fans out, so it is refused at once
  with an `Error`, like one on an earlier participant — closing the last
  frame gap named below.

- **`suspendAtomic` no longer tears across a thread hop** (issue #20, R9).
  Participants committed one after the other, so while the first one's
  `SuspendingBridge.publishAwaited` suspended, every thread saw it applied
  and the second one not; and after the body resumed on another thread, an
  observer of the first participant read the second one's old value (only
  the frame's own thread saw its pending writes). Every participant now
  applies, inside one write bracket, before any fans out — every participant
  this frame opens a root for; a store a nested `suspendAtomic` shares with
  its enclosing frame still applies with that frame (enroll every store in
  the outermost frame).

- **A blocking `action` or `atomic` from inside a `suspendAction` or
  `suspendAtomic` commit no longer spins forever** when it targets a store
  that commit has applied: from an observer, a sync `Bridge.publish` or a
  `SuspendingBridge.publishAwaited` (also after a dispatcher hop on
  JVM/Android), an event collector the suspending emit resumes inline, a
  `FrameObserver.onFrameCommitted`, or a later `suspendAtomic` participant's
  observer targeting an earlier participant. It was not recognised as nested
  (`suspendingOwner` is set, and the commit may run on another thread than the
  one it started on), so it waited for the store's serializer, held by the
  very commit running it. The commit phase now runs under a fanout marker
  (`FanoutMarkers`, a `ThreadContextElement` on JVM/Android, a bracketing
  interceptor on iOS/wasmJs) that follows its coroutine, and the call returns
  `TransactionResult.Error` at once. A blocking call from any other thread
  still waits its turn and commits. Remaining gaps: on iOS/wasmJs, a nested
  `withContext(dispatcher)` inside the commit replaces the interceptor, so a
  blocking call from there still waits; and a blocking call on a later
  `suspendAtomic` participant that has not committed yet (from an earlier
  one's fanout) still waits for the frame (closed since: see above).

- **Nested `suspendAtomic` no longer leaks writes into the outer frame on
  failure.** Stores shared with an enclosing frame get a savepoint of the
  outer root; a failed nested frame discards only its own writes.
  Previously nested writes staged directly into the outer root and
  committed with it even when the nested frame returned `Error`.
- **`derived` recomputes queued during a `suspendAtomic` are drained after
  the store's mutex releases.** Previously the drain ran while the frame
  still held the mutex, so a recompute (a blocking `action`) could spin
  forever.
- **Blocking actions on two threads no longer fail on a store that has used
  a coroutine entry point.** The store's serializer locked its mutex with one
  shared owner for every blocking caller, and kotlinx `Mutex.tryLock(owner)`
  throws — rather than returning `false` — when that owner already holds it.
  So while one thread's blocking `action` (or `atomic`) held the serializer, a
  second thread's blocking action on the same store threw a raw
  `"This mutex is already locked by the specified owner"` instead of waiting.
  Each blocking acquire now locks with its own owner token.
- **A `suspendAction` that hands the store's mutex to a queued
  `suspendAction` no longer spins in its `derived` recompute.** kotlinx
  `Mutex.unlock` transfers ownership straight to the first waiter, so the
  first action's post-commit drain met a mutex held by a coroutine that had
  not resumed; the recompute's blocking acquire spun on it — forever when both
  ran on one thread (`runBlocking`, a single-threaded dispatcher). The
  recompute now hands itself to the new holder, which runs it after its own
  commit. A `suspendAtomic` waiter that is cancelled after being handed the
  mutex (kotlinx then gives the mutex back and `lock` throws) drains that
  recompute on its way out, once the frame has unwound.
- **`suspendAtomic` runs its post-commit work only once the whole frame has
  unwound.** Each participant's queue drained at that participant's own unwind
  step, while the frame still held the EARLIER participants' mutexes with its
  roots installed. So an observer on a `derived` recompute that wrote to an
  earlier participant hit the frame's finished root and threw, and a blocking
  `action` on it waited forever for a mutex the frame held. The frame now
  drains every store whose root it opened after releasing all of them.

### Changed

- **BREAKING (behavior): `suspendAtomic` applies every participant before
  any fans out** (issue #20, R9; see `:holdfast`'s changelog for `atomic`).
  Then each participant fans out in lock order — observers, awaited bridge
  publishes, suspending event drain. An observer's `mutate`/`update`/`emit`
  into a LATER participant is refused like one into an earlier participant
  (it used to stage into that participant's open root and commit with the
  frame), and a participant whose fanout fails — a throwing failure handler,
  or a `CancellationException` from its `SuspendingBridge.publishAwaited`
  (one that times out, say) — no longer rolls the later ones back: they have
  applied, so they fan out and commit, and the frame returns the failure as
  an `Error` (the cancellation, when there is one, carrying any other
  failure as suppressed). Only the participants that roll back get
  `onTransactionError`.

- **BREAKING (behavior): derived states settle once per outermost suspending
  entry, and a frame's post-commit work runs then.** A `derivedState` over
  sources written by two `suspendAction`s nested in a third recomputes once,
  after the outer one, instead of after each; inside the outer one it
  reflects none of their writes. The `derived`/`suspendDerived` work queued
  on the stores whose roots a `suspendAtomic` opened runs once the outermost
  entry has exited, not when the frame does.

- **BREAKING (behavior, edge): an outermost `suspendAction`/`suspendAtomic`
  checks for cancellation first.** Before it takes anything it checks the
  caller's job, on every platform: a call from an already-cancelled
  coroutine now throws its `CancellationException` before taking the store,
  where it used to take a free store and run — and, with no suspension point
  in the body, commit — the body. It then runs inside a child of the caller
  carrying its settle scope (a `withContext` on JVM/Android, an undispatched
  child on iOS/wasmJs); once the body has returned, the call still returns
  the committed `TransactionResult` even if the caller was cancelled
  meanwhile — while its commit awaited a `SuspendingBridge.publishAwaited`,
  say — as it did before: the caller sees its cancellation at its next
  suspension point. `SuspendEntryCancellationTest` pins both edges.

- **BREAKING (behavior): writes into a `suspendAction` or `suspendAtomic`
  commit from its own fanout fail loudly** (see `:holdfast`'s changelog, issue
  #20). The suspending root stays installed while `suspendingCommit` fans out,
  and `suspendingOwner` relaxes `mutate`'s owner check to any thread, so an
  observer's `mutate`/`update`/`emit` on the committing store staged into the
  applied root and was lost. It now throws an `IllegalStateException` that
  reaches `uncaughtObserverHandler`. So does a write, from a later
  participant's observer, into a nested `suspendAtomic`'s savepoint entry for
  a store its enclosing frame holds, once that entry has committed into the
  enclosing root (it used to be rejected as "Cannot mutate state on a
  Committed transaction"). The same relaxation means a bare
  `mutate`/`update` on that store from any other thread while the commit runs
  (its fanout, a slow `publishAwaited`, a suspending event emit) throws too,
  with a message saying a suspending transaction holds the store — it used to
  be lost silently during the observer fanout, or rejected as "Cannot mutate
  state on a Committed transaction" in the bridge and event phases. Before the
  apply pass such a write joins the transaction; the check and the stage are
  atomic with the apply pass, so it is applied with the commit or refused,
  never lost in between. Write from other threads through `action { }`, which
  waits for the serializer.

- **BREAKING (behavior): a failed `SuspendingBridge.publishAwaited` is logged
  when no `uncaughtObserverHandler` is set**, like every other post-commit
  failure, instead of being dropped silently. The commit still succeeds.

- **BREAKING (behavior): `suspendAction` and `suspendAtomic` refuse to run
  inside a state initializer** (issue #20, R5; see `:holdfast`'s changelog).
  State initializers may read states but not write them; like the blocking
  `action` and `atomic`, a suspending entrypoint reached from one — through
  `runBlocking` — throws an `IllegalStateException` naming the state being
  initialized.

- **BREAKING (behavior): a `suspendDerived`'s backing state leaves
  `StoreSnapshot.stateNames`.** It is registered with the new
  `Store.registerDerivedBackingState`, like `derived`'s: `snapshot()` still
  captures it and an undo on the same store restores it in the restore's own
  commit, but it is no longer a state name, and restoring the snapshot into
  another store instance skips it instead of failing on an unknown
  `__suspendDerived_N` state. `suspendDerived` on a disposed store now throws.

- **BREAKING (behavior, misuse only): a `computed` source of `suspendDerived`
  fails with `IllegalArgumentException`** (issue #20, R6; see `:holdfast`'s
  changelog). A `computed { }` state (or any `State` no store produced)
  passed as a source used to fail with a bare `ClassCastException`; it now
  fails with an `IllegalArgumentException` that says why (a `computed` state
  has no commits to follow) and what to list instead: the states it reads.
  The check runs first, so a refused call neither blocks on the initial
  compute nor leaves a backing state or a subscription behind.

- `suspendAtomic`'s vararg parameter is named `stores` (was pre-rename
  `vaults`) — source-compatible for positional calls.

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
