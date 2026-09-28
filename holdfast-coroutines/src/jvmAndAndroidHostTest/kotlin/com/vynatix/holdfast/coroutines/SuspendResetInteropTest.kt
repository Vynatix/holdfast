package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameInteropException
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.reset
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

private class ResetAccount : Store<ResetAccount>() {
    val balance by state { 100L }
    val note by state { "" }
}

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. A regression here blocks on the participant's
 * suspend mutex, which the frame's own coroutine holds, so without the
 * watchdog it would burn the whole test-task cap before reporting anything.
 */
private fun completesWithin(
    seconds: Long,
    what: String,
    body: () -> Unit,
) {
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    val worker =
        Thread {
            try {
                body()
            } catch (e: Throwable) {
                thrown.set(e)
            } finally {
                done.countDown()
            }
        }
    worker.isDaemon = true
    worker.name = "suspend-reset-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — the blocking reset is waiting on the frame's mutex")
    }
    thrown.get()?.let { throw it }
}

/**
 * `reset()` (issue #20, R4) is a blocking action. Inside a `suspendAtomic`
 * body the participant's suspend mutex is held by the frame itself, so a
 * blocking reset of a participant must fail fast with the frame-interop
 * teaching exception — exactly like a blocking `action { }` there — instead
 * of deadlocking on that mutex, and the frame must roll back.
 */
@OptIn(ExperimentalStoreApi::class)
class SuspendResetInteropTest {
    @Test
    fun `a blocking reset of a suspendAtomic participant fails fast instead of deadlocking`() {
        val a = ResetAccount()
        val b = ResetAccount()
        runBlocking { a.suspendAction { balance mutate 40L } }

        completesWithin(10, "reset() inside a suspendAtomic body") {
            runBlocking {
                val e =
                    assertFailsWith<FrameInteropException> {
                        suspendAtomic(a, b) {
                            b { note mutate "staged before the reset" }
                            a.reset()
                        }
                    }
                assertContains(e.message.orEmpty(), "inside suspendAtomic")
            }
        }
        assertEquals(40L, a.balance.value, "nothing was reset")
        assertEquals("", b.note.value, "the frame rolled back")
    }

    @Test
    fun `a blocking reset of a store a suspendAtomic does not enroll fails fast`() {
        val a = ResetAccount()
        val outsider = ResetAccount()
        outsider action { balance mutate 40L }

        completesWithin(10, "reset() of an unenrolled store inside a suspendAtomic body") {
            runBlocking {
                assertFailsWith<UnenrolledStoreException> {
                    suspendAtomic(a) { outsider.reset() }
                }
            }
        }
        assertEquals(40L, outsider.balance.value)
    }

    @Test
    fun `reset still works on a store a suspendAction has used`() {
        val a = ResetAccount()
        runBlocking { a.suspendAction { balance mutate 40L } }

        completesWithin(10, "reset() after suspendAction installed the serializer") {
            assertIs<TransactionResult.Success<Unit>>(a.reset())
        }
        assertEquals(100L, a.balance.value)
    }
}
