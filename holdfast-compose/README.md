# holdfast-compose

Compose Multiplatform runtime adapter for the [Store](../holdfast/) library.
Brings only `compose.runtime` (no Material, no Foundation), so it's safe to
depend on from any UI module that uses Compose.

## Surface

```kotlin
@Composable
fun <T : Any> State<T>.collectAsState(): androidx.compose.runtime.State<T>

@Deprecated("The Store receiver is unused; call collectAsState() directly on the state.")
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(vararg keys: Any?, make: () -> Disposable): Disposable
```

## Examples

### Bind store state to a Composable

```kotlin
@Composable
fun CounterScreen(store: CounterStore) {
    val count by store.count.collectAsState()
    val label by store.label.collectAsState()

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
    // No keys: subscribes once on composition entry, disposes on exit.
    // The lambda is NOT a key — recomposition never resubscribes.
    rememberDisposable { store { status observeFrom channel } }

    // Keyed: resubscribes when `channel` changes.
    rememberDisposable(channel) { store { status observeFrom channel } }
}
```

## Dispose contract

Neither entry point survives `Store.dispose()`:

- Entering the composition with an **already-disposed** store throws
  `IllegalStateException` ("store disposed") — from `collectAsState`'s
  producer coroutine, or from a `rememberDisposable` factory that subscribes
  to the store (`observeFrom`, `effect`, …).
- Disposing the store **while composed** does not throw: subscriptions are
  silently shut down, so `collectAsState` values freeze at the last committed
  value and inbound bindings stop firing.

Dispose a store only after every dependent Composable has left the
composition — e.g. from a ViewModel's `onCleared`, or an `onDispose`
registered before (and therefore run after) the UI's own disposables.

## Build

```
./gradlew :holdfast-compose:allTests
./gradlew :holdfast-compose:apiCheck
./gradlew :holdfast-compose:dokkaGenerate
./gradlew :holdfast-compose:publishToMavenLocal
```
