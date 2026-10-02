@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class BsLeafStore : Store<BsLeafStore>() {
    val n by state { 0 }
}

private class BsRoot : Root("bs") {
    /** Only a property to hand `provideDelegate`; the branch it binds is returned by [declare]. */
    val marker: Branch? = null

    fun declare(vararg named: Pair<Store<*>, String>): Branch {
        var declaration = branch(*named.map { it.first }.toTypedArray()).named("late")
        for ((store, name) in named) declaration = declaration.named(store, name)
        return declaration.provideDelegate(this, BsRoot::marker).getValue(this, BsRoot::marker)
    }
}

/**
 * A listed store disposed on another thread after its attach and before the
 * branch registers: the listeners never hear of it (no `onDetached` without
 * an `onAttached`), and the registry never indexes it as a member. The
 * declaration is parked deterministically on the second store's slot lock,
 * after the first store attached.
 */
class BranchDeclarationSlotRaceTest {
    @Test
    fun aStoreDisposedBetweenAttachAndRegistrationIsNeverAMemberAndNeverAnnounced() =
        completesWithin(30, "dispose before registration") {
            val root = BsRoot()
            val a = BsLeafStore()
            val b = BsLeafStore()
            val events = ConcurrentLinkedQueue<String>()
            root.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onAttached(leaf: LeafNode) {
                        events += "attached ${leaf.name}"
                    }

                    override fun onDetached(leaf: LeafNode) {
                        events += "detached ${leaf.name}"
                    }
                },
            )

            val hold = SlotHold(b)
            val declared = AtomicReference<Branch?>(null)
            val declarer = daemon("declarer") { declared.set(root.declare(a to "a", b to "b")) }
            // a attached; the declarer is parked attaching b, so nothing is registered yet.
            awaitUntil("a's membership attached") { a.hasTreeMembership() }
            a.dispose()
            hold.release()
            declarer.join()
            val branch = checkNotNull(declared.get())

            assertEquals(listOf("attached b"), events.toList(), "a left before anyone heard of it")
            assertNull(root.nodeOf(a), "a store that left before registration is no member")
            assertEquals(listOf<Store<*>>(b), root.children(branch))
            assertEquals(listOf<StoreNode>(root, branch, root.nodeOf(b)!!), root.nodes)
        }
}
