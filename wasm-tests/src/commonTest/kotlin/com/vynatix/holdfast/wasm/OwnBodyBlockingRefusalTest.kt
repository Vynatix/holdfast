@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.reset
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class OwnBodyStore : Store<OwnBodyStore>() {
    val n by state { 0 }
}

/**
 * Issue #31: a blocking `action { }`, `reset()`, `restore()` or
 * `atomic(...)` on a store from inside that store's own `suspendAction` body
 * throws one teaching `IllegalStateException` on every platform, BEFORE any
 * wait. Pre-fix, the JVM control hung in the serializer's spin and wasmJs
 * refused with issue #27's single-threaded message instead — this pins the
 * one message (and that the body rolls back).
 */
class OwnBodyBlockingRefusalTest {
    private fun assertOwnBodyRefusal(result: TransactionResult<Unit>) {
        val ise = assertIs<IllegalStateException>(assertIs<TransactionResult.Error>(result).exception)
        assertTrue("OwnBodyStore inside that store's own suspending body" in ise.message.orEmpty(), ise.message)
        assertTrue("suspend mutex" in ise.message.orEmpty(), ise.message)
        assertTrue("mutate" in ise.message.orEmpty(), ise.message)
    }

    @Test
    fun aBlockingActionInsideTheBodysOwnSuspendActionIsRefused() =
        runTest {
            val s = OwnBodyStore()
            assertOwnBodyRefusal(s.suspendAction<OwnBodyStore, Unit> { s action { n mutate 1 } })
            assertEquals(0, s.n.value, "the refused write rolled back with the body")
        }

    @Test
    fun resetInsideTheBodysOwnSuspendActionIsRefused() =
        runTest {
            val s = OwnBodyStore()
            s.suspendAction<OwnBodyStore, Unit> { n mutate 7 }.getOrThrow()
            assertOwnBodyRefusal(s.suspendAction<OwnBodyStore, Unit> { s.reset() })
            assertEquals(7, s.n.value, "nothing was reset")
        }

    @Test
    fun restoreInsideTheBodysOwnSuspendActionIsRefused() =
        runTest {
            val s = OwnBodyStore()
            s.suspendAction<OwnBodyStore, Unit> { n mutate 7 }.getOrThrow()
            val snap = s.snapshot()
            assertOwnBodyRefusal(
                s.suspendAction<OwnBodyStore, Unit> {
                    n mutate 9
                    s.restore(snap)
                },
            )
            assertEquals(7, s.n.value, "nothing was restored and the body's write rolled back")
        }

    @Test
    fun aBlockingAtomicInsideTheBodysOwnSuspendActionIsRefused() =
        runTest {
            val s = OwnBodyStore()
            assertOwnBodyRefusal(s.suspendAction<OwnBodyStore, Unit> { atomic(s) { n mutate 1 } })
            assertEquals(0, s.n.value, "the frame never ran")
        }

    @Test
    fun aBlockingActionOnAnotherStoreInsideTheBodyStillRuns() =
        runTest {
            val s = OwnBodyStore()
            val t = OwnBodyStore()
            val r =
                s.suspendAction<OwnBodyStore, Unit> {
                    assertIs<TransactionResult.Success<*>>(t action { n mutate 41 })
                    n mutate 5
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(41, t.n.value)
            assertEquals(5, s.n.value)
        }
}
