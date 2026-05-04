# Holdfast

**Transactional state for Kotlin Multiplatform — atomic commits, savepoints, middleware bridges.**

A *holdfast* is the part of a kelp that anchors it to the seabed against the
tides. This library does the analogous thing for application state: a
`Holdfast<Self : Holdfast<Self>>` is a state container whose unit of consistency
is a **transaction**. Mutations buffer, observers see only committed values,
failed transactions never leak, and the type system enforces that a state class
anchors itself to its own type via the recursive `Holdfast<Self>` pattern.

No Compose dependency in core. No coroutines dependency in core. Runs on
Android, iOS, JVM, and wasmJs.

## Quick start

```kotlin
class CounterHoldfast : Holdfast<CounterHoldfast>() {
    val count by state { 0 }
    val label by state { "init" }
}

val counter = CounterHoldfast()

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
counter action {
    count mutate 99
    error("simulated")
}

sub.dispose()
```

## Mental model

A `Holdfast<Self>` is a **state container with transactional commit semantics**:

1. **States** (`val count by state { 0 }`) are typed cells that observers can
   subscribe to.
2. **Transactions** (`action { … }` or `transaction(on = holdfast) { … }`) are
   atomic units of mutation. Inside the body, writes are buffered; only on
   successful body completion do they commit and observers fire. A throw inside
   the body rolls back every write atomically — observers never see the
   intermediate state.
3. **Middleware** wraps every transaction with cross-cutting behavior — logging,
   timing, post-commit validation, anything you can implement against the
   `MiddlewareContext<V>` surface.
4. **Bridges** sync state to/from external systems (key-value stores, network
   sources, file system) — committed writes flow out; external changes flow in
   via `observeFrom`.

The recursive type `Holdfast<Self : Holdfast<Self>>` exists so that the inside
of a transaction has access to *your* state class's properties without casting.
Inside `counter action { count update { … } }`, `count` is your `CounterHoldfast`'s
property, fully typed.

## Major capabilities

### Core surface

- **Transactional `action { }`** — atomic multi-state writes; body's return value flows into `TransactionResult.Success<R>`.
- **Effects + bridges** — observe state changes; two-way external sync via `Bridge<T>`; inbound-only via `observeFrom(Observable<T>)`.
- **Middleware** — wrap every transaction with `LoggingMiddleware`, `TimingMiddleware`, `ValidationMiddleware`, or your own.
- **Transformers** — normalize on write / project on read, including the asymmetric case where `set` and `get` produce different shapes.
- **Cross-holdfast state ownership** — foreign-holdfast states are rejected at compile time of the call (runtime ownership check at O(1)).
- **`Holdfast.snapshot()` / `Holdfast.restore()`** — capture and restore raw state, asymmetric-transformer-safe (raw round-trip means no double-encrypt).
- **`Holdfast.computed { } / Holdfast.derived(sources) { }`** — read-time-computed and push-recomputed derived states; the latter returns its own observable `State<T>` plus a `Disposable`.
- **`atomic(vararg holdfasts) { }`** — cross-holdfast transactions. Sorts by `lockOrderKey` for deadlock-safe lock acquisition; body throw rolls back every holdfast.
- **`EncryptingTransformer(Cipher)`** — store ciphertext, read plaintext. Asymmetric-rollback-safe. Ships with educational `XorCipher`; production users plug their own AES via `javax.crypto` / CryptoKit.
- **`FileSystemKvStore(path)`** — disk-backed `KvStore` for `KvBridge`, atomic writes via tempfile + rename on JVM/Android and `NSData.writeToURL(atomically=true)` on iOS.

### `:holdfast-coroutines` extension

- **`suspendAction { }`** — async-aware transactional body. Mutually exclusive with blocking `action` on the same holdfast via an internal coroutine `Mutex`.
- **`Flow` / `StateFlow` / `first` / `awaitValue`** adapters for state observation in coroutine code.

### `:holdfast-compose` extension

- **`@Composable` `collectAsState`** for bridging `State<T>` into Compose recomposition.
- **`rememberDisposable`** for tying subscription lifetime to a Composable's scope.

### `:holdfast-testing` extension

- **`holdfastTest { }`** scope with auto-tracking, `HoldfastHandle.timeline` for ordered events, `TimelineMatcher` and `StateMatcher` DSLs for assertions.

### `:holdfast-hallmark` + `:holdfast-hallmark-coroutines`

