# vault-coroutines

`Flow` / `StateFlow` / suspend integration for the [Holdfast](../holdfast/) library.
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
```

## Examples

### Cold Flow over a state

```kotlin
viewModelScope.launch {
    vault.count.asFlow().collect { value ->
        log("count = $value")
    }
}
```

### Hot StateFlow scoped to a ViewModel

```kotlin
class CounterViewModel : ViewModel() {
    val vault = CounterHoldfast()
    val count: StateFlow<Int> = vault.count.asStateFlow(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
    )
}
```

### Await predicate

```kotlin
suspend fun waitForReady(vault: AccountHoldfast) {
    vault.status.first { it == AccountStatus.Active }
}
```

## Build

```
./gradlew :holdfast-coroutines:allTests
./gradlew :holdfast-coroutines:apiCheck
./gradlew :holdfast-coroutines:dokkaHtml
./gradlew :holdfast-coroutines:publishToMavenLocal
```
