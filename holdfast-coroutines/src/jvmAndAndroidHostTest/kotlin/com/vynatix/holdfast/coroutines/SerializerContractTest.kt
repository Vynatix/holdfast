package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.derived
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

private class Counter : Store<Counter>() {
    val count by state { 0 }
}

private class Cart : Store<Cart>() {
    val qty by state { 1 }
    val price by state { 10 }
}

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds].
 *
 * Every case in this file fails by *spinning*, not by throwing: the serializer's
 * acquire is a `tryLock`/yield loop, so a regression pins a core in `RUNNABLE`
 * forever and would otherwise burn the whole 10-minute test-task cap
 * (`holdfast.kmp.library.gradle.kts`) before reporting anything. The worker is a
 * daemon so a regression cannot keep the JVM alive after the failure is reported.
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
    worker.name = "serializer-contract-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — the serializer is being spun on, not released")
    }
    thrown.get()?.let { throw it }
}

/**
 * The `AsyncSerializer` installed by the first `suspendAction` on a store is
 * permanent and non-reentrant, while `Store.action` is reentrant by design
 * (savepoints) and is re-entered by the library itself (`derived` recompute).
 *
 * Each test here pairs two documented, first-class features on one store and
 * asserts they still compose after the store has been touched by a coroutine
 * entry point. All three regressed together and share one root cause.
 */
class SerializerContractTest {
    /**
     * Nested `action` is the documented savepoint mechanism. After the
     * serializer is installed, the inner `action`'s `blockingAcquire` hits a
     * mutex already held by the outer one — kotlinx `Mutex.tryLock(owner)`
     * throws for the same owner, and the raw error folds into an ignorable
     * `TransactionResult.Error` that takes the outer action's writes with it.
     */
    @Test
    fun `nested action still opens a savepoint after suspendAction installs the serializer`() {
        val store = Counter()

        // Baseline: savepoints work before any coroutines-module contact.
        val before =
            store action {
                count update { it + 1 }
                store action { count update { it + 1 } }
            }
        assertIs<TransactionResult.Success<*>>(before, "baseline: nested action should commit")
        assertEquals(2, store.count.value, "baseline: both savepoint writes should land")

        runBlocking { store.suspendAction { count mutate 0 } }

        completesWithin(10, "nested action after suspendAction") {
            val after =
                store action {
                    count update { it + 1 }
                    store action { count update { it + 1 } }
                }
            assertIs<TransactionResult.Success<*>>(
                after,
                "nested action must still commit once the serializer is installed",
            )
        }
        assertEquals(2, store.count.value, "nested savepoint writes must not be lost")
    }

    /**
     * `suspendAction` drains the post-commit queue in its `finally`. While that
     * drain ran inside `serializer.mutex.withLock`, a `derived` recompute — a
     * blocking `action` — spun on the mutex its own call stack was holding.
     * `suspendAtomic` already drains after releasing; this asserts
     * `suspendAction` does too.
     */
    @Test
    fun `suspendAction recomputes derived state without deadlocking on its own mutex`() {
        val store = Cart()
        val (total, dispose) = store.derived(store.qty, store.price) { qty.value * price.value }
        try {
            assertEquals(10, total.value)

            completesWithin(10, "suspendAction on a store with a derived state") {
                runBlocking { store.suspendAction { qty mutate 4 } }
                assertEquals(40, total.value, "derived state must recompute after a suspending commit")
            }
        } finally {
            dispose.dispose()
        }
    }

    /**
     * The blocking path drained inside the serializer bracket too. There the
     * recompute's `blockingAcquire` throws instead of spinning (same owner), and
     * `drainPostCommitTasks` swallows it in `runCatching` — so the derived state
     * silently froze with no error anywhere.
     */
    @Test
    fun `blocking action recomputes derived state after the store has seen suspendAction`() {
        val store = Cart()
        val (total, dispose) = store.derived(store.qty, store.price) { qty.value * price.value }
        try {
            // The setup call is itself the deadlocking pair, so it is watchdogged too.
            completesWithin(10, "blocking action on a store with a derived state") {
                runBlocking { store.suspendAction { price mutate 20 } }
                assertEquals(20, total.value, "derived state must track the suspending commit")

                val result = store action { qty mutate 5 }
                assertIs<TransactionResult.Success<*>>(result, "blocking action must commit")
                assertEquals(100, total.value, "derived recompute must not be silently swallowed")
            }
        } finally {
            dispose.dispose()
        }
    }
}
