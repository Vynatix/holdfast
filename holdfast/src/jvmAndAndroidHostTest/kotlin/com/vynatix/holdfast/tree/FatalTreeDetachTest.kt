@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachIfAbsent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, the store tree's dispose side: a fatal error from a membership
// listener's onDetached or from an owned keyed store's dispose is held while
// every later detach step still runs — the store leaves its parent, its
// children are released and re-synced, every owned keyed store is disposed —
// and is thrown from dispose() (or the entry whose settle ran the owned
// disposal) after the last one. Never reported.

private class FtdLeaf : Store<FtdLeaf>() {
    val n by state { 0 }
}

private class FtdChildParent : Store<FtdChildParent>() {
    val n by state { 0 }
    val child by store { FtdLeaf() }
}

private class FtdZ : Store<FtdZ>() {
    val n by state { 0 }
}

private class FtdY : Store<FtdY>() {
    val n by state { 0 }
    val z by store { FtdZ() }
}

private class FtdX : Store<FtdX>() {
    val y by store { FtdY() }
}

private class FtdW : Store<FtdW>() {
    val n by state { 0 }
    val x by store { FtdX() }
}

private class FtdKeyedParent : Store<FtdKeyedParent>() {
    val n by state { 0 }
    val ks by keyed<String, FtdLeaf> { FtdLeaf() }
}

private class FtdOther : Store<FtdOther>() {
    val n by state { 0 }
}

private val ftdDisposeProbe = StoreAttachmentKey<StoreAttachment>("fatal dispose probe")

/** Attach to [store] an attachment whose onStoreDisposed throws [failure] (attached after the tree's own). */
private fun failOnDispose(
    store: Store<*>,
    failure: Throwable,
) {
    store.internalAttachIfAbsent(ftdDisposeProbe) {
        object : StoreAttachment {
            override fun onStoreDisposed(): Unit = throw failure
        }
    }
}

private val ftdDisposeWitness = StoreAttachmentKey<FtdWitness>("dispose witness")

/** An attachment that records that its store's dispose reached it. */
private class FtdWitness : StoreAttachment {
    var told = 0

    override fun onStoreDisposed() {
        told++
    }
}

/** Records every leaf it hears leave; throws what [detachedThrows] answers for a leaf. */
private class FtdListener : LeafMembershipListener() {
    val detached = CopyOnWriteArrayList<LeafNode>()
    var detachedThrows: (LeafNode) -> Throwable? = { null }

    override fun onDetached(leaf: LeafNode) {
        detached += leaf
        detachedThrows(leaf)?.let { throw it }
    }
}

/** Counts the transactions its tree members start, by the member's store. */
private class FtdTreeCount : TreeMiddleware() {
    val started = CopyOnWriteArrayList<Store<*>>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        started += context.store
    }
}

private fun Store<*>.recordUncaught(): List<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

private fun assertThreadClean() {
    assertNull(SettleScopes.current(), "no settle scope left open on this thread")
    assertNull(FrameMarkers.current(), "no frame marker left on this thread")
}

class FatalTreeDetachTest {
    @Test
    fun anOnDetachedFatalErrorIsThrownFromDisposeAfterEveryStepAndAttachmentRan() {
        val parent = FtdChildParent()
        val child = parent.child
        val witness = child.internalAttachIfAbsent(ftdDisposeWitness) { FtdWitness() }
        val parentSeen = parent.recordUncaught()
        val childSeen = child.recordUncaught()
        val fatal = StackOverflowError()
        val thrower = FtdListener().apply { detachedThrows = { fatal } }
        val recorder = FtdListener()
        parent.internalAddMembershipListener(thrower)
        parent.internalAddMembershipListener(recorder)

        assertSame(fatal, assertFailsWith<StackOverflowError> { child.dispose() })

        assertTrue(child.isDisposed)
        assertEquals(1, recorder.detached.size, "the listener after the throwing one was still told")
        assertEquals(1, witness.told, "the attachment after the tree's was still told")
        assertEquals(listOf<Store<*>>(parent), parent.tree.stores(), "the child left its parent")
        child.dispose() // a second dispose is a silent no-op
        assertEquals(1, thrower.detached.size)
        assertEquals(emptyList(), parentSeen)
        assertEquals(emptyList(), childSeen)
        assertNull(parent.activeTransaction)
        assertThreadClean()
        parent action { n mutate 1 }
        assertEquals(1, parent.n.value)
    }

