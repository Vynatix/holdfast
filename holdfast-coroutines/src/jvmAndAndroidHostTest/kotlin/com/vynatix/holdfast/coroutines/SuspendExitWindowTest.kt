@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ExitWindowCounter : Store<ExitWindowCounter>() {
    val n by state { 0 }
}

/** Records the id of every transaction whose `started` hook fires. */
private class StartedRecorder<V : Store<V>> : Middleware<V>() {
    val started = Collections.synchronizedList(mutableListOf<String>())

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        started += context.transaction.id
    }
}

/**
 * Issue 002 residue, audit rows 22/23 (A6): pins for the suspending entries'
 * install-window hygiene and exit-window order. Both need threads or the
 * module's internal seams, so they live here (jvm + android host) rather than
 * in commonTest.
 *
 * The exit-window row: the entry's finally clears the transaction slot FIRST
 * and the suspending owner SECOND, and only then does the mutex bracket
 * release — so there is a window where the store has no transaction but still
 * names a suspending owner while holding the mutex.
 *
 * The audit dissolved the spec's reorder rationale (writes into a dead
 * transaction are refused loudly since #20 D16), and the current order is
 * load-bearing in the other direction (`ownsActiveTransaction` and
 * `appliedTransactionNestedHere` read owner-first and rely on
 * slot-cleared-before-owner; the serializer's leaked-owner self-heal relies on
 * owner-cleared-before-unlock). What remains to pin is that the window strands
 * nothing: a bare foreign `mutate` arriving inside it finds no transaction to
 * stage into and synthesizes its own one-shot action, which waits out the
 * serializer (budgeted, issue 003) and commits once the holder finishes its
 * exit.
 *
 * The window is two field writes wide, so it cannot be hit deterministically
 * from user space; the test reproduces its exact observable state through the
 * module's internal seams (`ensureSerializer`, the serializer's mutex, and the
 * `@StoreInternalApi` owner slot) and completes the holder's exit in the real
 * teardown's order.
 */
class SuspendExitWindowTest {
    @Test
    fun foreignMutateDuringExitWindowSynthesizesOneShot() {
        val store = ExitWindowCounter()
        val recorder = StartedRecorder<ExitWindowCounter>()
        store.middlewares(recorder)
        // Install the serializer exactly as the first suspending entry would,
        // and leave the store clean afterwards.
        runBlocking { store.suspendAction { n mutate 1 } }
        assertEquals(1, store.n.value)
        recorder.started.clear()

        val serializer = ensureSerializer(store)
        runBlocking {
            val ownerKey = Any()
            serializer.mutex.lock(ownerKey)
            // The exit window: the transaction slot is already null (the
            // finally's first clear), the suspending owner is still installed
            // (its second clear), the mutex is still held (withLock releases
            // only after the finally returns).
            store.suspendingOwner = ownerKey
            serializer.suspendingHolderLabel = "suspendAction"
            try {
                val done = CountDownLatch(1)
                val failures = ConcurrentLinkedQueue<Throwable>()
                val foreign =
                    Thread {
                        try {
                            // A bare mutate from a foreign thread — no action
                            // body, just the write.
                            store { n mutate 7 }
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            done.countDown()
                        }
                    }
                foreign.isDaemon = true
                foreign.name = "exit-window-foreign-mutate"
                foreign.start()
                // Give the foreign write time to reach the serializer's
                // blocking acquire. It must WAIT there — the budget clock is
                // running (the owner is set, the epoch quiet) — not throw, and
                // with the slot null there is no transaction it could stage
                // into either.
                Thread.sleep(200)
                assertEquals(1L, done.count, "the foreign mutate must still be waiting, not done or thrown")

                // Complete the holder's exit in the real order: owner cleared,
                // then the mutex released.
                store.suspendingOwner = null
                serializer.suspendingHolderLabel = null
                serializer.mutex.unlock(ownerKey)

                assertTrue(done.await(5, TimeUnit.SECONDS), "the foreign mutate must not be stranded by the exit window")
                foreign.join(5_000)
                failures.firstOrNull()?.let { throw AssertionError("the foreign mutate threw: $it", it) }
            } finally {
                // Self-heal if an assertion above skipped the exit's steps.
                store.suspendingOwner = null
                serializer.suspendingHolderLabel = null
                runCatching { serializer.mutex.unlock(ownerKey) }
            }
        }

        assertEquals(7, store.n.value, "the foreign write committed exactly once")
        assertEquals(1, recorder.started.size, "the foreign write ran as its own one-shot action (one transaction)")
        assertNull(store.suspendingOwner)
        assertNull(store.activeTransaction)
    }

    /**
     * Why the install-window hygiene (A6(i)) matters: the damage a leaked
     * install does, pinned through the internal seams. With a dead-but-Active
     * root in the slot and the suspending owner still named — exactly what a
     * pre-body throw left behind before the installs moved inside the
     * protected try, the mutex already released by then — a later blocking
     * action opens a SAVEPOINT of the dead root: it returns `Success` while
     * its writes merge into a buffer nobody ever applies. On the owner thread
     * even the read lies (the pending chain answers), so the committed value
     * is read from another thread: unchanged. The restructure makes this
     * state unreachable from the entry paths; this row keeps the failure mode
     * it closed visible.
     */
    @Test
    fun aLeakedInstalledRootSilentlySwallowsLaterWrites() {
        val store = ExitWindowCounter()
        runBlocking { store.suspendAction { n mutate 1 } }

        val deadRoot = Transaction.createForExternal("leaked", currentThreadId())
        store.internalSetActiveTransaction(deadRoot)
        store.suspendingOwner = Any()

        val r = store action { n mutate 2 }

        assertIs<TransactionResult.Success<*>>(r, "the write reports success")
        val committed = AtomicInteger(-1)
        val reader =
            Thread {
                committed.set(store.n.value)
            }
        reader.isDaemon = true
        reader.start()
        reader.join(5_000)
        assertEquals(
            1,
            committed.get(),
            "the committed value never moved: the write merged into the dead root, which nobody applies",
        )
        assertEquals(
            TransactionStatus.Active,
            deadRoot.status,
            "the dead root is still Active — the write sits in its buffer forever",
        )

        store.internalSetActiveTransaction(null)
        store.suspendingOwner = null
    }
}
