package com.vynatix.vault.testing

import com.vynatix.vault.testing.concurrency.barrier
import com.vynatix.vault.testing.concurrency.parallel
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class BarrierTest {

    @Test
    fun releasesAllPartiesOnFullArrival() = vaultTest {
        val b = barrier(parties = 3)
        val results = parallel(3) { idx ->
            b.arrive()
            idx
        }
        assertEquals(listOf(0, 1, 2), results)
    }

    @Test
    fun timesOutWhenArrivalsBelowParties() = vaultTest {
        val b = barrier(parties = 3, timeout = 100.milliseconds)
        assertFailsWith<TimeoutCancellationException> {
            parallel(2) { b.arrive() }
        }
    }

    @Test
    fun excessArrivalsFailLoudly() = vaultTest {
        val b = barrier(parties = 2)
        val ex = assertFailsWith<IllegalStateException> {
            parallel(3) { b.arrive() }
        }
        val msg = ex.message.orEmpty()
        assertTrue(
            msg.contains("Barrier of 2 parties exceeded"),
            "unexpected message: $msg",
        )
    }
}
