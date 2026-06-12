package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.testing.StoreHandle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Process-wide map from a [TransactionResult.Error] to the [StoreHandle] that
 * produced it. Used by the result matchers (`shouldBeError`, `shouldBeSuccess`,
 * `shouldRollbackWith`) to clear the corresponding handle's pending-error mark
 * when a result is asserted on, without threading the handle through every
 * matcher signature.
 *
 * Thread-safety: guarded by a [SynchronizedObject]. The registry can be touched
 * concurrently if a test uses `parallel { ... }` or otherwise drives `action` /
 * `suspendAction` from multiple coroutines.
 *
 * Lifetime caveat: this is a singleton, shared across all [storeTest][com.vynatix.holdfast.testing.storeTest]
 * blocks running in the same process. Two concurrent test scopes do not collide
 * because each [TransactionResult.Error] instance is unique by identity, so a
 * matcher call from scope A finds and clears only scope A's entry. The
 * [com.vynatix.holdfast.testing.StoreTestScope.tearDown] hook calls [unregisterAll]
 * for every handle in its registry, so leaked entries from a prematurely
 * aborted test do not accumulate indefinitely.
 */
@PublishedApi
internal object PendingErrorRegistry : SynchronizedObject() {
    private val errorToHandle: MutableMap<TransactionResult.Error, StoreHandle<*>> = mutableMapOf()

    /** Record [error] as produced by [handle]. Idempotent for the same key. */
    fun register(
        error: TransactionResult.Error,
        handle: StoreHandle<*>,
    ) {
        synchronized(this) {
            errorToHandle[error] = handle
        }
    }

    /**
     * Mark [error] as consumed by a matcher: drop the global mapping AND the
     * handle's own pending-error entry (so the scope-exit guard won't flag it).
     * No-op if the error is unknown to the registry (e.g. a matcher invoked
     * after the scope already cleaned up).
     *
     * Exposed via `@PublishedApi internal` so the public-inline `shouldBe*`
     * matchers in `matcher/ResultMatchers.kt` can call it; not part of the
     * stable API.
     */
    @PublishedApi
    internal fun markConsumed(error: TransactionResult.Error) {
        val handle = synchronized(this) { errorToHandle.remove(error) } ?: return
        handle.markConsumedInternal(error)
    }

    /** Drop every mapping that points to [handle]. Called from scope tearDown. */
    fun unregisterAll(handle: StoreHandle<*>) {
        synchronized(this) {
            errorToHandle.entries.removeAll { it.value === handle }
        }
    }
}
