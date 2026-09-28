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
import kotlin.test.assertTrue

private class StLeafStore : Store<StLeafStore>() {
    var initializerRuns = 0
    val n by state {
        initializerRuns++
        0
    }
    val other by state { "o" }
}

private class StKeyedStore(
    id: String,
    root: StRoot,
) : Store<StKeyedStore>(root.keyed.at(id)) {
    val title by state { "thread $id" }
}

private class StRoot : Root("st") {
    val left = StLeafStore()
    val leaf by branch(left)
    val keyed by keyed<String, StKeyedStore>()
}

private class StHostLog : Middleware<RootHost>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<RootHost>) {
        started++
    }
}

private class StBridge : Bridge<Int> {
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

/** T1 and T3: when the tree value is built, and how leaves joining and leaving reach it. */
class RootValueStructuralTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun StRoot.hostLog(): StHostLog = StHostLog().also { (rootValue.host).middlewares(it) }

    @Test
    fun theFirstReadBuildsTheTreeAndBranchRegistrationTakesNoCapture() {
        val root = StRoot()
        assertEquals(0, root.left.initializerRuns, "declaring a tree runs no leaf code")
        assertEquals(0, root.internalSettleCount, "no capture until the value is used")
        val tree = root.value.value
        assertEquals(1, root.left.initializerRuns)
        assertEquals(1, root.internalSettleCount)
        assertEquals(0, tree[root.left.n])
    }

    @Test
    fun aKeyedStoreCreatedOutsideAnyScopeAppearsOnTheNextRead() {
        val root = StRoot()
        root.value.value
        val k = root.keyed.create("k") { StKeyedStore(it, root) }
        assertEquals(2, root.internalSettleCount, "the attach recomputed once, after create returned")
        assertEquals("thread k", root.value.value[k.title])
        assertNotNull(root.value.value[root.nodeOf(k)!!])
    }

    @Test
    fun oneCreatedInsideAnActionAppearsAtThatActionsSettleAndReadsFreshInside() {
        val root = StRoot()
        val seen = mutableListOf<TreeSnapshot>()
        disposables += root.value effect { seen += this }
        val log = root.hostLog()
        var inside: TreeSnapshot? = null
        var k: StKeyedStore? = null
        root.left action {
            k = root.keyed.create("k") { StKeyedStore(it, root) }
            assertEquals(1, root.internalSettleCount, "nothing settles inside the action")
            inside = root.value.value
            assertEquals(0, log.started, "the read inside opened no transaction on the host")
        }
        assertNotNull(inside!![root.nodeOf(k!!)!!], "a read inside the action sees the leaf that just joined")
        assertEquals(1, log.started, "the settle at the action's exit did")
        assertEquals(2, root.internalSettleCount, "one settle at the action's exit")
        assertEquals(2, seen.size)
        assertEquals("thread k", seen.last()[k!!.title])
    }

    @Test
    fun aFirstEffectOnAnUnreadRootFiresARealCapture() {
        val root = StRoot()
        var fired: TreeSnapshot? = null
        disposables += root.value effect { fired = this }
        assertEquals(1, root.internalSettleCount)
        assertEquals(0, fired!![root.left.n])
    }

    @Test
    fun disposeOfAKeyedStoreDetachesAndRecomputesOnce() {
        val root = StRoot()
        val k = root.keyed.create("k") { StKeyedStore(it, root) }
        root.value.value
        val before = root.internalSettleCount
        val kLeaf = root.nodeOf(k)!!
        k.dispose()
        assertEquals(before + 1, root.internalSettleCount)
        assertNull(root.value.value[kLeaf], "the leaf left the tree")
        assertNull(root.nodeOf(k))
    }

    @Test
    fun disposeInsideAnotherLeafsActionJoinsThatScope() {
        val root = StRoot()
        val k = root.keyed.create("k") { StKeyedStore(it, root) }
        root.value.value
        val before = root.internalSettleCount
        root.left action {
            k.dispose()
            assertEquals(before, root.internalSettleCount)
        }
        assertEquals(before + 1, root.internalSettleCount, "the detach settled with the action")
    }

