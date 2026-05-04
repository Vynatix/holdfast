package com.vynatix.holdfast.testing.concurrency

import com.vynatix.holdfast.testing.HoldfastTestScope
import com.vynatix.holdfast.testing.internal.registerBarrier
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cyclic-rendezvous barrier for a fixed number of [parties]. Each participant
 * calls [arrive] once; the barrier triggers when the last party arrives, at
 * which point every [arrive] (and every separate [await]) resumes together.
 *
 * Lifetime is bounded by [timeout]: if the barrier has not triggered before the
 * deadline, every suspended waiter is cancelled with
 * [TimeoutCancellationException]. The hosting [HoldfastTestScope] additionally
 * cancels any non-triggered barrier when the test body returns, so a forgotten
 * `arrive()` never leaks past the test.
 *
 * Construct via [HoldfastTestScope.barrier]; the constructor is `internal` so the
 * scope can register barriers for cleanup.
 *
 * Calling [arrive] more times than [parties] is a programming error and fails
 * the offending coroutine with [IllegalStateException]. The barrier itself is
 * not reset and remains in its final triggered state.
 */
class Barrier internal constructor(private val parties: Int, private val timeout: Duration) {
    init {
        require(parties > 0) { "parties must be > 0, was $parties" }
    }

    private val arrivedCount = atomic(0)
    private val completion = CompletableDeferred<Unit>()

    /**
     * Register this caller's arrival and suspend until the barrier triggers
     * (i.e. until [parties] callers have arrived in total).
     *
     * Throws [IllegalStateException] if more than [parties] arrivals occur.
     * Throws [TimeoutCancellationException] if [timeout] expires before
     * triggering.
     */
    suspend fun arrive() {
        val newCount = arrivedCount.incrementAndGet()
        check(newCount <= parties) {
            "Barrier of $parties parties exceeded — got $newCount arrivals"
        }
        if (newCount == parties) {
            completion.complete(Unit)
        }
        await()
    }

    /**
     * Suspend until the barrier triggers. Useful when a coroutine wants to wait
     * for a phase to begin without contributing to the arrival count.
     *
     * Throws [TimeoutCancellationException] if [timeout] expires first.
     */
    suspend fun await() {
        withTimeout(timeout) { completion.await() }
    }

    internal fun cancelIfPending() {
        if (!completion.isCompleted) {
            completion.completeExceptionally(
                CancellationException("Barrier cancelled at scope exit"),
            )
        }
    }
}

/**
 * Create a [Barrier] for [parties] participants and register it with this
 * scope so that any pending waiters are cancelled when the test body returns.
 *
 * @param parties number of [Barrier.arrive] calls required to trigger.
 * @param timeout maximum total wait per call to [Barrier.arrive] / [Barrier.await].
 */
fun HoldfastTestScope.barrier(parties: Int, timeout: Duration = 5.seconds): Barrier {
    val b = Barrier(parties, timeout)
    registerBarrier(b)
    return b
}
