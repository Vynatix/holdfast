@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.STORE_EDGES
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

private class TreeValueStructuralLeafStore : Store<TreeValueStructuralLeafStore>() {
    var initializerRuns = 0
    val n by state {
        initializerRuns++
        0
    }
    val other by state { "o" }
}

private class TreeValueStructuralKeyedStore(
    id: String,
) : Store<TreeValueStructuralKeyedStore>() {
    val title by state { "thread $id" }
}

private class TreeValueStructuralParent : Store<TreeValueStructuralParent>() {
    val left = TreeValueStructuralLeafStore()
    val leaf by store { left }
    val keyed by stores<String, TreeValueStructuralKeyedStore> { TreeValueStructuralKeyedStore(it) }
}

private class TreeValueStructuralHostLog : Middleware<TreeValueHost>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<TreeValueHost>) {
        started++
    }
}

private class TreeValueStructuralBridge : Bridge<Int> {
    private var inbound: ((Int) -> Unit)? = null

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable { inbound = null }
    }

    override fun publish(value: Int): Boolean = true

    fun deliver(value: Int) {
        inbound?.invoke(value)
    }
}

/** When a `tree` handle's value is built, and how stores joining and leaving the subtree reach it. */
class TreeValueStructuralTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun StoreTree.hostLog(): TreeValueStructuralHostLog =
        TreeValueStructuralHostLog().also { (this as StoreTreeImpl).treeValue().host.middlewares(it) }

    @Test
    fun theFirstReadBuildsTheTreeAndTakingTheHandleTakesNoCapture() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        assertEquals(0, parent.left.initializerRuns, "declaring children runs no child code")
        assertEquals(0, tree.internalSettleCount, "no capture until the value is used")
        val value = tree.value
        assertEquals(1, parent.left.initializerRuns)
        assertEquals(1, tree.internalSettleCount)
        assertEquals(0, value[parent.left.n])
    }

    @Test
    fun aKeyedStoreCreatedOutsideAnyScopeAppearsOnTheNextRead() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        tree.value
        val k = parent.keyed.create("k")
        assertEquals(2, tree.internalSettleCount, "the attach recomputed once, after create returned")
        assertEquals("thread k", tree.value[k.title])
        assertNotNull(tree.value[tree.nodeOf(k)!!])
    }

    @Test
    fun oneCreatedInsideAnActionAppearsAtThatActionsSettleAndReadsFreshInside() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        val seen = mutableListOf<TreeSnapshot>()
        disposables += tree effect { seen += this }
        val log = tree.hostLog()
        var inside: TreeSnapshot? = null
        var k: TreeValueStructuralKeyedStore? = null
        parent.left action {
            k = parent.keyed.create("k")
            assertEquals(1, tree.internalSettleCount, "nothing settles inside the action")
            inside = tree.value
            assertEquals(0, log.started, "the read inside opened no transaction on the host")
        }
        assertNotNull(inside!![tree.nodeOf(k!!)!!], "a read inside the action sees the store that just joined")
        assertEquals(1, log.started, "the settle at the action's exit did")
        assertEquals(2, tree.internalSettleCount, "one settle at the action's exit")
        assertEquals(2, seen.size)
        assertEquals("thread k", seen.last()[k!!.title])
    }

    @Test
    fun aFirstEffectOnAnUnreadTreeFiresARealCapture() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        var fired: TreeSnapshot? = null
        disposables += tree effect { fired = this }
        assertEquals(1, tree.internalSettleCount)
        assertEquals(0, fired!![parent.left.n])
    }

    @Test
    fun disposeOfAKeyedStoreDetachesAndRecomputesOnce() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        val k = parent.keyed.create("k")
        tree.value
        val before = tree.internalSettleCount
        val kNode = tree.nodeOf(k)!!
        k.dispose()
        assertEquals(before + 1, tree.internalSettleCount)
        assertNull(tree.value[kNode], "the store left the tree")
        assertNull(tree.nodeOf(k))
    }

    @Test
    fun disposeInsideAnotherChildsActionJoinsThatScope() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        val k = parent.keyed.create("k")
        tree.value
        val before = tree.internalSettleCount
        parent.left action {
            k.dispose()
            assertEquals(before, tree.internalSettleCount)
        }
        assertEquals(before + 1, tree.internalSettleCount, "the detach settled with the action")
    }

    @Test
    fun detachOrCreateFromInsideAValueObserverSettlesAfterTheCurrentFanout() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        var created: TreeValueStructuralKeyedStore? = null
        disposables +=
            tree effect {
                if (created == null) created = parent.keyed.create("k")
            }
        assertEquals(2, tree.internalSettleCount, "the initial settle, then the join")
        assertNotNull(tree.value[tree.nodeOf(created!!)!!])
        var disposed = false
        disposables +=
            tree effect {
                if (!disposed) {
                    disposed = true
                    created!!.dispose()
                }
            }
        assertEquals(3, tree.internalSettleCount)
        assertNull(tree.nodeOf(created!!))
    }

    @Test
    fun aThousandKeyedCreateAndDisposeCyclesLeaveNoSourceEdgesOrChildren() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        tree.value
        val node = (tree as StoreTreeImpl).treeValue().node()
        val baseline = node.sourceStores.size
        repeat(1_000) { i ->
            val k = parent.keyed.create("k$i")
            k.dispose()
            assertEquals(0, k.internalAttachment(STORE_EDGES)?.followerCount ?: 0)
        }
        assertEquals(baseline, node.sourceStores.size)
        assertEquals(0, parent.keyed.entries.size)
        assertEquals(1 + 2_000, tree.internalSettleCount, "one settle per join and one per leave")
        assertEquals(listOf(tree.nodeOf(parent.left)!!, parent.keyed), tree.children)
        assertEquals(listOf<Store<*>>(parent, parent.left), tree.stores())
    }

    @Test
    fun aValueReadInsideAnActionNeverCommitsTheHost() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        tree.value
        val log = tree.hostLog()
        parent.left action {
            n mutate 1
            tree.value
            tree.value
        }
        assertEquals(1, log.started, "only the settle after the action opened a host transaction")
    }

    @Test
    fun valueInsideAChildsFanoutIsPreCommitWhileSnapshotIsPostCommit() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        tree.value
        var valueSaw: Int? = null
        var snapshotSaw: Int? = null
        disposables +=
            parent.left.n effect {
                if (this == 1) {
                    valueSaw = tree.value[parent.left.n]
                    snapshotSaw = tree.snapshot()[parent.left.n]
                }
            }
        parent.left action { n mutate 1 }
        assertEquals(0, valueSaw, "the settle comes after the fanout")
        assertEquals(1, snapshotSaw)
        assertEquals(1, tree.value[parent.left.n])
    }

    @Test
    fun removeStateAndClearStatesSettleLikeACommit() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        parent.leaf
        parent.left action { n mutate 5 }
        tree.value
        val before = tree.internalSettleCount
        parent.left.removeState("n")
        assertEquals(before + 1, tree.internalSettleCount)
        assertEquals(0, tree.value[parent.left.n], "re-created from its initializer")
        parent.left.clearStates()
        assertEquals(before + 2, tree.internalSettleCount)
    }

    @Test
    fun bridgeInboundPushesAndAttachReplaysSettleOnce() {
        val parent = TreeValueStructuralParent()
        val tree = parent.tree
        tree.value
        val before = tree.internalSettleCount
        val bridge = TreeValueStructuralBridge()
        parent.left { n bridge bridge }
        assertEquals(before, tree.internalSettleCount, "attaching a bridge that replays nothing")
        bridge.deliver(9)
        assertEquals(before + 1, tree.internalSettleCount)
        assertEquals(9, tree.value[parent.left.n])
    }

    @Test
    fun theHandleIsStableAndNamesItsStore() {
        val parent = TreeValueStructuralParent()
        assertSame(parent.tree, parent.tree)
        assertEquals("StoreTree(TreeValueStructuralParent)", parent.tree.toString())
        assertSame(parent, (parent.tree.internalHost() as TreeValueHost).owner)
    }
}