    @Test
    fun detachOrCreateFromInsideAValueObserverSettlesAfterTheCurrentFanout() {
        val root = StRoot()
        var created: StKeyedStore? = null
        disposables +=
            root.value effect {
                if (created == null) created = root.keyed.create("k") { StKeyedStore(it, root) }
            }
        assertEquals(2, root.internalSettleCount, "the initial settle, then the join")
        assertNotNull(root.value.value[root.nodeOf(created!!)!!])
        var disposed = false
        disposables +=
            root.value effect {
                if (!disposed) {
                    disposed = true
                    created!!.dispose()
                }
            }
        assertEquals(3, root.internalSettleCount)
        assertNull(root.nodeOf(created!!))
    }

    @Test
    fun aThousandKeyedCreateAndDisposeCyclesLeaveNoSourceEdgesOrLeaves() {
        val root = StRoot()
        root.value.value
        val node = root.rootValue.node()
        val baseline = node.sourceStores.size
        repeat(1_000) { i ->
            val k = root.keyed.create("k$i") { StKeyedStore(it, root) }
            k.dispose()
            assertEquals(0, k.internalAttachment(STORE_EDGES)?.followerCount ?: 0)
        }
        assertEquals(baseline, node.sourceStores.size)
        assertEquals(0, root.entries(root.keyed).size)
        assertEquals(1 + 2_000, root.internalSettleCount, "one settle per join and one per leave")
        assertEquals(listOf(root, root.leaf, root.nodeOf(root.left)!!, root.keyed), root.nodes)
    }

    @Test
    fun aValueReadInsideAnActionNeverCommitsTheHost() {
        val root = StRoot()
        root.value.value
        val log = root.hostLog()
        root.left action {
            n mutate 1
            root.value.value
            root.value.value
        }
        assertEquals(1, log.started, "only the settle after the action opened a host transaction")
    }

    @Test
    fun valueInsideALeafsFanoutIsPreCommitWhileSnapshotIsPostCommit() {
        val root = StRoot()
        root.value.value
        var valueSaw: Int? = null
        var snapshotSaw: Int? = null
        disposables +=
            root.left.n effect {
                if (this == 1) {
                    valueSaw = root.value.value[root.left.n]
                    snapshotSaw = root.snapshot()[root.left.n]
                }
            }
        root.left action { n mutate 1 }
        assertEquals(0, valueSaw, "the settle comes after the fanout")
        assertEquals(1, snapshotSaw)
        assertEquals(1, root.value.value[root.left.n])
    }

    @Test
    fun removeStateAndClearStatesSettleLikeACommit() {
        val root = StRoot()
        root.left action { n mutate 5 }
        root.value.value
        val before = root.internalSettleCount
        root.left.removeState("n")
        assertEquals(before + 1, root.internalSettleCount)
        assertEquals(0, root.value.value[root.left.n], "re-created from its initializer")
        root.left.clearStates()
        assertEquals(before + 2, root.internalSettleCount)
    }

    @Test
    fun bridgeInboundPushesAndAttachReplaysSettleOnce() {
        val root = StRoot()
        root.value.value
        val before = root.internalSettleCount
        val bridge = StBridge()
        root.left { n bridge bridge }
        assertEquals(before, root.internalSettleCount, "attaching a bridge that replays nothing")
        bridge.deliver(9)
        assertEquals(before + 1, root.internalSettleCount)
        assertEquals(9, root.value.value[root.left.n])
    }

    @Test
    fun theValueHandleIsStableAndNamesTheRoot() {
        val root = StRoot()
        assertTrue(root.value === root.value)
        assertEquals("Root.value(st)", root.value.toString())
        assertEquals(root, root.internalHost().let { (it as RootHost).root })
    }
}
