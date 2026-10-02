@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.awaitCollected
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GcThreadStore(
    val id: Int,
) : Store<GcThreadStore>() {
    val n by state { 0 }
}

/** [failingKey]'s factory run builds its store, then throws: the reservation is abandoned. */
private class GcParent : Store<GcParent>() {
    var failingKey: Int? = null
    var built: WeakReference<GcThreadStore>? = null
    val threads by stores<Int, GcThreadStore> { id ->
        val store = GcThreadStore(id)
        if (id == failingKey) {
            built = WeakReference(store)
            error("abandon")
        }
        store
    }
}

/**
 * T3: a keyed store that left the tree — disposed, or built by a factory
 * that then failed (the tree never took it, and disposes nothing) — is
 * collectable while its parent lives. The wait is `awaitCollected`
 * (`GcSupport.kt`): a bounded poll under allocation pressure, never
 * `System.runFinalization()`.
 */
class KeyedStoreGcTest {
    @Test
    fun aDisposedKeyedStoreIsCollectable() {
        val parent = GcParent()
        val ref = createDisposeAndForget(parent)
        assertTrue(awaitCollected(ref), "a disposed keyed store was not collected within the budget")
        assertNull(ref.get(), "the parent must not keep a disposed keyed store reachable")
        assertTrue(parent.threads.entries.isEmpty())
    }

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun createDisposeAndForget(parent: GcParent): WeakReference<GcThreadStore> {
        val store = parent.threads.create(1)
        store action { n mutate 1 }
        store.dispose()
        return WeakReference(store)
    }

    @Test
    fun aStoreAFailingFactoryBuiltIsCollectableTheTreeHoldsNoReferenceToIt() {
        val parent = GcParent()
        val ref = createAbandonAndForget(parent)
        assertTrue(awaitCollected(ref), "a store its failing factory built was not collected within the budget")
        assertNull(ref.get(), "the tree must hold no reference to a store it never attached")
        assertNull(parent.threads[2])
    }

    /** A frame of its own, like [createDisposeAndForget]: the factory's store leaves only through the weak reference. */
    private fun createAbandonAndForget(parent: GcParent): WeakReference<GcThreadStore> {
        parent.failingKey = 2
        runCatching { parent.threads.create(2) }
        return checkNotNull(parent.built) { "the factory ran" }
    }
}
