# holdfast-compose

Compose Multiplatform runtime adapter for the [Store](../holdfast/) library.
Brings only `compose.runtime` (no Material, no Foundation), so it's safe to
depend on from any UI module that uses Compose.

## Surface

```kotlin
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(make: () -> Disposable): Disposable
```

## Examples

### Bind store state to a Composable

```kotlin
@Composable
fun CounterScreen(store: CounterStore) {
    val count by store.collectAsState(store.count)
    val label by store.collectAsState(store.label)

    Column {
        Text("Count: $count — $label")
        // Inside action { } the local `count` delegate shadows the state
        // property, so qualify with `this.` to reach the State<Int>.
        Button(onClick = { store.action { this.count update { it + 1 } } }) {
            Text("+1")
        }
    }
}
```

### Wire an inbound `Observable<T>` to a store state for a Composable's lifetime

```kotlin
@Composable
fun StatusListener(store: AccountStore, channel: Observable<AccountStatus>) {
    rememberDisposable { store { status observeFrom channel } }
}
```

## Build

```
./gradlew :holdfast-compose:allTests
./gradlew :holdfast-compose:apiCheck
./gradlew :holdfast-compose:dokkaGenerate
./gradlew :holdfast-compose:publishToMavenLocal
```
