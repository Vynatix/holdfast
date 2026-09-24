package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.test.fail

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]; a body that throws rethrows here. For the settle
 * and frame-commit tests, whose regression is a wait for a store the waiting
 * call itself holds.
 */
internal fun settlesWithin(
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
    worker.name = "settle-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) fail("$what did not complete within ${seconds}s")
    thrown.get()?.let { throw it }
}

/** Resume on a fresh thread: under `Dispatchers.Unconfined`, the coroutine then carries on there. */
internal suspend fun resumeOnAnotherThread() {
    suspendCancellableCoroutine<Unit> { continuation -> thread(isDaemon = true) { continuation.resume(Unit) } }
}
