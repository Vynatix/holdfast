@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.testing.concurrency.transaction
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, the harness's open transactions (`transaction(on = …)`,
// OpenTransaction.commit/rollback; PrivilegedHooks.openTransaction,
// commitOpenTransaction, rollbackOpenTransaction): a fatal error is never a
// TransactionResult.Error and never swallowed — it propagates once the
// active-transaction slot is cleared and the post-commit queue drained, so
// the store is never left held.

private class FotCounter : Store<FotCounter>() {
    val count by state { 0 }
}

private class FotSource : Store<FotSource>() {
    val x by state { 1 }
}

private fun Store<*>.fotReports(): MutableList<Throwable> =
    CopyOnWriteArrayList<Throwable>().also { seen -> uncaughtObserverHandler = { seen += it } }

class FatalOpenTransactionTest {
    /** PrivilegedHooks.kt:263, hint (1): the body's own fatal error propagates as it is, the store left clean. */
    @Test
    fun aFatalBodyErrorPropagatesAndLeavesTheStoreClean() =
        storeTest {
            val host = FotCounter()
            val reported = host.fotReports()
            val h = track(host)
            val boom = StackOverflowError()
            val thrown =
                assertFailsWith<StackOverflowError> {
                    transaction(on = h) {
                        count mutate 5
                        throw boom
                    }
                }
            assertSame(boom, thrown)
            assertTrue(thrown.suppressed.isEmpty())
            assertNull(host.activeTransaction, "the slot was cleared")
            assertEquals(0, host.count.value, "the body's write was discarded")
            h.action { count mutate 1 }.shouldBeSuccess()
            assertEquals(1, host.count.value)
            assertEquals(emptyList(), reported.toList())
        }

    /**
     * PrivilegedHooks.kt:263, hint (2) (and :279's hold, reached through the
     * drain): an ordinary body failure whose cleanup drain runs a `derived`
     * recompute that overflows. The overflow is thrown, carrying the body's
     * failure; the slot is cleared all the same.
     */
    @Test
    fun aCleanupDrainsFatalErrorIsThrownCarryingTheBodysFailure() =
        storeTest {
            val host = FotCounter()
            val source = FotSource()
            val reported = listOf(host, source).associateWith { it.fotReports() }
            val h = track(host)
            val boom = StackOverflowError()
            val armed = AtomicBoolean(false)
            val (mirror, sub) = host.derived(source.x) { if (armed.get()) throw boom else source.x.value * 10 }
            try {
                val thrown =
                    assertFailsWith<Throwable> {
                        transaction(on = h) {
                            count mutate 5
                            source action { x mutate 4 } // the recompute is queued behind the open transaction
                            armed.set(true)
                            error("body")
                        }
                    }
                armed.set(false)
                assertSame(boom, thrown, "the drain's fatal error, not the body's ISE: $thrown")
                val body = thrown.suppressed.single()
                assertIs<IllegalStateException>(body)
                assertEquals("body", body.message)
                assertNull(host.activeTransaction, "the slot was cleared")
                assertEquals(0, host.count.value)
                assertEquals(10, mirror.value, "the recompute that overflowed committed nothing")
                h.action { count mutate 1 }.shouldBeSuccess()
                source action { x mutate 6 }
                assertEquals(60, mirror.value, "the derived recomputes again")
                reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
            } finally {
                sub.dispose()
            }
        }

    /** PrivilegedHooks.kt:263: a fatal body error stays what is thrown when the cleanup drain throws another. */
    @Test
    fun aFatalBodyErrorStaysThrownCarryingTheCleanupDrainsFatalError() =
        storeTest {
            val host = FotCounter()
            val source = FotSource()
            val reported = listOf(host, source).associateWith { it.fotReports() }
            val h = track(host)
            val bodyBoom = StackOverflowError("body")
            val drainBoom = StackOverflowError("drain")
            val armed = AtomicBoolean(false)
            val (_, sub) = host.derived(source.x) { if (armed.get()) throw drainBoom else source.x.value * 10 }
            try {
                val thrown =
                    assertFailsWith<StackOverflowError> {
                        transaction(on = h) {
                            source action { x mutate 4 }
                            armed.set(true)
                            throw bodyBoom
                        }
                    }
                armed.set(false)
                assertSame(bodyBoom, thrown, "the body's fatal error is held first")
                assertTrue(thrown.suppressed.any { it === drainBoom }, "the drain's rides along: ${thrown.suppressed}")
                assertNull(host.activeTransaction)
                h.action { count mutate 1 }.shouldBeSuccess()
                reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
            } finally {
                sub.dispose()
            }
        }

