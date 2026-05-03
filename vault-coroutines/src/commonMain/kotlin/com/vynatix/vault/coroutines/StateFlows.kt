@file:OptIn(com.vynatix.vault.VaultInternalApi::class)

package com.vynatix.vault.coroutines

import com.vynatix.vault.Disposable
import com.vynatix.vault.MutableState
import com.vynatix.vault.State
import com.vynatix.vault.effect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    // Seed the replay slot with the value at subscribe time so a brand-new collector's
    // first emission is the current value (not whatever effect fires next).
    shared.tryEmit(this@asFlow.value)
    // Top-level `effect` extension: cast-internal, public surface stays State<T>.
    // The handler ignores its receiver and re-emits to the shared flow.
    val disposable: Disposable = this@asFlow.effect { shared.tryEmit(this) }
    try {
        emitAll(shared)
    } finally {
        disposable.dispose()
    }
}

/**
 * Package-internal accessor: resolves the [CoroutineScope] of the [Vault] that
 * owns this [State]. The cast to [MutableState] stays here — never in any public
 * signature. Throws if the [State] was not produced by `vault.state { … }`.
 *
 * Used as the default for [asStateFlow]'s `scope` parameter so callers can write
 * `state.asStateFlow()` and pick up the vault's scope automatically.
 */
internal val <T : Any> State<T>.owningScope: CoroutineScope
    get() {
        @Suppress("UNCHECKED_CAST")
        val mutable = (this as? MutableState<T>) ?: error("owningScope is only defined for State produced by vault.state { ... }")
        return mutable.owningVault.scope
    }

/**
 * Hot [StateFlow] adapter over a [State]. Subscribers see the current value
 * immediately, then any subsequent commits while [scope] is active. When [scope]
 * cancels, the underlying observer is disposed.
 *
 * `scope` defaults to the owning vault's [com.vynatix.vault.Vault.scope] (resolved
 * via the chain documented on `Vault.scope` — per-vault override / bound scope /
 * `Vault.defaultScope`). Pass an explicit `scope` to override; the 1.x two-arg
 * call site `state.asStateFlow(myScope, started)` continues to compile.
 *
 * `started` defaults to [SharingStarted.WhileSubscribed] so the upstream
 * subscription only exists while at least one consumer collects — matching the
 * `stateIn` convention. Pass [SharingStarted.Eagerly] for the eager-publish path
 * (replacement for the removed `asEagerStateFlow()`).
 */
fun <T : Any> State<T>.asStateFlow(
    scope: CoroutineScope = owningScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T> = asFlow().stateIn(scope, started, this.value)

/**
 * K2 context-parameter overload of [asStateFlow]. Resolves the sharing [CoroutineScope]
 * from the surrounding `context(scope: CoroutineScope) { … }` block instead of from the
 * owning vault. Lets a consumer write
 *
 * ```
 * context(viewModelScope: CoroutineScope)
 * class MyViewModel(vault: MyVault) {
 *     val flow = vault.count.asStateFlow()
 * }
 * ```
 *
 * without forwarding `viewModelScope` explicitly through every adapter call. Coexists
 * with the default-param overload — outside any `context(...)` block, the call site
 * resolves to the default-param form and picks up `vault.scope`.
 *
 * Behavior is identical to the default-param overload otherwise: the returned
 * [StateFlow] subscribes upstream while [started] permits, replays the current value
 * to late subscribers, and stops upstream when [scope] cancels.
 */
context(scope: CoroutineScope)
fun <T : Any> State<T>.asStateFlow(
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): StateFlow<T> = asFlow().stateIn(scope, started, this.value)

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
