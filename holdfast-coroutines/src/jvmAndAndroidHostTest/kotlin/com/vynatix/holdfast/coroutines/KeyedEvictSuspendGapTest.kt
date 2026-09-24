@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.keyedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GapStore : Store<GapStore>() {
    val tick by state { 0 }
    val items by keyedState<String, Int> { 1 }
}

/**
 * PINNED GAP (issue #20 plan, Risks: "known gap left open"). While a
 * `suspendAction` holds a store, its body may resume on any thread, and
 * nothing identifies the body's thread yet, so every thread's write stages
 * into its transaction (`Store.suspendingOwner`) — a keyed-entry eviction as
 * much as a bare `mutate`. Another thread's `evict` therefore JOINS the
 * suspending transaction before it applies (committing or rolling back with
 * it) and is refused after, instead of running as an action of its own. The
 * planned "body is running here" marker (ROADMAP 0.2.0: fail fast on a
 * blocking action inside suspendAction) would let it open its own action;
 * flip these assertions then. Watchdogged: the regression would be a hang.
 */
class KeyedEvictSuspendGapTest {
    @Test fun aForeignThreadsEvictionDuringASuspendActionBodyJoinsItsTransaction() {
        val store = GapStore()
        store.items["a"]
        store.items["b"]
        val bodyParked = CountDownLatch(1)
        val resume = CompletableDeferred<Unit>()
        val foreign =
            thread(isDaemon = true, name = "foreign-evictor") {
                bodyParked.await(5, TimeUnit.SECONDS)
                store.items.evict("a")
                resume.complete(Unit)
            }

        val rolledBack =
            runBlockingWithin(10) {
                store.suspendAction {
                    bodyParked.countDown()
                    resume.await()
                    error("the suspending body fails")
                }
            }
        foreign.join(5_000)

        assertIs<TransactionResult.Error>(rolledBack)
        assertTrue("a" in store.items, "the foreign eviction joined the transaction, and rolled back with it")

        val parkedAgain = CountDownLatch(1)
        val resumeAgain = CompletableDeferred<Unit>()
        thread(isDaemon = true, name = "foreign-evictor-2") {
            parkedAgain.await(5, TimeUnit.SECONDS)
            store.items.evict("b")
            resumeAgain.complete(Unit)
        }
        val committed =
            runBlockingWithin(10) {
                store.suspendAction {
                    parkedAgain.countDown()
                    resumeAgain.await()
                    tick mutate 1
                }
            }

        assertIs<TransactionResult.Success<Unit>>(committed)
        assertFalse("b" in store.items, "…and commits with it: no action of its own")
        assertEquals(1, store.tick.value)
    }

    @Test fun aForeignThreadsEvictionAfterTheSuspendingApplyIsRefused() {
        val store = GapStore()
        store.items["a"]
        val inPublish = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        store {
            tick bridge
                object : SuspendingBridge<Int> {
                    override suspend fun publishAwaited(value: Int) {
                        inPublish.countDown()
                        release.await()
                    }

                    override fun publish(value: Int): Boolean = true

                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}
                }
        }
        val refused = AtomicReference<Throwable?>(null)
        thread(isDaemon = true, name = "foreign-evictor") {
            inPublish.await(5, TimeUnit.SECONDS)
            refused.set(runCatching { store.items.evict("a") }.exceptionOrNull())
            release.complete(Unit)
        }

        runBlockingWithin(10) { store.suspendAction { tick mutate 1 } }

        val error = assertIs<IllegalStateException>(assertNotNull(refused.get(), "the eviction must be refused"))
        val message = error.message.orEmpty()
        assertTrue("Cannot evict an entry of GapStore.items" in message, message)
        assertTrue("a suspendAction or suspendAtomic holds GapStore" in message, message)
        assertTrue("a" in store.items)
        assertNull(store.activeTransaction)
    }

    /** `runBlocking` on a daemon worker that fails, rather than hangs, after [seconds]. */
    private fun <R> runBlockingWithin(
        seconds: Long,
        block: suspend () -> R,
    ): R {
        val result = AtomicReference<Result<R>?>(null)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "suspend-gap-probe") {
            result.set(runCatching { runBlocking { block() } })
            done.countDown()
        }
        check(done.await(seconds, TimeUnit.SECONDS)) { "the suspendAction did not finish within ${seconds}s" }
        return checkNotNull(result.get()).getOrThrow()
    }
}
