@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private interface CoDraft {
    val text: State<String>

    fun edit(value: String)
}

private class CoLeafStore : Store<CoLeafStore>() {
    val n by state { 0 }
}

private class CoThreadStore(
    val id: String,
) : Store<CoThreadStore>() {
    val n by state { 0 }
}

private class CoParent(
    val shared: CoLeafStore = CoLeafStore(),
    val sharedMember: CoLeafStore = CoLeafStore(),
) : Store<CoParent>() {
    val n by state { 0 }
    val draft by store<CoDraft> {
        object : NodeStore(), CoDraft {
            override val text by state { "" }

            override fun edit(value: String) {
                text mutate value
            }
        }
    }
    val built by store { CoLeafStore() }
    val adopted by store { shared }
    val kept by store(onParentDispose = KeyedDisposal.Release) { CoLeafStore() }
    val members by group { listOf(CoLeafStore() named "fresh", sharedMember named "old") }
    val keptMembers by group(onParentDispose = KeyedDisposal.Release) { listOf(CoLeafStore()) }
    val threads by keyed<String, CoThreadStore> { CoThreadStore(it) }
    val keptThreads by keyed<String, CoThreadStore>(onParentDispose = KeyedDisposal.Release) { CoThreadStore(it) }
}

/** A `store { }`/`group { }` declaration owns what its winning lambda run built; it never owns a store it was handed. */
class ChildOwnershipTest {
    @Test
    fun anInlineChildTheLambdaBuiltIsDisposedWithItsParent() {
        val app = CoParent()
        val draft = app.draft
        val built = app.built
        draft.edit("w")
        app.dispose()
        assertTrue((draft as NodeStore).isDisposed, "the inline child the lambda built is disposed")
        assertTrue(built.isDisposed, "so is a plain store { } child the lambda constructed")
        assertFailsWith<IllegalStateException>("a held face no longer writes") { draft.edit("after") }
    }

    @Test
    fun aStoreThatExistedBeforeTheLambdaRanIsOnlyReleased() {
        val app = CoParent()
        val adopted = app.adopted
        val kept = app.kept
        app.dispose()
        assertFalse(adopted.isDisposed, "a store handed to store { } is released, never disposed")
        assertNull(adopted.tree.parent, "a subtree root")
        assertEquals("CoLeaf", adopted.tree.node.name)
        assertIs<TransactionResult.Success<*>>(adopted action { n mutate 1 })
        assertFalse(kept.isDisposed, "onParentDispose = Release keeps a built child alive")
        assertNull(kept.tree.parent)
        adopted.dispose()
        kept.dispose()
    }

    @Test
    fun aGroupDisposesTheMembersItsLambdaBuiltAndReleasesTheRest() {
        val app = CoParent()
        val (fresh, old) = app.members.stores
        val keptMember = app.keptMembers.stores.single()
        app.dispose()
        assertTrue(fresh.isDisposed, "a member the group lambda built is disposed")
        assertFalse(old.isDisposed, "a member that existed before is released")
        assertNull(old.tree.parent)
        assertFalse(keptMember.isDisposed, "a Release group only releases")
        old.dispose()
        keptMember.dispose()
    }

    @Test
    fun keyedOwnershipIsUnchanged() {
        val app = CoParent()
        val owned = app.threads.create("a")
        val kept = app.keptThreads.create("b")
        app.dispose()
        assertTrue(owned.isDisposed)
        assertFalse(kept.isDisposed)
        assertNull(kept.tree.parent)
        kept.dispose()
    }

    @Test
    fun insideTheParentsOwnActionTheOwnedChildrenDisposeWhenItSettles() {
        val app = CoParent()
        val draft = app.draft as NodeStore
        val fresh = app.members.stores.first()
        val adopted = app.adopted
        var disposedInside: Boolean? = null
        app action {
            n mutate 1
            app.dispose()
            disposedInside = draft.isDisposed || fresh.isDisposed
        }
        assertEquals(false, disposedInside, "never inside the action, holding its lock")
        assertTrue(draft.isDisposed, "once the action settled")
        assertTrue(fresh.isDisposed)
        assertFalse(adopted.isDisposed)
        adopted.dispose()
    }
}
