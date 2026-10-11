package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.reset
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

private class RefusalCounter : Store<RefusalCounter>() {
    val count by state { 0L }
}

private class RefusalOther : Store<RefusalOther>() {
    val note by state { "" }
}

/** The refusal's message: names the store, says the wait could never end, teaches `mutate`. */
private fun assertOwnBodyRefusal(
    thrown: Throwable,
    store: String,
) {
    val ise = assertIs<IllegalStateException>(thrown, thrown.message)
    assertTrue("$store inside that store's own suspending body" in ise.message.orEmpty(), ise.message)
    assertTrue("suspend mutex" in ise.message.orEmpty(), ise.message)
    assertTrue("mutate" in ise.message.orEmpty(), ise.message)
}

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. Every own-body blocking call this file makes runs
 * inside one of these: pre-fix (issue #31) such a call spins forever on the
 * store's suspend mutex, so the watchdog turns the hang into a red test
 * while the daemon keeps spinning harmlessly.
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
    worker.name = "own-body-refusal-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — a blocking entry inside the store's own suspending body is waiting on it")
    }
    thrown.get()?.let { throw it }
}

/**
 * Issue #31: a blocking `action { }`, `reset()`, `restore()` or
 * `atomic(...)` on a store, called from inside that same store's
 * `suspendAction` body, used to spin forever on the store's suspend mutex —
 * held by the very body making the call. Each of those entries now throws a
 * teaching `IllegalStateException` before any wait, on every platform, and
 * the suspending transaction rolls back.
 */
@OptIn(ExperimentalStoreApi::class)
class OwnBodyBlockingRefusalTest {
    @Test
    fun `a blocking action inside the store's own suspendAction body is refused`() {
        val s = RefusalCounter()

        completesWithin(10, "action { } inside the store's own suspendAction body") {
            runBlocking {
                val r = s.suspendAction<RefusalCounter, Unit> { s action { count mutate 1L } }
                assertOwnBodyRefusal(assertIs<TransactionResult.Error>(r).exception, "RefusalCounter")
            }
        }

        assertEquals(0L, s.count.value, "the refused write rolled back with the body")
        runBlocking { s.suspendAction { count mutate 2L }.getOrThrow() }
        assertEquals(2L, s.count.value, "the store is usable after the refusal")
    }

    @Test
    fun `reset inside the store's own suspendAction body is refused`() {
        val s = RefusalCounter()
        runBlocking { s.suspendAction { count mutate 7L }.getOrThrow() }

        completesWithin(10, "reset() inside the store's own suspendAction body") {
            runBlocking {
                val r = s.suspendAction<RefusalCounter, Unit> { s.reset() }
                assertOwnBodyRefusal(assertIs<TransactionResult.Error>(r).exception, "RefusalCounter")
            }
        }

        assertEquals(7L, s.count.value, "nothing was reset")
    }

    @Test
    fun `restore inside the store's own suspendAction body is refused`() {
        val s = RefusalCounter()
        runBlocking { s.suspendAction { count mutate 7L }.getOrThrow() }
        val snap = s.snapshot()

        completesWithin(10, "restore(…) inside the store's own suspendAction body") {
            runBlocking {
                val r =
                    s.suspendAction<RefusalCounter, Unit> {
                        count mutate 9L
                        s.restore(snap)
                    }
                assertOwnBodyRefusal(assertIs<TransactionResult.Error>(r).exception, "RefusalCounter")
            }
        }

        assertEquals(7L, s.count.value, "nothing was restored and the body's write rolled back")
    }

    @Test
    fun `a blocking atomic enrolling the store inside its own suspendAction body is refused`() {
        val s = RefusalCounter()
        val t = RefusalOther()

        completesWithin(10, "atomic(…) inside the store's own suspendAction body") {
            runBlocking {
                val r = s.suspendAction<RefusalCounter, Unit> { atomic(s, t) { count mutate 1L } }
                assertOwnBodyRefusal(assertIs<TransactionResult.Error>(r).exception, "RefusalCounter")
            }
        }

        assertEquals(0L, s.count.value)
        assertEquals("", t.note.value, "the frame never ran")
    }

