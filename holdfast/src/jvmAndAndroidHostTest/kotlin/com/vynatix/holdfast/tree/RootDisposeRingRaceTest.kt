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
import kotlin.test.assertTrue

private class RrLeafStore : Store<RrLeafStore>() {
    val n by state { 0 }
}

private class RrRoot(
    a: RrLeafStore,
    b: RrLeafStore,
) : Root("rr") {
    val pair by branch(a, b).named(a, "a").named(b, "b")
}

/** A root that lists a store already declared elsewhere, once that tree let it go. */
private class RrAdopter : Root("adopter") {
    /** Only a property to hand `provideDelegate`; the branch it binds is returned by [adopt]. */
    val marker: Branch? = null

    fun adopt(store: Store<*>): Branch =
        branch(store)
            .named(store, "a")
            .named("adopted")
            .provideDelegate(this, RrAdopter::marker)
            .getValue(this, RrAdopter::marker)
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
 * `Root.dispose()` beside another root listing a leaf it just released: the
 * disposing root's ring must never wipe what the new root's ring put on
 * that leaf. The dispose is parked deterministically on its second leaf's
 * slot lock, after the first leaf's membership is released.
 */
class RootDisposeRingRaceTest {
    @Test
    fun aLeafAnotherRootListsWhileThisRootDisposesKeepsThatRootsMiddleware() =
        completesWithin(30, "dispose beside an adoption") {
            val a = RrLeafStore()
            val b = RrLeafStore()
            val root = RrRoot(a, b)
            root.middlewares(RrTrace())
            val adopter = RrAdopter()
            val adopted = RrTrace()
            adopter.middlewares(adopted)

            val hold = SlotHold(b)
            val disposer = daemon("disposer") { root.dispose() }
            // The disposer released a's membership and is parked on b's slot, before it can finish.
            awaitUntil("a's membership released") { !a.hasTreeMembership() }
            adopter.adopt(a)
            assertTrue(a.hasTreeMembership(), "a now belongs to the adopter")
            hold.release()
            disposer.join()
            assertTrue(root.isDisposed)

            a action { }
            val seen = adopted.events.toList()
            assertEquals(listOf("started a", "completed a"), seen, "the adopter's ring survived the dispose")
            assertEquals(listOf(adopted), adopter.treeMiddleware.adaptersOf(a).map { it.middleware })
        }
}
