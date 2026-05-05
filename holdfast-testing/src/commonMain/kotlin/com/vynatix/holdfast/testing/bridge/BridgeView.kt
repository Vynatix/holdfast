package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.testing.internal.RecordingBridgeWrapper

/**
 * Inspection facade for a [Bridge] attached to a tracked store. Exposes the
 * publish history and provides a [receiving] hook to push inbound values
 * through the bridge as if from the external system.
 *
 * Construct in three ways:
 *  - [com.vynatix.holdfast.testing.StoreHandle.bridge] — looks up the bridge
 *    attached to a state on the tracked store, returning a `BridgeView<*>`
 *    backed by the recorder's wrapper. Throws if no bridge is attached.
 *  - [BridgeView] (with a [RecordingBridge]) — wraps a test
 *    bridge for assertions on the publish/inbound contract.
 *  - [BridgeView] (with a [LatchedBridge]) — wraps a latched
 *    bridge so the same `published` / `receiving` API works there too.
 *
 * Usage:
 * ```
 * val bridge = RecordingBridge<String>(initial = "")
 * store { theme bridge bridge }
 * store action { theme mutate "dark" }
 *
 * val view = BridgeView(bridge)
 * view.published shouldBe listOf("dark")
 * view.lastPublished shouldBe "dark"
 *
 * view receiving "light"
 * store.read { theme.value } shouldBe "light"
 * ```
 *
 * Or, using [com.vynatix.holdfast.testing.StoreHandle.bridge]:
 * ```
 * val ctr = track(SettingsVault().also { v ->
 *     v { theme bridge RecordingBridge<String>("") }
 * })
 * ctr.action { theme mutate "dark" }
 *
 * val view = ctr.bridge(SettingsVault::theme)
 * (view.published as List<String>) shouldBe listOf("dark")
 * ```
 */
class BridgeView<T : Any> internal constructor(private val source: Source<T>) {

    /**
     * Snapshot of every value passed to [Bridge.publish] on the underlying
     * bridge in call order. Returns a defensive copy; safe to iterate after
     * return.
     */
    val published: List<T>
        get() = source.published()

    /**
     * The most recent [Bridge.publish] argument, or `null` if [Bridge.publish]
     * has not been called yet.
     */
    val lastPublished: T?
        get() = published.lastOrNull()

    /**
     * Synthesise an inbound update from the external system. Invokes the
     * inbound observer registered by the store's bridge attachment, so the
     * state on the bound store is updated as if a real external source had
     * pushed [value]. Equivalent to calling `simulateInbound` directly on a
     * [RecordingBridge] or [LatchedBridge].
     *
     * If the underlying bridge has not been attached to a store yet (no
     * [Bridge.observe] call), the call is a silent no-op — there is no
     * observer to invoke.
     */
    infix fun receiving(value: T) {
        source.simulateInbound(value)
    }

    /**
     * Sealed source adapter so [BridgeView] can wrap any of the three
     * inspectable bridge types ([RecordingBridge], [LatchedBridge], or the
     * recorder's wrapper) without committing to one concrete type.
     */
    internal sealed interface Source<T : Any> {
        fun published(): List<T>
        fun simulateInbound(value: T)
    }

    internal class RecordingSource<T : Any>(private val bridge: RecordingBridge<T>) : Source<T> {
        override fun published(): List<T> = bridge.published
        override fun simulateInbound(value: T) {
            bridge.simulateInbound(value)
        }
    }

    internal class LatchedSource<T : Any>(private val bridge: LatchedBridge<T>) : Source<T> {
        override fun published(): List<T> = bridge.published
        override fun simulateInbound(value: T) {
            bridge.simulateInbound(value)
        }
    }

    internal class WrappedSource<T : Any>(private val wrapper: RecordingBridgeWrapper<T>) : Source<T> {
        override fun published(): List<T> = wrapper.published
        override fun simulateInbound(value: T) {
            wrapper.simulateInbound(value)
        }
    }
}

/**
 * Construct a [BridgeView] backed by [bridge]. Useful for tests that want to
 * assert on a [RecordingBridge] without going through [com.vynatix.holdfast.testing.StoreHandle.bridge].
 */
@Suppress("FunctionName")
fun <T : Any> BridgeView(bridge: RecordingBridge<T>): BridgeView<T> = BridgeView(BridgeView.RecordingSource(bridge))

/**
 * Construct a [BridgeView] backed by [bridge]. Useful for tests that want to
 * assert on a [LatchedBridge] without going through [com.vynatix.holdfast.testing.StoreHandle.bridge].
 */
@Suppress("FunctionName")
fun <T : Any> BridgeView(bridge: LatchedBridge<T>): BridgeView<T> = BridgeView(BridgeView.LatchedSource(bridge))
