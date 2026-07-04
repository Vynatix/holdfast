package com.vynatix.holdfast.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import androidx.compose.runtime.State as ComposeState

/**
 * Bridges this store [State] into Compose's snapshot system as a `androidx.compose.runtime.State`,
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
 * fun CounterScreen(store: CounterStore) {
 *     val count by store.count.collectAsState()
 *     Text("Count: $count")
 *     // Inside action { } the local `count` delegate shadows the state
 *     // property, so qualify with `this.` to reach the State<Int>.
 *     Button(onClick = { store.action { this.count update { it + 1 } } }) { Text("+1") }
 * }
 * ```
 */
@Composable
fun <T : Any> State<T>.collectAsState(): ComposeState<T> =
    produceState(initialValue = value, this) {
        val disposable = this@collectAsState effect { value = this }
        awaitDispose { disposable.dispose() }
    }

/**
 * Deprecated: the [Store] receiver was never used — call [collectAsState] directly
 * on the state instead: `store.count.collectAsState()`.
 */
@Deprecated(
    message = "The Store receiver is unused; call collectAsState() directly on the state.",
    replaceWith = ReplaceWith("state.collectAsState()"),
)
@Composable
fun <V : Store<V>, T : Any> V.collectAsState(state: State<T>): ComposeState<T> = state.collectAsState()

/**
 * Owns a [Disposable] for the lifetime of the surrounding Composable. The
 * factory [make] runs when the Composable enters the composition and again
 * whenever any of [keys] changes; the returned [Disposable] is `dispose()`d
 * when the keys change or the Composable leaves the composition.
 *
 * With no [keys], [make] runs exactly once per composition entry. The [make]
 * lambda itself is **not** a key: recreating the lambda on recomposition (the
 * common case for capturing lambdas) does not re-run the factory or
 * resubscribe. To resubscribe when a captured value changes, pass it as a key:
 * `rememberDisposable(store) { ... }`.
 *
 * Use for non-state store subscriptions you want bound to a Composable's
 * lifecycle — e.g. an [Store.observeFrom] inbound binding.
 *
 * ```
 * @Composable
 * fun StatusListener(store: AccountStore, channel: Observable<AccountStatus>) {
 *     rememberDisposable(store, channel) { store { status observeFrom channel } }
 * }
 * ```
 */
@Composable
fun rememberDisposable(
    vararg keys: Any?,
    make: () -> Disposable,
): Disposable {
    val disposable = remember(*keys) { make() }
    DisposableEffect(disposable) {
        onDispose { disposable.dispose() }
    }
    return disposable
}
