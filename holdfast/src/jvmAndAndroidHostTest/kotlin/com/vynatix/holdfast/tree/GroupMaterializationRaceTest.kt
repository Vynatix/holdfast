@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.internalAttachment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GmrAStore : Store<GmrAStore>() {
    val n by state { 0 }
}

private class GmrBStore : Store<GmrBStore>() {
    val n by state { 0 }
}

/** A parent whose group lists [listed] when it first materializes (set before the materializing thread starts). */
private class GmrParent : Store<GmrParent>() {
    var listed: List<Store<*>> = emptyList()
    val group by stores { listed }
}

/** Records every membership event a parent hears, from any thread. */
private class GmrRecording : LeafMembershipListener() {
    val events = ConcurrentLinkedQueue<String>()

    override fun onAttached(leaf: LeafNode) {
        events += "attached ${leaf.name}"
    }

    override fun onDetached(leaf: LeafNode) {
        events += "detached ${leaf.name}"
    }
}

/**
 * A listed store disposed on another thread while its group materializes:
 * the listeners never hear of it (no `onDetached` without an `onAttached`),
 * and the group never lists it as a member. Each case parks the
 * materializing thread at a known phase by holding the lock that phase
 * takes, so the interleaving is deterministic rather than timed.
 */
class GroupMaterializationRaceTest {
    @Test
    fun aMemberDisposedBetweenItsLinkAndTheRegistrationIsNeverAMemberAndNeverAnnounced() =
        completesWithin(30, "dispose between link and registration") {
            val parent = GmrParent()
            val a = GmrAStore()
            val b = GmrBStore()
            parent.listed = listOf(a, b)
            val recording = GmrRecording()
            parent.internalAddMembershipListener(recording)
            val registry = parent.treeAttachment().registry

            // Park the materializer at its link (phase 3), after it claimed the entry (phase 2)...
            val structure = LockHold(treeStructureLock)
            val declared = AtomicReference<Result<Branch>?>(null)
            val materializer = daemon("materializer") { declared.set(runCatching { parent.group }) }
            awaitUntil("the group's entry claimed") {
                registry.declaredEntry("group")?.phase == ChildEntry.Phase.Constructing
            }
            // ...then hold the registry it registers under (phase 4), and let it link.
            val registration = LockHold(registry.lock)
            structure.release()
            awaitUntil("both members linked") { a.hasTreeMembership() && b.hasTreeMembership() }

            // a disposes while the registration waits: its dispose marks it disposed before it
            // detaches, and its detach waits on the same registry lock.
            val disposer = daemon("disposer") { a.dispose() }
            awaitUntil("a marked disposed") { a.isDisposed }
            registration.release()
            disposer.join()
            materializer.join()
            val group = declared.get()!!.getOrThrow()

            assertEquals(listOf("attached GmrB"), recording.events.toList(), "a left before anyone heard of it")
            assertNull(parent.tree.nodeOf(a), "a store that left before registration is no member")
            assertFalse(a.hasTreeMembership())
            assertEquals(listOf<Store<*>>(b), parent.tree.stores(group))
            assertEquals(listOf(parent, b), parent.tree.stores())
            assertEquals(listOf<Store<*>>(a, b), group.stores, "the group's listing itself is fixed")
        }

    @Test
    fun aMemberDisposedBeforeTheLinkFailsTheMaterializationAndLinksNothing() =
        completesWithin(30, "dispose before the link") {
            val parent = GmrParent()
            val a = GmrAStore()
            val b = GmrBStore()
            parent.listed = listOf(a, b)
            val recording = GmrRecording()
            parent.internalAddMembershipListener(recording)

            // The group validated its listing and took a's tree state; it is parked taking b's.
            val hold = SlotHold(b)
            val declared = AtomicReference<Result<Branch>?>(null)
            val materializer = daemon("materializer") { declared.set(runCatching { parent.group }) }
            awaitUntil("a's tree state taken") { a.internalAttachment(treeMembershipKey) != null }
            a.dispose()
            hold.release()
            materializer.join()

            val failure = declared.get()!!.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertTrue("GmrParent.group: the child disposed while it was being attached" in failure.message!!, failure.message)
            assertTrue(recording.events.isEmpty(), "nothing was announced: ${recording.events}")
            assertFalse(b.hasTreeMembership(), "b was unlinked with the rest")
            val registry = parent.treeAttachment().registry
            assertEquals(ChildEntry.Phase.Declared, registry.declaredEntry("group")?.phase)
            assertTrue(registry.liveChildNodes().isEmpty(), "the group is not listed")
            // The next read retries, and the listing's validation refuses the disposed member.
            val retry = runCatching { parent.group }.exceptionOrNull()
            assertIs<IllegalArgumentException>(retry)
            assertTrue("lists a disposed GmrAStore" in retry.message!!, retry.message)
        }
}
