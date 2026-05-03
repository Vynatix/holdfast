package com.vynatix.vault.coroutines

import com.vynatix.vault.Bridge
import com.vynatix.vault.Disposable
import com.vynatix.vault.Vault
import com.vynatix.vault.bridge.Codec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Bridge variant that extends [Bridge] with await-completion persistence semantics.
 *
 * `:vault-coroutines` 2.0 ships two ways of binding a `SuspendingKvStore` to vault state:
 *
 *  1. **Fire-and-forget** — [SuspendingKvStore.bridge] returns a plain `Bridge<T>`
 *     whose [Bridge.publish] schedules a save on a [Channel] with [Channel.CONFLATED]
 *     capacity and returns immediately. Failures surface only via the bridge's
 *     `errors` flow.
 *  2. **Await-completion** — [SuspendingKvStore.suspendingBridge] returns a
 *     `SuspendingBridge<T>` whose [publishAwaited] suspends until the store has
 *     accepted the value. Used by `suspendAction`'s commit phase so persistence
 *     is part of the all-or-nothing guarantee that distinguishes vault from peer
 *     state libraries.
 *
 * **Same bridge, two semantics.** When attached to a state and that state is
 * mutated inside `vault.action { }` (sync), the bridge's [publish] runs — by
 * default this launches a coroutine on the bridge's scope and calls
 * [publishAwaited] fire-and-forget, returning `true` immediately. When the same
 * state is mutated inside `vault.suspendAction { }`, the commit phase detects
 * the `SuspendingBridge` via an `is`-check and **awaits** [publishAwaited]
 * sequentially under `withContext(NonCancellable)`. Caller picks the action
 * type to pick the persistence guarantee.
 */
interface SuspendingBridge<T : Any> : Bridge<T> {
    /**
     * Persist [value] and suspend until the store accepts it. Implementations
     * SHOULD be cooperative with cancellation up to the point at which the
     * external write is initiated; the suspendAction commit phase wraps every
     * call in `withContext(NonCancellable)` so partial-commit cannot happen.
     *
     * Throwing surfaces as a failed transaction commit. Implementations that
     * also publish failures on a hot `errors` flow (e.g. [SuspendingKvBridge])
     * may suppress the throw and emit on the flow instead — the contract is
     * that **either the value was accepted or an error was surfaced**.
     */
    suspend fun publishAwaited(value: T)

    /**
     * Default sync publish: launches a coroutine on the bridge's scope that
     * calls [publishAwaited] fire-and-forget and always returns `true`. This
     * is the path taken inside `vault.action { }` (sync). The await-interpose
     * inside `vault.suspendAction { }` bypasses this method entirely and
     * calls [publishAwaited] directly.
     *
     * Implementations may override to provide different fire-and-forget
     * behavior — e.g. [SuspendingKvBridge] uses a conflated channel + drainer
     * to coalesce rapid publishes. The contract is unchanged: never suspend,
     * never block, always return `true` (or `false` if shutdown — the vault
     * does not act on the boolean today).
     */
    override fun publish(value: T): Boolean
}

/**
 * Adapts a [SuspendingKvStore] to the **await-completion** [SuspendingBridge]
 * contract used by `vault.suspendAction { }`.
 *
 * Same store, same key, same codec as [SuspendingKvStore.bridge] — the
 * difference is purely binding semantics. When this bridge is attached to a
 * state and the state mutates inside a `suspendAction`, the action awaits the
 * persistence write before returning. Inside a sync `action`, the bridge
 * falls back to fire-and-forget by launching a coroutine on [scope] that
 * calls [publishAwaited]; the action returns immediately.
 *
 * Load semantics, conflated drainer for fire-and-forget calls coming through
 * [publish], and the `errors: SharedFlow<Throwable>` flow are identical to
 * [SuspendingKvBridge] — see that class's KDoc for the full contract.
 *
 * @param key Storage key under which the encoded value is persisted.
 * @param codec String-codec for [T].
 * @param scope Coroutine scope hosting the channel drainer, the load job, and
 *   the fire-and-forget [publish] launches. Defaults to [Vault.defaultScope].
 * @return A `SuspendingKvBridge.Awaiting<T>` whose [publishAwaited] is wired
 *   to `store.put(key, codec.encode(value))`. The same `errors` flow as the
 *   plain bridge surfaces serialization and store failures.
 */
fun <T : Any> SuspendingKvStore.suspendingBridge(
    key: String,
    codec: Codec<T>,
    scope: CoroutineScope = Vault.defaultScope,
): SuspendingKvBridge.Awaiting<T> = SuspendingKvBridge.Awaiting(this, key, codec, scope)

/**
 * Adapts a [SuspendingKvStore] to the sync [Bridge] contract used by `vault.action { }`.
 *
 * **Save semantics — fire-and-forget.** [Bridge.publish] enqueues the encoded
 * value on a [Channel] created with [Channel.CONFLATED] capacity and returns
 * `true` immediately without suspending. A single drainer coroutine, launched
 * on [scope], pulls from the channel and calls
 * `store.put(key, codec.encode(value))`. Because the channel is conflated,
 * rapid successive publishes coalesce — only the latest value is guaranteed
 * to reach the store. Callers who need every intermediate value must use the
 * await-completion variant ([suspendingBridge]).
 *
 * **Load semantics — async on observe attach.** When the bridge is attached
 * to a state via `state bridge bridge`, the vault calls [Bridge.observe].
 * This implementation launches a one-shot job on [scope] that reads
 * `store.get(key)`, decodes it, and pushes the value through the observer.
 * State remains at its initializer until the load completes. The returned
 * [Disposable] cancels the load job if attach is undone before the load
 * resolves.
 *
 * **Errors.** Any throwable from `store.put`, `store.get`, or `codec.decode`
 * is forwarded to [SuspendingKvBridge.errors]. The flow is configured
 * `replay = 0`, `extraBufferCapacity = 16`, with no implicit collection —
 * callers MUST attach a collector if they care about persistence failures.
 *
 * @param key Storage key under which the encoded value is persisted.
 * @param codec String-codec for [T].
 * @param scope Coroutine scope hosting the channel drainer and load job.
 *   Defaults to [Vault.defaultScope].
 * @return A plain `Bridge<T>` (NOT a `SuspendingBridge<T>` — the
 *   await-completion factory is [suspendingBridge]). The returned bridge also
 *   exposes an `errors: SharedFlow<Throwable>` field via [SuspendingKvBridge].
 */
