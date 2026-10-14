@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.testing.concurrency.OpenTransaction
import com.vynatix.holdfast.testing.concurrency.transaction
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

// Issue #61, StoreTestScope.tearDown and the steps it runs: a fatal error one
// teardown step throws — a leaked open transaction's rollback drain, a tree's
// listing, a tree's reset — is held while every later step still runs (the
// other rollbacks, the recorders, the clocks, every other tree, the registry
// clear), then thrown out of storeTest instead of the AssertionError report.

private class FtdCounter : Store<FtdCounter>() {
    val count by state { 0 }
}

private class FtdSource : Store<FtdSource>() {
    val x by state { 1 }
}

private class FtdLeaf : Store<FtdLeaf>() {
    val n by state { 0 }
}

private class FtdParent : Store<FtdParent>() {
    val a by store { FtdLeaf() }

    /** Only a property to hand `provideDelegate`: its name is the late child's. */
    val late: Int = 0

    /** Declare, after the tree is tracked, a child whose lambda throws [failure] when teardown lists the subtree. */
    fun declareFailingChild(failure: Throwable) {
        store<FtdLeaf> { throw failure }.provideDelegate(this, FtdParent::late)
    }
}

/** A child whose state initializer throws [failure] once it is set: what a teardown reset re-runs. */
private class FtdResetChild : Store<FtdResetChild>() {
    @Volatile var failure: Throwable? = null
    val x by state { failure?.let { throw it } ?: 0 }
}

private class FtdResetParent : Store<FtdResetParent>() {
    val child by store { FtdResetChild() }
}

private class FtdFixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

/** Throws [failure] from `onTransactionStarted` once it is set. */
private class FtdStartedThrows<V : Store<V>> : Middleware<V>() {
    @Volatile var failure: Throwable? = null

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        failure?.let { throw it }
    }
}

/** A tree middleware that throws [failure] from `onTransactionStarted` once it is set. */
private class FtdTreeStartedThrows : TreeMiddleware() {
    @Volatile var failure: Throwable? = null

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        failure?.let { throw it }
    }
}

/** Throws [failure] from `onStoreReset` once it is set. */
private class FtdResetAttachment : StoreAttachment {
    @Volatile var failure: Throwable? = null

    override fun onStoreReset() {
        failure?.let { throw it }
    }
}

private val ftdResetKey = StoreAttachmentKey<FtdResetAttachment>("FatalTeardownTest.reset")

private fun Store<*>.ftdReports(): MutableList<Throwable> =
    CopyOnWriteArrayList<Throwable>().also { seen -> uncaughtObserverHandler = { seen += it } }

