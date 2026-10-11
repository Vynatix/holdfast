@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.testing.concurrency.transaction
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TreeValueAncestryLeafStore : Store<TreeValueAncestryLeafStore>() {
    val n by state { 0 }
}

/** A mid-tree store: a child of a parent, with a child of its own. */
private class TreeValueAncestryMidStore : Store<TreeValueAncestryMidStore>() {
    val m by state { 0 }
    val leaf by store { TreeValueAncestryLeafStore() }
}

private class TreeValueAncestryTopStore : Store<TreeValueAncestryTopStore>() {
    val g by state { 0 }
    val mid by store { TreeValueAncestryMidStore() }
}

/** Grafts whatever [next] holds — a store whose own subtree may already be materialized — under a key. */
private class TreeValueAncestryGraftStore : Store<TreeValueAncestryGraftStore>() {
    var next: TreeValueAncestryMidStore? = null
    val mids by keyed<String, TreeValueAncestryMidStore> { checkNotNull(next) { "nothing to graft" } }
}

/** Runs [onDisposed] from its `onDispose()`: after it is disposed, before its tree dispose ran. */
private class TreeValueAncestryDisposingChildStore : Store<TreeValueAncestryDisposingChildStore>() {
    var onDisposed: () -> Unit = {}
    val leaf by store { TreeValueAncestryLeafStore() }

    override fun onDispose() {
        onDisposed()
    }
}

private class TreeValueAncestryMiddleStore : Store<TreeValueAncestryMiddleStore>() {
    val child by store { TreeValueAncestryDisposingChildStore() }
}

private class TreeValueAncestryGrandStore : Store<TreeValueAncestryGrandStore>() {
    val middle by store { TreeValueAncestryMiddleStore() }
}

/** Declares, as its child, a store another parent releases during the test. */
private class TreeValueAncestryAdopterStore(
    target: TreeValueAncestryLeafStore,
) : Store<TreeValueAncestryAdopterStore>() {
    val adopted by store { target }
}

private fun Store<*>.hasParentEdge(): Boolean = internalAttachment(treeMembershipKey)?.parentEdge?.value != null

private class TreeValueAncestryHostLog : Middleware<TreeValueHost>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<TreeValueHost>) {
        started++
    }
}

/**
 * Every store's `tree` handle has a value of its own, over its own subtree:
 * a store with D materialized values above it is recaptured D times per
 * commit (once per value), each value settles once per outermost entry, and
 * a value never read costs nothing. Joins, grafts and disposes anywhere
 * below reach every materialized value above them.
 */
class TreeValueAncestryTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun StoreTree.followed(): List<Store<*>> = (this as StoreTreeImpl).treeValue().node().sourceStores

    @Test
    fun aGrandchildCommitSettlesTheChildsAndTheParentsValuesOnceEach() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        topTree.value
        val leaf = top.mid.leaf
        val midTree = top.mid.tree
        midTree.value
        assertEquals(1, topTree.internalSettleCount)
        assertEquals(1, midTree.internalSettleCount)
        assertEquals(3, topTree.internalCaptureCount, "the parent, the child and the grandchild")
        assertEquals(2, midTree.internalCaptureCount, "the child and the grandchild")

        leaf action { n mutate 1 }

        assertEquals(2, topTree.internalSettleCount, "the parent's value settled once")
        assertEquals(2, midTree.internalSettleCount, "the child's value settled once")
        assertEquals(4, topTree.internalCaptureCount, "the grandchild recaptured once for each value above it")
        assertEquals(3, midTree.internalCaptureCount)
        assertEquals(1, topTree.value[leaf.n])
        assertEquals(1, midTree.value[leaf.n])
    }

    @Test
    fun aParentWithNoMaterializedValueCostsNothing() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        val log = TreeValueAncestryHostLog().also { (topTree as StoreTreeImpl).treeValue().host.middlewares(it) }
        val leaf = top.mid.leaf
        val midTree = top.mid.tree
        midTree.value

        leaf action { n mutate 1 }
        top.mid action { m mutate 1 }

        assertEquals(3, midTree.internalSettleCount, "the value that was read follows both commits")
        assertEquals(0, topTree.internalSettleCount, "a value never read never settles")
        assertEquals(0, topTree.internalCaptureCount, "and captures nothing")
        assertEquals(0, log.started, "its host never opened a transaction")
    }

    @Test
    fun materializingAMidTreeValueLaterFollowsOnTheNextCommit() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        topTree.value
        val leaf = top.mid.leaf
        leaf action { n mutate 1 }
        assertEquals(2, topTree.internalSettleCount)

        val midTree = top.mid.tree
        assertEquals(0, midTree.internalSettleCount, "nothing to settle before the first read")
        assertEquals(1, midTree.value[leaf.n], "the first read captures the committed subtree")
        assertEquals(1, midTree.internalSettleCount)

        leaf action { n mutate 2 }
        assertEquals(2, midTree.internalSettleCount, "followed from its first read on")
        assertEquals(3, topTree.internalSettleCount)
        assertEquals(2, midTree.value[leaf.n])
        assertEquals(2, topTree.value[leaf.n])
    }

    @Test
    fun twoParentsOverDisjointSubtreesSettleIndependently() {
        val one = TreeValueAncestryTopStore()
        val two = TreeValueAncestryTopStore()
        one.tree.value
        two.tree.value

        one.mid.leaf action { n mutate 1 }
        assertEquals(2, one.tree.internalSettleCount)
        assertEquals(1, two.tree.internalSettleCount, "a commit under one parent never settles the other")

        two.mid action { m mutate 1 }
        assertEquals(2, one.tree.internalSettleCount)
        assertEquals(2, two.tree.internalSettleCount)

        atomic(one.mid.leaf, two.mid.leaf) {
            one.mid.leaf { n mutate 2 }
            two.mid.leaf { n mutate 3 }
        }.getOrThrow()
        assertEquals(3, one.tree.internalSettleCount, "one frame over both: each settles once")
        assertEquals(3, two.tree.internalSettleCount)
        assertEquals(2, one.tree.value[one.mid.leaf.n])
        assertEquals(3, two.tree.value[two.mid.leaf.n])
    }

    @Test
    fun aCommitOnTheParentsOwnStatesSettlesOnceAndRecapturesExactlyTheParent() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        val first = topTree.value
        val midTree = top.mid.tree
        midTree.value
        val midNode = topTree.nodeOf(top.mid)!!
        val leafNode = topTree.nodeOf(top.mid.leaf)!!
        assertEquals(3, topTree.internalCaptureCount)

        top action { g mutate 1 }

        assertEquals(2, topTree.internalSettleCount)
        assertEquals(4, topTree.internalCaptureCount, "only the parent recaptured")
        assertEquals(1, midTree.internalSettleCount, "a value below the parent does not follow the parent")
        val second = topTree.value
        assertEquals(1, second[top.g])
        assertSame(first[midNode]!!.leaf, second[midNode]!!.leaf, "the child's capture is reused")
        assertSame(first[leafNode]!!.leaf, second[leafNode]!!.leaf, "and the grandchild's")
    }

    @Test
    fun graftingAnAlreadyMaterializedSubtreeFollowsItsGrandchildren() {
        val graft = TreeValueAncestryGraftStore()
        val graftTree = graft.tree
        graftTree.value
        assertEquals(1, graftTree.internalSettleCount)

        val mid = TreeValueAncestryMidStore()
        val leaf = mid.leaf
        mid.tree.value
        leaf action { n mutate 4 }

        graft.next = mid
        assertSame(mid, graft.mids.create("m"))
        assertEquals(2, graftTree.internalSettleCount, "the graft of a child and its child settles once")
        assertEquals(4, graftTree.value[leaf.n], "the grandchild came with it")
        assertEquals(listOf<Store<*>>(graft, mid, leaf), graftTree.stores())
        assertEquals(listOf<Store<*>>(graft, mid, leaf), graftTree.followed())

        leaf action { n mutate 5 }
        assertEquals(3, graftTree.internalSettleCount, "and is followed")
        assertEquals(5, graftTree.value[leaf.n])
        assertEquals(5, mid.tree.value[leaf.n], "the grafted store's own value keeps following too")
    }

    @Test
    fun aMidTreeDisposeDropsTheGrandparentsEdgesAndItsChildrenKeepTheirStores() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        topTree.value
        val mid = top.mid
        val leaf = mid.leaf
        val leafNode = topTree.nodeOf(leaf)!!
        assertEquals(listOf<Store<*>>(top, mid, leaf), topTree.followed())

        mid.dispose()

        assertEquals(listOf<Store<*>>(top), topTree.followed(), "the dispose and the release both reached the value")
        assertSame(leaf, leafNode.store, "the grandchild keeps its store")
        assertFalse(leaf.isDisposed)
        assertNull(leafNode.parent, "released as a subtree root")
        assertEquals(listOf<Store<*>>(top), topTree.stores())
        assertNull(topTree.value[leafNode])
        val settles = topTree.internalSettleCount
        leaf action { n mutate 9 }
        assertEquals(settles, topTree.internalSettleCount, "the released grandchild is no longer followed")
    }

    @Test
    fun aMidTreeDisposeDropsTheEdgesOfGrandchildrenThatJoinedBeforeTheHandleExisted() {
        val top = TreeValueAncestryTopStore()
        // The usual startup order: the children are built before anyone asks for a tree.
        val mid = top.mid
        val leaf = mid.leaf
        val topTree = top.tree
        topTree.value
        assertEquals(listOf<Store<*>>(top, mid, leaf), topTree.followed())

        mid.dispose()

        assertEquals(listOf<Store<*>>(top), topTree.followed(), "the released grandchild's edge is dropped")
        assertFalse(leaf.isDisposed)
        val settles = topTree.internalSettleCount
        leaf action { n mutate 9 }
        assertEquals(settles, topTree.internalSettleCount, "the released grandchild is no longer followed")
    }

    @Test
    fun aReleaseBetweenTheHandleAndTheFirstReadLeavesNoEdge() {
        val top = TreeValueAncestryTopStore()
        val mid = top.mid
        val leaf = mid.leaf
        val topTree = top.tree
        mid.dispose()
        topTree.value
        assertEquals(listOf<Store<*>>(top), topTree.followed())
        val settles = topTree.internalSettleCount
        leaf action { n mutate 9 }
        assertEquals(settles, topTree.internalSettleCount)
    }

    @Test
    fun anObserverOfAnAncestorsValueRunsOnlyOnceTheDisposeHasReleasedTheChildren() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        val mid = top.mid
        val leaf = mid.leaf
        val leafNode = leaf.tree.node
        topTree.value
        val adopter = TreeValueAncestryAdopterStore(leaf)
        val seen = ArrayList<String>()
        disposables +=
            topTree effect {
                if (this[leafNode] == null && seen.isEmpty()) {
                    seen += "parent=${leafNode.parent?.name}"
                    seen += "edge=${leaf.hasParentEdge()}"
                    // A released child can be declared under another parent — from here too.
                    seen += runCatching { adopter.adopted }.fold({ "adopted" }, { "refused: ${it.message}" })
                }
            }

        mid.dispose() // outside every entry

        assertEquals(listOf("parent=null", "edge=false", "adopted"), seen)
        assertSame(adopter.tree.node, leaf.tree.parent)
    }

    @Test
    fun anOwnerDisposedInsideItsOwnActionDisposesTheHostAfterTheAction() {
        val top = TreeValueAncestryTopStore()
        val topTree = top.tree
        val mid = top.mid
        val last = topTree.value
        var hostDisposedInside: Boolean? = null
        top action {
            dispose()
            hostDisposedInside = topTree.internalHost().isDisposed
        }
        assertEquals(false, hostDisposedInside, "never under the owner's lock: deferred to the action's settle")
        assertTrue(topTree.internalHost().isDisposed, "which ran once the action released everything")
        assertSame(last, topTree.value, "the last tree stays readable")
        assertFalse(mid.isDisposed)
        assertNull(mid.tree.parent, "the child is released, never disposed")
    }

    @Test
    fun anOwnerDisposedInsideAHarnessTransactionBodyDisposesTheHostInline() =
        storeTest {
            val top = TreeValueAncestryTopStore()
            val topTree = top.tree
            val last = topTree.value
            val handle = track(top)
            var hostDisposedInBody: Boolean? = null
            val open =
                transaction(on = handle) {
                    dispose()
                    hostDisposedInBody = topTree.internalHost().isDisposed
                }
            assertEquals(
                true,
                hostDisposedInBody,
                "the body holds no lock of the owner's and runs in no entry: the host is disposed inline",
            )
            assertSame(last, topTree.value)
            open.rollback()
        }

    @Test
    fun aParentDisposingWhileItsDirectChildIsMidDisposeStillAnnouncesTheGrandchildrensDetach() {
        val grand = TreeValueAncestryGrandStore()
        val grandTree = grand.tree
        grandTree.value
        val middle = grand.middle
        val child = middle.child
        val grandchild = child.leaf
        val leafNode = grandTree.nodeOf(grandchild)!!
        val childNode = grandTree.nodeOf(child)!!
        assertEquals(listOf<Store<*>>(grand, middle, child, grandchild), grandTree.followed())
        val detached = mutableListOf<LeafNode>()
        disposables +=
            grand.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onDetached(leaf: LeafNode) {
                        detached += leaf
                    }
                },
            )
        // The child is disposed (isDisposed) but its own tree dispose has not run when the middle store disposes.
        child.onDisposed = {
            assertTrue(child.isDisposed)
            middle.dispose()
        }

        child.dispose()

        assertTrue(childNode in detached, "the middle store's dispose announced its disposing child")
        assertTrue(leafNode in detached, "and the grandchild under it, which only that walk could reach")
        assertEquals(listOf<Store<*>>(grand), grandTree.followed(), "the grandparent's value dropped every edge")
        assertEquals(listOf<Store<*>>(grand), grandTree.stores())
        assertSame(grandchild, leafNode.store, "the grandchild keeps its store")
        assertNull(leafNode.parent)
        assertFalse(grandchild.isDisposed)
        val settles = grandTree.internalSettleCount
        grandchild action { n mutate 1 }
        assertEquals(settles, grandTree.internalSettleCount)
    }
}
