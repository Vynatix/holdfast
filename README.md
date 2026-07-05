# Holdfast

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.vynatix/holdfast)](https://central.sonatype.com/artifact/com.vynatix/holdfast)

**Transactional state for Kotlin Multiplatform — atomic commits, savepoints, middleware bridges.**

A *holdfast* is the part of a kelp that anchors it to the seabed against the
tides. This library does the analogous thing for application state: a
`Store<Self : Store<Self>>` is a state container whose unit of consistency
is a **transaction** — mutations buffer, observers see only committed values,
failed transactions never leak, and the type system enforces that a state
class anchors itself to its own type.

```kotlin
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
    val label by state { "init" }
}

val counter = CounterStore()
val sub = counter { count effect { println("count=$this") } }   // count=0

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
```

## Cross-store transactions

When an invariant spans stores, `atomic(a, b) { … }` (and its suspending peer
`suspendAtomic`) runs in-memory two-phase commit with deadlock-safe global
lock ordering — all participants commit or roll back together, something no
mainstream Kotlin state-management library offers:

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
```

The frame is enforced, not advisory: writing to a store you forgot to enroll
throws instead of committing independently, a failed inner action aborts the
whole frame, and blocking/suspending misuse fails fast with a teaching
exception instead of deadlocking. See the
[GUIDE's cross-store chapter](holdfast/GUIDE.md#15-cross-store-transactions)
for the full consistency contract.

## Modules

| Artifact | Role |
|---|---|
| [`com.vynatix:holdfast`](holdfast/) | Core — transactions, state, middleware, bridges, snapshot/restore, derived state, cross-store `atomic` frames, encryption transformer, file-system store. |
| [`com.vynatix:holdfast-coroutines`](holdfast-coroutines/) | `Flow` / `StateFlow` adapters + `suspendAction { … }` / `suspendAtomic(…) { … }` for async transactional bodies. |
| [`com.vynatix:holdfast-compose`](holdfast-compose/) | `@Composable` `collectAsState` / `rememberDisposable`. |
| [`com.vynatix:holdfast-testing`](holdfast-testing/) | Testing harness — `storeTest { }`, `StoreHandle`, timeline matchers, cross-store frame matchers. |
| [`com.vynatix:holdfast-hallmark`](holdfast-hallmark/) | [Hallmark](https://github.com/vynatix/hallmark) bridge — `ValidatingTransformer`, `Store.boxed { }` state factory, `BoxedCodec`, `shouldBeBoxedAs` test matcher. Unreleased — requires the sibling Hallmark repo; enable with `-Pholdfast.includeHallmark=true`. |
| [`com.vynatix:holdfast-hallmark-coroutines`](holdfast-hallmark-coroutines/) | Suspend-side Hallmark bridge — `Store.suspendValidateAndMutate`. Unreleased — requires the sibling Hallmark repo; enable with `-Pholdfast.includeHallmark=true`. |

## Platform support

| Platform | Tier | Notes |
|---|---|---|
| Android | Supported | Tests run in CI. |
| JVM | Supported | Tests run in CI. |
| iOS (`iosArm64`, `iosSimulatorArm64`) | Supported | Tests run in CI. |
| wasmJs (`:holdfast`, `:holdfast-coroutines`, `:holdfast-compose` only) | **Experimental** | Artifact published; limitations below. |

wasmJs artifacts are still published for downstream consumers, but the target
is **experimental** with these limitations:

- **Tests are disabled on wasmJs** — the test suite uses `runBlocking` /
  `newSingleThreadContext`, neither of which exists on wasm, so wasmJs test
  tasks are force-disabled and never run in CI.
- **`FileSystemKvStore` throws `UnsupportedOperationException`** — the browser
  has no synchronous filesystem API; use `InMemoryKvStore` or a
  browser-storage-backed `KvStore` instead.
- **`suspendDerived` is unusable** — its eager initial seed requires
  `runBlocking`, which is not available on wasmJs; seed asynchronously via
  `suspendAction { … }` instead.
- **Single-threaded model** — `currentThreadId()` returns `0` for every caller,
  so thread-confinement checks trivially pass.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// build.gradle.kts
dependencies {
    implementation("com.vynatix:holdfast:0.1.0")
    implementation("com.vynatix:holdfast-coroutines:0.1.0")   // optional
    implementation("com.vynatix:holdfast-compose:0.1.0")      // optional, Compose Multiplatform
    testImplementation("com.vynatix:holdfast-testing:0.1.0")  // optional
}
```

**Toolchain floor:** the artifacts are built with Kotlin 2.3.x, so consuming
projects need a Kotlin 2.3.x (or newer compatible) compiler to read the
published klib/metadata format, and the JVM/Android class files target
**JVM 21** — set your `jvmTarget` / `compileOptions` to 21 or higher.

## Documentation

- [`holdfast/README.md`](holdfast/README.md) — full guide: mental model, transactions, state, middleware, positioning vs. other state-management libraries.
- [`holdfast/GUIDE.md`](holdfast/GUIDE.md) — long-form tutorial with decision charts, feature differentiation tables, technique cookbook, and API reference.
- [`holdfast/CHANGELOG.md`](holdfast/CHANGELOG.md) — release history (with internal pre-rename design archive preserved).

## Companion library

[Hallmark](https://github.com/vynatix/hallmark) — refinement types for KMP. The
`:holdfast-hallmark` adapter (in this repo) bridges Hallmark's typed primitives
with Holdfast's transactional state, so validated values live in state that
respects them.

`com.vynatix:hallmark` is not yet on Maven Central, so the hallmark modules
are excluded from the default build. To work on them, publish the Hallmark
repo to `mavenLocal` and run Gradle with `-Pholdfast.includeHallmark=true` —
this adds `:holdfast-hallmark` and `:holdfast-hallmark-coroutines` to the
build, including the `shouldBeBoxedAs` matcher (which lives in
`:holdfast-hallmark`, not `:holdfast-testing`).

## Stability

**0.x — pre-stable.** The public API may break in any 0.x bump. Consumers should
pin to exact versions. SemVer guarantees apply once 1.0 is declared.

## Known issues

Three open hazards in 0.1.0, named honestly. All three fixes land in 0.3.0 —
see [`ROADMAP.md`](ROADMAP.md).

- **Mixing blocking `action { }` with `suspendAction { }` on the same store.**
  The self-spin case — a blocking `action { }` (or an `atomic`/`suspendAtomic`
  enrolling the store) called from *inside* a `suspendAction` body — now throws
  `FrameInteropException` immediately instead of livelocking; hoist the frame
  (run it first, `suspendAction` inside). A blocking `action` on **another
  thread** overlapping an in-flight `suspendAction` is a bounded spin-wait that
  serializes correctly (the two no longer cross-contaminate). The one residual
  hazard is a **single-threaded dispatcher**: a blocking `action` that spins on
  the only dispatcher thread can starve the `suspendAction` trying to resume on
  it — *workaround:* keep blocking `action` and `suspendAction` on separate
  stores there, or give the suspending work its own dispatcher.
- **Standalone `state.update { }` outside an action is not atomic.** It is a
  read-modify-write, so concurrent callers overwrite each other — measured,
  about 50% of 10,000 concurrent increments are lost. *Workaround:* wrap the
  update in `action { }`, which serializes it under the store's transaction
  lock. Standalone `update` becomes atomic in 0.3.0.
- **`store { }` (plain invoke) does not open a transaction — `store action { }`
  does.** Writes inside a bare invoke commit one by one, with observers firing
  between them, so observers can see intermediate states. *Workaround:* use
  `store action { }` for any mutation. In 0.3.0 mutating inside a bare invoke
  fails loudly instead of silently committing piecemeal.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). For security disclosures, email
front.desk@vynatix.com.

## License

Apache 2.0. See [`LICENSE`](LICENSE).
