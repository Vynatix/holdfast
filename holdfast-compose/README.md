# vault-compose

Compose Multiplatform runtime adapter for the [Holdfast](../holdfast/) library.
Brings only `compose.runtime` (no Material, no Foundation), so it's safe to
depend on from any UI module that uses Compose.

## Surface

```kotlin
@Composable
fun <V : Holdfast<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(make: () -> Disposable): Disposable
```

## Examples

### Bind vault state to a Composable

```kotlin
@Composable
fun CounterScreen(vault: CounterHoldfast) {
    val count by vault.collectAsState(vault.count)
    val label by vault.collectAsState(vault.label)

    Column {
        Text("Count: $count — $label")
        Button(onClick = { vault.action { count update { it + 1 } } }) {
            Text("+1")
        }
    }
}
```

### Wire an inbound `Observable<T>` to a vault state for a Composable's lifetime

```kotlin
@Composable
fun StatusListener(vault: AccountHoldfast, channel: Observable<AccountStatus>) {
    rememberDisposable { vault { status observeFrom channel } }
}
```

## Build

```
./gradlew :holdfast-compose:allTests
./gradlew :holdfast-compose:apiCheck
./gradlew :holdfast-compose:dokkaHtml
./gradlew :holdfast-compose:publishToMavenLocal
```
