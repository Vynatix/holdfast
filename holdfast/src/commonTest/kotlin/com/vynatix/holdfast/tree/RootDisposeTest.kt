@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachments
import com.vynatix.holdfast.observerCount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class DsLeafStore : Store<DsLeafStore>() {
    val n by state { 0 }
}

private class DsRoot : Root("ds") {
    val left = DsLeafStore()
    val leaf by branch(left)
}

/** `Root.dispose()` and the tree value: frozen, observers dropped, the host gone, the leaves untouched. */
class RootDisposeTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    @Test
    fun theLastTreeStaysReadableAndObserversAreDropped() {
        val root = DsRoot()
        root.left action { n mutate 3 }
        var fires = 0
        disposables += root.value effect { fires++ }
        val last = root.value.value
        assertEquals(1, root.value.observerCount)

        root.dispose()

        assertSame(last, root.value.value, "frozen at the last settled tree")
        assertEquals(3, last[root.left.n])
        assertEquals(0, root.value.observerCount, "the value's observers are dropped")
        assertTrue(root.internalHost().isDisposed)
        assertTrue(!root.left.isDisposed, "leaves are never disposed by the root")
        root.left action { n mutate 4 }
        assertEquals(1, fires, "no observer fires after dispose")
        assertSame(last, root.value.value, "and nothing recomputes")
        assertEquals(0, root.internalHost().internalAttachmentCount())
    }

    @Test
    fun aFirstReadOnADisposedRootThrows() {
        val root = DsRoot()
        root.dispose()
        val failure = assertFailsWith<IllegalStateException> { root.value.value }
        assertTrue("disposed" in failure.message!!)
        assertFailsWith<IllegalStateException> { root.value effect { } }
        assertFailsWith<IllegalStateException> { root.internalSettleNow() }
    }

    @Test
    fun disposeIsIdempotentAndDropsTheEdgesOnEveryLeaf() {
        val root = DsRoot()
        root.value.value
        val node = root.rootValue.node()
        assertEquals(listOf<Store<*>>(root.left), node.sourceStores)
        root.dispose()
        root.dispose()
        assertEquals(emptyList<Store<*>>(), node.sourceStores)
    }
}

private fun Store<*>.internalAttachmentCount(): Int = internalAttachments().size
