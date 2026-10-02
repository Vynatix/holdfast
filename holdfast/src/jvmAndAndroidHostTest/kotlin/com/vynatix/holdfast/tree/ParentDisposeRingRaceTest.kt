@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class RrLeafStore : Store<RrLeafStore>() {
    val n by state { 0 }
}

private class RrParent : Store<RrParent>() {
    val a by store { RrLeafStore() }
    val b by store { RrLeafStore() }
}

/** A store that declares, as its child, a store another parent just released. */
private class RrAdopter : Store<RrAdopter>() {
    /** Only a property to hand `provideDelegate`; the child it binds is returned by [adopt]. */
    val marker: Int = 0

    fun adopt(child: RrLeafStore): RrLeafStore =
        store(named = "a") { child }
            .provideDelegate(this, RrAdopter::marker)
            .getValue(this, RrAdopter::marker)
}

/** A released child whose keyed child is created while the released child's former parent is still disposing. */
private class RrMiddleStore : Store<RrMiddleStore>() {
    val lates by keyed<Int, RrLeafStore> { RrLeafStore() }
}

private class RrDisposing : Store<RrDisposing>() {
    val middle by store { RrMiddleStore() }
}

private class RrTop : Store<RrTop>() {
    val disposing by store { RrDisposing() }
}

private class RrTrace : TreeMiddleware() {
    val events = ConcurrentLinkedQueue<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "completed ${node.name}"
    }
}

/**
 * A parent's dispose beside another parent adopting a child it just
 * released: the dispose re-syncs every released child's ring from the
 * child's ancestry as it is THEN, so it never wipes what the new parent's
 * ring put on the child. The dispose is parked deterministically on its
 * tree value's host lock, held here: the host's dispose runs when the
 * detach's settle scope settles, after the children were released (and
 * their rings re-synced from a fresh listing), so the adoption lands while
 * the dispose is still in progress.
 *
 * And a store that attaches under a released child while the dispose is
 * still announcing — after the dispose listed the released subtree, before
 * the child became a subtree root — never keeps the disposed store's
 * ancestors' middleware.
 */
class ParentDisposeRingRaceTest {
    @Test
    fun aChildAnotherParentAdoptsWhileThisParentDisposesKeepsThatParentsMiddleware() =
        completesWithin(30, "dispose beside an adoption") {
            val parent = RrParent()
            val a = parent.a
            val b = parent.b
            val tree = parent.tree
            val disposedRing = RrTrace()
            tree.middlewares(disposedRing)
            val adopter = RrAdopter()
            val adopted = RrTrace()
            adopter.tree.middlewares(adopted)

            val hold = LockHold(tree.internalHost().transactionLock)
            val disposer = daemon("disposer") { parent.dispose() }
            // The disposer released a and b and is parked on the host lock, before it re-syncs their rings.
            awaitUntil("a released") { !a.hasTreeMembership() }
            adopter.adopt(a)
            assertTrue(a.hasTreeMembership(), "a now belongs to the adopter")
            hold.release()
            disposer.join()
            assertTrue(parent.isDisposed)
            assertTrue(tree.internalHost().isDisposed, "the parked step ran once the lock was free")

            a action { }
            b action { }
            assertEquals(listOf("started a", "completed a"), adopted.events.toList(), "the adopter's ring survived the dispose")
            assertEquals(emptyList<String>(), disposedRing.events.toList(), "the disposed parent's ring is gone everywhere")
            assertEquals(listOf<TreeMiddleware>(adopted), a.treeRingAdapters().map { it.middleware })
            assertTrue(b.treeRingAdapters().isEmpty(), "a released child with no new parent keeps no ring")
            assertSame<StoreNode?>(adopter.tree.node, a.tree.parent)
        }

    @Test
    fun aStoreAttachingUnderAReleasedChildWhileTheDisposeAnnouncesGetsNoFormerAncestorsMiddleware() =
        completesWithin(30, "an attach under a released child during the dispose's announce") {
            val top = RrTop()
            val disposing = top.disposing
            val middle = disposing.middle
            val middleNode = middle.tree.node
            val trace = RrTrace()
            top.tree.middlewares(trace)
            assertEquals(listOf<TreeMiddleware>(trace), middle.treeRingAdapters().map { it.middleware })

            // The dispose's announce (step 4) tells top that `middle` left; while it is there,
            // another thread creates a keyed child of `middle` — `middle` still hangs under the
            // disposing store, but it was already listed for the ring re-sync.
            var late: RrLeafStore? = null
            val failures = ConcurrentLinkedQueue<Throwable>()
            top.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onDetached(leaf: LeafNode) {
                        if (leaf === middleNode && late == null) {
                            daemon("attacher", failures) { late = middle.lates.create(1) }.join()
                        }
                    }
                },
            )
            disposing.dispose()
            assertTrue(failures.isEmpty(), failures.joinToString())

            val attached = checkNotNull(late) { "the attach ran" }
            assertSame<StoreNode?>(middle.lates, attached.tree.parent, "it attached under the released child")
            assertEquals(null, middle.tree.parent, "which is a subtree root now")
            assertTrue(attached.treeRingAdapters().isEmpty(), "no adapter of the former ancestors' middleware stays")
            assertTrue(middle.treeRingAdapters().isEmpty())
            attached action { n mutate 1 }
            middle action { }
            assertEquals(emptyList<String>(), trace.events.toList(), "top's middleware no longer sees the released subtree")
        }
}
