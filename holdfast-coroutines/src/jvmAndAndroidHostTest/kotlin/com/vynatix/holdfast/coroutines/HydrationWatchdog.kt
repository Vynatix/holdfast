package com.vynatix.holdfast.coroutines

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.fail

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]: a hydration regression that deadlocks, or spins
 * on a store it can never take, then fails the test instead of burning the
 * test task's time cap, and cannot keep the JVM alive after the failure.
 */
internal fun hydrationWatchdog(
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
    worker.name = "hydration-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — deadlocked or spinning. Threads:\n${threadDump()}")
    }
    thrown.get()?.let { throw it }
}

/** Every live thread's name, state and stack: what a hung hydration was waiting for. */
private fun threadDump(): String =
    Thread.getAllStackTraces().entries.joinToString("\n") { (thread, frames) ->
        "\"${thread.name}\" ${thread.state}\n" + frames.joinToString("\n") { "    at $it" }
    }
