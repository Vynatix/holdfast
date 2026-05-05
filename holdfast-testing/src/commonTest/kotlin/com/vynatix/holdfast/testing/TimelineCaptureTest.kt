package com.vynatix.holdfast.testing

import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TimelineCountVault : Store<TimelineCountVault>() {
    val count by state { 0 }
    val name by state { "init" }
}

class TimelineCaptureTest {

    @Test
    fun captureAllRecordsTransactionLifecycle() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }

        // Expect: TransactionStarted, MiddlewareStarted (recorder),
        // EmissionEvent (count: 0 -> 5), TransactionCommitted,
        // MiddlewareCompleted (recorder).
        val tl = ctr.timeline
        val txEvents = tl.filterIsInstance<TransactionEvent>()
        assertTrue(txEvents.any { it is TransactionStarted }, "expected TransactionStarted, got $tl")
        assertTrue(txEvents.any { it is TransactionCommitted }, "expected TransactionCommitted, got $tl")

        val emissions = tl.filterIsInstance<EmissionEvent>()
        assertEquals(1, emissions.size, "expected 1 EmissionEvent, got $tl")
        val emission = emissions.single()
        assertEquals(0, emission.oldValue)
        assertEquals(5, emission.newValue)
    }

    @Test
    fun timelineShapeMatchesIssueAcceptance() = vaultTest {
        // Mirrors the issue acceptance: 2 transactions x {start, emit, commit}.
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 1 }
        ctr.action { count mutate 2 }

        val txEvents = ctr.timeline.filterIsInstance<TransactionEvent>()
        val started = txEvents.filterIsInstance<TransactionStarted>()
        val committed = txEvents.filterIsInstance<TransactionCommitted>()
        assertEquals(2, started.size)
        assertEquals(2, committed.size)

        val emissions = ctr.timeline.filterIsInstance<EmissionEvent>()
        assertEquals(2, emissions.size)
    }

    @Test
    fun captureNoneRecordsNothing() = vaultTest {
        val ctr = track(TimelineCountVault(), Capture.None)
        ctr.action { count mutate 5 }
        ctr.action { count mutate 6 }
        assertTrue(ctr.timeline.isEmpty(), "expected empty timeline for Capture.None, got ${ctr.timeline}")
        assertNull(ctr.lastTransaction)
        assertNull(ctr.lastResult)
    }

    @Test
    fun captureRingBufferTruncatesOldest() = vaultTest {
        // Ring of 2 events. After many actions, the timeline must contain only
        // the 2 most recent events.
        val ctr = track(TimelineCountVault(), Capture.RingBuffer(2))
        repeat(5) { i -> ctr.action { count mutate i } }
        val tl = ctr.timeline
        assertEquals(2, tl.size, "expected ring buffer to keep last 2, got $tl")
    }

    @Test
    fun ringBufferRequiresPositiveSize() {
        try {
            Capture.RingBuffer(0)
            error("expected IllegalArgumentException for size=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("size must be > 0") == true)
        }
    }

    @Test
    fun lastTransactionAndLastResultReflectMostRecent() = vaultTest {
        val ctr = track(TimelineCountVault())
        val r1 = ctr.action { count mutate 1 }
        val r2 = ctr.action { count mutate 2 }

        assertNotNull(ctr.lastTransaction)
        assertNotNull(ctr.lastResult)
        assertSame(r2, ctr.lastResult)
        // Both transactions must be Committed at this point.
        assertEquals(TransactionStatus.Committed, ctr.lastTransaction!!.status)
        // r1 still committed; r2 happened after.
        assertEquals(TransactionStatus.Committed, (r1 as com.vynatix.holdfast.TransactionResult.Success).transaction.status)
    }

    @Test
    fun lastTransactionReflectsRolledBackTransaction() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 1 } // success
        val ise = IllegalStateException("boom")
        val errResult = ctr.action<Unit> { throw ise }
        ctr.consumeAllPendingErrors()

        // lastTransaction should reflect the rolled-back txn from the second action.
        val last = assertNotNull(ctr.lastTransaction)
        // The body threw, so vault rolled back -> status RolledBack.
        assertEquals(TransactionStatus.RolledBack, last.status)

        // Timeline should contain TransactionErrored + TransactionRolledBack
        // for the last action.
        val errored = ctr.timeline.filterIsInstance<TransactionErrored>()
        assertEquals(1, errored.size)
        assertSame(ise, errored.single().cause)
        val rolled = ctr.timeline.filterIsInstance<TransactionRolledBack>()
        assertEquals(1, rolled.size)

        // lastResult must be the second (Error) result.
        assertIs<com.vynatix.holdfast.TransactionResult.Error>(ctr.lastResult)
        assertSame(errResult, ctr.lastResult)
    }

    @Test
    fun nestedActionsRecordSeparateTransactions() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action {
            count mutate 1
            // Nested action runs as a savepoint - has its own Transaction + middleware chain.
            this action {
                name mutate "nested"
            }
        }

        val started = ctr.timeline.filterIsInstance<TransactionStarted>()
        // One outer + one inner = 2 starts.
        assertEquals(2, started.size, "expected 2 TransactionStarted events for nested action, got ${ctr.timeline}")
    }
}
