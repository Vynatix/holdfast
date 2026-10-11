@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HygieneCounter : Store<HygieneCounter>() {
    val n by state { 0 }
}

/**
 * Throws from the sync `started` hook. A suspendAtomic frame's middleware
 * session runs `started` UN-isolated (a throwing `started` aborts the frame,
 * before the body — see `Store.internalFrameMiddlewareSession`), so this is a
 * user-injectable pre-body failure on the frame path.
 */
private class ExplodingStarted<V : Store<V>> : Middleware<V>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        started++
        throw IllegalStateException("boom before the body")
    }
}

/**
 * Throws from the async `started` hook — the only user code that runs in a
 * `suspendAction`'s pre-body window (between the txn/owner install and the
 * body). Isolated by contract (`runCatching` per hook), so it cannot abort the
 * entry — the window's failure isolation is what the hygiene restructure
 * backstops for library-internal throws.
 */
private class ExplodingStartedAsync<V : Store<V>> :
    Middleware<V>(),
    SuspendingMiddlewareHooks<V> {
    override suspend fun onTransactionStartedAsync(context: MiddlewareContext<V>) =
        throw IllegalStateException("boom inside the pre-body window")
}

/**
 * Issue 002 residue, audit rows 22/23 (A6): the suspending entries install
 * their transaction and suspending owner INSIDE the protected try, so a throw
 * between the install and the body leaves nothing installed; and the exit
 * clears the transaction slot before the suspending owner, so an aborting
 * entry never poisons the store for later blocking callers (the issue-002
 * "permanently-throwing store" failure mode, issue 003's budget).
 *
 * The tests read `suspendingOwner`/`activeTransaction` through
 * `@StoreInternalApi`: the window's field state is exactly what those two
 * slots hold, and no user-space observation of it exists.
 */
class SuspendEntryHygieneTest {
    private val stores = mutableListOf<Store<*>>()

    @AfterTest fun cleanup() {
        stores.forEach { runCatching { it.dispose() } }
    }

    /**
     * A pre-body failure on the suspendAtomic path — a middleware's `started`
     * hook throwing before the body runs — must return an `Error` (not
     * escalate), never run the body, and leave the store exactly as it found
     * it: no suspending owner, no frame root installed, the serializer mutex
     * released. The follow-up blocking action proves the last two at once: a
     * leaked owner or a held mutex would make it wait out the serializer's
     * 2 s budget (or throw `StoreSuspendInFlightException`), not commit at
     * once.
     */
    @Test
    fun ownerAndTxnClearedWhenPreBodySetupThrows() =
        runBlocking {
            val store = HygieneCounter().also { stores += it }
            val boom = ExplodingStarted<HygieneCounter>()
            store.middlewares(boom)
            var bodyRan = false

            val r = suspendAtomic(store) { bodyRan = true }

            assertIs<TransactionResult.Error>(r, "a throwing pre-body started hook aborts the frame into an Error")
            assertFalse(bodyRan, "the body never ran")
            assertEquals(1, boom.started, "the exploding hook fired once")
            assertEquals(0, store.n.value, "nothing committed")
            assertNull(store.suspendingOwner, "the suspending owner must be restored (null before the frame)")
            assertNull(store.activeTransaction, "the frame's root must be uninstalled")

            store.clearMiddleware()
            val after = store action { n mutate 2 }
            assertIs<TransactionResult.Success<*>>(after, "the store must not be poisoned for blocking callers")
            assertEquals(2, store.n.value)
        }

    /**
     * The suspendAction edge of the same window. Its pre-body stretch runs no
     * abortable user code — sync and async `started` hooks are contract
     * isolated — so the pin covers both edges the restructure guards: an
     * isolated pre-body hook throw must not abort the entry (and leave nothing
     * installed), and the nearest injectable abort — a body throw, flowing
     * through the SAME finally that now covers the window — must clear the
     * owner and the transaction slot on every exit path.
     */
    @Test
    fun suspendActionWindowAbortsClearOwnerAndTxn() =
        runBlocking {
            val store = HygieneCounter().also { stores += it }
            store.middlewares(ExplodingStartedAsync<HygieneCounter>())
            var bodyRan = false

            val isolated =
                store.suspendAction {
                    bodyRan = true
                    n mutate 1
                }
            assertIs<TransactionResult.Success<*>>(isolated, "an isolated pre-body hook throw must not abort the entry")
            assertTrue(bodyRan)
            assertEquals(1, store.n.value)
            assertNull(store.suspendingOwner, "a completed entry leaves no owner installed")
            assertNull(store.activeTransaction, "a completed entry leaves no transaction installed")

            val failed = store.suspendAction { error("boom in the body") }
            assertIs<TransactionResult.Error>(failed)
            assertNull(store.suspendingOwner, "a failed entry must clear the suspending owner")
            assertNull(store.activeTransaction, "a failed entry must clear the transaction slot")

            val after = store action { n mutate 5 }
            assertIs<TransactionResult.Success<*>>(after, "the store must not be poisoned for blocking callers")
            assertEquals(5, store.n.value)
        }
}
