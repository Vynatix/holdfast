@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.DerivedState
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

private class RoutedSources : Store<RoutedSources>() {
    val a by state { 0 }
    val b by state { 0 }
}

private class RoutedHost : Store<RoutedHost>() {
    val y by state { 0 }
}

/** Resume on a fresh thread: under `Dispatchers.Unconfined`, the coroutine then carries on there. */
private suspend fun hopToAnotherThread() {
    suspendCancellableCoroutine<Unit> { continuation -> thread { continuation.resume(Unit) } }
}

/**
 * A suspending commit on a source's store that fans out on another thread
 * than the one that opened it — a `suspendAction`/`suspendAtomic` that resumed
 * elsewhere after a suspension, the usual case on `Dispatchers.IO`/`Default` —
 * recomputes a derived state hosted on another store once, after the commit
 * has released its store (issue #20, R6).
 *
 * No thread holds the source's store as a blocking holder there, so the
 * recompute's deferral to such a holder does not apply. The suspending
 * entry's settle scope, which SettleAmbientContext carries across the hop to
 * the fanout thread (issue #20, R9), gives this result: the commit queues the
 * recompute there, once for every changed source, and the entry runs it after
 * releasing its store. Without that scope on the fanout thread, the routing
 * that queues the recompute on the committing store's post-commit queue
 * (rather than the host's, which would run it inline in the fanout once per
 * changed source) does.
 */
class DerivedStateSourceRoutingTest {
    private class Probe(
        sources: RoutedSources,
        host: RoutedHost,
    ) {
        val computes = AtomicInteger()
        val sourceHeld = AtomicBoolean(false)
        val fanoutThread = AtomicReference<Thread?>()
        val watch = sources.a effect { if (this != 0) fanoutThread.set(Thread.currentThread()) }
        val sum: DerivedState<Int> =
            host.derivedState(sources.a, sources.b) {
                computes.incrementAndGet()
                if (sources.activeTransaction != null) sourceHeld.set(true)
                sources.a.value + sources.b.value
            }

        fun assertRecomputedOnceAfterTheCommit(opener: Thread) {
            val fannedOutOn = assertNotNull(fanoutThread.get(), "the commit fanned out")
            assertNotSame(opener, fannedOutOn, "the commit fanned out on another thread than its opener")
            assertEquals(3, sum.value)
            assertEquals(2, computes.get(), "the initial compute, then one recompute for the commit")
            assertFalse(sourceHeld.get(), "the recompute ran after the source commit released its store")
            watch.dispose()
            sum.dispose()
        }
    }

    @Test fun aSuspendActionThatFansOutOnAnotherThreadRecomputesOnceAfterItsCommit() {
        val sources = RoutedSources()
        val probe = Probe(sources, RoutedHost())
        val opener = Thread.currentThread()

        val result =
            runBlocking {
                withContext(Dispatchers.Unconfined) {
                    sources.suspendAction {
                        a mutate 1
                        hopToAnotherThread()
                        b mutate 2
                    }
                }
            }

        result.getOrThrow()
        probe.assertRecomputedOnceAfterTheCommit(opener)
    }

    @Test fun aSuspendAtomicThatFansOutOnAnotherThreadRecomputesOnceAfterItsCommit() {
        val sources = RoutedSources()
        val probe = Probe(sources, RoutedHost())
        val opener = Thread.currentThread()

        val result: TransactionResult<Unit> =
            runBlocking {
                withContext(Dispatchers.Unconfined) {
                    suspendAtomic(sources) {
                        sources { a mutate 1 }
                        hopToAnotherThread()
                        sources { b mutate 2 }
                    }
                }
            }

        result.getOrThrow()
        probe.assertRecomputedOnceAfterTheCommit(opener)
    }
}
