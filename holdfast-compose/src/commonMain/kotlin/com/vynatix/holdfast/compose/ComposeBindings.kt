package com.vynatix.holdfast.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Eventful
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import androidx.compose.runtime.State as ComposeState

/**
 * Bridges a store [State] into Compose's snapshot system as a `androidx.compose.runtime.State`,
 * triggering recomposition on every successful commit that modifies this state.
 *
 * Backed by [produceState], which manages the subscription's lifecycle: the
 * store's [State.effect] is registered when the Composable enters the composition
 * and disposed when it leaves.
 *
 * Read-only — to mutate, call `store.action { state mutate … }` from a coroutine
 * scope (e.g. `LaunchedEffect`), an event handler, or directly from non-Composable code.
 *
 * Example:
 * ```
 * @Composable
 * fun CounterScreen(store: CounterVault) {
 *     val count by store.collectAsState(store.count)
 *     Text("Count: $count")
 *     Button(onClick = { store.action { count update { it + 1 } } }) { Text("+1") }
 * }
 * ```
 */
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): ComposeState<T> = state.collectAsState()

/**
 * [collectAsState] for a [State] read without its store at hand: any
 * observable state — a declared one, a `derived`, a `derivedState`/`merged`,
 * or a store's tree (`App.tree.collectAsState()`, which recomposes once per
 * settle of the tree). The subscription's lifecycle is the same.
 */
@Composable
fun <T : Any> State<T>.collectAsState(): ComposeState<T> =
    produceState(initialValue = value, this) {
        val disposable = this@collectAsState effect { value = this }
        awaitDispose { disposable.dispose() }
    }

/**
 * Observes this store state from a Composable with **one mention at the use
 * site** — `val text by store.name.observeAsState()` — the recommended
 * single-mention spelling, named after the value-observation verb Compose
 * muscle memory already knows (`LiveData.observeAsState()`). The
 * flow-shaped name [collectAsState] reads the same states; this one exists
 * so a holdfast state reads like the value holder it is, not a flow.
 *
 * Accepts the same states as [collectAsState]: a declared one, a `derived`
 * state, core's experimental `derivedState`/`merged` states, or a store's
 * tree value (`App.tree.observeAsState()`, which recomposes once per settle
 * of the tree). A `computed { }` state has no commits to observe, so this
 * throws on it: read its `.value` in composition, or use
 * `derived`/`derivedState` over its sources.
 *
 * A commit that leaves the value equal (`==`) never recomposes this reader,
 * on two levels: a `distinct` state (the default at every declaration site)
 * does not fire its observers for an equal value at all, and the returned
 * Compose state itself uses structural-equality policy, so an equal write
 * through a `distinct = false` state still skips the invalidation.
 *
 * Disposed-store contract (inherited from the underlying `effect`
 * subscription): a state of a store that was already disposed throws
 * `IllegalStateException` ("store disposed") when the subscription starts,
 * as the composition applies; a store disposed while this Composable is
 * subscribed drops its observers silently, and the reader freezes at the
 * last value it saw — compose stores that outlive their UI, don't dispose
 * them under a live composition.
 *
 * Example:
 * ```
 * @Composable
 * fun ProfileScreen(store: ProfileStore) {
 *     val name by store.name.observeAsState()
 *     Text(name)
 * }
 * ```
 */
@Composable
fun <T : Any> State<T>.observeAsState(): ComposeState<T> = collectAsState()

/**
 * Owns a [Disposable] for the lifetime of the surrounding Composable. The
 * factory [make] runs once per `key`/`store`/etc. change; the returned
 * [Disposable] is `dispose()`d when the Composable leaves the composition or
 * the keys change.
 *
 * Use for non-state store subscriptions you want bound to a Composable's
 * lifecycle — e.g. an [Store.observeFrom] inbound binding.
 *
 * ```
 * @Composable
 * fun StatusListener(store: AccountVault, channel: Observable<AccountStatus>) {
 *     rememberDisposable { store { status observeFrom channel } }
 * }
 * ```
 */
@Composable
fun rememberDisposable(make: () -> Disposable): Disposable {
    val disposable = remember(make) { make() }
    DisposableEffect(disposable) {
        onDispose { disposable.dispose() }
    }
    return disposable
}

