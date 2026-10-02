@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RtPanelStore : Store<RtPanelStore>() {
    /** Whether a transaction was open on each run of `lazy`'s initializer. */
    val initializerRuns = mutableListOf<Boolean>()
    val count by state { 0 }
    val label by state(distinct = false) { "idle" }
    val lazy by state {
        initializerRuns += activeTransaction != null
        "lazy"
    }
}

private class RtOtherStore : Store<RtOtherStore>() {
    val n by state { 0 }
}

private class RtKeyedStore(
    id: String,
) : Store<RtKeyedStore>() {
    val title by state { "thread $id" }
}

/** A mid-tree store with no states of its own: a leaf child and a keyed branch under it. */
private class RtPanelsStore : Store<RtPanelsStore>() {
    val panel by store { RtPanelStore() }
    val keyed by stores<String, RtKeyedStore> { RtKeyedStore(it) }
}

private class RtOthersStore : Store<RtOthersStore>() {
    val other by store { RtOtherStore() }
    val emptyKeyed by stores<String, RtKeyedStore> { RtKeyedStore(it) }
}

private class RtResetApp : Store<RtResetApp>() {
    val panels by store { RtPanelsStore() }
    val others by store { RtOthersStore() }
    val panel: RtPanelStore get() = panels.panel
    val other: RtOtherStore get() = others.other
    val keyed: KeyedBranch<String, RtKeyedStore> get() = panels.keyed
    val emptyKeyed: KeyedBranch<String, RtKeyedStore> get() = others.emptyKeyed
}

/** The node of the `panels` child. */
private val RtResetApp.panelsNode: LeafNode get() = tree.nodeOf(panels)!!

private class RtFrameLog<V : Store<V>> : Middleware<V>() {
    val completed = mutableListOf<Pair<String, String?>>()

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        completed += context.transaction.id to context.transaction.frameId
    }
}

private class RtResetHook : StoreAttachment {
    var resets = 0

    override fun onStoreReset() {
        resets++
    }
}

private val rtHookKey = StoreAttachmentKey<RtResetHook>("tree-reset-test-hook")

private class RtDisposingMiddleware<V : Store<V>>(
    private val victim: Store<*>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        victim.dispose()
    }
}

private fun recordFires(vararg states: Pair<String, State<*>>): Pair<Map<String, MutableList<Any>>, Disposable> {
    val fires = states.associate { (name, _) -> name to mutableListOf<Any>() }
    val subs =
        states.map { (name, state) ->
            @Suppress("UNCHECKED_CAST")
            (state as State<Any>).effect { fires.getValue(name) += this }
        }
    fires.values.forEach { it.clear() }
    return fires to Disposable { subs.forEach { it.dispose() } }
}

/** T4 reset: `tree.reset(node)` resets exactly the subtree — its stores' own states included — as one frame. */
class TreeResetTest {
    private fun dirtied(): RtResetApp {
        val root = RtResetApp()
        root.panel action {
            count mutate 5
            label mutate "busy"
        }
        root.other action { n mutate 9 }
        root.keyed.create("k1") action { title mutate "changed" }
        return root
    }

    @Test
    fun resetOfANodeTouchesOnlyThatSubtree() {
        val root = dirtied()
        val report = root.tree.reset(root.panelsNode).getOrThrow()
        assertEquals(0, root.panel.count.value)
        assertEquals("idle", root.panel.label.value)
        assertEquals("thread k1", root.keyed["k1"]!!.title.value, "declared under the panels store")
        assertEquals(9, root.other.n.value, "outside the subtree: untouched")
        assertEquals(
            listOf(root.panelsNode, root.tree.nodeOf(root.panel)!!, root.tree.nodeOf(root.keyed["k1"]!!)!!),
            report.reset,
            "the subtree's top store is reset with its children",
        )
        assertEquals(emptyList<StoreNode>(), report.skipped)
    }

    @Test
    fun aResetTreeEqualsAFreshTreesCapture() {
        val root = dirtied()
        root.tree.reset().getOrThrow()
        val fresh = RtResetApp()
        fresh.keyed.create("k1")
        assertEquals(fresh.tree.snapshot().render(), root.tree.snapshot().render())
        assertEquals(fresh.panel.snapshot(), root.panel.snapshot())
        assertEquals(fresh.other.snapshot(), root.other.snapshot())
    }

    @Test
    fun oneFrameWithOneTransactionPerLeaf() {
        val root = dirtied()
        val panelLog = RtFrameLog<RtPanelStore>().also { root.panel.middlewares(it) }
        val otherLog = RtFrameLog<RtOtherStore>().also { root.other.middlewares(it) }
        val keyedLog = RtFrameLog<RtKeyedStore>().also { root.keyed["k1"]!!.middlewares(it) }
        val result = root.tree.reset()
        assertIs<TransactionResult.Success<TreeResetReport>>(result)
        val (id, frame) = panelLog.completed.single()
        assertNotNull(frame)
        assertTrue(frame.startsWith("atomic-"))
        assertEquals(listOf<Pair<String, String?>>(id to frame), otherLog.completed)
        assertEquals(listOf<Pair<String, String?>>(id to frame), keyedLog.completed)
        assertEquals(frame, result.transaction.frameId)
    }

