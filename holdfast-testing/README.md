# holdfast-testing

The testing harness for the [Store](../holdfast/) library: `storeTest { }`,
tracked stores with recorded timelines, and infix matchers over transactions,
emissions, bridges and cross-store frames. Pulls in `:holdfast`,
`:holdfast-coroutines` and `kotlinx-coroutines-test`; no assertion library —
the matchers are hand-rolled, so kotest is not a dependency of yours.

## Quick start (30 seconds)

```kotlin
dependencies {
    testImplementation("com.vynatix:holdfast-testing:0.1.0")
}
```

One complete, copy-paste test — track a store, run an action, match the
recorded timeline:

```kotlin
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.matcher.shouldFireInOrder
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
}

class CounterStoreTest {
    @Test
    fun incrementsAtomically() = storeTest {
        val counter = track(CounterStore())          // installs the recorder

        counter.action { count mutate 1 }            // handle verb: result recorded
            .shouldBeSuccess()

        counter shouldFireInOrder {                  // …and the timeline proves it
            started
            emitted(CounterStore::count, 1)
            committed
        }
        assertEquals(1, counter.read { count.value })
    }
}
```

Run it with your module's test task — `./gradlew :your-module:jvmTest`
(`testDebugUnitTest`-style names do not apply to KMP host tests; on Android
the host-test task is `testAndroidHostTest`). `storeTest` wraps
`runTest`, so virtual time, `advanceUntilIdle()` and the background scope are
available on the scope.

## The `action`-shadowing trap

Inside `storeTest { }` a bare `counter.action { … }` resolves to **`Store`'s
own member** (Kotlin prefers members over extensions) — it runs
production-faithful and feeds an already-installed recorder, but it never
auto-tracks the store and never feeds the handle's `lastResult` /
unconsumed-error guard. The auto-registering verb is therefore
**`trackedAction`**, a name `Store` does not declare:

```kotlin
@Test
fun noExplicitTrackNeeded() = storeTest {
    val v = MyStore()
    v.trackedAction { count mutate 1 }.shouldBeSuccess()   // auto-tracks first
    v.read { assertEquals(1, count.value) }                // same handle
}
```

`read { }`, `timeline`, `emissions(…)`, `suspendAction { }` and the other
scope extensions auto-register the same way (`track(v)` upfront if you need
`Capture.None` or `Capture.RingBuffer`).

## Surface

```kotlin
fun storeTest(timeout: Duration = 60.seconds, body: suspend StoreTestScope.() -> Unit): TestResult
@Deprecated fun vaultTest(...)                                        // renamed alias

class StoreTestScope : CoroutineScope {
    fun <V : Store<V>> track(store: V, capture: Capture = Capture.All): StoreHandle<V>
    fun <V : Store<V>> track(tree: StoreTree, capture: Capture = Capture.All, resetAtTeardown: Boolean = true): TreeHandle
    // member extensions on Store (auto-register on first touch):
    //   trackedAction { } / suspendAction { } / read { } / timeline /
    //   transactions / emissions(::prop) / bridgeEvents(::prop) /
    //   middlewareEventsOf(mw) / lastTransaction / lastResult
}

class StoreHandle<V : Store<V>> {
    val timeline: List<StoreEvent>                    // defensive copy, push order
    val transactions: List<TransactionEvent>
    val lastTransaction: Transaction?
    val lastResult: TransactionResult<*>?
    fun <R> action(body: V.() -> R): TransactionResult<R>
    suspend fun <R> suspendAction(body: suspend V.() -> R): TransactionResult<R>
    fun <R> read(block: V.() -> R): R
    fun emissions(prop: KProperty1<V, State<*>>): List<EmissionEvent>
    fun bridgeEvents(prop: KProperty1<V, State<*>>): List<BridgeEvent>
    fun bridge(prop: KProperty1<V, State<*>>): BridgeView<*>
    fun consumeAllPendingErrors()
}

sealed interface StoreEvent   // TransactionStarted/Committed/RolledBack/Errored,
                             // EmissionEvent, Middleware*(self events only, v1),
                             // BridgePublished/BridgeObserved
```

Timeline matchers (infix, on the handle or on a synthetic
`List<StoreEvent>`): `shouldFire { }`, `shouldFireInOrder { }`,
`shouldFireInExactOrder { }`, `shouldNotFire { }` with predicates
`started`/`committed`/`rolledBack`/`errored` (optionally by transaction id),
`emitted(::prop)` / `emitted(::prop, value)`, `bridgePublished(::prop)`,
`bridgeObserved(::prop)`, `middleware(mw) { started; completed; errored }`.
Also: `shouldBeSuccess()`, `shouldBeError<T>()`,
`shouldRollbackWith(T::class)`, `shouldMatch { ::count shouldEqual 1 }`,
`shouldMatchExactly { }`, `shouldMatchSnapshotOf(other)`,
`shouldCommitTogether()`/`shouldNotCommitTogether()` over `(a and b)`,
`shouldCompleteWithin(...)` — and concurrency helpers `parallel { }`,
`barrier(...)`, `awaiting { }`, `transaction { }`, plus test bridges
(`RecordingBridge`, `LatchedBridge`, `FailingBridge`, `FakeKvStore`).

## What teardown enforces

- Every `TransactionResult.Error` a handle returns must be consumed by a
  matcher (or `consumeAllPendingErrors()`) — otherwise the test fails with a
  report of each unconsumed error.
- Each tracked store's `bindClock` binding is restored to its value at first
  track — track a store **before** binding a test clock.
- The recorder middleware is removed **alone**: middlewares your test
  installed stay; a tracked tree is reset as one frame (opt out with
  `track(tree, resetAtTeardown = false)`).

## Matchers that cannot match, refuse

A `middleware { }` predicate scoped to anything but the harness recorder can
never match (user middlewares are not wrapped in v1), and a bridge predicate
on a state whose bridge was not attached before `track(v)` can never match —
both throw a teaching error instead of passing vacuously through
`shouldNotFire`. A `StateTag.Secret` state's values are recorded as
`Redacted`; value matchers on it refuse the same way. The
`shouldBeBoxedAs` matcher does not live here: it lives in
`:holdfast-hallmark` (its `com.vynatix:hallmark` dependency resolves from
`mavenLocal()`; build the [hallmark](https://github.com/vynatix/hallmark)
repo first and opt in with `-Pholdfast.includeHallmark=true`).

## Build

```
./gradlew :holdfast-testing:jvmTest
./gradlew :holdfast-testing:apiCheck        # iOS klib dump needs a macOS host
./gradlew :holdfast-testing:dokkaGenerate
./gradlew :holdfast-testing:publishToMavenLocal
```
