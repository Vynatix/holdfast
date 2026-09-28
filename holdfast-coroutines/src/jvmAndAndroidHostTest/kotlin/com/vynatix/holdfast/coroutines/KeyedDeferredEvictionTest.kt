@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.keyedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

private class DeferringDocs : Store<DeferringDocs>() {
    val tick by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

/**
 * An eviction deferred out of a suspending commit's fanout (issue #20, R7;
 * D16) never waits for the store. `suspendAction`/`suspendAtomic` drain the
 * post-commit queue in their `finally`, after releasing the store's mutex —
 * which kotlinx hands straight to the next coroutine waiting for it. On one
 * thread (runBlocking, Android's main thread, wasmJs) that coroutine only
 * runs once the drain returns, so a drained task that blocked for the mutex
 * would spin forever. The deferred eviction is a never-blocking attempt,
 * handed to that holder, which runs it once it releases the store.
 * Watchdogged: the regression is a hang.
 */
class KeyedDeferredEvictionTest {
    @Test fun anEvictionDeferredOutOfASuspendActionNeverWaitsForTheNextHolder() {
        val store = DeferringDocs()
        store.docs["a"]
        val reported = AtomicReference<Throwable?>(null)
        store.uncaughtObserverHandler = { reported.set(it) }
        store.tick effect { if (this == 1) store.docs.evict("a") }

        runBlockingWithin(20) {
            val gate = CompletableDeferred<Unit>()
            val first =
                async {
                    store.suspendAction {
                        gate.await()
                        tick mutate 1
                    }
                }
            yield() // first holds the mutex, parked on the gate
            val second = async { store.suspendAction { tick mutate 2 } }
            yield() // second waits for the mutex
            gate.complete(Unit)

            assertIs<TransactionResult.Success<Unit>>(first.await())
            assertIs<TransactionResult.Success<Unit>>(second.await())
        }

        assertFalse("a" in store.docs, "evicted once the next holder released the store")
        assertEquals(2, store.tick.value, "the second holder's write committed")
        assertNull(reported.get())
        val fresh = store.docs["a"]
        runBlockingWithin(20) { store.suspendAction { tick mutate 3 } }
        assertSame(fresh, store.docs.getOrNull("a"), "a later drain leaves an entry created again alone")
    }

    @Test fun anEvictionDeferredOutOfASuspendAtomicNeverWaitsForTheNextHolder() {
        val store = DeferringDocs()
        store.docs["a"]
        val reported = AtomicReference<Throwable?>(null)
        store.uncaughtObserverHandler = { reported.set(it) }
        store.tick effect { if (this == 1) store.docs.evict("a") }

        runBlockingWithin(20) {
            val gate = CompletableDeferred<Unit>()
            val first =
                async {
                    suspendAtomic(store) {
                        gate.await()
                        store { tick mutate 1 }
                    }
                }
            yield()
            val second = async { suspendAtomic(store) { store { tick mutate 2 } } }
            yield()
            gate.complete(Unit)

            assertIs<TransactionResult.Success<Unit>>(first.await())
            assertIs<TransactionResult.Success<Unit>>(second.await())
        }

        assertFalse("a" in store.docs)
        assertEquals(2, store.tick.value)
        assertNull(reported.get())
    }

    /** `runBlocking` on a daemon worker that fails, rather than hangs, after [seconds]. */
    private fun <R> runBlockingWithin(
        seconds: Long,
        block: suspend CoroutineScope.() -> R,
    ): R {
        val result = AtomicReference<Result<R>?>(null)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "deferred-eviction-probe") {
            result.set(runCatching { runBlocking(block = block) })
            done.countDown()
        }
        check(done.await(seconds, TimeUnit.SECONDS)) {
            "the suspending calls did not finish within ${seconds}s — a drained task is waiting for the next holder"
        }
        return checkNotNull(result.get()).getOrThrow()
    }
}
