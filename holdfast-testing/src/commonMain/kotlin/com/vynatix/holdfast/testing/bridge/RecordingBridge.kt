package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * In-memory test [Bridge] that records every outbound [publish] and exposes a
 * [simulateInbound] hook for pushing values into the attached state as if they
 * came from an external source. Intended to be the most common bridge in unit
 * tests — replaces production [com.vynatix.holdfast.bridge.KvBridge] /
 * [com.vynatix.holdfast.coroutines.flow.FlowBridge] / etc. for tests that only
 * need to assert on the publish/observe contract.
 *
 * Usage:
 * ```
 * val bridge = RecordingBridge<String>(initial = "init")
 * store { theme bridge bridge }
 * store action { theme mutate "dark" }
 *
 * bridge.published shouldBe listOf("dark")
 * bridge.lastPublished shouldBe "dark"
 *
 * // Simulate an inbound update from the external system:
 * bridge.simulateInbound("light")
 * store.read { theme.value } shouldBe "light"
 * ```
 *
 * On attach (when the store calls [observe] from `MutableState.bridge` setter),
 * this bridge replays [initial] through the observer once — matching the
 * load-on-attach convention shared by [com.vynatix.holdfast.bridge.KvBridge].
 *
 * Concurrency: every internal mutation runs under a single
 * [SynchronizedObject]. The [published] / [lastPublished] reads return a
 * defensive snapshot so iteration cannot race with concurrent commits.
 *
 * @param initial value pushed to the inbound observer on attach. Use this to
 *   pre-seed the state with a "previously persisted" value the same way a real
 *   bridge would on startup.
 */
class RecordingBridge<T : Any>(
    private val initial: T,
) : Bridge<T> {
    private val lock = SynchronizedObject()
    private val publishedList: MutableList<T> = mutableListOf()
    private var inboundObserver: ((T) -> Unit)? = null

    /**
     * Snapshot of every value passed to [publish] in call order. Returns a
     * defensive copy taken under the bridge's lock, safe to iterate after
     * return.
     */
    val published: List<T>
        get() = synchronized(lock) { publishedList.toList() }

    /**
     * The most recent [publish] argument, or `null` if [publish] has not been
     * called yet.
     */
    val lastPublished: T?
        get() = synchronized(lock) { publishedList.lastOrNull() }

    /**
     * Store-driven inbound subscription. The store's `MutableState.bridge`
     * setter calls this once on attach. We:
     *  1. Store [observer] as the inbound observer (the only reference; this
     *     bridge supports a single attach-target as bridges are 1:1 with
     *     states by contract).
     *  2. Replay [initial] through [observer] — load-on-attach convention.
     *     If the user wants to skip the replay, they can attach the bridge
     *     before tracking the store and ignore the initial emission, or pick
     *     [com.vynatix.holdfast.testing.bridge.LatchedBridge] which never replays.
     *
     * The returned [Disposable] clears the inbound observer reference; calling
     * it twice is safe (idempotent).
     */
    override fun observe(observer: (T) -> Unit): Disposable {
        synchronized(lock) {
            inboundObserver = observer
        }
        observer(initial)
        return Disposable {
            synchronized(lock) {
                if (inboundObserver === observer) {
                    inboundObserver = null
                }
            }
        }
    }

    /**
     * Append [value] to [published] and return `true`. Matches the
     * [com.vynatix.holdfast.Publisher] contract: the bridge is a passive sink
     * that always accepts the publish.
     */
    override fun publish(value: T): Boolean {
        synchronized(lock) { publishedList.add(value) }
        return true
    }

    /**
     * Synthesise an inbound update from the external system. Calls the
     * registered observer (set by the store's bridge attachment) so the state
     * is updated as if a real external source had pushed [value].
     *
     * If the bridge has not been attached yet (no [observe] call), this is a
     * silent no-op — the bridge has nothing to push to. Tests that depend on
     * the observer being attached should `track(v)` before calling this.
     */
    fun simulateInbound(value: T) {
        val observer = synchronized(lock) { inboundObserver }
        observer?.invoke(value)
    }
}