    /** Non-fatal control for :263: an ordinary drain failure still leaves the body's failure as it is, reported. */
    @Test
    fun anOrdinaryCleanupDrainFailureStillLeavesTheBodysFailure() =
        storeTest {
            val host = FotCounter()
            val source = FotSource()
            val reported = host.fotReports()
            val h = track(host)
            val armed = AtomicBoolean(false)
            val (_, sub) = host.derived(source.x) { if (armed.get()) error("compute") else source.x.value * 10 }
            try {
                val body = IllegalStateException("body")
                val thrown =
                    assertFailsWith<IllegalStateException> {
                        transaction(on = h) {
                            source action { x mutate 4 }
                            armed.set(true)
                            throw body
                        }
                    }
                armed.set(false)
                assertSame(body, thrown)
                assertTrue(thrown.suppressed.isEmpty())
                assertEquals("compute", reported.single().message, "the recompute's failure is reported, as before")
            } finally {
                sub.dispose()
            }
        }

    /**
     * PrivilegedHooks.kt:365: `rollback()` drains a `derived` recompute
     * queued behind the open transaction, and its compute overflows. The
     * rollback still happened and the slot is clear before it is thrown.
     */
    @Test
    fun rollbackThrowsADrainedRecomputesFatalErrorOnceTheSlotIsCleared() =
        storeTest {
            val host = FotCounter()
            val source = FotSource()
            val reported = listOf(host, source).associateWith { it.fotReports() }
            val h = track(host)
            val boom = StackOverflowError()
            val armed = AtomicBoolean(false)
            val (mirror, sub) = host.derived(source.x) { if (armed.get()) throw boom else source.x.value * 10 }
            try {
                val open = transaction(on = h) { count mutate 5 }
                source action { x mutate 4 }
                armed.set(true)
                val thrown = assertFailsWith<StackOverflowError> { open.rollback() }
                armed.set(false)
                assertSame(boom, thrown)
                assertTrue(open.isClosed)
                assertEquals(TransactionStatus.RolledBack, open.transaction.status)
                assertNull(host.activeTransaction)
                assertEquals(0, host.count.value)
                assertEquals(10, mirror.value)
                h.action { count mutate 1 }.shouldBeSuccess()
                assertEquals(1, host.count.value)
                source action { x mutate 6 }
                assertEquals(60, mirror.value, "the derived recomputes again")
                reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
            } finally {
                sub.dispose()
            }
        }

    /** Non-fatal control for :365: an ordinary drained-recompute failure is still swallowed by rollback, reported. */
    @Test
    fun rollbackStillSwallowsAnOrdinaryDrainedRecomputeFailure() =
        storeTest {
            val host = FotCounter()
            val source = FotSource()
            val reported = host.fotReports()
            val h = track(host)
            val armed = AtomicBoolean(false)
            val (_, sub) = host.derived(source.x) { if (armed.get()) error("compute") else source.x.value * 10 }
            try {
                val open = transaction(on = h) { count mutate 5 }
                source action { x mutate 4 }
                armed.set(true)
                open.rollback()
                armed.set(false)
                assertEquals(TransactionStatus.RolledBack, open.transaction.status)
                assertNull(host.activeTransaction)
                assertEquals("compute", reported.single().message)
            } finally {
                sub.dispose()
            }
        }

    /**
     * OpenTransaction.kt:95: an observer overflows during `commit()`'s
     * fanout. The commit stands, the other observer still ran, the slot is
     * clear, and the error propagates — never a TransactionResult.Error, so
     * teardown finds nothing unconsumed and the test passes.
     */
    @Test
    fun commitThrowsAnObserversFatalErrorAfterEveryObserverRan() =
        storeTest {
            val host = FotCounter()
            val reported = host.fotReports()
            val h = track(host)
            val boom = StackOverflowError()
            val armed = AtomicBoolean(false)
            val seen = CopyOnWriteArrayList<Int>()
            val first = host.count effect { if (armed.get()) throw boom }
            val second = host.count effect { seen += this }
            seen.clear()
            try {
                val open = transaction(on = h) { count mutate 1 }
                armed.set(true)
                val thrown = assertFailsWith<StackOverflowError> { open.commit() }
                armed.set(false)
                assertSame(boom, thrown)
                assertTrue(open.isClosed)
                assertNull(host.activeTransaction)
                assertEquals(1, host.count.value, "applied before the fanout: the commit stands")
                assertEquals(listOf(1), seen.toList(), "the other observer still ran")
                h.action { count mutate 2 }.shouldBeSuccess()
                assertEquals(emptyList(), reported.toList())
            } finally {
                first.dispose()
                second.dispose()
            }
        }

    /** Non-fatal control for OpenTransaction.kt:95: an ordinary observer failure is reported, commit succeeds. */
    @Test
    fun commitWithAnOrdinaryObserverFailureStillSucceedsAndReportsIt() =
        storeTest {
            val host = FotCounter()
            val reported = host.fotReports()
            val h = track(host)
            val armed = AtomicBoolean(false)
            val sub = host.count effect { if (armed.get()) error("observer") }
            try {
                val open = transaction(on = h) { count mutate 1 }
                armed.set(true)
                open.commit().shouldBeSuccess()
                armed.set(false)
                assertEquals(1, host.count.value)
                assertEquals("observer", reported.single().message)
            } finally {
                sub.dispose()
            }
        }
}
