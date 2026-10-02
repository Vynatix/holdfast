@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.awaitCollected
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.internalAttachment
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ParentDisposeFanoutRaceKeyedStore(
    id: Int,
) : Store<ParentDisposeFanoutRaceKeyedStore>() {
    val n by state { id }
}

private class ParentDisposeFanoutRaceParent : Store<ParentDisposeFanoutRaceParent>() {
    val threads by stores<Int, ParentDisposeFanoutRaceKeyedStore> { ParentDisposeFanoutRaceKeyedStore(it) }
}

/** What the creator saw of the store its `create` returned, recorded while it still held the store. */
private class ParentDisposeFanoutRaceOutcome(
    val disposed: Boolean,
    val parentNode: StoreNode?,
    val hasParentEdge: Boolean,
)

/**
 * A keyed `create` whose attach announce (phase 6) is in flight while its
 * parent disposes. The store was promoted (phase 4) before the dispose, so
 * the attach is irrevocable: the parent's dispose releases it as a subtree
 * root and the announcer delivers its deferred detach. The `create` returns
 * the store live and unattached — the tree disposes nothing — and the
 * parent's tree value, whose membership listener the announce reaches after
 * the dispose cleared its bookkeeping, must refuse that late attach: the
 * store is collectable once the caller drops it, and nothing is reported.
 *
 * The creator is parked deterministically by a listener registered on the
 * parent before its `tree` handle (whose value listener therefore comes
 * after it in the parent's listener list), so it holds the announce open
 * from inside, before the value's listener has run.
 */
class ParentDisposeFanoutRaceTest {
    /** Keeps each parent reachable for the whole test, so a collected store is not explained by a collected parent. */
    private val parents = mutableListOf<Store<*>>()

    @Test
    fun aStoreReleasedByADisposeDuringItsAttachAnnounceIsNotRetainedBeforeTheValueExists() =
        completesWithin(30, "create parked in its announce beside a dispose") {
            val parent = ParentDisposeFanoutRaceParent().also { parents += it }
            val race = Race(parent)
            parent.tree
            val ref = race.run()
            assertTrue(awaitCollected(ref), "the released store was not collected within the budget")
            assertNull(ref.get(), "a disposed parent must not retain a store whose attach announce it overtook")
            assertTrue(parent.isDisposed)
        }

    @Test
    fun aStoreReleasedByADisposeDuringItsAttachAnnounceIsNotRetainedOnceTheValueExists() =
        completesWithin(30, "create parked in its announce beside a dispose, value read") {
            val parent = ParentDisposeFanoutRaceParent().also { parents += it }
            val race = Race(parent)
            val tree = parent.tree
            val before = tree.value
            val ref = race.run()
            assertTrue(awaitCollected(ref), "the released store was not collected within the budget")
            assertNull(ref.get(), "a disposed parent must not retain a store whose attach announce it overtook")
            assertEquals(before, tree.value, "the last settled tree stays readable, without the released store")
        }

    /** The parking listener, registered on [parent] at construction, ahead of the value's listener. */
    private class Race(
        private val parent: ParentDisposeFanoutRaceParent,
    ) {
        private val armed = AtomicBoolean(false)
        private val inAnnounce = CountDownLatch(1)
        private val disposed = CountDownLatch(1)
        private val reported = ConcurrentLinkedQueue<Throwable>()

        init {
            parent.internalAddMembershipListener(
                object : LeafMembershipListener() {
                    override fun onAttached(leaf: LeafNode) {
                        if (!armed.compareAndSet(true, false)) return
                        inAnnounce.countDown()
                        assertTrue(
                            disposed.await(10, TimeUnit.SECONDS),
                            "the dispose finished while the announce was parked",
                        )
                    }
                },
            )
            parent.uncaughtObserverHandler = { reported += it }
        }

        /**
         * A frame of its own, so no interpreter-local slot of the test method
         * keeps the store alive: the creator parks inside the attach announce
         * before the value's listener runs, the parent disposes meanwhile,
         * and the creator then finishes — the value's listener and the
         * deferred detach both run against the disposed parent.
         */
        fun run(): WeakReference<ParentDisposeFanoutRaceKeyedStore> {
            armed.set(true)
            val weak = AtomicReference<WeakReference<ParentDisposeFanoutRaceKeyedStore>?>(null)
            val outcome = AtomicReference<ParentDisposeFanoutRaceOutcome?>(null)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val creator =
                daemon("creator", failures) {
                    val created = parent.threads.create(1)
                    weak.set(WeakReference(created))
                    val attachment = created.internalAttachment(treeMembershipKey)
                    outcome.set(
                        ParentDisposeFanoutRaceOutcome(
                            disposed = created.isDisposed,
                            parentNode = attachment?.node?.parent,
                            hasParentEdge = attachment?.parentEdge?.value != null,
                        ),
                    )
                }
            assertTrue(inAnnounce.await(10, TimeUnit.SECONDS), "the create reached its attach announce")
            parent.dispose()
            disposed.countDown()
            creator.join()

            assertEquals(emptyList(), failures.toList(), "the create returned: the attach was irrevocable")
            val seen = checkNotNull(outcome.get()) { "the creator recorded its outcome" }
            assertEquals(false, seen.disposed, "the tree disposes nothing: the store is live")
            assertNull(seen.parentNode, "released as a subtree root")
            assertEquals(false, seen.hasParentEdge, "with no parent edge left")
            assertEquals(emptyList(), reported.toList(), "nothing was reported through the parent's handler")
            return checkNotNull(weak.get()) { "the factory ran" }
        }
    }
}
