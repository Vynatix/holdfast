package com.vynatix.vault.coroutines

import com.vynatix.vault.Disposable
import com.vynatix.vault.MutableState
import com.vynatix.vault.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * Cold [Flow] adapter over a [State]. Emits the current value once on collection,
 * then once per top-level commit that includes this state in its pending writes.
 *
 * Backed by `callbackFlow` over [State.observe]. The observer is registered on
 * subscribe and disposed when the flow's collector cancels or completes.
 *
 * Thread safety: emissions happen on whatever thread runs the commit. Collectors
 * receive values on the same thread until they switch via `flowOn`.
 */
fun <T : Any> State<T>.asFlow(): Flow<T> = callbackFlow {
    val disposable: Disposable = (this@asFlow as MutableState<T>).observe { value ->
        trySend(value)
    }
    awaitClose { disposable.dispose() }
}

/**
 * Hot [StateFlow] adapter over a [State], scoped to [scope]. Subscribers see the
 * current value immediately, then any subsequent commits while [scope] is active.
 * When [scope] cancels, the underlying observer is disposed.
 *
 * Use [SharingStarted.WhileSubscribed] (the default here) so the upstream
 * subscription only exists while at least one consumer collects — matching the
 * `stateIn` convention.
 */
fun <T : Any> State<T>.asStateFlow(scope: CoroutineScope, started: SharingStarted = SharingStarted.WhileSubscribed()): StateFlow<T> =
    asFlow().stateIn(scope, started, this.value)

/**
 * Hot, eager [StateFlow] independent of any scope. Subscribes to the state once
 * (eagerly, at construction) and forwards every commit to a [MutableStateFlow].
 * Returns a `StateFlow<T>` paired with a [Disposable] for explicit teardown.
 *
 * Use this when you don't have a [CoroutineScope] handy — typically a top-level
 * adapter for tooling. For Compose / ViewModel scopes, prefer the scope-bound
 * [asStateFlow] above.
 */
fun <T : Any> State<T>.asEagerStateFlow(): EagerStateFlow<T> {
    val flow = MutableStateFlow(this.value)
    val disposable = (this as MutableState<T>).observe { value ->
        flow.value = value
    }
    return EagerStateFlow(flow.asStateFlow(), disposable)
}

/**
 * A [StateFlow] paired with the [Disposable] that owns its upstream observer
 * subscription. Call [dispose] to detach.
 */
class EagerStateFlow<T : Any> internal constructor(val state: StateFlow<T>, private val disposable: Disposable) : Disposable {
    override fun dispose(): Unit = disposable.dispose()
}

/**
 * Suspend until [predicate] holds for the state's value. Returns the value that
 * satisfied the predicate. Useful for awaiting state-machine progress in tests
 * or in await-style application code.
 *
 * ```
 * val finished = vault.status.first { it == Status.Done }
 * ```
 */
suspend fun <T : Any> State<T>.first(predicate: (T) -> Boolean): T = asFlow().first(predicate)

/**
 * Convenience: suspend until the state equals [target]. Implemented as
 * `first { it == target }`.
 */
suspend fun <T : Any> State<T>.awaitValue(target: T): T = first { it == target }
