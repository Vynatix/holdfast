@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class HandFinishedStore : Store<HandFinishedStore>() {
    /**
     * When set, `b`'s initializer rolls the store's active transaction back:
     * the shape of an initializer a restore re-runs while it stages (for a
     * state `removeState` dropped after the restore planned it) that ends the
     * restore's own transaction from inside.
     */
    var rollBackFromInitializer = false
    val a by state { 0 }
    val b by state {
        if (rollBackFromInitializer) activeTransaction?.rollback()
        0
    }
}

private class KeyedHandFinishedStore : Store<KeyedHandFinishedStore>() {
    val docs by keyedState<Int, Int> { 0 }
}

/** Ends every transaction whose id is [only] (every transaction when `null`) by hand as it starts, with [finish]. */
private class FinishOnStart(
    private val only: String?,
    private val finish: (Transaction) -> Unit,
) : Middleware<HandFinishedStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<HandFinishedStore>) {
        if (only == null || context.transaction.id == only) finish(context.transaction)
    }
}

/** Removes `b` as a restore's action starts: after the restore planned it, before it stages, so staging re-materializes it. */
private class RemoveBOnRestore : Middleware<HandFinishedStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<HandFinishedStore>) {
        if (context.transaction.id == "Restore") context.store.removeState("b")
    }
}

/**
 * A restore stages into the action it runs in — after user code has had the
 * transaction in hand: a middleware's `onTransactionStarted` sees it as
 * `context.transaction`, and an initializer re-run while staging can reach it
 * through `activeTransaction`. A `rollback()` or `commit()` there closes the
 * transaction to writes, and every other staging path refuses a closed
 * transaction (issue #20, D16). The restore's raw staging used to be the one
 * that did not: every planned write landed in the closed buffer, the report
 * listed the states as restored, and `restore()` returned Success with
 * nothing applied. It must return Error like `action { x mutate 1 }` under
 * the same middleware does (PR #22 review).
 */
class RestoreIntoFinishedTransactionTest {
    /** [r] failed with the D16 refusal of [attempt] ("restore", "write"): an [IllegalStateException] in its cause chain saying [fragment]. */
    private fun assertRefused(
        r: TransactionResult<*>,
        attempt: String,
        fragment: String,
    ) {
        val error = assertIs<TransactionResult.Error>(r, "the finished transaction must fail the call, not swallow it")
        val refusal =
            generateSequence(error.exception) { it.cause }
                .filterIsInstance<IllegalStateException>()
                .firstOrNull()
        val message = assertNotNull(refusal?.message, "no IllegalStateException in the cause chain of ${error.exception}")
        assertTrue("Cannot $attempt" in message, "not a refused $attempt: $message")
        assertTrue(fragment in message, "not the finished-transaction refusal: $message")
    }

    /** A store whose snapshot holds `a = 1, b = 3` while it holds `a = 5, b = 6`. */
    private fun changedSinceSnapshot(): Pair<HandFinishedStore, StoreSnapshot> {
        val store = HandFinishedStore()
        store action {
            a mutate 1
            b mutate 3
        }
        val snap = store.snapshot()
        store action {
            a mutate 5
            b mutate 6
        }
        return store to snap
    }

    @Test fun aRestoreWhoseTransactionAMiddlewareRolledBackReturnsError() {
        val (store, snap) = changedSinceSnapshot()
        store.middlewares(FinishOnStart("Restore") { it.rollback() })

        assertRefused(store.restore(snap), "restore", "has already been rolled back (status: RolledBack)")
        assertEquals(5, store.a.value, "nothing was restored")
        assertEquals(6, store.b.value, "nothing was restored")
    }

    @Test fun aRestoreWhoseTransactionAMiddlewareCommittedReturnsError() {
        val (store, snap) = changedSinceSnapshot()
        store.middlewares(FinishOnStart("Restore") { it.commit() })

        assertRefused(store.restore(snap), "restore", "has already applied its writes (status: Committed)")
        assertEquals(5, store.a.value, "nothing was restored")
        assertEquals(6, store.b.value, "nothing was restored")
    }

    @Test fun anActionWhoseTransactionAMiddlewareRolledBackReturnsErrorTheSameWay() {
        val store = HandFinishedStore()
        store.middlewares(FinishOnStart(only = null) { it.rollback() })

        assertRefused(store action { a mutate 1 }, "write", "has already been rolled back (status: RolledBack)")
        assertEquals(0, store.a.value)
    }

    @Test fun anInitializerReRunWhileStagingThatRollsTheRestoreBackReturnsError() {
        val (store, snap) = changedSinceSnapshot()
        // `b` is dropped once the restore has planned it; staging materializes
        // it again, and that re-run initializer ends the restore's transaction.
        store.middlewares(RemoveBOnRestore())
        store.rollBackFromInitializer = true

        assertRefused(store.restore(snap), "restore", "has already been rolled back (status: RolledBack)")
        assertEquals(5, store.a.value, "nothing was restored")
        assertEquals(0, store.b.value, "b holds what its re-run initializer computed, not the snapshot's value")
    }

    /**
     * The replacement of a keyed family a restore stages is refused, never
     * deferred, once the restore's transaction is closed: an eviction from a
     * commit's own fanout is deferred past the commit (D16), but a restore
     * whose transaction was committed by hand between its checks must not
     * evict, after failing, the entries it meant to replace. Pinned on the
     * staging step itself: only a commit from another thread can close the
     * transaction between the restore's up-front check and this step.
     */
    @Test fun aFamilyReplacementIntoAFinishedTransactionIsRefusedNotDeferred() {
        val store = KeyedHandFinishedStore()
        store.docs[1]
        val family = assertNotNull(store.registry.keyed.family("docs"))
        var refusal: IllegalStateException? = null

        val r =
            store action {
                val txn = assertNotNull(activeTransaction)
                txn.commit()
                refusal =
                    assertFailsWith<IllegalStateException> {
                        family.stageEvictionsOrRefuse(txn, "replace the entries of ${family.qualifiedName}") {
                            family.entries.values.map { it as MutableState<*> }
                        }
                    }
            }

        assertIs<TransactionResult.Success<*>>(r, "the hand-committed action's own commit is a no-op")
        val message = assertNotNull(refusal?.message)
        assertTrue("Cannot replace the entries of" in message, "unexpected: $message")
        assertTrue("has already applied its writes (status: Committed)" in message, "unexpected: $message")
        assertTrue(1 in store.docs, "no eviction was deferred past the refused replacement")
    }
}
