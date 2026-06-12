package com.vynatix.holdfast.testing

import com.vynatix.holdfast.testing.concurrency.eventually
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

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
}
