package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.Codec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Bridge variant that extends [Bridge] with await-completion persistence semantics.
 *
 * **Same bridge, two semantics — picked by the action type.** When a state
 * bound to a `SuspendingBridge` is mutated inside `store.action { }` (sync),
 * the bridge's [publish] runs: fire-and-forget, never suspends, returns
 * immediately. When the same state is mutated inside `store.suspendAction { }`,
 * the commit phase detects the `SuspendingBridge` via an `is`-check and
 * **awaits** [publishAwaited] sequentially under `withContext(NonCancellable)`.
 *
 * The canonical implementation is [SuspendingKvBridge], produced by
 * [SuspendingKvStore.suspendingBridge].
 */
interface SuspendingBridge<T : Any> : Bridge<T> {
    /**
     * Persist [value] and suspend until the external store accepts it.
     * Implementations SHOULD be cooperative with cancellation up to the point
     * at which the external write is initiated; the suspendAction commit phase
     * wraps every call in `withContext(NonCancellable)` so a caller's
     * cancellation cannot abandon the write midway.
     *
     * Throwing surfaces as a failed transaction commit
     * ([com.vynatix.holdfast.TransactionResult.Error]). Implementations that
     * also publish failures on a hot `errors` flow (e.g. [SuspendingKvBridge])
     * may emit there in addition to throwing — the contract is that **either
     * the value was accepted or an error was surfaced**.
     */
    suspend fun publishAwaited(value: T)

    /**
     * Sync publish: fire-and-forget, must never suspend or block. This is the
     * path taken inside `store.action { }` (sync). The await-interpose inside
     * `store.suspendAction { }` bypasses this method entirely and calls
     * [publishAwaited] directly.
     *
     * Implementations choose their fire-and-forget mechanics — e.g.
     * [SuspendingKvBridge] uses a conflated channel + drainer to coalesce
     * rapid publishes. Return `true` when the value was accepted for
     * asynchronous persistence, `false` if the bridge has shut down.
     */
    override fun publish(value: T): Boolean
}

/**
 * Adapts a [SuspendingKvStore] to the [SuspendingBridge] contract:
 * save-on-commit + load-on-attach over a suspending key-value store.
 *
 * The returned bridge behaves per the action type that commits the mutation:
 * awaited persistence under `store.suspendAction { }` (via
 * [SuspendingBridge.publishAwaited]), conflated fire-and-forget under sync
 * `store.action { }` (via [Bridge.publish]). See [SuspendingKvBridge] for the
 * full save/load/error contract.
 *
 * @param key Storage key under which the encoded value is persisted.
 * @param codec String-codec for [T].
 * @param scope Coroutine scope hosting the channel drainer and load jobs.
 *   Defaults to [Store.defaultScope].
 */
fun <T : Any> SuspendingKvStore.suspendingBridge(
    key: String,
    codec: Codec<T>,
    scope: CoroutineScope = Store.defaultScope,
): SuspendingKvBridge<T> = SuspendingKvBridge(this, key, codec, scope)

/**
 * Deprecated alias of [suspendingBridge].
 *
 * Historically this factory returned a fire-and-forget-only bridge (a plain
 * `Bridge<T>` that `suspendAction` could not await) while [suspendingBridge]
 * returned the await-completion variant. The two products are now the same
 * class with the same behavior — [SuspendingKvBridge] implements
 * [SuspendingBridge], so `suspendAction` awaits its writes and sync `action`
 * keeps the conflated fire-and-forget path. Call [suspendingBridge] directly.
 */
@Deprecated(
    message =
        "bridge() and suspendingBridge() now produce the same SuspendingKvBridge " +
            "(awaited under suspendAction, conflated fire-and-forget under sync action). " +
            "Use suspendingBridge().",
    replaceWith = ReplaceWith("this.suspendingBridge(key, codec, scope)"),
    level = DeprecationLevel.WARNING,
)
fun <T : Any> SuspendingKvStore.bridge(
    key: String,
    codec: Codec<T>,
    scope: CoroutineScope = Store.defaultScope,
): SuspendingKvBridge<T> = SuspendingKvBridge(this, key, codec, scope)