class FatalTeardownTest {
    /**
     * StoreTestScope.kt:228 with OpenTransactionRegistry.kt:59 and
     * PrivilegedHooks.kt:365: two leaked open transactions, A's first. A
     * hosts a `derived` whose source commits while A's transaction is open,
     * so the recompute is handed to A's queue; teardown's rollback of A
     * drains it and its compute overflows. Every later step still runs: B's
     * rollback, both recorders off, B's clock back to its pre-track binding,
     * a tracked tree reset, the registry cleared — then the error is thrown.
     */
    @Test
    fun aRollbackDrainsFatalErrorIsHeldWhileEveryLaterTeardownStepRuns() {
        val a = FtdCounter()
        val b = FtdCounter()
        val source = FtdSource()
        val parent = FtdParent()
        val reported = listOf(a, b, source).associateWith { it.ftdReports() }
        val preTrack = FtdFixedClock(Instant.fromEpochMilliseconds(1))
        val testClock = FtdFixedClock(Instant.fromEpochMilliseconds(2))
        b.bindClock(preTrack)
        val boom = StackOverflowError()
        val armed = AtomicBoolean(false)
        val (mirror, mirrorSub) = a.derived(source.x) { if (armed.get()) throw boom else source.x.value * 10 }
        lateinit var scope: StoreTestScope
        lateinit var ha: StoreHandle<FtdCounter>
        lateinit var hb: StoreHandle<FtdCounter>
        lateinit var openA: OpenTransaction
        lateinit var openB: OpenTransaction
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    scope = this
                    ha = track(a)
                    hb = track(b)
                    b.bindClock(testClock)
                    track(parent.tree)
                    parent.a action { n mutate 6 }
                    openA = transaction(on = ha) { count mutate 5 }
                    openB = transaction(on = hb) { count mutate 7 }
                    armed.set(true)
                    source action { x mutate 4 }
                    assertEquals(10, mirror.value, "A is held by its open transaction: the recompute waits in its queue")
                }
            }
        armed.set(false)
        try {
            assertSame(boom, thrown, "the drained recompute's error, not an AssertionError: $thrown")
            assertTrue(openA.isClosed)
            assertEquals(TransactionStatus.RolledBack, openA.transaction.status)
            assertNull(a.activeTransaction)
            assertTrue(openB.isClosed, "the later leaked transaction was still rolled back")
            assertEquals(TransactionStatus.RolledBack, openB.transaction.status)
            assertNull(b.activeTransaction)
            assertEquals(0, a.count.value)
            assertEquals(0, b.count.value)
            assertSame(preTrack, b.clock, "B's clock is back to its pre-track binding")
            assertEquals(0, parent.a.n.value, "the tracked tree was reset after the held error")
            assertTrue(scope.registry.allHandles().isEmpty(), "the registry was cleared")
            for (store in listOf(a, b, parent, parent.a)) {
                assertTrue(store.snapshotMiddleware().isEmpty(), "every recorder came off $store")
            }
            assertIs<TransactionResult.Success<*>>(a action { count mutate 1 })
            assertIs<TransactionResult.Success<*>>(b action { count mutate 1 })
            assertTrue(ha.timeline.isEmpty() && hb.timeline.isEmpty(), "a later action records nothing")
            assertIs<TransactionResult.Success<*>>(source action { x mutate 6 })
            assertEquals(60, mirror.value, "the derived recomputes again: the host is not wedged")
            reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
        } finally {
            mirrorSub.dispose()
        }
    }

    /**
     * TreeFixtures.kt:131 (with :172 and :182 on the way): a child lambda
     * that overflows when teardown lists the first tree is held, not
     * reported as "could not reset"; the second tree is still reset, every
     * recorder comes off and the registry is cleared.
     */
    @Test
    fun aChildLambdasFatalErrorAtTeardownSkipsNoLaterTree() {
        val first = FtdParent()
        val second = FtdParent()
        val boom = StackOverflowError()
        lateinit var scope: StoreTestScope
        lateinit var firstTree: TreeHandle
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    scope = this
                    firstTree = track(first.tree)
                    track(second.tree)
                    first.a action { n mutate 5 }
                    second.a action { n mutate 4 }
                    first.declareFailingChild(boom)
                }
            }
        assertSame(boom, thrown, "not the \"could not reset\" AssertionError: $thrown")
        assertEquals(5, first.a.n.value, "the first tree's listing threw: no reset ran there")
        assertEquals(0, second.a.n.value, "the later tree was still reset")
        assertTrue(scope.registry.allHandles().isEmpty(), "the registry was cleared")
        for (store in listOf(first, first.a, second, second.a)) {
            assertTrue(store.snapshotMiddleware().isEmpty(), "every recorder came off $store")
        }
        assertIs<TransactionResult.Success<*>>(first action { })
        assertTrue(firstTree.timeline.isEmpty(), "the first tree's recorder records nothing more")
    }

    /**
     * TreeFixtures.kt:199: teardown's tree reset re-runs a child's
     * initializer, which overflows. The reset frame rolls back (the child
     * keeps the body's value), the error is thrown instead of a "could not
     * reset" line, and a tree tracked after it is still reset.
     */
    @Test
    fun aResetInitializersFatalErrorAtTeardownRollsTheFrameBackAndResetsLaterTrees() {
        val parent = FtdResetParent()
        val child = parent.child
        val later = FtdParent()
        val reported = listOf(parent, child, later, later.a).associateWith { it.ftdReports() }
        val boom = StackOverflowError()
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    track(parent.tree)
                    track(later.tree)
                    child action { x mutate 3 }
                    later.a action { n mutate 4 }
                    child.failure = boom
                }
            }
        child.failure = null
        assertSame(boom, thrown, "not the \"could not reset\" AssertionError: $thrown")
        assertEquals(3, child.x.value, "the reset frame rolled back")
        assertEquals(0, later.a.n.value, "the later tree was still reset")
        for (store in listOf(parent, child, later, later.a)) {
            assertTrue(store.snapshotMiddleware().isEmpty(), "every recorder came off $store")
        }
        assertNull(child.activeTransaction)
        assertIs<TransactionResult.Success<*>>(child action { x mutate 1 })
        reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
    }

    /** TreeFixtures.kt:199, variant: a member's middleware overflows in `onTransactionStarted` of the reset frame. */
    @Test
    fun aResetMiddlewaresFatalErrorAtTeardownIsThrownAndRollsTheFrameBack() {
        val parent = FtdResetParent()
        val child = parent.child
        val veto = FtdStartedThrows<FtdResetChild>().also { child.middlewares(it) }
        val reported = child.ftdReports()
        val boom = StackOverflowError()
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    track(parent.tree)
                    child action { x mutate 3 }
                    veto.failure = boom
                }
            }
        veto.failure = null
        assertSame(boom, thrown)
        assertEquals(3, child.x.value, "the reset frame rolled back")
        assertEquals(listOf<Middleware<*>>(veto), child.snapshotMiddleware(), "only the test's own middleware stays")
        assertIs<TransactionResult.Success<*>>(child action { x mutate 1 })
        assertEquals(emptyList(), reported.toList())
    }

    /** TreeFixtures.kt:199, variant: the test's own tree middleware overflows as the reset frame starts. */
    @Test
    fun aResetTreeMiddlewaresFatalErrorAtTeardownIsThrownAndRollsTheFrameBack() {
        val parent = FtdResetParent()
        val child = parent.child
        val veto = FtdTreeStartedThrows()
        parent.tree.middlewares(veto)
        val reported = listOf(parent, child).associateWith { it.ftdReports() }
        val boom = StackOverflowError()
        try {
            val thrown =
                assertFailsWith<Throwable> {
                    storeTest {
                        track(parent.tree)
                        child action { x mutate 3 }
                        veto.failure = boom
                    }
                }
            veto.failure = null
            assertSame(boom, thrown)
            assertEquals(3, child.x.value, "the reset frame rolled back")
            assertNull(child.activeTransaction)
            assertNull(parent.activeTransaction)
            assertIs<TransactionResult.Success<*>>(child action { x mutate 1 })
            reported.forEach { (store, seen) -> assertEquals(emptyList(), seen.toList(), "reported on $store") }
        } finally {
            veto.failure = null
            parent.tree.removeMiddleware(veto)
        }
    }

    /** TreeFixtures.kt:199, variant: a store attachment overflows in `onStoreReset` during the reset frame. */
    @Test
    fun aResetAttachmentsFatalErrorAtTeardownIsThrownAndRollsTheFrameBack() {
        val parent = FtdResetParent()
        val child = parent.child
        val attachment = child.internalAttachIfAbsent(ftdResetKey) { FtdResetAttachment() }
        val reported = child.ftdReports()
        val boom = StackOverflowError()
        val thrown =
            assertFailsWith<Throwable> {
                storeTest {
                    track(parent.tree)
                    child action { x mutate 3 }
                    attachment.failure = boom
                }
            }
        attachment.failure = null
        assertSame(boom, thrown)
        assertEquals(3, child.x.value, "the reset frame rolled back")
        assertTrue(child.snapshotMiddleware().isEmpty())
        assertIs<TransactionResult.Success<*>>(child action { x mutate 1 })
        assertEquals(emptyList(), reported.toList())
    }
}
