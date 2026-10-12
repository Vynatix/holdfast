@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSuspendInFlightException
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
 * Since issue 005 (#60) the bare-mutate spelling with one-shot semantics is
 * the scope function (`with(store) { … }`): a `mutate` inside a bare
 * `store { }` invoke block is defused — it throws the teaching refusal to the
 * caller on the one-shot-synthesis branch, which is exactly the branch the
 * window drives (the slot is null, so there is no transaction to stage into).
 * The main row therefore drives the window with the scope-function spelling,
 * and the row after it pins the interaction this file's original vehicle ran
 * into when #60 and #62 merged: inside the window the invoke spelling is
 * refused BEFORE the synthesized action ever reaches the serializer.
 *
 * The window is two field writes wide, so it cannot be hit deterministically
 * from user space; the tests reproduce its exact observable state through the
 * module's internal seams (`ensureSerializer`, the serializer's mutex, and
 * the `@StoreInternalApi` owner slot) and complete the holder's exit in the
 * real teardown's order.
 */
class SuspendExitWindowTest {
    /**
     * The A6(ii) exit-window row, anchored deterministically. The serializer
     * is installed with a test-shrunk budget (issue 003's internal-ctor seam,
     * as `SuspendGuardBudgetTest`), and the foreign mutate retries the
     * recovery `StoreSuspendInFlightException` itself prescribes ("from a
     * blocking thread, call again once the suspending operation has
     * finished"). An escape is thrown only while the window's own two
     * conditions hold (`suspendingOwner` set, the mutex held by this test),
     * and its message names the installed holder label, so awaiting the first
     * escape needs no grace sleep hoping the foreign thread got there — the
     * waiter reports itself from inside the budgeted wait. While the window
     * is open nothing may commit, start a transaction, or throw anything
     * else; after the holder's exit completes in the real order, the retry
     * must commit its write exactly once, as its own one-shot action.
     */
    @Test
    fun foreignMutateDuringExitWindowSynthesizesOneShot() {
        val store = ExitWindowCounter()
        val recorder = StartedRecorder<ExitWindowCounter>()
        store.middlewares(recorder)
        // Install the serializer exactly as the first suspending entry would
        // adopt it — with the shrunk budget — and leave the store clean
        // afterwards.
        val serializer =
            MutexSerializer(
                storeName = store::class.simpleName ?: "Store",
                store = store,
                budget = 250.milliseconds,
            )
        store.asyncSerializer = serializer
        runBlocking { store.suspendAction { n mutate 1 } }
        assertEquals(1, store.n.value)
        recorder.started.clear()

        val firstEscape = AtomicReference<String?>(null)
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
                val escapedOnce = CountDownLatch(1)
                val failures = ConcurrentLinkedQueue<Throwable>()
                val foreign =
                    Thread {
                        try {
                            // A bare mutate from a foreign thread — no action
                            // body, just the write — in the post-005 one-shot
                            // spelling (the invoke spelling is defused; the
                            // row below pins that). The budget escape is the
                            // waiter's own report from inside the window, and
                            // the retry is its documented recovery.
                            while (true) {
                                try {
                                    with(store) { n mutate 7 }
                                    break
                                } catch (e: StoreSuspendInFlightException) {
                                    if (firstEscape.compareAndSet(null, e.message)) escapedOnce.countDown()
                                }
                            }
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            done.countDown()
                        }
                    }
                foreign.isDaemon = true
                foreign.name = "exit-window-foreign-mutate"
                foreign.start()

                // The deterministic anchor: the escape proves the foreign
                // mutate reached the serializer's budgeted wait and found the
                // window — the owner set, the epoch quiet, the mutex held —
                // not a transaction to stage into, and not a throw.
                if (!escapedOnce.await(10, TimeUnit.SECONDS)) {
                    failures.firstOrNull()?.let {
                        throw AssertionError("the foreign mutate threw before the budgeted wait: $it", it)
                    }
                    throw AssertionError("the foreign mutate never reported the budgeted wait inside the window")
                }

                // The window is still open: the original checkpoint's "still
                // waiting, not done or thrown", on evidence instead of
                // elapsed time.
                assertTrue(failures.isEmpty(), "nothing but the budget escape was thrown inside the window: $failures")
                assertEquals(1L, done.count, "the foreign mutate must still be waiting, not done or thrown")
                assertEquals(1, store.n.value, "nothing committed while the window was open")
                assertTrue(recorder.started.isEmpty(), "no transaction started while the window was open")
                val escape = firstEscape.get()
                assertTrue(
                    "suspendAction" in (escape ?: ""),
                    "the escape names the window's installed holder label; was: $escape",
                )

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
     * The #60 interaction this file hit at the #62 merge, kept pinned: the
     * original vehicle for "a bare foreign mutate" was `store { n mutate 7 }`,
     * which issue 005 defused. Inside the exit window that spelling takes the
     * one-shot-synthesis branch (the slot is null — no transaction to stage
     * into), so the teaching refusal fires to the caller BEFORE the
     * synthesized action ever reaches the serializer: not the budget escape,
     * not a wait, and nothing staged. The window's waiting contract belongs
     * to the scope-function spelling alone.
     */
    @Test
    fun bareInvokeSpellingDuringExitWindowIsDefusedBeforeTheSerializerWait() {
        val store = ExitWindowCounter()
        val recorder = StartedRecorder<ExitWindowCounter>()
        store.middlewares(recorder)
        runBlocking { store.suspendAction { n mutate 1 } }
        assertEquals(1, store.n.value)
        recorder.started.clear()

        val serializer = ensureSerializer(store)
        runBlocking {
            val ownerKey = Any()
            serializer.mutex.lock(ownerKey)
            store.suspendingOwner = ownerKey
            serializer.suspendingHolderLabel = "suspendAction"
            try {
                val done = CountDownLatch(1)
                val thrown = AtomicReference<Throwable?>(null)
                val foreign =
                    Thread {
                        try {
                            store { n mutate 7 }
                        } catch (t: Throwable) {
                            thrown.set(t)
                        } finally {
                            done.countDown()
                        }
                    }
                foreign.isDaemon = true
                foreign.name = "exit-window-bare-invoke-mutate"
                foreign.start()
                // The refusal returns without waiting out the serializer: a
                // waiter that had reached the default-budget gate would only
                // surface as the budget escape at the earliest, and one that
                // spun unbudgeted would not return at all.
                assertTrue(done.await(5, TimeUnit.SECONDS), "the defused mutate returns without waiting out the serializer")
                foreign.join(5_000)
                val t = assertIs<IllegalStateException>(thrown.get(), "the issue-005 refusal, to the caller")
                assertFalse(
                    t is StoreSuspendInFlightException,
                    "the refusal fires on the one-shot-synthesis branch, before the serializer wait",
                )
                assertTrue(
                    "bare `store { }`" in (t.message ?: ""),
                    "the refusal names the bare block; was: ${t.message}",
                )
                assertTrue(
                    "`store action { }`" in (t.message ?: ""),
                    "the refusal prescribes the mutating form; was: ${t.message}",
                )
                assertEquals(1, store.n.value, "nothing staged, nothing committed")
                assertTrue(recorder.started.isEmpty(), "no transaction ever started")
            } finally {
                store.suspendingOwner = null
                serializer.suspendingHolderLabel = null
                runCatching { serializer.mutex.unlock(ownerKey) }
            }
        }
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
