@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

// Issue #20, R4: a reset in progress on one thread is invisible to every other
// thread — its reset values are pending writes like any other, and its pass
// runs initializers only on the resetting thread — and holds every state it has
// resolved against another thread's removeState/clearStates until it ends.

/**
 * Once [armed], `gate`'s initializer — re-run by a reset — signals [entered]
 * and holds the reset there until [release] opens: `count`, declared before
 * it, is already reset (staged, not committed) and `later`, declared after it,
 * still pending.
 */
private class GatedResetStore : Store<GatedResetStore>() {
    val armed = AtomicBoolean(false)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val laterRuns = AtomicInteger()
    val laterThreads = ConcurrentLinkedQueue<String>()
    val count by state { 0 }
    val gate by state {
        if (armed.get()) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the test never released the reset" }
        }
        0
    }
    val later by state {
        laterRuns.incrementAndGet()
        laterThreads += Thread.currentThread().name
        1
    }
}

/** A store with `count` and `later` committed away from their initial values, `gate` read, and the gate armed. */
private fun armedGatedStore(): GatedResetStore {
    val store = GatedResetStore()
    store action {
        count mutate 5
        later mutate 7
    }
    store.gate.value
    store.laterRuns.set(0)
    store.laterThreads.clear()
    store.armed.set(true)
    return store
}

/** Start `store.reset()` on a daemon thread and wait until it holds at the gate. */
private fun GatedResetStore.resetInBackground(result: AtomicReference<TransactionResult<Unit>>): Thread {
    val worker = daemon("reset-worker") { result.set(reset()) }
    check(entered.await(10, TimeUnit.SECONDS)) { "the reset never reached the gate" }
    return worker
}

/** `greeting` reads `name`, so it is at its reset value whenever `name` is. */
private class GreetingStore : Store<GreetingStore>() {
    val name by state { "anon" }
    val greeting by state { "hello, ${name.value}" }
}

class ResetConcurrencyTest {
    @Test fun anotherThreadReadsCommittedValuesWhileAResetIsInProgress() {
        val store = armedGatedStore()
        val result = AtomicReference<TransactionResult<Unit>>()
        val worker = store.resetInBackground(result)
        try {
            assertNotNull(store.activeTransaction?.pendingReset, "the probe runs while the reset's pass is staging")
            assertEquals(5, store.count.value, "count's reset 0 is staged, not committed: the committed 5 shows")
            assertEquals(7, store.later.value, "later's reset is still pending: the committed 7 shows")
            assertEquals(0, store.laterRuns.get(), "later's initializer did not run on the reading thread")
        } finally {
            store.release.countDown()
        }
        completesWithin(10, "the gated reset") { worker.join() }

        assertIs<TransactionResult.Success<Unit>>(result.get())
        assertEquals(0, store.count.value)
        assertEquals(1, store.later.value)
        assertEquals(listOf("reset-worker"), store.laterThreads.toList(), "only the resetting thread ran later's initializer")
    }

    @Test fun aStateRemovedWhileAResetIsInProgressIsMaterializedAgainAndReset() {
        val store = armedGatedStore()
        val result = AtomicReference<TransactionResult<Unit>>()
        val worker = store.resetInBackground(result)
        try {
            store.removeState("later") // still pending in the reset: no pending write blocks the removal
        } finally {
            store.release.countDown()
        }
        completesWithin(10, "the gated reset") { worker.join() }

        assertIs<TransactionResult.Success<Unit>>(result.get(), "later's reset materialized it again")
        assertEquals(1, store.later.value)
        assertEquals(0, store.count.value)
    }

    @Test fun anOpenResetHoldsEveryStateItResolvedAgainstRemoval() {
        val store = GreetingStore()
        assertEquals("hello, anon", store.greeting.value)
        store action { name mutate "ada" } // greeting keeps its committed "hello, anon"
        val resolved = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<TransactionResult<Unit>>()
        val worker =
            daemon("reset-worker") {
                val r =
                    store action {
                        reset().getOrThrow() // stages name = "anon"; greeting already holds its reset value
                        resolved.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "the test never released the reset" }
                    }
                result.set(r)
            }
        try {
            check(resolved.await(10, TimeUnit.SECONDS)) { "the reset never resolved its states" }
            // Removed and read now, greeting would be re-created from the committed "ada"
            // and keep "hello, ada" after the reset commits name = "anon".
            val unstaged = assertFailsWith<IllegalStateException> { store.removeState("greeting") }
            assertContains(unstaged.message.orEmpty(), "reset()")
            assertFailsWith<IllegalStateException> { store.removeState("name") }
            assertFailsWith<IllegalStateException> { store.clearStates() }
            assertEquals("hello, anon", store.greeting.value, "still the committed value, never re-created")
        } finally {
            release.countDown()
        }
        completesWithin(10, "the gated reset") { worker.join() }

        assertIs<TransactionResult.Success<Unit>>(result.get())
        assertEquals("anon", store.name.value)
        assertEquals("hello, anon", store.greeting.value)
        assertEquals(GreetingStore().snapshot().rawValues, store.snapshot().rawValues, "what a fresh store holds")

        store.removeState("greeting") // the hold ended with the reset's transaction
        assertEquals("hello, anon", store.greeting.value)
    }
}
