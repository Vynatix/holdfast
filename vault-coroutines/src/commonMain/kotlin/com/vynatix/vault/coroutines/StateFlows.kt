package com.vynatix.vault.coroutines

import com.vynatix.vault.Disposable
import com.vynatix.vault.MutableState
import com.vynatix.vault.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Cold [Flow] adapter over a [State] with **lossless-conflated** delivery: the latest
 * value is always recoverable (replay slot of size 1), the producer never blocks, and
 * intermediate values may be conflated under fast-emit / slow-collect.
 *
 * Backed by `MutableSharedFlow(replay=1, extraBufferCapacity=0,
 * onBufferOverflow=DROP_OLDEST)`. Producer-side `tryEmit` always succeeds — the replay
 * slot is atomically overwritten — so the commit thread is never back-pressured by a
 * slow subscriber. A late subscriber sees the value at subscribe time via the replay
 * slot, then every subsequent commit (subject to conflation under contention).
 *
 * **Behavior change vs. 1.x.** The 1.x adapter used `callbackFlow { trySend }` with
 * default `BUFFERED` capacity (64). Under fast-emit / slow-collect, `trySend` returned
 * `false` past 64 backlog and values silently dropped — including, potentially, the
 * latest. The 2.0 contract is "you may miss intermediate values; you will always see
 * the latest." For lossless event delivery, use a vault's `events: SharedFlow<E>`
 * (issue 14) rather than state subscriptions.
 *
 * Thread safety: emissions happen on whatever thread runs the commit. Collectors
 * receive values on the same thread until they switch via `flowOn`.
 */
fun <T : Any> State<T>.asFlow(): Flow<T> = flow {
    val shared = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @Suppress("UNCHECKED_CAST")
    val mutable = this@asFlow as MutableState<T>
    // Seed the replay slot with the value at subscribe time so a brand-new collector's
    // first emission is the current value (not whatever observe fires next).
    shared.tryEmit(mutable.value)
    val disposable: Disposable = mutable.observe { value -> shared.tryEmit(value) }
    try {
        emitAll(shared)
    } finally {
        disposable.dispose()
    }
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
