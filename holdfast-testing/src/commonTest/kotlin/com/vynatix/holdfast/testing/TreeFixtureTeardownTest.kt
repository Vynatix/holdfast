@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.testing.internal.TEARDOWN_HELD_LEAF_BUDGET
import com.vynatix.holdfast.tree.Root
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private class TdLeafStore : Store<TdLeafStore>() {
    val n by state { 0 }
}

private class TdRoot : Root("td") {
    val a = TdLeafStore()
    val b = TdLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
}

/** Vetoes every `completed` while [armed], with a message that quotes no value. */
private class TdVeto<V : Store<V>> : Middleware<V>() {
    var armed = false

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        if (armed) error("rejected by policy")
    }
}

/** How a `storeTest` run on its own thread ended: what it threw (or `null`) and how long it took. */
private class Run(
    val failure: Throwable?,
    val elapsed: Duration,
)

/** A teardown that hangs must fail the calling test, not hang it: the bound on a whole `storeTest`. */
private val NO_HANG_BOUND = 20.seconds

/** How long past the body's end the briefly-held leaf stays held: well inside the teardown's re-probe budget. */
private val BRIEF_HOLD = 100.milliseconds

/**
 * Run [test] (a `storeTest`) on its own thread and wait at most
 * [NO_HANG_BOUND] for it: `null` when it did not finish in time, so a
 * teardown that spins on the test thread fails the calling test instead of
 * hanging it.
 */
private fun runOnItsOwnThread(test: () -> Unit): Run? =
    runBlocking {
        val run =
            CoroutineScope(Dispatchers.Default).async {
                val mark = TimeSource.Monotonic.markNow()
                val failure = runCatching { test() }.exceptionOrNull()
                Run(failure, mark.elapsedNow())
            }
        withTimeoutOrNull(NO_HANG_BOUND) { run.await() }
    }

/** The tree fixture's teardown: what the reset does with a held leaf, and how failures are reported. */
class TreeFixtureTeardownTest {
    /**
     * The teardown reset is one frame over every leaf, taken through each
     * leaf's serializer. A leaf a parked `suspendAction` still holds (un-joined
     * work is not waited for) is re-probed for a bounded time, then the reset
     * is skipped and the test FAILS naming the tree and the leaf: a silent
     * skip would leak the body's values into the next test. Waiting longer
     * would spin on the test thread — the only thread that could resume the
     * body and release the leaf — so the body runs on its own thread here and
     * a regression fails this test instead of hanging it. An unconsumed error
     * of the same body is reported first, in the same `AssertionError`.
     */
    @Test
    fun teardownFailsNamingTheLeafAParkedSuspendActionStillHolds() {
        val root = TdRoot()
        val gate = CompletableDeferred<Unit>()
        val run =
            runOnItsOwnThread {
                storeTest {
                    val tree = trackTree(root)
                    tree.handle(root.b).action { error("left unconsumed") }
                    root.a action { n mutate 5 }
                    backgroundScope.launch { root.a.suspendAction { gate.await() } }
                    yield() // the body parks inside the leaf and still holds it when teardown runs
                }
            }
        assertNotNull(run, "teardown hung: the tree reset waited for a leaf a parked suspendAction holds")
        val failure = assertIs<AssertionError>(run.failure, "a leaf still held after the budget fails the test")
        val message = failure.message!!
        assertContains(message, "tree 'td'")
        assertContains(message, "leaf 'a'")
        assertContains(message, "resetAtTeardown = false")
        assertContains(message, "1 unconsumed TransactionResult.Error")
        assertTrue(
            message.indexOf("unconsumed") < message.indexOf("leaf 'a'"),
            "the unconsumed errors are reported first, then the skipped reset",
        )
        assertTrue(
            run.elapsed >= TEARDOWN_HELD_LEAF_BUDGET,
            "teardown re-probes the held leaf for the whole budget before giving up (took ${run.elapsed})",
        )
        assertEquals(5, root.a.n.value, "the reset was skipped, so the held leaf keeps the body's value")
        gate.complete(Unit)
    }

    /**
     * A leaf held by a thread inside an action when teardown begins, and
     * released within the budget — a transient holder, like an in-flight
     * `suspendDerived` recompute on `Store.scope` that no test can join — is
     * waited out: the reset runs and the test passes. The hold begins after
     * `trackTree` (the tree middleware is installed from outside every
     * entry) and lets go only once the body has ended, so teardown's first
     * probe finds the leaf held and a later one finds it free.
     */
    @Test
    fun aLeafHeldBrieflyOnAnotherThreadIsWaitedOutAndReset() {
        val root = TdRoot()
        val entered = CompletableDeferred<Unit>()
        val leaving = CompletableDeferred<Unit>()
        val holder = CompletableDeferred<Job>()
        val run =
            runOnItsOwnThread {
                storeTest {
                    trackTree(root)
                    holder.complete(
                        CoroutineScope(Dispatchers.Default).launch {
                            root.a action {
                                n mutate 7
                                entered.complete(Unit)
                                runBlocking {
                                    leaving.await()
                                    delay(BRIEF_HOLD)
                                }
                            }
                        },
                    )
                    entered.await()
                    leaving.complete(Unit) // the body ends with the leaf still held; it is released shortly after
                }
            }
        assertNotNull(run, "teardown hung: the tree reset waited for a leaf another thread's action holds")
        assertNull(run.failure, "a leaf released within the budget does not fail the test")
        runBlocking { holder.await().join() }
        assertEquals(0, root.a.n.value, "teardown waited the brief holder out, then reset the tree")
    }

    /**
     * `resetAtTeardown = false` is the opt-out for a test that deliberately
     * parks work in a leaf: no reset, no held-leaf failure, no hang.
     */
    @Test
    fun resetAtTeardownFalseWithAParkedHolderNeitherHangsNorFails() {
        val root = TdRoot()
        val gate = CompletableDeferred<Unit>()
        val run =
            runOnItsOwnThread {
                storeTest {
                    trackTree(root, resetAtTeardown = false)
                    root.a action { n mutate 5 }
                    backgroundScope.launch { root.a.suspendAction { gate.await() } }
                    yield() // the body parks inside the leaf and still holds it when teardown runs
                }
            }
        assertNotNull(run, "teardown hung: the opted-out tree must not be reset or probed")
        assertNull(run.failure, "the opt-out skips the reset without failing the test")
        assertEquals(5, root.a.n.value, "no reset: the held leaf keeps the body's value")
        gate.complete(Unit)
    }

    @Test
    fun aVetoedResetAndAnUnconsumedErrorAreReportedTogether() {
        val root = TdRoot()
        val veto = TdVeto<TdLeafStore>().also { root.b.middlewares(it) }
        val failure =
            assertFailsWith<AssertionError> {
                storeTest {
                    val tree = trackTree(root)
                    tree.handle(root.a).action { error("left unconsumed") }
                    root.b action { n mutate 1 }
                    veto.armed = true
                }
            }
        val message = failure.message!!
        assertContains(message, "1 unconsumed TransactionResult.Error")
        assertContains(message, "left unconsumed")
        assertContains(message, "could not reset")
        assertContains(message, "leaf 'b'")
        assertTrue(
            message.indexOf("unconsumed") < message.indexOf("could not reset"),
            "the unconsumed errors are reported first, then the reset",
        )
        veto.armed = false
    }
}