    @Test
    fun theHeldFatalErrorCarriesTheCollectedDetachFailuresInsteadOfReportingThem() {
        val parent = FtdChildParent()
        val child = parent.child
        val parentSeen = parent.recordUncaught()
        val childSeen = child.recordUncaught()
        val failure = IllegalStateException("listener refused")
        val fatal = StackOverflowError()
        parent.internalAddMembershipListener(FtdListener().apply { detachedThrows = { failure } })
        parent.internalAddMembershipListener(FtdListener().apply { detachedThrows = { fatal } })

        val thrown = assertFailsWith<StackOverflowError> { child.dispose() }

        assertSame(fatal, thrown)
        val aggregate = assertIs<IllegalStateException>(thrown.suppressed.firstOrNull())
        assertTrue("could not fully detach" in aggregate.message.orEmpty(), aggregate.message)
        assertSame(failure, aggregate.cause)
        assertTrue(child.isDisposed)
        assertEquals(emptyList(), parentSeen)
        assertEquals(emptyList(), childSeen, "neither the failure nor the fatal error was reported")
        assertThreadClean()
    }

    @Test
    fun aNonFatalOnDetachedFailureIsStillReportedThroughTheDisposingStore() {
        val parent = FtdChildParent()
        val child = parent.child
        val childSeen = child.recordUncaught()
        val failure = IllegalStateException("listener refused")
        parent.internalAddMembershipListener(FtdListener().apply { detachedThrows = { failure } })

        child.dispose()

        val reported = assertIs<IllegalStateException>(childSeen.single())
        assertTrue("could not fully detach" in reported.message.orEmpty(), reported.message)
        assertSame(failure, reported.cause)
        assertTrue(child.isDisposed)
    }

    @Test
    fun releasedChildrenBecomeRootsWithTheirRingsResyncedDespiteAFatalError() {
        val w = FtdW()
        val x = w.x
        val y = x.y
        val z = y.z
        val counted = FtdTreeCount()
        w.tree.middlewares(counted)
        y action { n mutate 1 }
        z action { n mutate 1 }
        assertEquals(listOf<Store<*>>(y, z), counted.started.toList(), "W's tree middleware covers Y and Z")
        counted.started.clear()
        val seen = listOf(w, x, y, z).map { it.recordUncaught() }
        val fatal = StackOverflowError()
        val thrower = FtdListener().apply { detachedThrows = { leaf -> fatal.takeIf { leaf.store === y } } }
        w.internalAddMembershipListener(thrower)

        assertSame(fatal, assertFailsWith<StackOverflowError> { x.dispose() })

        assertTrue(x.isDisposed)
        assertFalse(y.isDisposed)
        assertFalse(z.isDisposed)
        assertNull(y.tree.parent, "Y is released as a parentless root")
        assertSame(y.tree.node, z.tree.parent, "Z stays under Y")
        val told = thrower.detached.map { it.store }
        assertTrue(y in told && z in told, "every released leaf was announced: $told")
        assertEquals(listOf<Store<*>>(w), w.tree.stores())
        y action { n mutate 2 }
        z action { n mutate 2 }
        assertEquals(emptyList(), counted.started.toList(), "the released subtree's rings were re-synced")
        assertEquals(2, y.n.value)
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        assertThreadClean()
    }