Bridge to the [Hallmark](https://github.com/vynatix/hallmark) refinement-types
library — `ValidatingTransformer` for write-validating state, `Holdfast.boxed { }`
state factory pairing a state cell with a `BoxedValidator`, `BoxedCodec` for
validated values in `KvBridge` persistence, and `Holdfast.suspendValidateAndMutate`
for async-validation flows. Hallmark itself is a separate library; use the
bridge only when you want validated values living in transactional state.

## Standard library (in-tree)

Helpers under `com.vynatix.holdfast.middleware`, `com.vynatix.holdfast.bridge`,
and `com.vynatix.holdfast.crypto`:

| Helper | Purpose |
|---|---|
| `LoggingMiddleware<V>(tag, log)` | Trace every transaction's lifecycle |
| `TimingMiddleware<V>(onResult)` | Wall-clock duration per transaction |
| `ValidationMiddleware<V>(check)` | Post-body invariant check (throws → rollback) |
| `KvBridge<T>(kv, key, codec)` | Save-on-commit + load-on-attach via any `KvStore` |
| `Codec<T>` (`StringCodec`, `LongCodec`, `IntCodec`, `BooleanCodec`) | Trivial encoders for common types |
| `InMemoryKvStore` | Trivial KV impl for tests + dev |
| `FileSystemKvStore(rootPath)` | Disk-backed `KvStore` (`expect`/`actual`; JVM + iOS) |
| `Cipher` + `EncryptingTransformer(Cipher)` | Encrypt-on-write, decrypt-on-read transformer |
| `XorCipher(seed)` | KMP-pure educational `Cipher` (**not** production-grade — documented) |

## Concurrency model

- All holdfast writes serialize through a per-holdfast reentrant lock.
- Transactions are thread-confined: only the action's owner thread sees pending
  writes. Cross-thread reads see committed values.
- `mutate` from a non-owner thread auto-wraps in a one-shot transaction —
  middleware fires; observers see only committed values.
- `atomic(h1, h2, …)` sorts holdfasts by a process-monotonic `lockOrderKey`
  and acquires locks in order — deadlock-safe across any combination.
- `suspendAction` and blocking `action` are mutually exclusive on the same
  holdfast via a coroutine `Mutex` installed lazily through an internal
  `AsyncSerializer` hook.

## Modules

| Artifact | Role |
|---|---|
| `com.vynatix:holdfast` | Core. |
| `com.vynatix:holdfast-coroutines` | `Flow` / `StateFlow` / `first` / `awaitValue` adapters + `suspendAction { … }`. |
| `com.vynatix:holdfast-compose` | `@Composable` `collectAsState` / `rememberDisposable`. |
| `com.vynatix:holdfast-testing` | Test scope, handle, timeline, matchers. |
| `com.vynatix:holdfast-hallmark` | [Hallmark](https://github.com/vynatix/hallmark) bridge — `ValidatingTransformer`, `Holdfast.boxed { }`, `BoxedCodec`, `BoxedHandle`. |
| `com.vynatix:holdfast-hallmark-coroutines` | Suspend-side Hallmark bridge — `Holdfast.suspendValidateAndMutate`. |

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
dependencies beyond `kotlin-stdlib` + `kotlinx-atomicfu`. Optional features
(coroutines, Compose, testing, Hallmark validation) layer on as separate
modules.

## Documentation

- **[GUIDE.md](GUIDE.md)** — long-form tutorial: mental model, decision charts, feature differentiation tables, technique cookbook, and API reference.
- **[BankingDemo.kt](src/commonTest/kotlin/com/vynatix/holdfast/demo/BankingDemo.kt)** — single-file end-to-end demo exercising every public API across a banking domain. Reads as a tutorial; runs as a test.
- **[CHANGELOG.md](CHANGELOG.md)** — release history (with internal pre-rename design archive preserved).

## Stability

**0.x — pre-stable.** The public API may break in any 0.x bump. Consumers
should pin to an exact version. SemVer guarantees apply once 1.0 is declared.

## Building

```sh
./gradlew :holdfast:allTests              # tests on Android JVM + iOS sim + JVM + wasmJs
./gradlew :holdfast:detekt :holdfast:ktlintCheck
./gradlew :holdfast:apiCheck              # ABI binary-compat check
./gradlew :holdfast:dokkaHtml             # API doc site at build/dokka/html
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
