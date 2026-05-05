package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.concurrency.AwaitingTimeoutException
import com.vynatix.holdfast.testing.concurrency.awaiting
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class AwaitingCounterVault : Store<AwaitingCounterVault>() {
    val count by state { 0 }
}

class AwaitingTest {

    @Test
    fun returnsNextMatchingEvent() = vaultTest {
        val ctr = track(AwaitingCounterVault())
        // Schedule a future commit on the test scheduler's virtual time.
        // Action body returns synchronously; delay participates in virtual
        // time so the whole test resolves in near-zero wall time.
        backgroundScope.launch {
            delay(50.milliseconds)
            ctr.action { count mutate 1 }.shouldBeSuccess()
        }
        val event = awaiting(timeout = 500.milliseconds) { it is TransactionCommitted }
        assertIs<TransactionCommitted>(event)
        // Final value must reflect the commit that satisfied the predicate.
        assertEquals(1, ctr.read { count.value })
    }

    @Test
    fun returnsPastEventWhenAlreadyOccurred() = vaultTest {
        val ctr = track(AwaitingCounterVault())
        ctr.action { count mutate 1 }.shouldBeSuccess()
        // Event already in timeline; awaiting must return immediately via the
        // replay path without suspending — assert by using a tight timeout.
        val event = awaiting(timeout = 100.milliseconds) { it is TransactionCommitted }
        assertIs<TransactionCommitted>(event)
    }

    @Test
    fun timesOutWhenNoMatch() = vaultTest {
        val ctr = track(AwaitingCounterVault())
        ctr.action { count mutate 1 }.shouldBeSuccess()
        // No BridgeEvent will ever fire (Issue 12 owns bridge instrumentation);
        // awaiting must time out and surface the augmented message.
        val err = assertFailsWith<AwaitingTimeoutException> {
            awaiting(timeout = 50.milliseconds) { it is BridgePublished }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "awaiting", message = "expected augmented message, got: $msg")
        assertContains(msg, "saw", message = "expected event-tail in message, got: $msg")
    }

    @Test
    fun skipsNonMatchingEvents() = vaultTest {
        val ctr = track(AwaitingCounterVault())
        // Two commits scheduled on virtual time — non-matching events
        // (TransactionStarted, MiddlewareStarted, EmissionEvent, etc.) must
        // be skipped while waiting for the second TransactionCommitted with
        // count == 2.
        backgroundScope.launch {
            delay(20.milliseconds)
            ctr.action { count mutate 1 }.shouldBeSuccess()
            delay(20.milliseconds)
            ctr.action { count mutate 2 }.shouldBeSuccess()
        }
        val event = awaiting(timeout = 500.milliseconds) {
            it is TransactionCommitted && ctr.read { count.value } == 2
        }
        assertIs<TransactionCommitted>(event)
        assertEquals(2, ctr.read { count.value })
    }

    @Test
    fun timeoutMessageIncludesEventTailFromTimeline() = vaultTest {
        val ctr = track(AwaitingCounterVault())
        // Three actions ⇒ several events in the timeline. The augmented
        // message must include the recent tail (up to 5 events).
        ctr.action { count mutate 1 }.shouldBeSuccess()
        ctr.action { count mutate 2 }.shouldBeSuccess()
        ctr.action { count mutate 3 }.shouldBeSuccess()
        val err = assertFailsWith<AwaitingTimeoutException> {
            awaiting(timeout = 25.milliseconds) { it is BridgeObserved }
        }
        val msg = err.message.orEmpty()
        // "saw 5 events" — the takeLast(5) tail across the whole timeline.
        assertContains(msg, "saw 5 events", message = "expected tail of 5, got: $msg")
        // Sanity — the message should reference at least one TransactionCommitted
        // since the recent tail of three commits will include them.
        assertTrue(
            msg.contains("TransactionCommitted") || msg.contains("MiddlewareCompleted") || msg.contains("EmissionEvent"),
            "expected tail to include event class names, got: $msg",
        )
    }

    @Test
    fun replayCheckMatchesAcrossMultipleHandles() = vaultTest {
        // Two tracked vaults, only the second emits the matching event.
        // awaiting fans in across all tracked handles.
        val a = track(AwaitingCounterVault())
        val b = track(AwaitingCounterVault())
        b.action { count mutate 99 }.shouldBeSuccess()
        // The matching commit is on `b`. Predicate references the b vault's
        // state to verify identity-aware filtering works across the fan-in.
        val event = awaiting(timeout = 100.milliseconds) {
            it is TransactionCommitted && b.read { count.value } == 99
        }
        assertIs<TransactionCommitted>(event)
        // a's timeline must remain empty.
        assertTrue(a.timeline.isEmpty(), "expected a's timeline to be empty, got ${a.timeline}")
    }
}
