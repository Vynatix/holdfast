@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class KoThreadStore(
    val id: String,
) : Store<KoThreadStore>() {
    val title by state { "thread $id" }
}

private class KoLeafStore : Store<KoLeafStore>() {
    val n by state { 0 }
}

private class KoParent : Store<KoParent>() {
    val n by state { 0 }
    val owned by keyed<String, KoThreadStore> { KoThreadStore(it) }
    val kept by keyed<String, KoThreadStore>(onParentDispose = KeyedDisposal.Release) { KoThreadStore(it) }
    val child by store { KoLeafStore() }
    val members by group { listOf(KoLeafStore()) }
}

/** A keyed branch owns the stores its factory built (decision 5). */
class KeyedOwnershipTest {
    @Test
    fun theDefaultIsDisposeAndTheOptOutIsRelease() {
        val parent = KoParent()
        assertEquals(KeyedDisposal.Dispose, parent.owned.onParentDispose)
        assertEquals(KeyedDisposal.Release, parent.kept.onParentDispose)
    }

    @Test
    fun disposingTheParentDisposesEveryOwnedKeyedStore() {
        val parent = KoParent()
        val a = parent.owned.create("a")
        val b = parent.owned.create("b")
        val aNode = a.tree.node
        parent.dispose()
        assertTrue(a.isDisposed, "a keyed store its factory built is disposed with the parent")
        assertTrue(b.isDisposed)
        assertNull(aNode.store, "and gone from the tree")
        assertNull(aNode.parent, "released as a subtree root first")
    }

    @Test
    fun releaseKeepsKeyedStoresAliveAsSubtreeRoots() {
        val parent = KoParent()
        val kept = parent.kept.create("k")
        parent.dispose()
        assertFalse(kept.isDisposed, "a Release branch only releases")
        assertNull(kept.tree.parent, "a subtree root")
        assertEquals("KoThread", kept.tree.node.name, "class-named again")
        assertIs<TransactionResult.Success<*>>(kept action { title mutate "x" })
        kept.dispose()
    }

    @Test
    fun storeAndGroupChildrenAreStillReleasedNotDisposed() {
        val parent = KoParent()
        val child = parent.child
        val member = parent.members.stores.single()
        parent.owned.create("a")
        parent.dispose()
        assertFalse(child.isDisposed, "a store { } child is released")
        assertFalse(member.isDisposed, "a group member is released")
        assertNull(child.tree.parent)
        assertNull(member.tree.parent)
        child.dispose()
        member.dispose()
    }

    @Test
    fun disposeOfAKeyDisposesItsLiveStoreAndAnswersWhetherThereWasOne() {
        val parent = KoParent()
        val a = parent.owned.create("a")
        val b = parent.owned.create("b")
        assertTrue(parent.owned.dispose("a"))
        assertTrue(a.isDisposed)
        assertNull(parent.owned["a"], "it left the branch")
        assertFalse(parent.owned.dispose("a"), "no live store any more")
        assertFalse(parent.owned.dispose("never"), "never created")
        assertSame(b, parent.owned["b"], "the other key is untouched")
        assertFalse(b.isDisposed)
        parent.dispose()
    }

    @Test
    fun disposeAllDisposesEveryLiveStoreAndTheBranchKeepsWorking() {
        val parent = KoParent()
        val stores = listOf("a", "b", "c").map { parent.owned.create(it) }
        parent.owned.disposeAll()
        assertTrue(stores.all { it.isDisposed })
        assertEquals(emptyMap(), parent.owned.entries())
        parent.owned.disposeAll()
        val again = parent.owned.create("a")
        assertFalse(again.isDisposed, "a key can be created again")
        assertEquals(setOf("a"), parent.owned.entries().keys)
        parent.dispose()
        assertTrue(again.isDisposed)
    }

    @Test
    fun aParentDisposedInsideItsOwnActionDisposesItsKeyedStoresWhenTheActionSettles() {
        val parent = KoParent()
        val a = parent.owned.create("a")
        var disposedInside: Boolean? = null
        val result =
            parent action {
                n mutate 1
                parent.dispose()
                disposedInside = a.isDisposed
            }
        assertEquals(false, disposedInside, "never inside the action, holding its lock")
        assertTrue(a.isDisposed, "once the action settled")
        // The action's own result: the store disposed under its body.
        assertTrue(result is TransactionResult.Success<*> || result is TransactionResult.Error)
    }

    @Test
    fun aDisposedKeyedStoreLeavesNoEntryAndTheBranchRefusesAfterTheParentsDispose() {
        val parent = KoParent()
        parent.owned.create("a")
        parent.dispose()
        assertFailsWith<IllegalStateException> { parent.owned.dispose("a") }
        assertFailsWith<IllegalStateException> { parent.owned.disposeAll() }
    }
}
