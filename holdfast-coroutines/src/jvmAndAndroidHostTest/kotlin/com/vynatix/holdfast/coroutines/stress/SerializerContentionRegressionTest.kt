package com.vynatix.holdfast.coroutines.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAction
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScCounterStore : Store<ScCounterStore>() {
    val count by state { 0 }
}

private class ScAccountStore : Store<ScAccountStore>() {
    val balance by state { 1_000 }
}

/**
 * Regression pins for the blocking bracket of the `AsyncSerializer`.
 *
 * `MutexSerializer.blockingAcquire` spins on `Mutex.tryLock(owner)`. kotlinx's
 * `tryLock(owner)` does not return `false` when the mutex is already held by
 * that same owner token — it throws `IllegalStateException("This mutex is
 * already locked by the specified owner")`. With a single shared owner token
 * for every blocking caller, the second of two concurrent blocking callers
 * on a store that had ever run a `suspendAction` threw that raw exception out
 * of `Store.action` (before its `try`, so not even as a `TransactionResult.Error`)
 * and out of `atomic()`.
 *
 * Both tests here fail on that code and pass once each blocking acquire uses
 * an owner that cannot collide (or no owner at all).
 */
class SerializerContentionRegressionTest {
    private fun runContended(
        threads: Int,
        namePrefix: String,
        body: (Int) -> Unit,
    ) {
        val start = CyclicBarrier(threads)
        val failure = AtomicReference<Throwable?>(null)
        val workers =
            (0 until threads).map { t ->
                Thread {
                    try {
                        start.await(10, TimeUnit.SECONDS)
                        body(t)
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }.apply {
                    isDaemon = true
                    name = "$namePrefix-$t"
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join(60_000) }
        workers.forEach { assertTrue(!it.isAlive, "${it.name} did not finish — a blocking caller is stuck") }
        failure.get()?.let { throw AssertionError("a blocking caller threw under contention: $it", it) }
    }

    @Test
    fun `concurrent blocking actions on a serializer-installed store never throw and conserve exactly`() {
        val store = ScCounterStore()
        assertEquals(0, store.count.value)
        // Installs the store's AsyncSerializer; every later blocking action brackets itself with it.
        runBlocking { assertIs<TransactionResult.Success<*>>(store.suspendAction { }) }
        val threads = 4
        val iterations = 500
        runContended(threads, "sc-blocking-action") {
            repeat(iterations) {
                val result = store action { count update { it + 1 } }
                assertIs<TransactionResult.Success<*>>(result, "every contended blocking action must commit")
            }
        }
        assertEquals(threads * iterations, store.count.value, "every increment must land exactly once")
    }

    @Test
    fun `concurrent blocking atomic frames on serializer-installed stores never throw and conserve exactly`() {
        val a = ScAccountStore()
        val b = ScAccountStore()
        assertEquals(1_000, a.balance.value)
        assertEquals(1_000, b.balance.value)
        runBlocking {
            assertIs<TransactionResult.Success<*>>(a.suspendAction { })
            assertIs<TransactionResult.Success<*>>(b.suspendAction { })
        }
        val threads = 4
        val iterations = 250
        runContended(threads, "sc-blocking-atomic") { t ->
            val (from, to) = if (t % 2 == 0) a to b else b to a
            repeat(iterations) {
                val result =
                    atomic(from, to) {
                        from.action { balance update { it - 1 } }
                        to.action { balance update { it + 1 } }
                    }
                assertIs<TransactionResult.Success<*>>(result, "every contended frame must commit")
            }
        }
        assertEquals(2_000, a.balance.value + b.balance.value, "transfers must conserve the total")
        assertEquals(1_000, a.balance.value, "two threads each way cancel out exactly")
    }
}