    @Test
    fun everyOwnedKeyedStoreIsDisposedBeforeTheParentsDisposeThrowsTheirFatalError() {
        val parent = FtdKeyedParent()
        val a = parent.ks.create("a")
        val b = parent.ks.create("b")
        val fatalA = StackOverflowError()
        val fatalB = StackOverflowError()
        failOnDispose(a, fatalA)
        failOnDispose(b, fatalB)
        val seen = listOf(parent, a, b).map { it.recordUncaught() }

        val thrown = assertFailsWith<StackOverflowError> { parent.dispose() }

        assertSame(fatalA, thrown)
        assertTrue(fatalB in thrown.suppressed, "the second store was disposed too: ${thrown.suppressed.toList()}")
        assertTrue(parent.isDisposed)
        assertTrue(a.isDisposed)
        assertTrue(b.isDisposed)
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        assertThreadClean()
    }

    @Test
    fun anOwnedKeyedStoresFatalErrorComesOutOfTheEntryWhoseSettleDisposedIt() {
        val parent = FtdKeyedParent()
        val a = parent.ks.create("a")
        val b = parent.ks.create("b")
        val fatal = StackOverflowError()
        failOnDispose(a, fatal)
        val other = FtdOther()
        val seen = listOf(parent, a, b, other).map { it.recordUncaught() }

        val thrown =
            assertFailsWith<StackOverflowError> {
                other action {
                    parent.dispose()
                    n mutate 1
                }
            }

        assertSame(fatal, thrown)
        assertEquals(1, other.n.value, "the action committed before its settle threw")
        assertTrue(parent.isDisposed)
        assertTrue(a.isDisposed)
        assertTrue(b.isDisposed, "the settle disposed every owned store")
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        assertNull(other.activeTransaction)
        assertThreadClean()
        other action { n mutate 2 }
        assertEquals(2, other.n.value)
    }

    @Test
    fun disposeAllDisposesEveryStoreThenThrowsTheFirstFatalError() {
        val parent = FtdKeyedParent()
        val a = parent.ks.create("a")
        val b = parent.ks.create("b")
        val c = parent.ks.create("c")
        val fatalA = StackOverflowError()
        val fatalB = OutOfMemoryError()
        val failureC = IllegalStateException("c refused")
        failOnDispose(a, fatalA)
        failOnDispose(b, fatalB)
        failOnDispose(c, failureC)
        val parentSeen = parent.recordUncaught()
        val (aSeen, bSeen, cSeen) = listOf(a, b, c).map { it.recordUncaught() }

        val thrown = assertFailsWith<StackOverflowError> { parent.ks.disposeAll() }

        assertSame(fatalA, thrown)
        assertTrue(fatalB in thrown.suppressed)
        assertTrue(a.isDisposed && b.isDisposed && c.isDisposed)
        assertEquals(emptyMap(), parent.ks.entries())
        assertFalse(parent.isDisposed)
        assertEquals(emptyList(), parentSeen)
        assertEquals(emptyList(), aSeen)
        assertEquals(emptyList(), bSeen)
        assertEquals(listOf<Throwable>(failureC), cSeen, "a non-fatal dispose failure is still reported")
        assertThreadClean()
        parent action { n mutate 1 }
        assertEquals(1, parent.n.value)
    }

    @Test
    fun anOwnedKeyedStoresFatalErrorComesOutOfTheDrainThatRanItsHandedOffDisposal() {
        val parent = FtdKeyedParent()
        val a = parent.ks.create("a")
        val b = parent.ks.create("b")
        val fatal = StackOverflowError()
        failOnDispose(a, fatal)
        val seen = listOf(parent, a, b).map { it.recordUncaught() }

        // Disposed under its own transaction lock with no settle scope open, the parent hands its owned
        // keyed stores to its post-commit queue, for the drain its lock's holder owes.
        parent.runUnderLock { parent.dispose() }
        assertTrue(parent.isDisposed)
        assertFalse(a.isDisposed, "handed off, not disposed under the lock")

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.internalDrainPostCommitTasks() })

        assertTrue(a.isDisposed)
        assertTrue(b.isDisposed, "every owned store was disposed")
        assertTrue(seen.all { it.isEmpty() }, "$seen")
        parent.internalDrainPostCommitTasks() // nothing left queued
        assertThreadClean()
    }
}
