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
    id: Int,
    root: GcRoot,
) : Store<GcThreadStore>(root.threads.at(id)) {
    val n by state { 0 }
}

private class GcRoot : Root() {
    val threads by keyed<Int, GcThreadStore>()
}

/**
 * T3: a keyed store that left the tree — disposed, or abandoned by a failing
 * factory — is collectable. The wait is `awaitCollected` (`GcSupport.kt`):
 * a bounded poll under allocation pressure, never `System.runFinalization()`.
 */
class KeyedStoreGcTest {
    @Test
    fun aDisposedKeyedStoreIsCollectable() {
        val root = GcRoot()
        val ref = createDisposeAndForget(root)
        assertTrue(awaitCollected(ref), "a disposed keyed store was not collected within the budget")
        assertNull(ref.get(), "the root must not keep a disposed keyed store reachable")
    }

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun createDisposeAndForget(root: GcRoot): WeakReference<GcThreadStore> {
        val store = root.threads.create(1) { GcThreadStore(it, root) }
        store action { n mutate 1 }
        store.dispose()
        return WeakReference(store)
    }

    @Test
    fun anAbandonedKeyedStoreIsCollectable() {
        val root = GcRoot()
        val ref = createAbandonAndForget(root)
        assertTrue(awaitCollected(ref), "an abandoned keyed store was not collected within the budget")
        assertNull(ref.get(), "the root must not keep an abandoned keyed store reachable")
    }

    /** A frame of its own, like [createDisposeAndForget]: the factory's store leaves only through the weak reference. */
    private fun createAbandonAndForget(root: GcRoot): WeakReference<GcThreadStore> {
        var ref: WeakReference<GcThreadStore>? = null
        runCatching {
            root.threads.create(2) { id ->
                val s = GcThreadStore(id, root)
                ref = WeakReference(s)
                error("abandon")
            }
        }
        return checkNotNull(ref) { "the factory ran" }
    }
}
