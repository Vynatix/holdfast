package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.matcher.shouldFireInOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertTrue

private class HopVault : Store<HopVault>() {
    val count by state { 0 }
}

/**
 * Resume on a fresh thread: under `Dispatchers.Unconfined`, the coroutine then
 * carries on there. A resume that lands before the coroutine has suspended
 * hands `suspendCancellableCoroutine` its result synchronously, so the
 * coroutine carries on where it was. So the fresh thread resumes it only
 * once the thread it ran on has stopped running — it suspended, and that
 * thread went back to waiting for work (`runBlocking`'s event loop) — and a
 * resume that still landed early hops again, until the coroutine has moved.
 *
 * (Same helper as `:holdfast-coroutines`' host-test `SettleTestSupport.kt`,
 * inlined here: test sources are not shared across modules.)
 */
private suspend fun resumeOnAnotherThread() {
    val from = Thread.currentThread()
    repeat(MAX_HOP_ATTEMPTS) {
        suspendCancellableCoroutine<Unit> { continuation ->
            thread(isDaemon = true) {
                awaitStopped(from)
                continuation.resume(Unit)
            }
        }
        if (Thread.currentThread() !== from) return
    }
    throw AssertionError("the coroutine never resumed on another thread in $MAX_HOP_ATTEMPTS hops")
}

/** Wait, at most [STOP_WAIT_MS], until [thread] is no longer running: parked, waiting for work. */
private fun awaitStopped(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MS)
    while (thread.state == Thread.State.RUNNABLE && System.nanoTime() < deadline) Thread.yield()
}

private const val MAX_HOP_ATTEMPTS = 100

private const val STOP_WAIT_MS = 500L

/**
 * A suspending entry whose body ends on another thread keeps its timeline's
 * commit markers. `Transaction.modifiedStates` is owner-thread-confined and
 * the completed hook runs where the body ended, so the emission events for
 * such a transaction cannot be recorded (the recorder degrades, as
 * `ProfilingMiddleware` degrades its attribution); before the fix the
 * recorder's whole completed hook aborted on that throw — silently, the hook
 * is runCatching-isolated on the suspending path — and the timeline kept
 * `started` alone: the intermittently empty timeline of a coroutine-first
 * test. The markers must survive either way.
 */
class SuspendingHopTimelineTest {
    @Test
    fun suspendingEntryEndingOnAnotherThreadKeepsCommitMarkers() =
        storeTest {
            val ctr = track(HopVault())
            runBlocking {
                withContext(Dispatchers.Unconfined) {
                    ctr.suspendAction {
                        resumeOnAnotherThread() // the body carries on on the fresh thread
                        count mutate 5
                        count.value
                    }
                }
            }
            ctr shouldFireInOrder {
                started
                committed
            }
            assertTrue(ctr.timeline.none { it is EmissionEvent })
        }
}
