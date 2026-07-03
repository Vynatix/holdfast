# holdfast-coroutines

`Flow` / `StateFlow` / suspend integration for the [Store](../holdfast/) library.
Pulls in `kotlinx-coroutines-core`; nothing else.

## Surface

```kotlin
fun <T : Any> State<T>.asFlow(): Flow<T>

fun <T : Any> State<T>.asStateFlow(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T>

suspend fun <T : Any> State<T>.first(predicate: (T) -> Boolean): T
suspend fun <T : Any> State<T>.awaitValue(target: T): T

suspend fun <V : Store<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R>

suspend fun <R> suspendAtomic(
    vararg stores: Store<*>,
    policy: FramePolicy = FramePolicy.Strict,
    body: suspend () -> R,
): TransactionResult<R>
```

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
    val holdfast = CounterHoldfast()
    val count: StateFlow<Int> = holdfast.count.asStateFlow(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
    )
}
```

### Await predicate

```kotlin
suspend fun waitForReady(holdfast: AccountHoldfast) {
    holdfast.status.first { it == AccountStatus.Active }
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
