# holdfast-coroutines

`Flow` / `StateFlow` / suspend integration for the [Store](../holdfast/) library.
Pulls in `kotlinx-coroutines-core`; nothing else.

## Surface

```kotlin
fun <T : Any> State<T>.asFlow(): Flow<T>

fun <T : Any> State<T>.asStateFlow(
    scope: CoroutineScope = …,   // defaults to the owning store's Store.scope
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T>
// Eager publishing: asStateFlow(started = SharingStarted.Eagerly).

suspend fun <T : Any> State<T>.first(predicate: (T) -> Boolean): T
suspend fun <T : Any> State<T>.awaitValue(target: T): T

// Suspending transactions — bodies may delay/await/withContext. Mutually
// exclusive with blocking action/atomic on the same store(s).
suspend fun <V : Store<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R>
suspend fun <R> suspendAtomic(
    vararg stores: Store<*>,
    policy: FramePolicy = FramePolicy.Strict,
    body: suspend () -> R,
): TransactionResult<R>

// Push-recomputed derived state with a suspending compute.
fun <V : Store<V>, T : Any> V.suspendDerived(
    vararg sources: State<*>,
    compute: suspend V.() -> T,
): Pair<State<T>, Disposable>

// Async persistence: suspend KvStore + a bridge over it.
interface SuspendingKvStore                          // suspend get / put / remove / snapshot
interface SuspendingBridge<T : Any> : Bridge<T>      // suspend fun publishAwaited(value: T)
class SuspendingKvBridge<T : Any> : SuspendingBridge<T>, Disposable  // errors: SharedFlow<Throwable>; dispose()
fun <T : Any> SuspendingKvStore.suspendingBridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge<T>
// Deprecated WARNING-level alias returning the same type: SuspendingKvStore.bridge(key, codec, scope)
```

`asStateFlow`'s `scope` parameter defaults to the owning store's
`Store.scope` (per-store override → `bindToScope` binding →
`Store.defaultScope`); pass a scope explicitly to override. `suspendAction`
allows the transaction body to suspend; cancellation of the body rolls the
transaction back, and the commit fanout runs under `NonCancellable` so it
completes even if the surrounding scope cancels mid-commit.
`suspendingBridge(...)` returns a `SuspendingKvBridge` — one bridge, two save
semantics picked by the action type: inside `suspendAction` the commit phase
awaits `publishAwaited`, so every committed value is written before the action
returns; inside sync `action { }` it saves fire-and-forget through a conflated
channel (rapid publishes coalesce — only the latest value is guaranteed to
land). The old `bridge(...)` factory is a WARNING-level deprecated alias that
returns the same type.

The awaited path's failure contract is **ordering plus a surfaced error, not
rollback**: when `store.put` throws, the in-memory commit has already applied
and observers have already fired — the `suspendAction` returns
`TransactionResult.Error` naming the persistence exception, and the same
throwable is emitted on the bridge's `errors: SharedFlow<Throwable>`.
Fire-and-forget failures (sync `action { }`) surface only on `errors`; attach
a collector if you care about persistence reliability.

`SuspendingKvBridge` owns a long-lived drainer coroutine, so it is
`Disposable`. Detaching with `state bridge null` releases only the inbound
subscription; call `dispose()` to shut the bridge down — it closes the save
channel (the last conflated value still drains), cancels in-flight loads, and
after that `publish` returns `false` and `observe` is a no-op. `dispose()` is
idempotent.

`suspendDerived` has two overloads. Prefer the **`initial`-seeded** one: it
holds `initial` until the first async `compute` lands and uses no `runBlocking`,
so it runs on every target including wasmJs. The **seedless** overload seeds
eagerly with `runBlocking` (computed value at construction), but that crashes on
wasmJs and can deadlock on single-threaded dispatchers. Disposing either handle
stops recomputes and unregisters the synthetic backing state.

## Examples

### Cold Flow over a state

```kotlin
viewModelScope.launch {
    holdfast.count.asFlow().collect { value ->
        log("count = $value")
    }
}
```

### Hot StateFlow scoped to a ViewModel

```kotlin
class CounterViewModel : ViewModel() {
    val holdfast = CounterStore()
    val count: StateFlow<Int> = holdfast.count.asStateFlow(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
    )
}
```

### Await predicate

```kotlin
suspend fun waitForReady(store: AccountStore) {
    store.status.first { it == AccountStatus.Active }
}
```

### Suspending transaction

```kotlin
suspend fun refresh(store: CounterStore, api: Api) {
    val result = store.suspendAction {
        val delta = api.fetchDelta()      // suspending I/O inside the transaction
        count update { it + delta }
    }
    result.onError { log("refresh rolled back: ${it.exception}") }
}
```

### Cross-store frame with a suspending body

```kotlin
val r = suspendAtomic(accountA, accountB) {
    accountA { balance update { it - amount } }   // stages into A's frame root
    accountB.suspendAction {                      // joins the frame as a savepoint
        balance update { it + amount }
    }
}
```

Same contract as core `atomic` (see the
[GUIDE's cross-store chapter](../holdfast/GUIDE.md#15-cross-store-transactions)):
enrollment is enforced, inner errors abort the frame, and blocking
`action { }` on a participant fails fast with `FrameInteropException`
instead of deadlocking. Commit runs under `NonCancellable` with suspending
bridge publishes awaited and event back-pressure honored.

## Build

```
./gradlew :holdfast-coroutines:allTests
./gradlew :holdfast-coroutines:apiCheck
./gradlew :holdfast-coroutines:dokkaGenerate
./gradlew :holdfast-coroutines:publishToMavenLocal
```
