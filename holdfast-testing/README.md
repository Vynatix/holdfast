# holdfast-testing

Testing harness for the [Store](../holdfast/) library: a `storeTest { }` scope, a
`StoreHandle` that records a **timeline** of every transaction lifecycle event,
infix matchers over that timeline, concurrency primitives on virtual time, and
in-memory bridge fakes.

**Assertion-library-free.** The harness depends only on `:holdfast`,
`:holdfast-coroutines`, `kotlinx-coroutines-core/-test`, and `atomicfu`. Its
matchers throw plain `AssertionError`; mix them with `kotlin.test.assert*` or any
assertion library you like — nothing is imposed on your classpath.

## Quickstart

```kotlin
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
}

@Test
fun increments() = storeTest {
    val ctr = track(CounterStore())         // returns a StoreHandle

    ctr.action { count mutate 5 }.shouldBeSuccess()

    assertEquals(5, ctr.read { count.value })
    ctr shouldFire { emitted(CounterStore::count, 5) }
}
```

`track(store)` installs a privileged recorder middleware and returns a
`StoreHandle`. A store used bare inside the scope (`myStore.action { }`,
`myStore.timeline`, `myStore.act { }`) is auto-tracked on first use.

> **Do not** call `store.action { }` expecting the timeline to capture it *before*
> the store is tracked — `Store.action` is a member function and always wins over
> the auto-tracking extension. Use `track(store)`, the handle's `action`, or the
> `store.act { }` verb, all of which route through the handle.

## Matchers

Timeline matchers (on a `StoreHandle`, or a raw `List<StoreEvent>`):

```kotlin
ctr shouldFire { committed() }                       // at least one commit
ctr shouldFireInOrder { started(); committed() }     // subsequence, in order
ctr shouldFireInExactOrder { /* … */ }               // exact, contiguous
ctr shouldNotFire { errored() }

// Inside the builder: emitted(prop, value) / emitted(prop), committed(),
// started(), errored(), rolledBack(), bridgePublished(prop),
// bridgeObserved(prop), middleware<M>().started/.completed/.errored
```

`emitted(prop, value)` is typed by the property's state type — `emitted(Store::count,
"nope")` fails at **compile** time rather than silently never matching.

Result matchers (on a `TransactionResult`):

```kotlin
ctr.action { … }.shouldBeSuccess()
ctr.action<Unit> { throw e }.shouldBeError()          // consumes the pending error
result.shouldRollbackWith(IllegalStateException::class)
```

State / snapshot / bridge matchers:

```kotlin
ctr shouldMatch { CounterStore::count shouldEqual 5 }
ctr shouldMatchSnapshotOf expectedStore
(a and b).shouldCommitTogether()                       // one atomic frame
view shouldHavePublished "dark"
view.shouldHavePublishedInOrder(listOf("a", "b"))
```

Empty matcher builders and never-recordable negations now **fail loudly** instead
of passing vacuously (e.g. an empty `shouldFire { }`, or a bare `middleware<M>()`
with no `.started/.completed/.errored` access). On failure, the timeline is
printed; an empty timeline lists the known causes (`Capture.None`, a
`suspendAction` recorded under `Capture.None`, or an untracked `Store.action`).

## Concurrency helpers

All run on the `storeTest` virtual-time scheduler:

```kotlin
val e = awaiting(2.seconds) { it is TransactionCommitted }  // suspend until match
eventually { assertEquals(3, ctr.read { count.value }) }    // retry until it holds
val results = parallel(n = 8) { i -> ctr.action { count update { it + 1 } } }
val gate = barrier(parties = 3)                             // rendezvous
val open = transaction(on = ctr) { count mutate 9 }         // manual open txn
{ … } shouldCompleteWithin 1.seconds
```

`awaiting` timeouts throw `AwaitingTimeoutException : AssertionError`, so a timeout
inside a launched coroutine fails the test loudly and is retryable inside
`eventually`. A forgotten `awaiting` left suspended at scope end unwinds quietly.

> **Mixing `parallel` with `awaiting`:** `parallel` workers run on real
> `Dispatchers.Default` threads while `awaiting`'s timeout burns *virtual* time —
> the scheduler jumps to expiry the moment the test thread idles, so a short
> `awaiting` racing `parallel` work is a flake by construction. Use a generous
> timeout (seconds), or poll with `eventually`.

## Pending-error teardown contract

Every `TransactionResult.Error` a handle returns is marked **pending**. Consume it
with a result matcher (`shouldBeError`, `shouldBeSuccess`, `shouldRollbackWith`) or
`handle.consumeAllPendingErrors()`. Any error left unconsumed when the `storeTest`
body returns fails the test — you cannot silently drop an observed error.

Teardown also cancels barriers, closes live `awaiting` channels, rolls back leaked
open transactions, disposes recorders, and clears the process-global
`FrameObservers` registry (so a frame observer registered in one `storeTest` never
fires in the next).

## Bridge fakes

```kotlin
RecordingBridge<T>(initial)   // records publishes; replays `initial` on attach
LatchedBridge<T>()            // records publishes; awaitPublishAttempt() gate; no replay
FailingBridge<T>(…)           // publish returns false / throws, to test failure paths
FakeKvStore                   // in-memory KvStore for KvBridge-backed tests
```

Inspect a store's attached bridge with `handle.bridge(Store::prop)`, which returns
a typed `BridgeView<T>` (no cast) exposing `published` / `lastPublished` and a
`receiving` hook to simulate inbound values. Attach bridges **before** `track(v)`
so the recorder can wrap them.

## v1 capture gaps

| Area | Captured on the timeline? |
|---|---|
| Blocking `action { }` / `mutate` | Yes |
| `suspendAction { }` | Yes — it runs the middleware chain, so the recorder sees it |
| Commit-time errors *after* the body returns | No (the result is still `Error`) |
| User-installed middleware lifecycle events | No (`middlewareEventsOf<M>()` is empty for user `M`) |
| Bridges attached *after* `track(v)` | No — attach before tracking |

See `Recorder`'s KDoc for the hook strategy behind these limits.
