# holdfast-compose

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

### Bind holdfast state to a Composable

```kotlin
@Composable
fun CounterScreen(holdfast: CounterHoldfast) {
    val count by holdfast.collectAsState(holdfast.count)
    val label by holdfast.collectAsState(holdfast.label)

    Column {
        Text("Count: $count — $label")
        Button(onClick = { holdfast.action { count update { it + 1 } } }) {
            Text("+1")
        }
    }
}
```

### Wire an inbound `Observable<T>` to a holdfast state for a Composable's lifetime

```kotlin
@Composable
fun StatusListener(holdfast: AccountHoldfast, channel: Observable<AccountStatus>) {
    rememberDisposable { holdfast { status observeFrom channel } }
}
```

## Build

```
./gradlew :holdfast-compose:allTests
./gradlew :holdfast-compose:apiCheck
./gradlew :holdfast-compose:dokkaHtml
./gradlew :holdfast-compose:publishToMavenLocal
```
