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
        vaultTest {
            var ran = false
            run { ran = true }
            assertTrue(ran)
        }

    @Test
    fun trackReturnsHandleWithVault() =
        vaultTest {
            val v = TinyVault()
            val h = track(v)
            assertSame(v, h.store)
        }

    @Test
    fun trackIsIdempotentByIdentity() =
        vaultTest {
            val v = TinyVault()
            val h1 = track(v)
            val h2 = track(v)
            assertSame(h1, h2)
        }

    @Test
    fun distinctVaultsGetDistinctHandles() =
        vaultTest {
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
        vaultTest {
            val h = track(TinyVault())
            assertEquals(Capture.All, h.captureMode)
        }

    @Test
    fun captureModeNonePreserved() =
        vaultTest {
            val h = track(TinyVault(), Capture.None)
            assertEquals(Capture.None, h.captureMode)
        }

    @Test
    fun captureModeRingBufferPreserved() =
        vaultTest {
            val h = track(TinyVault(), Capture.RingBuffer(size = 16))
            assertEquals(Capture.RingBuffer(size = 16), h.captureMode)
        }
}
