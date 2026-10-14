@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.internalAttachIfAbsent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, the store tree's attach side: a fatal error from a membership
// listener, a child lambda, a keyed factory or an orphan's dispose propagates
// out of the read or create that ran it — after every remaining listener was
// told and the attach stood, or every orphan was disposed — and is never
// reported.

private class FtaLeaf : Store<FtaLeaf>() {
    val n by state { 0 }
}

private class FtaOther : Store<FtaOther>() {
    val n by state { 0 }
}

private class FtaChildParent : Store<FtaChildParent>() {
    var runs = 0
    val n by state { 0 }
    val child by store {
        runs++
        FtaLeaf()
    }
}

private class FtaGroupParent : Store<FtaGroupParent>() {
    var runs = 0
    val built = ArrayList<Store<*>>()
    val n by state { 0 }
    val pair by group {
        runs++
        listOf(FtaLeaf(), FtaOther()).also { built += it }
    }
}

private class FtaKeyedParent : Store<FtaKeyedParent>() {
    var runs = 0
    val n by state { 0 }
    val threads by keyed<String, FtaLeaf> {
        runs++
        FtaLeaf()
    }
}

private class FtaThrowingParent(
    private val fatal: Throwable,
) : Store<FtaThrowingParent>() {
    var childRuns = 0
    var factoryRuns = 0
    val n by state { 0 }
    val child by store<FtaLeaf> {
        childRuns++
        throw fatal
    }
    val threads by keyed<String, FtaLeaf> { key ->
        factoryRuns++
        if (key == "bad") throw fatal
        FtaLeaf()
    }
}

private val ftaDisposeProbe = StoreAttachmentKey<StoreAttachment>("fatal-on-dispose probe")

/** Its dispose throws [fatal] (from an attachment, as a store's last teardown step); reports go to [seen]. */
private class FtaFatalOnDispose(
    fatal: Throwable,
    seen: MutableList<Throwable>,
) : Store<FtaFatalOnDispose>() {
    init {
        uncaughtObserverHandler = { seen += it }
        internalAttachIfAbsent(ftaDisposeProbe) {
            object : StoreAttachment {
                override fun onStoreDisposed(): Unit = throw fatal
            }
        }
    }
}

/** Lists two unpinned stores of one class: the group's validation refuses it, and the run's orphans are disposed. */
private class FtaOrphanParent(
    private val fatals: List<Throwable>,
) : Store<FtaOrphanParent>() {
    val seen = CopyOnWriteArrayList<Throwable>()
    val built = ArrayList<FtaFatalOnDispose>()
    val n by state { 0 }
    val twins by group {
        listOf(FtaFatalOnDispose(fatals[0], seen), FtaFatalOnDispose(fatals[1], seen)).also { built += it }
    }
}

/** Records every leaf store it hears of; throws what [attachedThrows] answers for a leaf. */
private class FtaListener : LeafMembershipListener() {
    val attached = CopyOnWriteArrayList<Store<*>?>()
    val detached = CopyOnWriteArrayList<LeafNode>()
    var attachedThrows: (LeafNode) -> Throwable? = { null }

    override fun onAttached(leaf: LeafNode) {
        attached += leaf.store
        attachedThrows(leaf)?.let { throw it }
    }

    override fun onDetached(leaf: LeafNode) {
        detached += leaf
    }
}

