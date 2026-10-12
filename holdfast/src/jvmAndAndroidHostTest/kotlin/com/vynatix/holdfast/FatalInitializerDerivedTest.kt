@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentComputeReadsLocal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61: a fatal error from a state initializer, a keyed entry's
// initializer or a derivedState compute propagates out of the read, the
// derivedState call or the entry whose settle recomputed — never reported —
// and leaves nothing behind: the latch is released (the state stays
// retryable, for every thread, and is never a false cycle), the compute's
// cut and slot are undone, and the derived state's follower is dropped or its
// value kept.

/** [x]'s and [docs]`["bad"]`'s initializers throw [fatal]; [runs] counts their runs. */
private class FidInitStore(
    private val fatal: Throwable,
) : Store<FidInitStore>() {
    val runs = AtomicInteger()
    val n by state { 0 }
    val x by state<Int> {
        runs.incrementAndGet()
        throw fatal
    }
    val docs by keyedState<String, Int> { key ->
        if (key == "bad") {
            runs.incrementAndGet()
            throw fatal
        }
        0
    }
}

private class FidStore : Store<FidStore>() {
    val a by state { 1 }
    val n by state { 0 }
}

private fun Store<*>.recordUncaught(): List<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

private fun assertThreadClean() {
    assertNull(SettleScopes.current(), "no settle scope left open on this thread")
    assertNull(FrameMarkers.current(), "no frame marker left on this thread")
    assertNull(currentComputeReadsLocal(), "no compute's cut left on this thread")
}

private fun FidStore.cutReadersOfA(): Int = checkNotNull(a.observableBacking()).cutReaders.value

class FatalInitializerDerivedTest {
    @Test
    fun anInitializersFatalErrorReleasesTheLatchAndStaysRetryableOnEveryThread() {
        val fatal = StackOverflowError()
        val store = FidInitStore(fatal)
        val seen = store.recordUncaught()

        assertSame(fatal, assertFailsWith<StackOverflowError> { store.x })
        assertSame(fatal, assertFailsWith<StackOverflowError> { store.x }, "retryable, not a 'State initializer cycle'")
        assertEquals(2, store.runs.get())
        completesWithin(10, "a first read of the state from another thread") {
            assertSame(fatal, assertFailsWith<StackOverflowError> { store.x }, "the latch was released")
        }
        assertEquals(3, store.runs.get())
        // snapshot() materializes every declared state: the same first read.
        assertSame(fatal, assertFailsWith<StackOverflowError> { store.snapshot() })
        assertEquals(4, store.runs.get())
        assertEquals(0, InitializerGraph.Process.waitingCount)

        assertEquals(emptyList(), seen)
        assertNull(store.activeTransaction)
        assertThreadClean()
        store action { n mutate 1 }
        assertEquals(1, store.n.value)
    }

    @Test
    fun aKeyedEntryInitializersFatalErrorLeavesNoEntryAndStaysRetryable() {
        val fatal = StackOverflowError()
        val store = FidInitStore(fatal)
        val seen = store.recordUncaught()

        assertSame(fatal, assertFailsWith<StackOverflowError> { store.docs["bad"] })
        assertSame(fatal, assertFailsWith<StackOverflowError> { store.docs["bad"] })
        assertEquals(2, store.runs.get())
        assertEquals(emptySet(), store.docs.entries.keys, "no stale live entry")
        assertEquals(0, store.docs["ok"].value)
        assertEquals(setOf("ok"), store.docs.entries.keys)

        assertEquals(emptyList(), seen)
        assertThreadClean()
        store action { docs["ok"] mutate 1 }
        assertEquals(1, store.docs["ok"].value)
    }

    @Test
    fun anInitialComputesFatalErrorUndoesItsCutAndLeavesNoFollower() {
        val store = FidStore()
        val seen = store.recordUncaught()
        val fatal = StackOverflowError()
        val computes = AtomicInteger()

        val thrown =
            assertFailsWith<StackOverflowError> {
                store.derivedState<FidStore, Int>(store.a) {
                    computes.incrementAndGet()
                    a.value
                    throw fatal
                }
            }

        assertSame(fatal, thrown)
        assertEquals(1, computes.get())
        assertEquals(0, store.cutReadersOfA(), "the cut taken over the source is released")
        assertThreadClean()
        store action { a mutate 5 }
        assertEquals(5, store.a.value, "a read on this thread sees the commit, not a stale cut")
        assertEquals(1, computes.get(), "the follower is gone: nothing recomputes")
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aRecomputesFatalErrorComesOutOfTheEntryThatSettledItWithTheSourceCommitted() {
        val store = FidStore()
        val seen = store.recordUncaught()
        val fatal = StackOverflowError()
        val derived = store.derivedState(store.a) { if (a.value == 2) throw fatal else a.value }
        assertEquals(1, derived.value)

        assertSame(fatal, assertFailsWith<StackOverflowError> { store action { a mutate 2 } })

        assertEquals(2, store.a.value, "the source committed")
        assertEquals(1, derived.value, "the recompute rolled back")
        assertEquals(emptyList(), seen)
        assertEquals(0, store.cutReadersOfA())
        assertNull(store.activeTransaction)
        assertThreadClean()
        store action { a mutate 3 }
        assertEquals(3, derived.value, "nothing was stranded: the next commit recomputes")
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aNonFatalRecomputeFailureIsStillReportedAndTheActionSucceeds() {
        val store = FidStore()
        val seen = store.recordUncaught()
        val failure = IllegalStateException("recompute refused")
        val derived = store.derivedState(store.a) { if (a.value == 2) throw failure else a.value }

        val result = store action { a mutate 2 }

        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(2, store.a.value)
        assertEquals(1, derived.value)
        assertTrue(seen.any { it === failure || it.cause === failure }, "$seen")
        store action { a mutate 3 }
        assertEquals(3, derived.value)
    }
}
