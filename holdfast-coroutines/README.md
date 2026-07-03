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

// Async persistence: suspend KvStore + bridges over it.
interface SuspendingKvStore                          // suspend get / put / remove / snapshot
interface SuspendingBridge<T : Any> : Bridge<T>      // suspend fun publishAwaited(value: T)
fun <T : Any> SuspendingKvStore.bridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge<T>
fun <T : Any> SuspendingKvStore.suspendingBridge(key: String, codec: Codec<T>, scope: CoroutineScope = Store.defaultScope): SuspendingKvBridge.Awaiting<T>
```

`asStateFlow`'s `scope` parameter defaults to the owning store's
`Store.scope` (per-store override → `bindToScope` binding →
`Store.defaultScope`); pass a scope explicitly to override. `suspendAction`
allows the transaction body to suspend; cancellation of the body rolls the
transaction back, and the commit fanout runs under `NonCancellable` so it
completes even if the surrounding scope cancels mid-commit. `bridge(...)`
saves fire-and-forget (conflated — rapid publishes coalesce);
`suspendingBridge(...)` returns an await-completion `SuspendingBridge` whose
`publishAwaited` suspends until the value is persisted.

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
