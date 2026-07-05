package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class TinyVault : Store<TinyVault>() {
    val n by state { 0 }
}

class StoreTestScopeTest {
    @Test
    fun bodyExecutes() =
        storeTest {
            var ran = false
            run { ran = true }
            assertTrue(ran)
        }

    @Test
    fun trackReturnsHandleWithVault() =
        storeTest {
            val v = TinyVault()
            val h = track(v)
            assertSame(v, h.store)
        }

    @Test
    fun trackIsIdempotentByIdentity() =
        storeTest {
            val v = TinyVault()
            val h1 = track(v)
            val h2 = track(v)
            assertSame(h1, h2)
        }

    @Test
    fun distinctVaultsGetDistinctHandles() =
        storeTest {
            val a = TinyVault()
            val b = TinyVault()
            val ha = track(a)
            val hb = track(b)
            assertSame(a, ha.store)
            assertSame(b, hb.store)
            assertTrue(ha !== hb)
        }

    @Test
    fun captureModeAllByDefault() =
        storeTest {
            val h = track(TinyVault())
            assertEquals(Capture.All, h.captureMode)
        }

    @Test
    fun captureModeNonePreserved() =
        storeTest {
            val h = track(TinyVault(), Capture.None)
            assertEquals(Capture.None, h.captureMode)
        }

    @Test
    fun captureModeRingBufferPreserved() =
        storeTest {
            val h = track(TinyVault(), Capture.RingBuffer(size = 16))
            assertEquals(Capture.RingBuffer(size = 16), h.captureMode)
        }

    // -------- runTest time-control forwarders (F24) --------

    @Test
    fun timeControlWorksUnprefixed() =
        storeTest {
            // The runTest vocabulary must work without a testScope./testScheduler.
            // prefix: runCurrent, advanceTimeBy, advanceUntilIdle, currentTime.
            var immediate = false
            var afterDelay = false
            launch { immediate = true }
            launch {
                delay(75.milliseconds)
                afterDelay = true
            }

            runCurrent()
            assertTrue(immediate, "runCurrent() must run tasks scheduled at the current time")
            assertFalse(afterDelay, "the delayed task must not have run yet")

            val before = currentTime
            advanceTimeBy(50.milliseconds)
            assertEquals(before + 50, currentTime)
            assertFalse(afterDelay, "75ms task must not run after only 50ms")

            advanceUntilIdle()
            assertTrue(afterDelay, "advanceUntilIdle() must drain the remaining scheduled work")
        }
}
