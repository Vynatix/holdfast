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

/**
 * Resume on a fresh thread: under `Dispatchers.Unconfined`, the coroutine then
 * carries on there. A resume that lands before the coroutine has suspended
 * hands `suspendCancellableCoroutine` its result synchronously, so the
 * coroutine carries on where it was: hop again until it has moved.
 */
internal suspend fun resumeOnAnotherThread() {
    val from = Thread.currentThread()
    repeat(MAX_HOP_ATTEMPTS) {
        suspendCancellableCoroutine<Unit> { continuation -> thread(isDaemon = true) { continuation.resume(Unit) } }
        if (Thread.currentThread() !== from) return
    }
    fail("the coroutine never resumed on another thread in $MAX_HOP_ATTEMPTS hops; is it under Dispatchers.Unconfined?")
}

private const val MAX_HOP_ATTEMPTS = 100
