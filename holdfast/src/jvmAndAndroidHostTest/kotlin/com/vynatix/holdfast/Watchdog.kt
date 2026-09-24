package com.vynatix.holdfast

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.fail

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. A body that throws rethrows here.
 *
 * For concurrency tests whose regression is a hang or a spin rather than an
 * exception: without the watchdog a regression would burn the whole 10-minute
 * test-task cap (`holdfast.kmp.library.gradle.kts`) before reporting anything.
 * The worker is a daemon so a regression cannot keep the JVM alive after the
 * failure is reported.
 */
internal fun completesWithin(
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
    worker.name = "watchdog-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — something is blocking or spinning on a held store")
    }
    thrown.get()?.let { throw it }
}

/** Start [body] on a named daemon thread. */
internal fun daemon(
    name: String,
    body: () -> Unit,
): Thread =
    Thread(body, name).apply {
        isDaemon = true
        start()
    }
