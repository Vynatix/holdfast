package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

private class Counter : Store<Counter>() {
    val count by state { 0 }
}

private class Cart : Store<Cart>() {
    val qty by state { 1 }
    val price by state { 10 }

    /** Read by no derived: a commit that writes only this queues no recompute of its own. */
    val note by state { "" }
}

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds].
 *
 * Most cases in this file fail by *spinning*, not by throwing: the serializer's
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
 * entry point. The first three regressed together and share one root cause;
 * the fourth is the serializer's shared blocking owner, and the rest cover the
 * derived recompute's hand-off to a coroutine that was handed the mutex.
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

    /**
     * Every blocking acquire used to lock the serializer's mutex with ONE
     * shared owner. kotlinx `Mutex.tryLock(owner)` throws — rather than
     * returning `false` — when that owner already holds the lock, so while one
     * thread's blocking action held the serializer, a second thread's blocking
     * action on the same store died with a raw
     * "This mutex is already locked by the specified owner" instead of waiting.
     * Each blocking acquire now locks with its own token.
     */
    @Test
    fun `blocking actions on two threads all commit once suspendAction has installed the serializer`() {
        val store = Counter()
        runBlocking { store.suspendAction { count mutate 0 } }

        val perThread = 2_000
        val start = CyclicBarrier(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        completesWithin(60, "overlapping blocking actions on two threads") {
            val workers =
                List(2) { n ->
                    Thread {
                        try {
                            start.await(10, TimeUnit.SECONDS)
                            // Thousands of back-to-back actions from both threads, released
                            // together: the other thread's acquire meets a held serializer
                            // many times over.
                            repeat(perThread) {
                                val result = store action { count update { it + 1 } }
                                result.getOrThrow()
                            }
                        } catch (e: Throwable) {
                            failures += e
                        }
                    }.apply {
                        isDaemon = true
                        name = "blocking-action-$n"
                        start()
                    }
                }
            workers.forEach { it.join() }
        }

        failures.firstOrNull()?.let { throw AssertionError("a blocking action failed: $it", it) }
        assertEquals(2 * perThread, store.count.value, "every increment must commit exactly once")
    }

    /**
     * kotlinx `Mutex.unlock` hands ownership straight to the first queued
     * waiter, which then holds the mutex before it has resumed. On a
     * single-threaded event loop, the first `suspendAction`'s post-commit drain
     * therefore met a mutex held by a coroutine that needed this very thread:
     * the derived recompute's blocking acquire spun on it forever. The
     * recompute now makes one non-blocking attempt, hands itself to the new
     * holder, and that holder runs it after its own commit.
     *
     * The second action writes only `note`, which no derived reads, so its own
     * commit queues no recompute: only the handed-off one can move the derived
     * from 10 to 20. Each value the derived commits is recorded with the `note`
     * committed at that moment — `""` would mean the recompute ran in the
     * first action's drain (the second was not queued, so no hand-off
     * happened), `"x"` that it ran in the second action's.
     */
    @Test
    fun `a derived recompute does not spin on a mutex handed to a queued suspendAction`() {
        val store = Cart()
        val (total, dispose) = store.derived(store.qty, store.price) { qty.value * price.value }
        val seen = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        val sub = total effect { seen += this to store.note.value }
        try {
            completesWithin(10, "two suspendActions and a derived on one event-loop thread") {
                runBlocking {
                    val entered = CompletableDeferred<Unit>()
                    val gate = CompletableDeferred<Unit>()
                    val first =
                        launch {
                            store.suspendAction {
                                qty mutate 2
                                entered.complete(Unit)
                                gate.await()
                            }
                        }
                    entered.await()
                    // UNDISPATCHED runs `second` right here up to its first
                    // suspension: its wait on the mutex `first` holds.
                    val second = launch(start = CoroutineStart.UNDISPATCHED) { store.suspendAction { note mutate "x" } }
                    gate.complete(Unit)
                    joinAll(first, second)
                }
            }
            assertEquals(
                listOf(10 to "", 20 to "x"),
                seen.toList(),
                "the first action's drain must hand the recompute to the queued suspendAction, which runs it after its own commit",
            )
            assertEquals(20, total.value, "the second suspendAction must drain the recompute handed to it (qty 2 x price 10)")
        } finally {
            sub.dispose()
            dispose.dispose()
        }
    }

    /**
     * The same hand-off, to a queued `suspendAtomic` that is cancelled before
     * it resumes. kotlinx's prompt cancellation then releases the mutex it was
     * handed and `lock` throws — but the recompute handed to it is still
     * queued, and nobody else is left to drain it.
     */
    @Test
    fun `a suspendAtomic cancelled after being handed the mutex still drains the recompute handed to it`() {
        val store = Cart()
        val (total, dispose) = store.derived(store.qty, store.price) { qty.value * price.value }
        try {
            completesWithin(10, "a cancelled suspendAtomic waiter and a derived on one event-loop thread") {
                runWithWaiterCancelledAfterHandOff(store) { suspendAtomic(store) { store { price mutate 7 } } }
            }
            assertEquals(10, store.price.value, "the cancelled frame never ran its body")
            assertEquals(20, total.value, "the cancelled waiter must drain the recompute handed to it (qty 2 x price 10)")
        } finally {
            dispose.dispose()
        }
    }

    /**
     * As above, for a store the frame acquires after another one: the
     * cancellation unwinds the earlier store's drain, never this one's.
     *
     * The drain must also wait until the frame has unwound the earlier store:
     * while its fresh root is still installed, an observer of the recompute
     * that writes to that store stages the write into a root nobody commits,
     * and the write is lost without an error.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun `a multi-store suspendAtomic cancelled after being handed a later store's mutex still drains that store`() {
        // Constructed first, so it sorts first: `store` is acquired second.
        val other = Counter()
        val store = Cart()
        assertTrue(other.lockOrderKey < store.lockOrderKey)
        val (total, dispose) = store.derived(store.qty, store.price) { qty.value * price.value }
        // Fires once right away (10), then on the recompute's commit.
        val sub =
            total effect {
                val t = this
                other { count mutate t }
            }
        try {
            completesWithin(10, "a cancelled multi-store suspendAtomic waiter and a derived on one event-loop thread") {
                runWithWaiterCancelledAfterHandOff(store) { suspendAtomic(other, store) { store { price mutate 7 } } }
            }
            assertEquals(10, store.price.value, "the cancelled frame never ran its body")
            assertEquals(20, total.value, "the cancelled waiter must drain the recompute handed to it (qty 2 x price 10)")
            assertEquals(20, other.count.value, "an observer of the recompute must commit its write to the earlier participant")
        } finally {
            sub.dispose()
            dispose.dispose()
        }
    }

    /**
     * The normal-path counterpart: a frame that commits drains a later
     * participant only after it has released the earlier one. Draining at the
     * later store's own unwind step ran the recompute while the earlier store
     * still held the frame's finished root and its mutex, so an observer's
     * write to it threw into the uncaught-observer handler instead of
     * committing.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun `a suspendAtomic drains a later participant only after releasing the earlier ones`() {
        // Constructed first, so it sorts first: `store` is acquired second.
        val other = Counter()
        val store = Cart()
        assertTrue(other.lockOrderKey < store.lockOrderKey)
        val (total, dispose) = store.derived(store.qty, store.price) { qty.value * price.value }
        val failures = ConcurrentLinkedQueue<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        val sub =
            total effect {
                val t = this
                other { count mutate t }
            }
        try {
            completesWithin(10, "a two-store suspendAtomic whose derived writes to the earlier store") {
                runBlocking { suspendAtomic(other, store) { store { qty mutate 3 } } }
            }
            assertEquals(emptyList<Throwable>(), failures.toList(), "the observer's write must not throw")
            assertEquals(30, total.value)
            assertEquals(30, other.count.value, "an observer of the recompute must commit its write to the earlier participant")
        } finally {
            sub.dispose()
            dispose.dispose()
        }
    }

    /**
     * On one `runBlocking` thread: a `suspendAction` on [store] writes
     * `qty` = 2 and parks; [waiterBody] is then started and queues on
     * [store]'s mutex; the action is released, and — once its unlock has
     * handed the mutex to the waiter and its drain has handed the derived
     * recompute to the waiter too — it cancels the waiter before the waiter
     * resumes.
     */
    private fun runWithWaiterCancelledAfterHandOff(
        store: Cart,
        waiterBody: suspend () -> Unit,
    ) {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            lateinit var waiter: Job
            val first =
                launch {
                    store.suspendAction {
                        qty mutate 2
                        entered.complete(Unit)
                        gate.await()
                    }
                    waiter.cancel()
                }
            entered.await()
            waiter = launch(start = CoroutineStart.UNDISPATCHED) { waiterBody() }
            gate.complete(Unit)
            joinAll(first, waiter)
        }
    }
}
