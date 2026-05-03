# vault-coroutines

`Flow` / `StateFlow` / suspend integration for the [Vault](../vault/) library.
Pulls in `kotlinx-coroutines-core`; nothing else.

## Surface

```kotlin
fun <T : Any> State<T>.asFlow(): Flow<T>

fun <T : Any> State<T>.asStateFlow(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T>

fun <T : Any> State<T>.asEagerStateFlow(): EagerStateFlow<T>

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
    val vault = CounterVault()
    val count: StateFlow<Int> = vault.count.asStateFlow(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
    )
}
```

### Await predicate

```kotlin
suspend fun waitForReady(vault: AccountVault) {
    vault.status.first { it == AccountStatus.Active }
}
```

### Eager StateFlow for ad-hoc use

```kotlin
val eager = vault.balance.asEagerStateFlow()
println(eager.state.value) // current value
// later:
eager.dispose()
```

## Build

```
./gradlew :vault-coroutines:allTests
./gradlew :vault-coroutines:apiCheck
./gradlew :vault-coroutines:dokkaHtml
./gradlew :vault-coroutines:publishToMavenLocal
```