/**
 * [SuspendingBridge] over a [SuspendingKvStore], produced by
 * [SuspendingKvStore.suspendingBridge].
 *
 * **Save semantics under `suspendAction` — awaited.** The commit phase calls
 * [publishAwaited], which encodes and `store.put`s the value directly and
 * suspends until the store accepts it. No conflation applies on this path —
 * every committed value is written, in commit order.
 *
 * **Save semantics under sync `action` — fire-and-forget, conflated.**
 * [Bridge.publish] enqueues the value on a [Channel] created with
 * [Channel.CONFLATED] capacity and returns `true` immediately without
 * suspending. A single drainer coroutine, launched on [scope], pulls from the
 * channel and calls `store.put(key, codec.encode(value))`. Because the channel
 * is conflated, rapid successive publishes coalesce — only the latest value is
 * guaranteed to reach the store. Callers who need every intermediate value
 * persisted must commit via `suspendAction`.
 *
 * **Load semantics — async on observe attach.** When the bridge is attached
 * to a state via `state bridge bridge`, the store calls [Bridge.observe].
 * This implementation launches a one-shot job on [scope] that reads
 * `store.get(key)`, decodes it, and pushes the value through the observer.
 * State remains at its initializer until the load completes. The returned
 * [Disposable] cancels the load job if attach is undone before the load
 * resolves.
 *
 * **Errors.** Any throwable from `store.put`, `store.get`, or `codec.decode`
 * is forwarded to [errors]. The flow is configured `replay = 0`,
 * `extraBufferCapacity = 16`, with no implicit collection — callers MUST
 * attach a collector if they care about persistence failures on the
 * fire-and-forget path.
 */
class SuspendingKvBridge<T : Any> internal constructor(
    private val store: SuspendingKvStore,
    private val key: String,
    private val codec: Codec<T>,
    private val scope: CoroutineScope,
) : SuspendingBridge<T> {
    private val saves = Channel<T>(Channel.CONFLATED)

    private val _errors = MutableSharedFlow<Throwable>(replay = 0, extraBufferCapacity = 16)

    /**
     * Hot stream of throwables observed by this bridge — `store.put` failures,
     * `store.get` failures, and decode failures during load-on-attach.
     *
     * `replay = 0`, `extraBufferCapacity = 16`, no implicit collection. **Callers
     * MUST attach their own collector** if they care about persistence reliability —
     * an unobserved errors flow swallows fire-and-forget failures silently. Use
     * `bridge.errors.onEach { … }.launchIn(appScope)` or similar.
     */
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    init {
        // One drainer per bridge: pulls conflated values and persists them.
        // Conflation is structural (Channel.CONFLATED); we do not rate-limit here.
        scope.launch {
            for (value in saves) {
                putOrEmit(value)
            }
        }
    }

    /**
     * Encode + put + emit on error, used by the channel drainer
     * (fire-and-forget). Errors are surfaced to [errors] only — rethrowing
     * inside the drainer's `scope.launch` would crash the scope's uncaught
     * handler instead of reporting.
     */
    private suspend fun putOrEmit(value: T) {
        try {
            store.put(key, codec.encode(value))
        } catch (t: Throwable) {
            _errors.tryEmit(t)
        }
    }

    /**
     * Persists [value] sequentially: encode + `store.put`, suspending until
     * the store accepts it. Errors surface on [errors] (same as the
     * fire-and-forget path); the call itself returns normally.
     *
     * Wrapped in `withContext(NonCancellable)` by the calling commit phase
     * so cancellation cannot leave the store in an inconsistent state.
     */
    override suspend fun publishAwaited(value: T) {
        putOrEmit(value)
    }

    override fun observe(observer: (T) -> Unit): Disposable {
        // Async load-on-attach: read the persisted value (if any) on `scope` and push
        // it to the observer when it arrives. State remains at its initializer until
        // then — the suspending nature of the store is preserved.
        val job =
            scope.launch {
                val encoded =
                    try {
                        store.get(key)
                    } catch (t: Throwable) {
                        _errors.tryEmit(t)
                        null
                    } ?: return@launch
                val decoded =
                    try {
                        codec.decode(encoded)
                    } catch (t: Throwable) {
                        _errors.tryEmit(t)
                        return@launch
                    }
                observer(decoded)
            }
        return Disposable { job.cancel() }
    }

    override fun publish(value: T): Boolean {
        // Conflated channel: trySend always succeeds for CONFLATED unless closed.
        // We swallow the result intentionally — fire-and-forget is the contract.
        saves.trySend(value)
        return true
    }
}