fun <T : Any> SuspendingKvStore.bridge(
    key: String,
    codec: Codec<T>,
    scope: CoroutineScope = Vault.defaultScope,
): SuspendingKvBridge<T> = SuspendingKvBridge(this, key, codec, scope)

/**
 * Concrete fire-and-forget [Bridge] returned by [SuspendingKvStore.bridge].
 * Held as a class (rather than a hidden anonymous object) so callers can
 * reach the [errors] flow without an extra cast. See [bridge] for the full
 * contract.
 *
 * The [Awaiting] nested class is the await-completion sibling returned by
 * [SuspendingKvStore.suspendingBridge]. It implements the same load-on-attach
 * and conflated-fire-and-forget machinery and additionally implements
 * [SuspendingBridge.publishAwaited] for use by the suspendAction commit
 * interpose.
 */
open class SuspendingKvBridge<T : Any> internal constructor(
    private val store: SuspendingKvStore,
    private val key: String,
    private val codec: Codec<T>,
    private val scope: CoroutineScope,
) : Bridge<T> {

    private val saves = Channel<T>(Channel.CONFLATED)

    private val _errors = MutableSharedFlow<Throwable>(replay = 0, extraBufferCapacity = 16)

    /**
     * Hot stream of throwables observed by this bridge — `store.put` failures,
     * `store.get` failures, and decode failures during load-on-attach.
     *
     * `replay = 0`, `extraBufferCapacity = 16`, no implicit collection. **Callers
     * MUST attach their own collector** if they care about persistence reliability —
     * an unobserved errors flow swallows failures silently. Use
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
     * Encode + put + emit on error. Shared between the channel drainer
     * (fire-and-forget) and [Awaiting.publishAwaited] (await-completion).
     * Errors are surfaced to [errors] in both cases — the await-completion
     * caller does not need to attach the errors flow if it tolerates
     * silent failures, but the recommended pattern is to attach.
     */
    protected suspend fun putOrEmit(value: T) {
        try {
            store.put(key, codec.encode(value))
        } catch (t: Throwable) {
            _errors.tryEmit(t)
        }
    }

    override fun observe(observer: (T) -> Unit): Disposable {
        // Async load-on-attach: read the persisted value (if any) on `scope` and push
        // it to the observer when it arrives. State remains at its initializer until
        // then — the suspending nature of the store is preserved.
        val job = scope.launch {
            val encoded = try {
                store.get(key)
            } catch (t: Throwable) {
                _errors.tryEmit(t)
                null
            } ?: return@launch
            val decoded = try {
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

    /**
     * Internal access to [scope] for the [Awaiting] subclass — needed so the
     * default [Bridge.publish] override can launch a fire-and-forget call to
     * [SuspendingBridge.publishAwaited] on the same scope as the drainer.
     */
    protected val bridgeScope: CoroutineScope get() = scope

    /**
     * Await-completion sibling of [SuspendingKvBridge]. Inherits the load
     * machinery, the conflated drainer (used by [Bridge.publish] under sync
     * `action`), and the `errors` flow. Adds [publishAwaited] for the
     * `suspendAction` commit interpose.
     *
     * The class is `class` (not `object`) and not exposed as a constructable
     * type — instances are produced exclusively by [suspendingBridge].
     */
    class Awaiting<T : Any> internal constructor(
        store: SuspendingKvStore,
        key: String,
        codec: Codec<T>,
        scope: CoroutineScope,
    ) : SuspendingKvBridge<T>(store, key, codec, scope), SuspendingBridge<T> {

        /**
         * Persists [value] sequentially: encode + `store.put`. Errors surface
         * on [errors] (same as the fire-and-forget path) AND the call returns
         * normally — the suspendAction commit phase does not crash on
         * persistence failure today; it relies on the errors-flow contract.
         *
         * Wrapped in `withContext(NonCancellable)` by the calling commit phase
         * so cancellation cannot leave the store in an inconsistent state.
         */
        override suspend fun publishAwaited(value: T) {
            putOrEmit(value)
        }

        /**
         * Fire-and-forget under sync `vault.action { }` — launches a coroutine
         * on the bridge's scope that calls [publishAwaited]. Returns `true`
         * immediately. Distinct from the parent's channel-drainer path (which
         * coalesces rapid publishes via [Channel.CONFLATED]); we use a direct
         * launch here because the await-completion bridge's primary path is
         * already serial under `suspendAction` — the sync fallback need not
         * conflate to be sound. Callers that want conflation under sync
         * action should use [SuspendingKvStore.bridge] instead.
         */
        override fun publish(value: T): Boolean {
            bridgeScope.launch { publishAwaited(value) }
            return true
        }
    }
}
