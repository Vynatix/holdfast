@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, what storeTest itself throws (StoreTest.kt `tearDownAfter`): a
// fatal error the body throws stays what the runner sees, and one teardown
// throws is never folded into the teardown AssertionError — a fatal error
// wins over every ordinary failure and carries it as suppressed.

private class FstLeaf : Store<FstLeaf>() {
    val n by state { 0 }
}

private class FstParent : Store<FstParent>() {
    val own by state { 0 }
    val a by store { FstLeaf() }

    /** Only a property to hand `provideDelegate`: its name is the late child's. */
    val late: Int = 0

    /** Declare, after the tree is tracked, a child whose lambda throws [failure] when teardown lists the subtree. */
    fun declareFailingChild(failure: Throwable) {
        store<FstLeaf> { throw failure }.provideDelegate(this, FstParent::late)
    }
}

private fun Store<*>.fstReports(): MutableList<Throwable> =
    CopyOnWriteArrayList<Throwable>().also { seen -> uncaughtObserverHandler = { seen += it } }

class FatalStoreTestTest {
    /** StoreTest.kt:62, hint (1): the body's own fatal error is what storeTest throws; teardown still ran. */
    @Test
    fun aFatalErrorTheBodyThrowsPropagatesOutOfStoreTestAsItIs() {
        val store = FstLeaf()
        val reported = store.fstReports()
        val boom = StackOverflowError()
        lateinit var handle: StoreHandle<FstLeaf>
        val thrown =
            assertFailsWith<StackOverflowError> {
                storeTest {
                    handle = track(store)
                    handle.action { error("left unconsumed") } // suppressed: the body failed
                    handle.action { n mutate 1 }
                    throw boom
                }
            }
        assertSame(boom, thrown)
        assertTrue(thrown.suppressed.isEmpty(), "teardown had nothing to add: ${thrown.suppressed.toList()}")
        assertTrue(store.snapshotMiddleware().isEmpty(), "teardown still removed the recorder")
        assertIs<TransactionResult.Success<*>>(store action { n mutate 2 })
        assertTrue(handle.timeline.isEmpty(), "a later action records nothing")
        assertEquals(emptyList(), reported.toList())
    }

    /** StoreTest.kt:62: a body's fatal error stays in flight when teardown throws another; that one is suppressed. */
    @Test
    fun aFatalBodyErrorStaysInFlightCarryingATeardownFatalError() {
        val parent = FstParent()
        val bodyBoom = StackOverflowError("body")
        val teardownBoom = StackOverflowError("teardown")
        val thrown =
            assertFailsWith<StackOverflowError> {
                storeTest {
                    track(parent.tree)
                    parent.declareFailingChild(teardownBoom)
                    throw bodyBoom
                }
            }
        assertSame(bodyBoom, thrown, "the body's fatal error is what the runner reports")
        assertTrue(thrown.suppressed.any { it === teardownBoom }, "the teardown's rides along: ${thrown.suppressed}")
        assertTrue(parent.snapshotMiddleware().isEmpty(), "the tree recorder came off after the failed listing")
    }

    /**
     * StoreTest.kt:62, hint (2), and TreeFixtures.kt:131's body-failed
     * variant: a fatal error teardown throws after an ordinary body failure
     * replaces it and carries it — before, a listing failure after a failed
     * body was dropped silently.
     */
    @Test
    fun aTeardownFatalErrorAfterAnOrdinaryBodyFailureReplacesItAndCarriesIt() {
        val parent = FstParent()
        val reported = parent.fstReports()
        val boom = StackOverflowError()
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    track(parent.tree)
                    parent.a action { n mutate 5 }
                    parent.declareFailingChild(boom)
                    error("body")
                }
            }
        assertSame(boom, thrown, "not the body's ISE, not an AssertionError: $thrown")
        val body = thrown.suppressed.single()
        assertIs<IllegalStateException>(body)
        assertEquals("body", body.message)
        assertTrue(parent.snapshotMiddleware().isEmpty())
        assertTrue(parent.a.snapshotMiddleware().isEmpty())
        assertEquals(emptyList(), reported.toList())
    }

    /** StoreTest.kt:62, hint (3): a clean body with an unconsumed Error, then a teardown fatal error — no report. */
    @Test
    fun aTeardownFatalErrorOutranksTheUnconsumedErrorReport() {
        val parent = FstParent()
        val boom = StackOverflowError()
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    val tree = track(parent.tree)
                    tree.handle(parent.a).action { error("left unconsumed") }
                    parent.declareFailingChild(boom)
                }
            }
        assertSame(boom, thrown, "the fatal error, never the \"unconsumed\" AssertionError: $thrown")
        assertTrue(thrown.suppressed.none { it is AssertionError }, "no report was built: ${thrown.suppressed}")
    }

    /** Non-fatal control: an ordinary teardown failure after an ordinary body failure still leaves the body's. */
    @Test
    fun anOrdinaryTeardownFailureAfterAnOrdinaryBodyFailureStillLeavesTheBodys() {
        val parent = FstParent()
        val body = IllegalStateException("body")
        val thrown =
            assertFailsWith<IllegalStateException> {
                storeTest {
                    track(parent.tree)
                    parent.declareFailingChild(IllegalStateException("the late child cannot be built"))
                    throw body
                }
            }
        assertSame(body, thrown)
        assertTrue(thrown.suppressed.isEmpty(), "the suppressed listing failure is not attached: ${thrown.suppressed}")
    }

    /** Non-fatal control: a clean body still gets the listing failure as the teardown AssertionError. */
    @Test
    fun anOrdinaryTeardownFailureAfterACleanBodyIsStillTheReport() {
        val parent = FstParent()
        val failure =
            assertFailsWith<AssertionError> {
                storeTest {
                    track(parent.tree)
                    parent.declareFailingChild(IllegalStateException("the late child cannot be built"))
                }
            }
        assertContains(failure.message!!, "reset skipped — listing the subtree threw IllegalStateException")
    }
}
