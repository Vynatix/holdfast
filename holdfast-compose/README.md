# holdfast-compose

Compose Multiplatform runtime adapter for the [Store](../holdfast/) library.
Brings only `compose.runtime` (no Material, no Foundation), so it's safe to
depend on from any UI module that uses Compose.

## Surface

```kotlin
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T>

@Composable
fun <T : Any> State<T>.collectAsState(): androidx.compose.runtime.State<T>

@Composable
fun <T : Any> State<T>.observeAsState(): androidx.compose.runtime.State<T>

@Composable
fun rememberDisposable(make: () -> Disposable): Disposable

@Composable
fun <S : Store<S>> rememberStore(vararg keys: Any?, make: () -> S): S

@Composable
fun <E : Any> Eventful<E>.collectEvents(handler: (E) -> Unit)
```

`observeAsState` is the recommended way to read a store state from a
Composable — one mention at the use site, `val text by store.text.observeAsState()`.
It accepts any observable state: a declared state (`val x by state { … }`), a
`derived` state, core's experimental `derivedState`/`merged` states, or a
store's tree value (`App.tree.observeAsState()`, experimental: one
recomposition per settle of the tree, so a two-store frame recomposes once).
A commit that recomputes a merged value recomposes its readers once, and one
that leaves the value as it was does not recompose them — a `distinct` state
(the default) never fires its observers for an equal value, and the Compose
state itself is structural-equality policy, so equal values never recompose
at all. A `computed { }` state has no commits to observe, so `observeAsState`
(and `collectAsState`) throws on it: read its `.value` in composition, or use
`derived`/`derivedState` over its sources. `collectAsState` (on the state, or
with the store receiver) reads exactly the same states under the flow-shaped
name.

`rememberStore` owns a store for a Composable: the factory runs when the
Composable first composes (and again, disposing the old store, whenever the
keys change), and the store is disposed when the Composable leaves the
composition. The composition's lifetime is the store's lifetime — use it for
screen-local state; a store that must outlive the UI (an app-level store, a
ViewModel's) belongs to its owner and is passed in, not remembered.

`collectEvents` collects an `EventfulStore`'s events into a callback for the
Composable's lifetime, keyed on the store (a different instance restarts the
collection) and always calling the latest recomposition's callback. The
contract is the store's own `events` SharedFlow stated honestly: `replay = 0`,
so anything emitted before the composition's collector subscribes — including
events the store emitted before this Composable's first frame — is never
delivered. If an event must not be missed, don't race the UI: record it into
a state the UI observes with `observeAsState`. A store already disposed when
the Composable composes fails fast instead of silently waiting forever.

## Examples

### Bind store state to a Composable

```kotlin
@Composable
fun CounterScreen(store: CounterStore) {
    val count by store.count.observeAsState()
    val label by store.label.observeAsState()

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

### Own a screen-local store

```kotlin
@Composable
fun ProfileScreen(userId: String) {
    // The composition owns this store: made here, disposed when this screen
    // leaves — and when userId changes, the old one is disposed and a fresh
    // one is made. A shared store must NOT be remembered here.
    val store = rememberStore(userId) { ProfileStore(userId) }
    val name by store.name.observeAsState()
    Text(name)
}
```

### Collect events into a callback

```kotlin
sealed class CartEvent {
    data class Added(val item: String) : CartEvent()
    data class Failed(val why: String) : CartEvent()
}

class CartStore : EventfulStore<CartStore, CartEvent>() {
    val items by state { emptyList<String>() }
}

@Composable
fun ToastHost(store: CartStore, toasts: Toasts) {
    // Starts collecting when the composition applies; events emitted before
    // that are not replayed (replay = 0 — see the surface notes above).
    store.collectEvents { event ->
        when (event) {
            is CartEvent.Added -> toasts.show("${event.item} added")
            is CartEvent.Failed -> toasts.show(event.why)
        }
    }
}
```

## Build

```
./gradlew :holdfast-compose:allTests
./gradlew :holdfast-compose:apiCheck
./gradlew :holdfast-compose:dokkaGenerate
./gradlew :holdfast-compose:publishToMavenLocal
```
