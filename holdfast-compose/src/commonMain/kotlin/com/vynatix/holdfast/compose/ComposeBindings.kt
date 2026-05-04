package com.vynatix.vault.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.vynatix.vault.Disposable
import com.vynatix.vault.State
import com.vynatix.vault.Vault
import com.vynatix.vault.effect

/**
 * Bridges a vault [State] into Compose's snapshot system as a `androidx.compose.runtime.State`,
 * triggering recomposition on every successful commit that modifies this state.
 *
 * Backed by [produceState], which manages the subscription's lifecycle: the
 * vault's [State.effect] is registered when the Composable enters the composition
 * and disposed when it leaves.
 *
 * Read-only — to mutate, call `vault.action { state mutate … }` from a coroutine
 * scope (e.g. `LaunchedEffect`), an event handler, or directly from non-Composable code.
 *
 * Example:
 * ```
 * @Composable
 * fun CounterScreen(vault: CounterVault) {
 *     val count by vault.collectAsState(vault.count)
 *     Text("Count: $count")
 *     Button(onClick = { vault.action { count update { it + 1 } } }) { Text("+1") }
 * }
 * ```
 */
@Composable
fun <V : Vault<V>, T : Any> V.collectAsState(state: State<T>): androidx.compose.runtime.State<T> =
    produceState(initialValue = state.value, state) {
        val disposable = state effect { value = this }
        awaitDispose { disposable.dispose() }
    }

/**
 * Owns a [Disposable] for the lifetime of the surrounding Composable. The
 * factory [make] runs once per `key`/`vault`/etc. change; the returned
 * [Disposable] is `dispose()`d when the Composable leaves the composition or
 * the keys change.
 *
 * Use for non-state vault subscriptions you want bound to a Composable's
 * lifecycle — e.g. an [Vault.observeFrom] inbound binding.
 *
 * ```
 * @Composable
 * fun StatusListener(vault: AccountVault, channel: Observable<AccountStatus>) {
 *     rememberDisposable { vault { status observeFrom channel } }
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
