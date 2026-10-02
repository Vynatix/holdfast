@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.awaitCollected
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DgLeafStore : Store<DgLeafStore>() {
    val n by state { 0 }
}

private class DgThreadStore(
    val id: Int,
) : Store<DgThreadStore>() {
    val n by state { 0 }
}

private class DgParent : Store<DgParent>() {
    val leaf by store { DgLeafStore() }
    val threads by keyed<Int, DgThreadStore> { DgThreadStore(it) }
}

/**
 * A keyed store live when its parent disposed — released as a subtree root,
 * then disposed by its branch (`KeyedDisposal.Dispose`; the test's own
 * later `dispose()` is a no-op) — is collectable while the parent is still
 * referenced: the parent had released the store before disposing it, so it
 * must hold nothing of it — not in its registry, not in its tree value. `KeyedStoreGcTest` covers the live-parent case. The
 * wait is `awaitCollected` (`GcSupport.kt`).
 */
class DisposedParentGcTest {
    /** Keeps each parent reachable for the whole test, so a collected store is not explained by a collected parent. */
    private val parents = mutableListOf<Store<*>>()

    @Test
    fun aKeyedStoreDisposedAfterItsParentIsCollectable() {
        val parent = DgParent().also { parents += it }
        val ref = createDisposeParentThenStore(parent)
        assertTrue(awaitCollected(ref), "a keyed store disposed after its parent was not collected within the budget")
        assertNull(ref.get(), "a disposed parent must not keep a keyed store it once held reachable")
        assertTrue(parent.isDisposed)
    }

    @Test
    fun aKeyedStoreThatJoinedInsideTheActionDisposingTheParentIsCollectable() {
        val parent = DgParent().also { parents += it }
        val ref = createInsideAnActionThatDisposesTheParent(parent)
        assertTrue(awaitCollected(ref), "a keyed store joined inside the disposing action was not collected within the budget")
        assertNull(ref.get(), "a join still pending at dispose must not keep the store reachable")
        assertTrue(parent.isDisposed)
    }

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun createDisposeParentThenStore(parent: DgParent): WeakReference<DgThreadStore> {
        val store = parent.threads.create(1)
        store action { n mutate 1 }
        parent.dispose()
        store.dispose()
        return WeakReference(store)
    }

    /**
     * The value's node exists (it was read), so the join reached its edge
     * bookkeeping, not the pending list; the parent disposes inside its own
     * action, so its value's host is disposed after that action, not inline.
     */
    private fun createInsideAnActionThatDisposesTheParent(parent: DgParent): WeakReference<DgThreadStore> {
        parent.tree.value
        val threads = parent.threads
        var store: DgThreadStore? = null
        parent action {
            store = threads.create(2)
            parent.dispose()
        }
        val ref = WeakReference(checkNotNull(store))
        store!!.dispose()
        store = null
        return ref
    }
}
