package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
}
