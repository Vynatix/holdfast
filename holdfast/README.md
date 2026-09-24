# Holdfast

**Transactional state for Kotlin Multiplatform — atomic commits, savepoints, middleware bridges.**

A *holdfast* is the part of a kelp that anchors it to the seabed against the
tides. This library does the analogous thing for application state: a
`Store<Self : Store<Self>>` is a state container whose unit of consistency
is a **transaction**. Mutations buffer, observers see only committed values,
failed transactions never leak, and the type system enforces that a state class
anchors itself to its own type via the recursive `Store<Self>` pattern.

Core depends only on `kotlinx-coroutines-core` (as `api` — `CoroutineScope`
and `SharedFlow` appear in the public surface) and `kotlinx-atomicfu`; there
are no Compose or Android framework dependencies. Runs on Android, iOS, JVM,
and wasmJs.

## Quick start

```kotlin
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val label by state { "init" }
}

val counter = CounterStore()

// Subscribe.
val sub = counter { count effect { println("count=$this") } }   // count=0

// Atomic multi-state action — body's value flows into Success.
val result = counter action {
    count update { it + 1 }
    label mutate "ready"
    "transitioned to ${count.value}"
}
when (result) {
    is TransactionResult.Success -> println(result.value)        // "transitioned to 1"
    is TransactionResult.Error   -> handle(result.exception)
}

// Failed transactions roll back atomically — observers never fire.
// Don't drop the result: surface the failure.
val failed = counter action {
    count mutate 99
    error("simulated")
}
failed.onError { println("rolled back: ${it.exception.message}") }   // rolled back: simulated

sub.dispose()
```

## Mental model

A `Store<Self>` is a **state container with transactional commit semantics**:

1. **States** (`val count by state { 0 }`) are typed cells that observers can
   subscribe to.
2. **Transactions** (`action { … }`) are atomic units of mutation.
   Inside the body, writes are buffered; only on
   successful body completion do they commit and observers fire. A throw inside
   the body rolls back every write atomically — observers never see the
   intermediate state.
3. **Middleware** wraps every transaction with cross-cutting behavior — logging,
   timing, post-commit validation, anything you can implement against the
   `MiddlewareContext<V>` surface.
4. **Bridges** sync state to/from external systems (key-value stores, network
   sources, file system) — committed writes flow out; external changes flow in
   via `observeFrom`.

The recursive type `Store<Self : Store<Self>>` exists so that the inside
of a transaction has access to *your* state class's properties without casting.
Inside `counter action { count update { … } }`, `count` is your `CounterStore`'s
property, fully typed.

## Cross-store transactions

A single `action { }` is atomic within one store. When one invariant spans
several stores — debit one account and credit another, or neither — enroll
them all in an `atomic(...)` frame: every participant commits or rolls back
together.

```kotlin
class AccountStore(initial: Long = 0) : Store<AccountStore>() {
    val balance by state { initial }
}

val accountA = AccountStore(initial = 100)
val accountB = AccountStore()

// Both stores commit together, or neither does.
val transfer = atomic(accountA, accountB) {
    accountA.action { balance update { it - 30 } }
    accountB.action { balance update { it + 30 } }
    "transferred"                                    // body's value flows into Success
}
when (transfer) {
    is TransactionResult.Success -> println(transfer.value)   // transferred — A=70, B=30
    is TransactionResult.Error   -> println("rolled back: ${transfer.exception}")
}

// A throw anywhere in the body rolls back EVERY participant:
// the debit below never commits because the credit leg failed.
val failed = atomic(accountA, accountB) {
    accountA.action { balance update { it - 50 } }
    error("credit leg failed")
}
failed.onError { println("rolled back: ${it.exception.message}") }   // rolled back: credit leg failed
// accountA.balance.value is still 70 — the staged debit was discarded.
```

