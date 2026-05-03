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
 * Marker interface that extends [Bridge] with await-completion persistence semantics.
 *
 * `:vault-coroutines` 2.0 ships two ways of binding a `SuspendingKvStore` to vault state:
 *
 *  1. **Fire-and-forget** — `SuspendingKvStore.bridge(...)` returns a plain `Bridge<T>`
 *     whose `publish` schedules a save on a [Channel] with [Channel.CONFLATED] capacity
 *     and returns immediately. Failures surface only via the bridge's `errors` flow.
 *  2. **Await-completion** — `SuspendingKvStore.suspendingBridge(...)` returns a
 *     `SuspendingBridge<T>` whose `publishAwaited` suspends until the store has accepted
 *     the value. Used by `suspendAction`'s commit phase (issue 12) so persistence
 *     is part of the all-or-nothing guarantee that distinguishes vault from peer
 *     state libraries.
 *
 * **Issue 11 ships only the parent type.** The full surface — `publishAwaited` and the
 * `suspendingBridge()` factory — lands in issue 12. This file exists now so issue 12
 * can extend it without an ABI churn.
 */
interface SuspendingBridge<T : Any> : Bridge<T>

/**
 * Adapts a [SuspendingKvStore] to the sync [Bridge] contract used by `vault.action { }`.
 *
 * **Save semantics — fire-and-forget.** `Bridge.publish(value)` enqueues the encoded
 * value on a [Channel] created with [Channel.CONFLATED] capacity and returns `true`
 * immediately without suspending. A single drainer coroutine, launched on [scope],
 * pulls from the channel and calls `store.put(key, codec.encode(value))`. Because the
 * channel is conflated, rapid successive publishes coalesce — only the latest value
 * is guaranteed to reach the store. Callers who need every intermediate value must
 * use the await-completion variant (issue 12).
 *
 * **Load semantics — async on observe attach.** When the bridge is attached to a state
 * via `state bridge bridge`, the vault calls `Bridge.observe`. This implementation
 * launches a one-shot job on [scope] that reads `store.get(key)`, decodes it, and
 * pushes the value through the observer. State remains at its initializer until the
 * load completes. The returned [Disposable] cancels the load job if attach is undone
 * before the load resolves.
 *
 * **Errors.** Any throwable from `store.put`, `store.get`, or `codec.decode` is
 * forwarded to [errors]. The flow is configured `replay = 0`, `extraBufferCapacity = 16`,
 * with no implicit collection — callers MUST attach a collector if they care about
 * persistence failures. A backend that fails silently and an unobserved errors flow
 * looks identical to a healthy bridge from outside; it is the caller's responsibility
 * to observe.
 *
 * @param key Storage key under which the encoded value is persisted.
 * @param codec String-codec for [T].
 * @param scope Coroutine scope hosting the channel drainer and load job. Defaults to
 *   [Vault.defaultScope]; pass an explicit scope to bind persistence lifetime to a
 *   structured parent. Cancelling the scope stops further saves; in-flight saves
 *   complete (subject to coroutine cancellation cooperatively).
 * @return A plain `Bridge<T>` (NOT a `SuspendingBridge<T>` — the await-completion
 *   factory is `suspendingBridge(...)` in issue 12). The returned bridge also exposes
 *   an `errors: SharedFlow<Throwable>` field via [SuspendingKvBridge].
 */
fun <T : Any> SuspendingKvStore.bridge(
    key: String,
    codec: Codec<T>,
    scope: CoroutineScope = Vault.defaultScope,
): SuspendingKvBridge<T> = SuspendingKvBridge(this, key, codec, scope)

/**
 * Concrete fire-and-forget [Bridge] returned by [SuspendingKvStore.bridge]. Held as
 * a class (rather than a hidden anonymous object) so callers can reach the [errors]
 * flow without an extra cast. See [bridge] for the full contract.
 */
class SuspendingKvBridge<T : Any> internal constructor(
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
                try {
                    store.put(key, codec.encode(value))
                } catch (t: Throwable) {
                    _errors.tryEmit(t)
                }
            }
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
}