/** Throws [first] the first time it is asked, then nothing. */
private fun once(first: Throwable): (LeafNode) -> Throwable? {
    var pending: Throwable? = first
    return { pending.also { pending = null } }
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

class FatalTreeAttachTest {
    @Test
    fun aListenersFatalErrorIsThrownFromTheChildReadOnceTheOthersHeardAndTheAttachStands() {
        val parent = FtaChildParent()
        val seen = parent.recordUncaught()
        val fatal = StackOverflowError()
        val thrower = FtaListener().apply { attachedThrows = { fatal } }
        val recorder = FtaListener()
        parent.internalAddMembershipListener(thrower)
        parent.internalAddMembershipListener(recorder)

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.child })

        assertEquals(1, recorder.attached.size, "the listener after the throwing one was still told")
        val child = parent.child
        assertSame(child, recorder.attached.single())
        assertEquals(1, parent.runs, "the attach stood: the lambda does not run again")
        assertSame(parent.tree.node, child.tree.parent)
        assertEquals(listOf(parent, child), parent.tree.stores())
        assertEquals(emptyList(), seen, "a fatal error is never reported")
        assertNull(parent.activeTransaction)
        assertThreadClean()
        parent action { n mutate 1 }
        assertEquals(1, parent.n.value)

        // The phase ended ANNOUNCED: the child's dispose is announced, not left deferred.
        val childSeen = child.recordUncaught()
        child.dispose()
        assertEquals(listOf<Store<*>?>(null), recorder.detached.map { it.store })
        assertEquals(1, recorder.detached.size)
        assertEquals(emptyList(), childSeen)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aFatalErrorTheHandlerThrowsForAReportedListenerFailureIsThrownToo() {
        val parent = FtaChildParent()
        val reported = CopyOnWriteArrayList<Throwable>()
        val fatal = StackOverflowError()
        parent.uncaughtObserverHandler = {
            reported += it
            throw fatal
        }
        val failure = IllegalStateException("listener refused")
        val thrower = FtaListener().apply { attachedThrows = { failure } }
        val recorder = FtaListener()
        parent.internalAddMembershipListener(thrower)
        parent.internalAddMembershipListener(recorder)

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.child })

        assertEquals(listOf<Throwable>(failure), reported, "the non-fatal failure is still reported")
        assertEquals(1, recorder.attached.size, "and the other listener still told")
        assertSame(parent.child, recorder.attached.single())
        assertEquals(1, parent.runs)
        assertThreadClean()
    }

    @Test
    fun aNonFatalListenerFailureIsStillReportedAndTheReadReturns() {
        val parent = FtaChildParent()
        val seen = parent.recordUncaught()
        val failure = IllegalStateException("listener refused")
        parent.internalAddMembershipListener(FtaListener().apply { attachedThrows = { failure } })

        val child = parent.child

        assertEquals(listOf<Throwable>(failure), seen)
        assertSame(parent.tree.node, child.tree.parent)
    }

    @Test
    fun aGroupsLeavesAllAttachAndAreAllAnnouncedBeforeTheFatalErrorIsThrown() {
        val parent = FtaGroupParent()
        val seen = parent.recordUncaught()
        val fatal = StackOverflowError()
        val thrower = FtaListener().apply { attachedThrows = once(fatal) }
        val recorder = FtaListener()
        parent.internalAddMembershipListener(thrower)
        parent.internalAddMembershipListener(recorder)

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.pair })

        val (first, second) = parent.built
        assertEquals(listOf<Store<*>?>(first, second), thrower.attached, "the second leaf was announced too")
        assertEquals(listOf<Store<*>?>(first, second), recorder.attached)
        val branch = parent.pair
        assertEquals(1, parent.runs)
        assertEquals(listOf(first, second), branch.stores)
        assertSame(branch, first.tree.parent)
        assertSame(branch, second.tree.parent)
        assertEquals(listOf(parent, first, second), parent.tree.stores())
        assertEquals(emptyList(), seen)
        assertThreadClean()
        parent action { n mutate 1 }
    }

    @Test
    fun aKeyedCreatesFatalErrorIsThrownWithTheStoreAttachedAndItsConstructionLockReleased() {
        val parent = FtaKeyedParent()
        val seen = parent.recordUncaught()
        val fatal = StackOverflowError()
        val recorder = FtaListener()
        parent.internalAddMembershipListener(FtaListener().apply { attachedThrows = once(fatal) })
        parent.internalAddMembershipListener(recorder)

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.threads.create("a") })

        val created = assertNotNull(parent.threads["a"])
        assertSame(created, recorder.attached.single())
        completesWithin(10, "a lookup of the key from another thread") {
            assertSame(created, parent.threads["a"])
            assertEquals(mapOf("a" to created), parent.threads.entries())
        }
        assertSame(created, parent.threads.getOrCreate("a"))
        assertEquals(1, parent.runs, "the factory does not run again")
        assertEquals(emptyList(), seen)
        assertThreadClean()
        parent action { n mutate 1 }
    }

    @Test
    fun aGroupRunsOrphansAreAllDisposedBeforeTheirFatalErrorReplacesTheRefusal() {
        val fatals = listOf(StackOverflowError(), StackOverflowError())
        val parent = FtaOrphanParent(fatals)
        val seen = parent.recordUncaught()

        val thrown = assertFailsWith<StackOverflowError> { parent.twins }

        assertSame(fatals[0], thrown, "not the 'pin one of them' refusal")
        assertTrue(fatals[1] in thrown.suppressed, "the second orphan's fatal error rides along")
        assertEquals(2, parent.built.size)
        assertTrue(parent.built.all { it.isDisposed }, "every orphan of the refused run is disposed")
        assertEquals(emptyList(), seen)
        assertEquals(emptyList(), parent.seen.toList())
        assertThreadClean()
        parent action { n mutate 1 }
    }

    @Test
    fun aChildLambdaOrFactorysFatalErrorStaysRetryableAndIsNeverACycle() {
        val fatal = StackOverflowError()
        val parent = FtaThrowingParent(fatal)
        val seen = parent.recordUncaught()

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.child })
        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.child }, "not a 'Materialization cycle'")
        assertEquals(2, parent.childRuns)
        // Every structural entrypoint materializes the subtree first.
        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.tree.stores() })
        assertEquals(3, parent.childRuns)

        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.threads.create("bad") })
        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.threads.create("bad") })
        assertSame(fatal, assertFailsWith<StackOverflowError> { parent.threads.getOrCreate("bad") })
        assertEquals(3, parent.factoryRuns)
        assertNull(parent.threads["bad"])
        val ok = parent.threads.create("ok")
        assertEquals(mapOf("ok" to ok), parent.threads.entries())
        assertEquals(emptyList(), seen)
        assertThreadClean()
        parent action { n mutate 1 }
    }
}
