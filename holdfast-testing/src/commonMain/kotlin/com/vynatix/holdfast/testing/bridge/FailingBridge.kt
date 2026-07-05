package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable

/**
 * Test [Bridge] that throws on attach ([observe]), publish, or both. Use to
 * verify that the store and its surrounding code handle external-sync failures
 * — typically commit-time errors when a downstream system rejects the write.
 *
 * Usage:
 * ```
 * val ex = IllegalStateException("kv unreachable")
 * val bridge = FailingBridge<String>(initial = "init", failOn = FailureMode.Publish, cause = ex)
 * val captured = mutableListOf<Throwable>()
 * val ctr = track(SettingsVault().also { v ->
 *     v.uncaughtObserverHandler = { captured.add(it) }
 *     v { theme bridge bridge }
 * })
 *
 * // A sync bridge publish is fire-and-forget: the commit SUCCEEDS and the
 * // publish failure is routed to uncaughtObserverHandler.
 * val result = ctr.action { theme mutate "dark" }
 * result.shouldBeSuccess()
 * ```
 *
 * Failure semantics:
 *  - [FailureMode.Publish]: [observe] succeeds (replays [initial] through the
 *    observer); [publish] always throws [cause].
 *  - [FailureMode.Observe]: [observe] throws [cause] **before** invoking the
 *    observer, surfacing the failure at attach time. [publish] succeeds.
 *  - [FailureMode.Both]: both [observe] and [publish] throw [cause].
 *
 * Note: the `MutableState.bridge` setter calls [observe] synchronously during
 * attach, so [FailureMode.Observe] / [Both] propagates out of the setter call
 * site (`store { state bridge bridge }`). Wrap that line in `assertFailsWith`
 * to assert on the attach-time failure.
 *
 * A commit-phase [publish] throw is fire-and-forget: the store's commit path
 * catches it and routes it to the store's `uncaughtObserverHandler`, and the
 * action still returns [com.vynatix.holdfast.TransactionResult.Success]. Assert
 * on the failure by capturing it in a handler (see the usage example), not via a
 * rollback matcher. [FailureMode.Observe] / [FailureMode.Both] still propagate at
 * attach time out of the `state bridge bridge` setter call.
 */
class FailingBridge<T : Any>(
    private val initial: T,
    val failOn: FailureMode,
    val cause: Throwable = RuntimeException("FailingBridge"),
) : Bridge<T> {
    /**
     * Selects which methods of [FailingBridge] throw [cause].
     *
     *  - [Publish] — only [Bridge.publish] throws; attach succeeds.
     *  - [Observe] — only [com.vynatix.holdfast.Observable.observe] throws; publish succeeds.
     *  - [Both] — every public method throws.
     */
    enum class FailureMode {
        /** Throw from [publish]. [observe] succeeds and replays [initial]. */
        Publish,

        /** Throw from [observe]. [publish] succeeds. */
        Observe,

        /** Throw from both [observe] and [publish]. */
        Both,
    }

    override fun observe(observer: (T) -> Unit): Disposable {
        if (failOn == FailureMode.Observe || failOn == FailureMode.Both) {
            throw cause
        }
        observer(initial)
        return Disposable { /* no-op: nothing to detach */ }
    }

    override fun publish(value: T): Boolean {
        if (failOn == FailureMode.Publish || failOn == FailureMode.Both) {
            throw cause
        }
        return true
    }
}