/**
 * Remembers a [Store] this Composable **owns**: [make] runs when the
 * Composable first composes — and again, disposing the store it made before,
 * whenever [keys] change — and the remembered store is [Store.dispose]d when
 * the Composable leaves the composition or the keys change again. The
 * ownership is the whole contract: the composition's lifetime is the store's
 * lifetime.
 *
 * Use it for screen-local state. A store that must outlive this UI (an
 * app-level store, one owned by a ViewModel or a DI container) belongs to
 * its owner and is passed into the Composable instead — remembering it here
 * would dispose the shared store when this screen leaves.
 *
 * Keyed like Compose's `remember`: a recomposition that does not change
 * [keys] reuses the remembered instance and never re-runs [make] — the
 * factory lambda itself is deliberately NOT the key (an unstable capturing
 * lambda would otherwise rebuild the store on every recomposition, the
 * churn `rememberDisposable`'s single-argument form is prone to).
 *
 * [make] must return a live store: one already disposed fails fast, because
 * nothing on it can be observed. A store disposed from outside while this
 * Composable still composes breaks the ownership contract — its readers
 * freeze at the last values they saw, like any store disposed under a live
 * composition.
 *
 * Example:
 * ```
 * @Composable
 * fun ProfileScreen(userId: String) {
 *     val store = rememberStore(userId) { ProfileStore(userId) }
 *     val name by store.name.observeAsState()
 *     Text(name)
 * }
 * ```
 */
@Composable
fun <S : Store<S>> rememberStore(
    vararg keys: Any?,
    make: () -> S,
): S {
    val store =
        remember(*keys) {
            val made = make()
            if (made.isDisposed) {
                error(
                    "rememberStore: the factory returned an already-disposed store — nothing on it " +
                        "can be observed; return the fresh store this composition owns",
                )
            }
            made
        }
    DisposableEffect(store) {
        onDispose { store.dispose() }
    }
    return store
}

/**
 * Collects an [Eventful] store's events into [handler] for the lifetime of
 * the surrounding Composable — the Compose-shaped twin of collecting
 * [Eventful.events] in a `LaunchedEffect` yourself. The subscription is
 * keyed on the store: a different store instance restarts the collection,
 * and [handler] is NOT captured at first composition — the collector always
 * calls the latest recomposition's lambda.
 *
 * Events are delivered in emission order, with the store's own lossless
 * policy: `events` is the store's `SharedFlow` (replay = 0, `SUSPEND`
 * overflow — see `EventfulStore` for the buffer knobs and what a slow
 * collector does to a suspending commit). Keep [handler] fast — it runs on
 * the composition's context; launch heavier work in your own scope.
 *
 * **No replay, and no first-event guarantee** — the honest contract of
 * `replay = 0`: collection starts when the composition applies this
 * Composable's effects, after the first successful composition, and an
 * event emitted before that moment is never delivered to this collector —
 * including anything the store emitted between its construction and this
 * Composable's first frame. If an event must not be missed, don't race the
 * UI: have the store record it into a state the UI observes with
 * [observeAsState], or collect from a scope that starts before the UI.
 *
 * A store already disposed when this Composable composes fails fast here:
 * its events flow can never emit again, so this collector would wait
 * forever. One disposed while subscribed simply stops emitting.
 *
 * Example:
 * ```
 * @Composable
 * fun ToastHost(store: CartStore) {
 *     store.collectEvents { event ->
 *         when (event) {
 *             is CartEvent.Added -> toasts.show("${event.item} added")
 *             is CartEvent.Failed -> toasts.show(event.why)
 *         }
 *     }
 * }
 * ```
 */
@Composable
fun <E : Any> Eventful<E>.collectEvents(handler: (E) -> Unit) {
    if (this is Store<*> && isDisposed) {
        error(
            "collectEvents: the store is disposed — its events flow will never emit again, and " +
                "this collector would wait forever",
        )
    }
    val currentHandler by rememberUpdatedState(handler)
    LaunchedEffect(this) {
        events.collect { currentHandler(it) }
    }
}
