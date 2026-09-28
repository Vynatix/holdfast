package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.State
import com.vynatix.holdfast.testing.BridgeObserved
import com.vynatix.holdfast.testing.BridgePublished
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * Privileged wrapper installed by [com.vynatix.holdfast.testing.StoreHandle] over
 * each user-attached bridge on a tracked store. Forwards every [observe] /
 * [publish] call to the wrapped [delegate] while pushing matching
 * [BridgePublished] / [BridgeObserved] events into the handle's [Recorder].
 *
 * Also captures the publish history and inbound observer reference so a
 * [com.vynatix.holdfast.testing.bridge.BridgeView] can read past publishes and
 * synthesise inbound updates without touching the wrapped bridge — useful for
 * delegate types that don't expose their own recording (e.g. a real
 * [com.vynatix.holdfast.bridge.KvBridge]).
 *
 * Lifecycle:
 *  - The wrapper is constructed at handle install time
 *    ([com.vynatix.holdfast.testing.StoreHandle.init]); the previous bridge
 *    reference on the [com.vynatix.holdfast.MutableState] is replaced by `this`.
 *    Setting the new bridge re-runs the store's attach path
 *    ([com.vynatix.holdfast.MutableState.bridge] setter), which disposes the old
 *    inbound subscription and calls our [observe] — a single re-attach is
 *    therefore observable. Note: production code calling `bridge.observe`
 *    typically replays a load-on-attach value through the observer, so the
 *    re-attach can produce one synthetic-looking observed value. Tests that
 *    need to ignore that should attach the bridge before tracking, or check
 *    `published.size > N` rather than exact equality.
 *  - At handle dispose ([com.vynatix.holdfast.testing.StoreHandle.disposeRecorderInternal])
 *    the wrapper remains attached to the store but no further events are
 *    pushed because the recorder's buffer has been cleared. The wrapper
 *    itself is benign — it forwards to the delegate normally.
 *
 * Concurrency: every internal mutation runs under [lock]. Forwarding to the
 * delegate happens outside the lock so a long-running delegate publish doesn't
 * serialise unrelated wrapper inspection.
 *
 * Secret redaction: for a `StateTag.Secret` [state] the wrapper never keeps a
 * value. Its events carry [com.vynatix.holdfast.Redacted], and so does its
 * [published] history, one entry per publish (the delegate still gets the
 * real value: the bridge is the app's own).
 *
 * @param T value type carried by the bridge.
 * @param state the [State] this bridge is bound to, used to label the
 *   resulting [BridgePublished] / [BridgeObserved] events.
 * @param delegate the user-attached bridge being wrapped.
 * @param recorder the handle's recorder that receives the synthesised events.
 *   Held by reference; `null` would mean Capture.None and we wouldn't be
 *   wrapping in the first place.
 */
internal class RecordingBridgeWrapper<T : Any>(
    private val state: State<*>,
    private val delegate: Bridge<T>,
    private val recorder: Recorder<*>,
) : Bridge<T> {
    private val lock = SynchronizedObject()
    private val publishedList: MutableList<Any> = mutableListOf()
    private var capturedObserver: ((T) -> Unit)? = null

    /**
     * Snapshot of every published value, in call order. Defensive copy. For a
     * Secret state every entry is [com.vynatix.holdfast.Redacted] (erased
     * into `T`: only reachable through a star-projected
     * [com.vynatix.holdfast.testing.bridge.BridgeView]).
     */
    @Suppress("UNCHECKED_CAST")
    val published: List<T>
        get() = synchronized(lock) { publishedList.toList() as List<T> }

    override fun observe(observer: (T) -> Unit): Disposable {
        synchronized(lock) {
            capturedObserver = observer
        }
        return delegate.observe { value ->
            recorder.push(BridgeObserved(state = state, value = recorded(value), timestamp = nowMillis()))
            observer(value)
        }
    }

    override fun publish(value: T): Boolean {
        val recorded = recorded(value)
        synchronized(lock) {
            publishedList.add(recorded)
        }
        recorder.push(BridgePublished(state = state, value = recorded, timestamp = nowMillis()))
        // Forward to the delegate AFTER recording so the timeline ordering is
        // "we saw the publish attempt" → "delegate did its thing"; if the
        // delegate throws (e.g. FailingBridge), the BridgePublished event still
        // appears in the timeline before the throw propagates.
        return delegate.publish(value)
    }

    /**
     * Invoke the inbound observer captured during [observe] with [value]. If
     * the wrapper has not been attached yet (no [observe] call), silent no-op.
     */
    fun simulateInbound(value: T) {
        val observer = synchronized(lock) { capturedObserver }
        observer?.invoke(value)
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /** [value] as the harness may keep it: `Redacted` for a Secret state. */
    private fun recorded(value: T): Any = PrivilegedHooks.recordedValue(state, value) ?: value
}
