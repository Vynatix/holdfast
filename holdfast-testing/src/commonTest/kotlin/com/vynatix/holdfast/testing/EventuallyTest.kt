package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.concurrency.awaiting
import com.vynatix.holdfast.testing.concurrency.eventually
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class EventuallyCounterVault : Store<EventuallyCounterVault>() {
    val count by state { 0 }
}

class EventuallyTest {
    @Test
    fun succeedsWhenAssertionEventuallyPasses() =
        storeTest {
            var attempts = 0
            eventually(within = 500.milliseconds, every = 10.milliseconds) {
                attempts++
                assertTrue(attempts >= 5, "still warming up: $attempts")
            }
            assertTrue(attempts >= 5)
        }

    @Test
    fun failsAfterTimeoutWithLastErrorMessage() =
        storeTest {
            val err =
                assertFailsWith<AssertionError> {
                    eventually(within = 50.milliseconds, every = 5.milliseconds) {
                        fail("never passes")
                    }
                }
            val msg = err.message.orEmpty()
            assertTrue(msg.startsWith("eventually: gave up after 50ms"), "unexpected: $msg")
            assertTrue(msg.contains("never passes"), "missing inner cause: $msg")
        }

    @Test
    fun returnsImmediatelyOnFirstSuccess() =
        storeTest {
            var attempts = 0
            eventually(within = 1000.milliseconds, every = 100.milliseconds) {
                attempts++
            }
            assertEquals(1, attempts)
        }

    @Test
    fun retriesAwaitingTimeouts() =
        storeTest {
            // AwaitingTimeoutException is an AssertionError, so the eventually
            // loop must RETRY it (F21) — the first awaiting attempt times out
            // before the commit lands, a later attempt sees it.
            val ctr = track(EventuallyCounterVault())
            backgroundScope.launch {
                delay(80.milliseconds)
                ctr.action { count mutate 1 }.shouldBeSuccess()
            }
            var attempts = 0
            eventually(within = 2.seconds, every = 10.milliseconds) {
                attempts++
                awaiting(timeout = 30.milliseconds) { it is TransactionCommitted }
            }
            assertTrue(attempts > 1, "expected the first awaiting attempt to time out and be retried, attempts=$attempts")
            assertEquals(1, ctr.read { count.value })
        }
}
