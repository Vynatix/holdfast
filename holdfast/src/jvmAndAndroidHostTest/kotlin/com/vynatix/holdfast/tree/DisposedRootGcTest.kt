@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DgLeafStore : Store<DgLeafStore>() {
    val n by state { 0 }
}

private class DgThreadStore(
    id: Int,
    root: DgRoot,
) : Store<DgThreadStore>(root.threads.at(id)) {
    val n by state { 0 }
}

private class DgRoot : Root("dg") {
    val left = DgLeafStore()
    val leaf by branch(left)
    val threads by keyed<Int, DgThreadStore>()
}

private val GC_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5)
private const val PRESSURE_CHUNKS = 32
private const val PRESSURE_CHUNK_BYTES = 256 * 1024

/**
 * A keyed store live when its root disposed, and disposed itself later, is
 * collectable while the root is still referenced: the root heard nothing of
 * that later dispose (the membership was released), so it must hold nothing
 * of the store. `KeyedStoreGcTest` covers the live-root case.
 */
class DisposedRootGcTest {
    /** Keeps each root reachable for the whole test, so a collected store is not explained by a collected root. */
    private val roots = mutableListOf<Root>()

    @Test
    fun aKeyedStoreDisposedAfterItsRootIsCollectable() {
        val root = DgRoot().also { roots += it }
        val ref = createDisposeRootThenStore(root)
        awaitCollected(ref)
        assertNull(ref.get(), "a disposed root must not keep a keyed store it once listed reachable")
        assertTrue(root.isDisposed)
    }

    @Test
    fun aKeyedStoreThatJoinedInsideTheActionDisposingTheRootIsCollectable() {
        val root = DgRoot().also { roots += it }
        val ref = createInsideAnActionThatDisposesTheRoot(root)
        awaitCollected(ref)
        assertNull(ref.get(), "a join still pending at dispose must not keep the store reachable")
        assertTrue(root.isDisposed)
    }

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun createDisposeRootThenStore(root: DgRoot): WeakReference<DgThreadStore> {
        val store = root.threads.create(1) { DgThreadStore(it, root) }
        store action { n mutate 1 }
        root.dispose()
        store.dispose()
        return WeakReference(store)
    }

    /** The value's node exists (it was read), so the join reached its edge bookkeeping, not the pending list. */
    private fun createInsideAnActionThatDisposesTheRoot(root: DgRoot): WeakReference<DgThreadStore> {
        root.value.value
        var store: DgThreadStore? = null
        root.left action {
            store = root.threads.create(2) { DgThreadStore(it, root) }
            root.dispose()
        }
        val ref = WeakReference(checkNotNull(store))
        store!!.dispose()
        store = null
        return ref
    }

    /** Polls under allocation pressure, never `System.runFinalization()`. */
    private fun awaitCollected(ref: WeakReference<*>) {
        val deadline = System.nanoTime() + GC_BUDGET_NANOS
        while (ref.get() != null && System.nanoTime() < deadline) {
            val pressure = ArrayList<ByteArray>(PRESSURE_CHUNKS)
            repeat(PRESSURE_CHUNKS) { pressure += ByteArray(PRESSURE_CHUNK_BYTES) }
            pressure.clear()
            System.gc()
            Thread.sleep(20)
        }
    }
}