    @Test
    fun observersFireOncePerChangedStateAndNeverForAnUnchangedOne() {
        val root = dirtied()
        root.panel action { label mutate "idle" }
        val (fires, subs) = recordFires("count" to root.panel.count, "label" to root.panel.label, "n" to root.other.n)
        try {
            root.tree.reset().getOrThrow()
            assertEquals(listOf<Any>(0), fires.getValue("count"))
            assertEquals(emptyList<Any>(), fires.getValue("label"), "already at its reset value: silent, even with distinct = false")
            assertEquals(listOf<Any>(0), fires.getValue("n"))
        } finally {
            subs.dispose()
        }
    }

    @Test
    fun nestingIsRefused() {
        val root = dirtied()
        var fromAction: Throwable? = null
        val panelsNode = root.panelsNode
        root.other.action { fromAction = runCatching { root.tree.reset(panelsNode) }.exceptionOrNull() }.getOrThrow()
        assertContains(assertIs<IllegalStateException>(fromAction).message!!, "reset the subtree at 'panels' under 'RtResetApp'")
        var fromFrame: Throwable? = null
        atomic(root.panel) { fromFrame = runCatching { root.tree.reset() }.exceptionOrNull() }.getOrThrow()
        assertIs<IllegalStateException>(fromFrame)
        assertEquals(5, root.panel.count.value, "nothing reset")
    }

    @Test
    fun anEmptyKeyedBranchIsANoOpSuccess() {
        val root = dirtied()
        val result = root.tree.reset(root.emptyKeyed)
        val success = assertIs<TransactionResult.Success<TreeResetReport>>(result)
        assertEquals(TransactionStatus.Committed, success.transaction.status)
        assertEquals("tree-reset", success.transaction.id)
        assertTrue(success.value.reset.isEmpty() && success.value.skipped.isEmpty())
        assertEquals(5, root.panel.count.value)
    }

    @Test
    fun neverReadStatesAreMaterializedBeforeTheFrameThenReRunInsideIt() {
        val root = RtResetApp()
        assertEquals(emptyList<Boolean>(), root.panel.initializerRuns)
        root.tree.reset().getOrThrow()
        assertEquals(
            listOf(false, true),
            root.panel.initializerRuns,
            "materialized outside every lock, then re-run by the reset inside the frame's transaction",
        )
        assertEquals("lazy", root.panel.lazy.value)
    }

    @Test
    fun attachmentsHearOnStoreResetInsideTheFrame() {
        val root = dirtied()
        val hook = root.panel.internalAttachIfAbsent(rtHookKey) { RtResetHook() }
        val otherHook = root.other.internalAttachIfAbsent(rtHookKey) { RtResetHook() }
        root.tree.reset(root.panelsNode).getOrThrow()
        assertEquals(1, hook.resets)
        assertEquals(0, otherHook.resets, "outside the subtree")
    }

    @Test
    fun aThrowingInitializerFailsTheWholeFrameBeforeAnyLeafIsWritten() {
        val root = RtFragileApp()
        root.fragile.action { count mutate 1 }.getOrThrow()
        root.sibling action { n mutate 3 }
        root.fragile.shouldThrow = true
        val result = root.tree.reset()
        assertIs<TransactionResult.Error>(result)
        assertEquals(1, root.fragile.count.value)
        assertEquals(3, root.sibling.n.value, "the sibling leaf rolled back with the frame")
    }

    @Test
    fun aLeafDisposedAfterLockAcquisitionIsSkippedAndReported() {
        val root = dirtied()
        assertTrue(root.panel.lockOrderKey < root.other.lockOrderKey)
        root.other.middlewares(RtDisposingMiddleware(root.panel))
        val panelLeaf = root.tree.nodeOf(root.panel)!!
        val report = root.tree.reset().getOrThrow()
        assertTrue(root.panel.isDisposed)
        assertEquals(listOf(panelLeaf), report.skipped)
        assertTrue(panelLeaf !in report.reset)
        assertEquals(0, root.other.n.value, "the rest of the frame committed")
        assertNull(root.tree.nodeOf(root.panel))
    }
}

private class RtFragileStore : Store<RtFragileStore>() {
    var shouldThrow = false
    val count by state { 0 }
    val fragile by state {
        if (shouldThrow) error("initializer refused")
        "ok"
    }
}

private class RtFragileApp : Store<RtFragileApp>() {
    val fragile = RtFragileStore()
    val sibling = RtOtherStore()
    val leaves by stores { listOf(fragile, sibling) }
}
