@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.awaitCollected
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class VfKeyedStore(
    id: Int,
    root: VfRoot,
) : Store<VfKeyedStore>(root.threads.at(id)) {
    val n by state { 0 }
}

private class VfRoot : Root("vf") {
    val threads by keyed<Int, VfKeyedStore>()
}

/**
 * A keyed `create` whose attach fanout is in flight while the root disposes:
 * the fanout listed `Root.value`'s membership listener before
 * `registry.close()` dropped it, so that listener runs after
 * `RootValue.dispose()` cleared its bookkeeping, the `create` then fails at
 * promotion (the root is disposed) and `abandon` tells an already-empty
 * listener list. The value must refuse the late bookkeeping — else the
 * disposed root retains the abandoned store — and its listener path must
 * throw nothing, so the `create` fails with the root's own "disposed" error.
 *
 * The creator is parked deterministically by a listener prepended to the
 * registry's list (the value's listener is registered in `Root`'s own
 * initializer, ahead of anything a test can register), so it holds the
 * fanout open from inside, before the value's listener has run.
 */
class RootValueDisposeFanoutRaceTest {
    /** Keeps each root reachable for the whole test, so a collected store is not explained by a collected root. */
    private val roots = mutableListOf<Root>()

    @Test
    fun aStoreAbandonedByADisposeDuringItsAttachFanoutIsNotRetainedBeforeTheValueExists() =
        completesWithin(30, "create parked in its fanout beside a dispose") {
            val root = VfRoot().also { roots += it }
            val ref = raceCreateAgainstDispose(root)
            assertTrue(awaitCollected(ref), "the abandoned store was not collected within the budget")
            assertNull(ref.get(), "a disposed root must not retain a store whose attach fanout it overtook")
            assertTrue(root.isDisposed)
        }

    @Test
    fun aStoreAbandonedByADisposeDuringItsAttachFanoutIsNotRetainedOnceTheValueExists() =
        completesWithin(30, "create parked in its fanout beside a dispose, value read") {
            val root = VfRoot().also { roots += it }
            val before = root.value.value
            val ref = raceCreateAgainstDispose(root)
            assertTrue(awaitCollected(ref), "the abandoned store was not collected within the budget")
            assertNull(ref.get(), "a disposed root must not retain a store whose attach fanout it overtook")
            assertEquals(before, root.value.value, "the last settled tree stays readable, without the abandoned store")
        }

    /**
     * A frame of its own, so no interpreter-local slot of the test method
     * keeps the store alive: the creator parks inside the attach fanout
     * before the value's listener runs, the root disposes meanwhile, and the
     * creator then finishes — the value's listener, the failed promotion and
     * the abandon all run against the disposed root.
     */
    private fun raceCreateAgainstDispose(root: VfRoot): WeakReference<VfKeyedStore> {
        val inFanout = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val park =
            object : LeafMembershipListener() {
                override fun onAttached(leaf: LeafNode) {
                    inFanout.countDown()
                    assertTrue(disposed.await(10, TimeUnit.SECONDS), "the dispose finished while the fanout was parked")
                }
            }
        root.registry.lock.withLock { root.registry.listeners = listOf(park) + root.registry.listeners }
        val reported = ConcurrentLinkedQueue<Throwable>()
        root.uncaughtObserverHandler = { reported += it }

        val weak = AtomicReference<WeakReference<VfKeyedStore>?>(null)
        // Read while the creator still holds the store strongly: once it has returned, nothing may.
        val disposedAfterAbandon = AtomicReference<Boolean?>(null)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val creator =
            daemon("creator", failures) {
                var built: VfKeyedStore? = null
                try {
                    root.threads.create(1) { id ->
                        VfKeyedStore(id, root).also {
                            built = it
                            weak.set(WeakReference(it))
                        }
                    }
                } finally {
                    disposedAfterAbandon.set(built?.isDisposed)
                }
            }
        assertTrue(inFanout.await(10, TimeUnit.SECONDS), "the create reached its attach fanout")
        root.dispose()
        disposed.countDown()
        creator.join()

        val failure = assertIs<IllegalStateException>(failures.singleOrNull(), "the create fails once, at promotion")
        assertEquals("root 'vf' disposed", failure.message, "the listener path threw nothing of its own")
        assertEquals(emptyList(), reported.toList(), "nothing was reported through the root's handler")
        assertEquals(true, disposedAfterAbandon.get(), "the abandoned store was disposed")
        return checkNotNull(weak.get()) { "the factory ran" }
    }
}
