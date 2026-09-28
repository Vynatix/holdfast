@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.DerivedState
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class SettleSourceA : Store<SettleSourceA>() {
    val a by state { 0 }
}

private class SettleSourceB : Store<SettleSourceB>() {
    val b by state { 0 }
}

private class SettleHostStore : Store<SettleHostStore>() {
    val y by state { 0 }
}

/**
 * `suspendAction` and `suspendAtomic` are entries like `action` and `atomic`
 * (issue #20, R9): the derived states their commits — and every commit nested
 * in them — change a source of settle once, when the outermost entry has
 * released every store, even when the body or the commit resumes on other
 * threads (the scope travels with the coroutine, SettleAmbientContext.kt).
 * And a `suspendAtomic` applies every participant before any fans out. The
 * single-threaded cases are in the common `SuspendSettleCommonTest`.
 */
class SuspendSettleTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private inner class Probe(
        left: SettleSourceA,
        right: SettleSourceB,
        host: SettleHostStore,
    ) {
        val computes = AtomicInteger()
        val sourceHeld = AtomicBoolean(false)
        val seen = mutableListOf<Pair<Int, Int>>()
        val pair: DerivedState<Pair<Int, Int>> =
            host.derivedState(left.a, right.b) {
                computes.incrementAndGet()
                if (left.activeTransaction != null || right.activeTransaction != null) sourceHeld.set(true)
                left.a.value to right.b.value
            }

        init {
            disposables += listOf(pair effect { synchronized(seen) { seen += this } }, pair)
        }

        fun assertSettledOnce(expected: Pair<Int, Int>) {
            assertEquals(expected, pair.value)
            assertEquals(2, computes.get(), "the initial compute, then one recompute for the whole entry")
            assertEquals(listOf(0 to 0, expected), synchronized(seen) { seen.toList() })
            assertTrue(!sourceHeld.get(), "the recompute ran once the entry had released every store")
        }
    }

    /**
     * The body resumes on another thread between the two nested commits: the
     * outer entry's scope travels with the coroutine, so the second commit
     * queues into it there, and both settle once, together.
     */
    @Test fun aSuspendActionWhoseBodyHopsThreadsStillSettlesOnce() {
        val left = SettleSourceA()
        val right = SettleSourceB()
        val outer = SettleHostStore()
        val probe = Probe(left, right, SettleHostStore())
        val opener = Thread.currentThread()
        var secondCommitOn: Thread? = null

        runBlocking {
            withContext(Dispatchers.Unconfined) {
                outer
                    .suspendAction {
                        left.suspendAction { a mutate 3 }.getOrThrow()
                        resumeOnAnotherThread()
                        secondCommitOn = Thread.currentThread()
                        right.suspendAction { b mutate 3 }.getOrThrow()
                    }.getOrThrow()
            }
        }

        assertTrue(assertNotNull(secondCommitOn) !== opener, "the second commit ran on another thread")
        probe.assertSettledOnce(3 to 3)
    }

    /**
     * A blocking action on a later `suspendAtomic` participant from an
     * earlier one's observer used to wait forever for the frame, which still
     * held that participant's serializer and had not committed it yet. Every
     * participant has now applied before any fans out, so it is refused at
     * once, like one on an earlier participant.
     */
    @Test fun aBlockingActionOnALaterParticipantFromAnEarlierOnesObserverIsRefused() {
        val left = SettleSourceA()
        val right = SettleSourceB()
        assertTrue(left.lockOrderKey < right.lockOrderKey)
        var nested: TransactionResult<*>? = null
        var initial = true
        disposables +=
            left.a effect {
                if (initial) initial = false else nested = right.action { b mutate 99 }
            }

        settlesWithin(10, "a blocking action on a later suspendAtomic participant from an observer") {
            runBlocking {
                suspendAtomic(left, right) {
                    left { a mutate 1 }
                    right { b mutate 1 }
                }.getOrThrow()
            }
        }

        val refused = assertIs<TransactionResult.Error>(nested)
        assertIs<IllegalStateException>(refused.exception)
        assertTrue("has already applied its writes" in refused.exception.message.orEmpty())
        assertEquals(1, right.b.value)
    }

    /**
     * A `suspendAction` nested in another on the same store reads the same
     * owner `Job` inside the outer entry's settle scope as the outer one
     * locked the store's mutex with, so kotlinx's `Mutex` refuses it at once
     * ("already locked by the specified owner") instead of suspending forever
     * for the very coroutine that holds it — also after a thread hop.
     */
    @Test fun aNestedSuspendActionOnTheSameStoreFailsFastInsteadOfWaitingForItself() {
        for (hop in listOf(false, true)) {
            val store = SettleSourceA()
            var r: TransactionResult<*>? = null

            settlesWithin(10, "a nested suspendAction on the store its outer one holds (hop=$hop)") {
                r =
                    runBlocking {
                        withContext(Dispatchers.Unconfined) {
                            store.suspendAction {
                                if (hop) resumeOnAnotherThread()
                                store.suspendAction { a mutate 1 }.getOrThrow()
                            }
                        }
                    }
            }

            val error = assertIs<TransactionResult.Error>(r, "hop=$hop")
            val failure = assertIs<IllegalStateException>(error.exception)
            assertTrue("already locked" in failure.message.orEmpty(), failure.message)
            assertEquals(0, store.a.value)
            runBlocking { store.suspendAction { a mutate 2 }.getOrThrow() }
            assertEquals(2, store.a.value, "the outer call released the store")
        }
    }
}