    @Test
    fun `the refusal still finds the body after a dispatcher hop`() {
        val s = RefusalCounter()

        completesWithin(10, "action { } after a hop inside the store's own suspendAction body") {
            runBlocking {
                val r =
                    s.suspendAction<RefusalCounter, Unit> {
                        withContext(Dispatchers.Default) { s action { count mutate 1L } }
                    }
                assertOwnBodyRefusal(assertIs<TransactionResult.Error>(r).exception, "RefusalCounter")
            }
        }

        assertEquals(0L, s.count.value)
    }

    @Test
    fun `a coroutine the body launches shares its settle scope and is refused too`() {
        val s = RefusalCounter()

        completesWithin(10, "action { } from a coroutine the body launched") {
            runBlocking {
                val r =
                    s.suspendAction<RefusalCounter, Unit> {
                        coroutineScope {
                            val done = CompletableDeferred<Unit>()
                            launch(Dispatchers.Default) {
                                try {
                                    s action { count mutate 1L }
                                    done.completeExceptionally(
                                        AssertionError("the blocking action was not refused"),
                                    )
                                } catch (e: IllegalStateException) {
                                    assertOwnBodyRefusal(e, "RefusalCounter")
                                    done.complete(Unit)
                                }
                            }
                            done.await()
                        }
                    }
                assertIs<TransactionResult.Success<*>>(r)
            }
        }

        assertEquals(0L, s.count.value, "the suspending body itself committed nothing")
    }

    @Test
    fun `a blocking action on another store inside the body still runs`() {
        val s = RefusalCounter()
        val t = RefusalOther()

        completesWithin(10, "action { } on another store inside the body") {
            runBlocking {
                val r =
                    s.suspendAction<RefusalCounter, Unit> {
                        assertIs<TransactionResult.Success<*>>(t action { note mutate "from the body" })
                        count mutate 5L
                    }
                assertIs<TransactionResult.Success<*>>(r)
            }
        }

        assertEquals("from the body", t.note.value)
        assertEquals(5L, s.count.value)
    }

    @Test
    fun `a blocking action beside a parked suspending body still waits and runs`() {
        val s = RefusalCounter()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodyDone = CountDownLatch(1)
        val blockedDone = CountDownLatch(1)
        val blockedOutcome = AtomicReference<TransactionResult<*>?>(null)

        val suspendingWorker =
            Thread {
                try {
                    runBlocking {
                        s
                            .suspendAction<RefusalCounter, Unit> {
                                count mutate 3L
                                entered.countDown()
                                release.await()
                            }.getOrThrow()
                    }
                } finally {
                    bodyDone.countDown()
                }
            }
        suspendingWorker.isDaemon = true
        suspendingWorker.name = "own-body-parked-holder"
        suspendingWorker.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the suspending body never started")

        val blockingWorker =
            Thread {
                try {
                    blockedOutcome.set(s action { count mutate count.value + 1L })
                } finally {
                    blockedDone.countDown()
                }
            }
        blockingWorker.isDaemon = true
        blockingWorker.name = "own-body-beside-blocker"
        blockingWorker.start()
        // Let the blocker reach the blocking acquire beside the parked holder,
        // then let the holder go: the blocker must still be WAITING for it —
        // not refused — and run once the body has released the store.
        Thread.sleep(200)
        release.countDown()

        assertTrue(bodyDone.await(10, TimeUnit.SECONDS), "the suspending body never finished")
        assertTrue(blockedDone.await(10, TimeUnit.SECONDS), "the blocking action never finished")
        assertIs<TransactionResult.Success<*>>(blockedOutcome.get())
        assertEquals(4L, s.count.value, "the blocker ran after the parked body, and read its commit")
    }
}