Participant locks are acquired in a deadlock-safe global order, and the frame
is enforced rather than advisory: writing to a store you forgot to enroll
throws `UnenrolledStoreException` instead of committing independently, and a
failed inner action aborts the whole frame (both enforcements have per-call-site
opt-outs via `FramePolicy`). The suspending peer `suspendAtomic` ships in
`:holdfast-coroutines`. Full consistency contract in
[GUIDE §15](GUIDE.md#15-cross-store-transactions).

## Major capabilities

### Core surface

- **Transactional `action { }`** — atomic multi-state writes; body's return value flows into `TransactionResult.Success<R>`.
- **Effects + bridges** — observe state changes; two-way external sync via `Bridge<T>`; inbound-only via `observeFrom(Observable<T>)`. A throwing effect, bridge publish or `derived` recompute never undoes its commit: it goes to `Store.uncaughtObserverHandler`, and is logged (standard error on JVM/Android) while no handler is set — set one at app init to route these failures into your own logging. An effect writing back into the store whose commit is notifying it is refused instead of being lost: `mutate`/`update`/`emit` throw into that handler, and a nested `action`/`atomic` returns an `Error` the effect must check ([GUIDE §4.4](GUIDE.md#4-the-seven-primitives)).
- **Middleware** — wrap every transaction with `LoggingMiddleware`, `TimingMiddleware`, `ValidationMiddleware`, `ProfilingMiddleware`, or your own.
- **Transformers** — normalize on write / project on read, including the asymmetric case where `set` and `get` produce different shapes.
- **Cross-store state ownership** — foreign-store states are rejected at compile time of the call (runtime ownership check at O(1)).
- **`Store.snapshot()` / `Store.restore()`** — capture and restore raw state, asymmetric-transformer-safe (raw round-trip means no double-encrypt). States are declared when the store is constructed, so a snapshot holds every declared state — a never-read one at its initial value — as one consistent cut that no concurrent commit is half-way through. `restore` ignores a state name the store does not declare, and a declared state the snapshot has no value for keeps its value; snapshots compare by value.
- **Snapshots that leave memory** *(experimental)* — declare a state with a codec (`state(codec = IntCodec) { 0 }`; every `bridge.Codec` is a stable `StateCodec`), and `snapshot().encode()` writes the snapshot as canonical text that `StoreSnapshot.decode(text)` reads back, in another store instance or process. States without a codec are listed, never written. `restore(snapshot, RestorePolicy.Strict | IgnoreUnknown | BestEffort)` reports what it restored, kept and skipped, and a rejected restore changes nothing; `snapshot[store.count]` reads one state's value, typed by the state. No exception these APIs throw quotes a state's value ([GUIDE §16.2](GUIDE.md#162-encoding-snapshots-codecs-restore-policies-typed-reads)).
- **Schema versions** *(experimental)* — a store whose states are renamed, or whose codecs' text changes, between releases implements `SchemaVersioned`: `schemaVersion` is recorded in every snapshot and its encoded text, and `migrate(from, view)` upcasts an older decoded snapshot's text (`view.rename("fontSize", "textSize")`) before the restore reads it. A newer snapshot, or a captured one of another version, is refused with a `SnapshotMigrationException` naming the store and both versions, and nothing changes; `migrate` may read states but not write any store ([GUIDE §16.3](GUIDE.md#163-schema-versions-and-migration)).
- **State tags** *(experimental)* — `state(tags = setOf(StateTag.Secret)) { "" }`. A `Secret` value stays readable in memory (and a captured snapshot restores it), but is written as `null` by `encode()`, shown as `<redacted>` by a captured snapshot's `render()`, read as `Redacted` from any snapshot but a `snapshot(SnapshotScope.Raw)` capture, and never reaches a `:holdfast-testing` timeline, a matcher's failure message or the built-in middleware's output; a `derived` with a Secret source is one. `snapshot(SnapshotScope.UserAuthored)` captures exactly the states and keyed state families tagged `UserAuthored`. `Remote` states are left out of `encode()` unless `includeRemote = true`, and `restore(snapshot, policy, sterile = true)` resets them to their initial values instead of restoring them. `state.tags` and `store.taggedStates(tag)` read the tags back ([GUIDE §16.4](GUIDE.md#164-state-tags-snapshot-scopes-and-redaction)).
- **`Store.reset()`** *(experimental)* — put every declared state back to what its initializer computes, in one transaction. The initializers run again (one that reads another declared state reads that state's reset value, in the order a new store's first reads would run them), their results are staged raw (an encrypted state is not encrypted twice), and only the states whose value changes are staged, so observers and bridges fire once for each and never for the rest. A throwing initializer rolls the whole reset back ([GUIDE §16.1](GUIDE.md#161-reset)).
- **`Store.computed { } / Store.derived(sources) { }`** — read-time-computed and push-recomputed derived states; the latter returns its own observable `State<T>` plus a `Disposable`.
- **`derivedState(sources) { } / merged(local, remote) { }`** *(experimental)* — a read-only `DerivedState<T>` (declare it with `by`), that settles: recomputed once per outermost `action`, `atomic` frame, `suspendAction` or `suspendAtomic` that changes a source — however many sources, stores and nested actions or frame participants it touches — after that entry has released every store, reading its sources from one committed cut (never a torn cross-store pair; inside the entry it reflects none of that entry's writes). `merged` names the split between what the user writes (`local`, tagged `UserAuthored`) and what sync adopts (`remote`, tagged `Remote`): an adoption writes `remote` only, so it never clobbers the user's side, and recomputes the merge once. Observable through `effect`, the coroutines flows and Compose's `collectAsState`; writing it throws; snapshots, `reset()` and `restore` leave it to recompute from its sources ([GUIDE §16.5](GUIDE.md#165-derived-states-and-merged)).
- **`keyedState<K, T> { key -> … }`** *(experimental)* — a keyed state family, `val docs by keyedState<String, Doc>(codec = …, keyCodec = …) { id -> Doc(id) }`: one ordinary state per key, created at the key's first `docs[key]` and the same `State` while it lives, so actions, frames, `effect` and derived states work on it unchanged. `docs.evict(key)`/`evictAll()` are transactional — staged, committed or rolled back with their action, applied in the same cut as its writes — and leave a stale handle whose writes throw, while every other entry keeps its observers and bridges; an eviction from the store's own commit fanout is deferred until the commit ends. Snapshots capture every live entry (`snapshot.keysOf(docs)`, `snapshot[docs[key]]`), `encode()` writes a family as an object under its name, `restore` creates the entries it holds and never evicts, and `reset()` re-runs every live entry's initializer. No message, `toString` or middleware sample shows a key ([GUIDE §16.6](GUIDE.md#166-keyed-state-families)).
- **`atomic(vararg stores, policy) { }`** — cross-store transaction frames: all enrolled stores commit or roll back together (basic usage in [Cross-store transactions](#cross-store-transactions) above). Per-store middleware fires for the frame with a shared `Transaction.frameId`; full contract in [GUIDE §15](GUIDE.md#15-cross-store-transactions).
- **`EncryptingTransformer(Cipher)`** — store ciphertext, read plaintext. Asymmetric-rollback-safe. Ships with educational `XorCipher`; production users plug their own AES via `javax.crypto` / CryptoKit.
- **`FileSystemKvStore(path)`** — disk-backed `KvStore` for `KvBridge`, atomic writes via tempfile + rename on JVM/Android and `NSData.writeToURL(atomically=true)` on iOS.
- **`Store.clock` / `bindClock(clock)`** *(experimental)* — time as an input: store code reads `clock.now()`, and a test pins it with a fixed `kotlin.time.Clock` (subclass getter override → bound clock → `Clock.System`). `storeTest { }` restores each tracked store's binding to its value at first track, so track before binding.

### `:holdfast-coroutines` extension

- **`suspendAction { }`** — async-aware transactional body. Mutually exclusive with blocking `action` on the same store via an internal coroutine `Mutex`.
- **`Flow` / `StateFlow` / `first` / `awaitValue`** adapters for state observation in coroutine code.
- **`hydrator { base { }; refresh { } adopt { } }`** *(experimental)* — a store's hydration lifecycle, `Detached` → `Seeded` → `Hydrated` (or `Failed(cause)`): `hydrate()` seeds in one transaction that also marks the refresh in flight, fetches only once that has committed, and does nothing while in flight or hydrated, however many callers ask; `adopt` may write only `Remote` states; `invalidate()` and `reset()` detach. `Hydrator.state` is observable and a `derivedState` source, so a health flag over several stores needs no frame ([GUIDE §16.7](GUIDE.md#167-hydration-holdfast-coroutines)).

### `:holdfast-compose` extension

- **`@Composable` `collectAsState`** for bridging `State<T>` into Compose recomposition.
- **`rememberDisposable`** for tying subscription lifetime to a Composable's scope.

### `:holdfast-testing` extension

- **`storeTest { }`** scope with auto-tracking, `StoreHandle.timeline` for ordered events, `TimelineMatcher` and `StateMatcher` DSLs for assertions. Teardown puts each tracked store's clock binding (`bindClock`) back to what it was when the store was first tracked, including bindings made by the test's un-joined child coroutines, so a test clock does not leak into the next test through a singleton store. Track the store before binding its clock, with `track(store)` or an auto-tracking call such as `store.read { }` (a bare `store.action { }` resolves to the `Store` member and does not track): a clock bound before the first track, or on a store the test never tracks, is kept. Work in `backgroundScope` or on scopes outside the test is not waited for, so join it before the body ends. A `StateTag.Secret` state's values are recorded as `Redacted` in the timeline and in bridge views, value matchers on it (`emitted(prop, value)`, `shouldHavePublished`) are refused with a teaching error while `emitted(prop)` and counts still work, and `shouldMatch`/`shouldMatchSnapshotOf` compare it without printing it.

### `:holdfast-hallmark` + `:holdfast-hallmark-coroutines`

Bridge to the [Hallmark](https://github.com/vynatix/hallmark) refinement-types
library — `ValidatingTransformer` for write-validating state, `Store.boxed { }`
state factory pairing a state cell with a `BoxedValidator`, `BoxedCodec` for
validated values in `KvBridge` persistence, and `Store.suspendValidateAndMutate`
for async-validation flows. The experimental `boxed(validator, codec, tags)` and
`boxedHandle(validator, codec, tags)` overloads declare a boxed state with a
snapshot codec and state tags; for a `StateTag.Secret` state, a validation
failure's `HallmarkException` withholds the rejected value. Hallmark itself is a separate library; use the
bridge only when you want validated values living in transactional state.

## Standard library (in-tree)

Helpers under `com.vynatix.holdfast.middleware`, `com.vynatix.holdfast.bridge`,
and `com.vynatix.holdfast.crypto`:

| Helper | Purpose |
|---|---|
| `LoggingMiddleware<V>(tag, log)` | Trace every transaction's lifecycle |
| `TimingMiddleware<V>(onResult)` | Wall-clock duration per transaction |
| `ValidationMiddleware<V>(check)` | Post-body invariant check (throws → rollback) |
| `ProfilingMiddleware<V>(onSample)` | Per-transaction profile (duration, outcome, written states) with aggregates via `profile()` |
| `KvBridge<T>(kv, key, codec)` | Save-on-commit + load-on-attach via any `KvStore` |
| `Codec<T>` (`StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`) | Trivial encoders for common types |
| `InMemoryKvStore` | Trivial KV impl for tests + dev |
| `FileSystemKvStore(rootPath)` | Disk-backed `KvStore` (`expect`/`actual`; JVM + iOS) |
| `Cipher` + `EncryptingTransformer(Cipher)` | Encrypt-on-write, decrypt-on-read transformer |
| `XorCipher(seed)` | KMP-pure educational `Cipher` (**not** production-grade — documented) |

## Concurrency model

- All store writes serialize through a per-store reentrant lock.
- Transactions are thread-confined: only the action's owner thread sees pending
  writes. Cross-thread reads see committed values.
- `mutate` from a non-owner thread auto-wraps in a one-shot transaction —
  middleware fires; observers see only committed values — except while a
  `suspendAction`/`suspendAtomic` holds the store: then a bare write from any
  thread stages into (or, once it has applied, is refused by) that
  transaction; see GUIDE §8.2.
- `atomic(s1, s2, …)` sorts stores by a process-monotonic `lockOrderKey`
  and acquires locks in order — deadlock-safe across any combination. Frame
  bodies are policed: writes to unenrolled stores throw, nested frames verify
  lock order at entry, and blocking/suspending frame misuse fails fast with
  `FrameInteropException` instead of deadlocking.
- `suspendAction` and blocking `action` are mutually exclusive on the same
  store via a coroutine `Mutex` installed lazily through an internal
  `AsyncSerializer` hook.
- `derived` recomputes never wait on the derived's store: one recompute per
  source commit for same-store sources, and if that store is busy the
  recompute is handed to its current holder and runs when that holder
  releases — so `value` may briefly lag the committing call. The experimental
  `derivedState`/`merged` settle instead: once per outermost `action`,
  `atomic` frame, `suspendAction` or `suspendAtomic` that changes their
  sources — on any stores, in any nesting — after it has released every
  store, each reading its sources from one committed cut, a chain in order.
  So one never commits or shows a torn pair across the participants of a
  frame, even when its recompute races another thread's frame (a frame
  nested in an action or frame it shares a store with applies that store
  with the enclosing entry: see the next point).
- `atomic`/`suspendAtomic` apply every participant, inside one write
  bracket, before any participant fans out; then each fans out in lock
  order. A consistent read across the participants sees a frame whole or
  not at all — an outermost frame, or a nested one sharing no store with its
  enclosing action or frame (a shared store joins as a savepoint and applies
  when the enclosing transaction commits) — and an observer of one
  participant finds the others applied, and may not write into any of them.
- Commit fanout (observers, bridge publishes, events) runs after the
  transaction has applied, while the store is still held — under its lock
  for a blocking `action`, under its serializer for `suspendAction`. An
  observer that writes back into that same store gets an
  `IllegalStateException` (`mutate`/`update`/`emit`) or an `Error` result
  (nested `action`/`atomic`) — the write could never commit. Other threads'
  actions just wait for the store. (While a `suspendAction`/`suspendAtomic`
  holds the store, a bare `mutate`/`update` from another thread is not
  wrapped in its own action: it joins the suspending transaction before that
  applies and throws after — use `action` from other threads.)

## Modules

| Artifact | Role |
|---|---|
| `com.vynatix:holdfast` | Core. |
| `com.vynatix:holdfast-coroutines` | `Flow` / `StateFlow` / `first` / `awaitValue` adapters + `suspendAction { … }` + `hydrator { … }` (experimental). |
| `com.vynatix:holdfast-compose` | `@Composable` `collectAsState` / `rememberDisposable`. |
| `com.vynatix:holdfast-testing` | Test scope, handle, timeline, matchers. |
| `com.vynatix:holdfast-hallmark` | [Hallmark](https://github.com/vynatix/hallmark) bridge — `ValidatingTransformer`, `Store.boxed { }`, `BoxedCodec`, `BoxedHandle`. |
| `com.vynatix:holdfast-hallmark-coroutines` | Suspend-side Hallmark bridge — `Store.suspendValidateAndMutate`. |

## Platform support

Android, JVM, and iOS are supported tiers — their tests run in CI. **wasmJs is
experimental**: the artifact is still published for downstream consumers, but

- tests are disabled on wasmJs (the test suite uses `runBlocking` /
  `newSingleThreadContext`, absent on wasm);
- `FileSystemKvStore` throws `UnsupportedOperationException` (no synchronous
  filesystem API in the browser);
- `suspendDerived` is unusable (its eager initial seed requires `runBlocking`);
- the platform is single-threaded (`currentThreadId() == 0`), so
  thread-confinement checks trivially pass.

## Positioning

Holdfast sits in a different niche from common Kotlin/JVM state-management choices:

- **`StateFlow` / `MutableStateFlow`**: a single typed cell with hot-share
  semantics. Holdfast's *unit* is the transaction across multiple cells, not
  a single value. If your "state" is one value, use `StateFlow` and skip this
  library. If it's a coordinated set of cells with cross-field invariants, this
  is what Holdfast offers.
- **Redux / MVI / Mavericks**: time-travel-friendly, store + reducer + action
  shape. Holdfast doesn't enforce a reducer — mutations happen inline via
  `update { }` / `mutate` / nested helpers. Closer to "object with transactional
  methods" than "store with serializable actions." If you want serializable-
  action history for replay, use redux-like libraries.
- **Cash App's [Molecule](https://github.com/cashapp/molecule)**: turns
  `@Composable` into `StateFlow`. Different concern (Compose-style state from
  imperative) — composes well with Holdfast for the rendering side.
- **[Saga](https://github.com/redux-saga/redux-saga)**: side-effect orchestration
  pattern, primarily JS. Different niche; Holdfast doesn't model effect
  sequencing as a separate concept — `suspendAction { }` covers async-side flows.

The library is intentionally focused — ~50 public types in core, no required
dependencies beyond `kotlin-stdlib`, `kotlinx-coroutines-core`, and
`kotlinx-atomicfu`. Optional features (Flow/StateFlow adapters and suspending
transactions, Compose, testing, Hallmark validation) layer on as separate
modules.

## Documentation

- **[GUIDE.md](GUIDE.md)** — long-form tutorial: mental model, decision charts, feature differentiation tables, technique cookbook, and API reference.
- **[vynatix/banking-demo](https://github.com/vynatix/banking-demo)** — companion sample repo: a banking-domain narrative demo exercising every public API of Holdfast and Hallmark across a runnable JVM `main()` plus 186 feature-coverage tests. Best place to see the libraries in action.
- **[CHANGELOG.md](CHANGELOG.md)** — release history (with internal pre-rename design archive preserved).

## Stability

**0.x — pre-stable.** The public API may break in any 0.x bump. Consumers
should pin to an exact version. SemVer guarantees apply once 1.0 is declared.

## Building

```sh
./gradlew :holdfast:allTests              # tests on Android JVM + iOS sim + JVM + wasmJs
./gradlew :holdfast:detekt :holdfast:ktlintCheck
./gradlew :holdfast:apiCheck              # ABI binary-compat check
./gradlew :holdfast:dokkaGenerate         # API doc site at build/dokka/html
./gradlew :holdfast:publishToMavenLocal   # publish to ~/.m2 for local consumption

# Companion modules
./gradlew :holdfast-coroutines:allTests :holdfast-coroutines:apiCheck
./gradlew :holdfast-compose:allTests     :holdfast-compose:apiCheck
./gradlew :holdfast-testing:allTests     :holdfast-testing:apiCheck
./gradlew :holdfast-hallmark:allTests    :holdfast-hallmark:apiCheck
./gradlew :holdfast-hallmark-coroutines:allTests :holdfast-hallmark-coroutines:apiCheck
```

## License

Apache 2.0. See [`LICENSE`](../LICENSE).
