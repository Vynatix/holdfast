@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNull

private class GcThreadStore(
    id: Int,
    root: GcRoot,
) : Store<GcThreadStore>(root.threads.at(id)) {
    val n by state { 0 }
}

private class GcRoot : Root() {
    val threads by keyed<Int, GcThreadStore>()
}

private const val GC_ATTEMPTS = 20

/** T3: a keyed store that left the tree — disposed, or abandoned by a failing factory — is collectable. */
class KeyedStoreGcTest {
    @Test
    fun aDisposedKeyedStoreIsCollectable() {
        val root = GcRoot()
        val ref = createDisposeAndForget(root)
        awaitCollected(ref)
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
        var ref: WeakReference<GcThreadStore>? = null
        runCatching {
            root.threads.create(2) { id ->
                val s = GcThreadStore(id, root)
                ref = WeakReference(s)
                error("abandon")
            }
        }
        awaitCollected(ref!!)
        assertNull(ref!!.get(), "the root must not keep an abandoned keyed store reachable")
    }

    private fun awaitCollected(ref: WeakReference<*>) {
        repeat(GC_ATTEMPTS) {
            if (ref.get() == null) return
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
    }
}
