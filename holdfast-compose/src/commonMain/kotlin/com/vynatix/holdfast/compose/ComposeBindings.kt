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
