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

## An invariant enforced by a comment

Grep your codebase for `must be updated together` or `keep in sync`. Every
hit is an invariant enforced by a comment, and this is what usually lives
under it:

```kotlin
// balance and history must be updated together
try {
    accounts.debit(amount)
    history.append(entry)
} catch (e: Exception) {
    accounts.refund(amount)
}
```

A transaction with a hand-written rollback. The rollback can fail too, and
the screen already showed the debit before the refund landed. The same
transfer in Holdfast, with `accounts` and `history` as two stores that own a
`balance` and an `entries` state:

```kotlin
val result = atomic(accounts, history) {
    accounts.action { balance update { it - amount } }
    history.action { entries update { it + entry } }
    "transferred"
}
```

Two stores, one frame. Writes buffer in memory; both stores commit, or
neither does. Observers fire once, with committed values. Persistence
bridges publish only on commit. Participant locks are taken in one global
order, so frames cannot deadlock each other. And `result` is a value —
`TransactionResult.Success("transferred")` or `TransactionResult.Error` —
not an exception somebody forgot to catch.

Six months later someone adds a badge counter inside that transfer and
forgets to enroll its store. The frame refuses instead of committing the
badge on its own, and this is the message it throws, verbatim:

```
UnenrolledStoreException: BadgeStore was mutated (via action) inside
atomic(AccountStore, HistoryStore) but is not enrolled. Its writes would
commit independently and would NOT roll back with the frame.
Fix: add BadgeStore to the atomic(...) participant list.
(Mid-frame enrollment is not possible — it would acquire a lock outside the
sorted global order.) To deliberately run an independent side-transaction,
pass policy = FramePolicy.AllowUnenrolled.
```

Enforcement happens at runtime, so the message has to do the compiler's job:
cause, consequence, exact fix.

## Quick start

A single store has the same transaction boundary:

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
throws the `UnenrolledStoreException` shown above instead of committing
independently, a failed inner action aborts the whole frame, and
blocking/suspending misuse fails fast with a teaching exception instead of
deadlocking. Both enforcements have per-call-site opt-outs via `FramePolicy`.
See the [GUIDE's cross-store chapter](holdfast/GUIDE.md#15-cross-store-transactions)
for the full consistency contract.

## Where it fits

| If your state is… | Reach for |
|---|---|
| One value | `StateFlow`. Holdfast's unit is the transaction across several cells; a single cell does not need one. |
| Several fields that change together | `store action { … }` — one commit, one observer fanout, all-or-nothing rollback. |
| Two stores that must agree | `atomic(a, b) { … }` (blocking) or `suspendAtomic` (`:holdfast-coroutines`) — cross-store commit with enforced enrollment. |
| Under test | `storeTest { }` (`:holdfast-testing`) records a timeline of store events with ordered matchers, and fails at teardown if a `TransactionResult.Error` returned through a tracked handle was never asserted on. |

Adoption does not start with a rewrite: move one screen's fields into one
store, expose them with `asStateFlow` (`:holdfast-coroutines`), and the
Compose code collecting that flow never finds out. Core depends only on
`kotlinx-coroutines-core` and `kotlinx-atomicfu`, has about 50 public types,
and no Compose or Android framework dependencies. If what you want is a
serializable action history for replay, a Redux-style library is the better
fit — see [positioning](holdfast/README.md#positioning).

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

**Not on Maven Central yet.** The coordinates below are the intended ones;
`0.3.0` is the release that ships the publishing pipeline (see
[`ROADMAP.md`](ROADMAP.md)). Until then, build from source and add
`mavenLocal()` to your repositories:

```sh
./gradlew publishToMavenLocal -Pholdfast.version=0.1.0
```

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
- Every fenced Kotlin block in this README, [`holdfast/README.md`](holdfast/README.md), [`holdfast/GUIDE.md`](holdfast/GUIDE.md) and the coroutines/compose module READMEs is either compiled by `./gradlew check` (the `:doc-snippets` module) or listed with a reason in [`doc-snippets/snippet-exclusions.txt`](doc-snippets/snippet-exclusions.txt). The four examples above are also executed: their printed output, final balances, and the quoted exception message are asserted.

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

Open hazards in the current tree, named honestly. See [`ROADMAP.md`](ROADMAP.md)
for when each lands.

- **A blocking `action { }` called from inside a `suspendAction { }` body on the
  same store still spins.** The serializer is held by the suspending body and the
  blocking call waits for it. *Workaround:* inside a suspending body use
  `mutate`/`update` or a nested `suspendAction`, never blocking `action`. A
  fail-fast guard is next in 0.2.0. Other combinations that used to hang —
  `suspendAction` with a `derived()` state, nested `action` on a
  coroutine-touched store, and blocking `atomic()` racing a `suspendAction` — are
  fixed.

- **Writing to a second store from an observer can deadlock.** `action` holds the
  store's transaction lock across the whole commit fanout, so two stores whose
  observers write to each other block on each other's locks — an ordering
  `atomic`'s deadlock-safe `lockOrderKey` never sees, because it does not run
  through `atomic`. *Workaround:* from an observer, write to another store via
  `Store.scope` rather than inline.

- **Standalone `state.update { }` outside an action is not atomic.** It is a
  read-modify-write, so concurrent callers overwrite each other — measured, about
  50% of 10,000 concurrent increments are lost. *Workaround:* wrap the update in
  `action { }`, which serializes it under the store's transaction lock.

- **`store { }` (plain invoke) does not open a transaction — `store action { }`
  does.** Writes inside a bare invoke commit one by one, with observers firing
  between them, so observers can see intermediate states. *Workaround:* use
  `store action { }` for any mutation.

- **Nothing is published to Maven Central yet.** The badge and the install block
  above describe the intended coordinates, not a resolvable artifact — build from
  source until 0.3.0 ships the release pipeline.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). For security disclosures, email
front.desk@vynatix.com.

## License

Apache 2.0. See [`LICENSE`](LICENSE).
