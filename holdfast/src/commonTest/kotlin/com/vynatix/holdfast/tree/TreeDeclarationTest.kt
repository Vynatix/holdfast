@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class DeclSettingsStore : Store<DeclSettingsStore>() {
    var initializerRuns = 0
    val theme by state {
        initializerRuns++
        "light"
    }
}

private class DeclProfileStore : Store<DeclProfileStore>() {
    val name by state { "" }
}

private class DeclSessionStore : Store<DeclSessionStore>() {
    val token by state { "" }
}

private class DeclThreadStore(
    val id: String,
) : Store<DeclThreadStore>() {
    val title by state { "thread $id" }
}

/** A store with children of its own: a group and a keyed branch, so nesting is a store declaring under itself. */
private class DeclOuterStore : Store<DeclOuterStore>() {
    val token by state { "" }
    val inner by stores { listOf(DeclProfileStore()) }
    val threads by stores<String, DeclThreadStore> { DeclThreadStore(it) }
}

private class DeclParent(
    val settingsStore: DeclSettingsStore = DeclSettingsStore(),
    val profileStore: DeclProfileStore = DeclProfileStore(),
    val outerStore: DeclOuterStore = DeclOuterStore(),
) : Store<DeclParent>() {
    var settingsRuns = 0
    val settings by stores {
        settingsRuns++
        listOf(settingsStore, profileStore)
    }
    val session by store { outerStore }
    val threads by stores<String, DeclThreadStore> { DeclThreadStore(it) }
}

/** Two stores whose classes share the simple name `SessionStore`, so their default leaf names collide. */
private class DeclDupA {
    class SessionStore : Store<SessionStore>()
}

private class DeclDupB {
    class SessionStore : Store<SessionStore>()
}

private class DeclCountingListener : LeafMembershipListener() {
    val attached = ArrayList<LeafNode>()
    val detached = ArrayList<LeafNode>()

    override fun onAttached(leaf: LeafNode) {
        attached += leaf
    }

    override fun onDetached(leaf: LeafNode) {
        detached += leaf
    }
}

/** Declares `stolen` through a declaration another store's `store { }` made. */
private class DeclThief(
    declaration: StoreDeclaration<DeclProfileStore>,
) : Store<DeclThief>() {
    val stolen by declaration
}

private class DeclFirstOwner(
    shared: DeclSessionStore,
) : Store<DeclFirstOwner>() {
    val a by store { shared }
}

private class DeclSecondOwner(
    shared: DeclSessionStore,
) : Store<DeclSecondOwner>() {
    val b by store { shared }
}

/**
 * Declaring a store's children next to its states: registration at
 * declaration (no child code runs), naming, one parent per store, and the
 * order the tree lists stores in.
 */
class TreeDeclarationTest {
    @Test
    fun childrenRegisterAtDeclarationAndAreNamedByTheirProperties() {
        val parent = DeclParent()
        // Declared, not materialized: only the keyed branch is a node before a read.
        assertEquals(
            listOf<StoreNode>(parent.threads),
            parent.treeAttachment().registry.liveChildNodes(),
        )
        assertEquals(0, parent.settingsRuns, "declaring a group runs none of its code")

        val tree = parent.tree
        assertEquals(listOf("settings", "session", "threads"), tree.children.map { it.name })
        assertEquals(1, parent.settingsRuns)
        assertEquals(NameOrigin.Property, parent.settings.nameOrigin)
        assertSame(tree.node, parent.settings.parent)
        assertSame(tree.node, parent.threads.parent)
        assertEquals(listOf(parent.settingsStore, parent.profileStore), parent.settings.stores)
        val session = assertNotNull(tree.nodeOf(parent.outerStore))
        assertEquals("session", session.name)
        assertEquals(NameOrigin.Property, session.nameOrigin)
        assertSame(tree.node, session.parent)
    }

    @Test
    fun nestingIsAChildStoreDeclaringItsOwnChildren() {
        val parent = DeclParent()
        val outer = parent.session
        val outerNode = assertNotNull(parent.tree.nodeOf(outer))
        assertSame(outerNode, outer.inner.parent)
        assertSame(outerNode, outer.threads.parent)
        assertTrue(outer.inner.isUnder(outerNode))
        assertTrue(outer.inner.isUnder(parent.tree.node))
        assertFalse(outerNode.isUnder(outer.inner))
        assertSame(outerNode, outer.tree.node, "the child's own tree is rooted at the node it has under its parent")
        assertSame(parent.tree.node, outer.tree.parent)
    }

    @Test
    fun aStoreDeclaredUnderTwoGroupsOfOneParentFailsAtTheSecondReadNamingBoth() {
        val shared = DeclSessionStore()

        class Twice : Store<Twice>() {
            val first by stores { listOf(shared) }
            val second by stores { listOf(shared) }
        }
        val twice = Twice()
        assertEquals(listOf<Store<*>>(shared), twice.first.stores)
        val error = assertFailsWith<IllegalStateException> { twice.second }
        val message = assertNotNull(error.message)
        assertTrue("already belongs to Twice/first" in message, message)
        assertTrue("under Twice/second" in message, message)
        assertTrue("a store has one parent" in message, message)
        // The first stays attached, and the refusal is retried (and refused) on the next read — and by every
        // tree operation, which materializes the declared children first.
        assertSame(twice.first, shared.tree.parent)
        assertFailsWith<IllegalStateException> { twice.second }
        assertFailsWith<IllegalStateException> { twice.tree.children }
        assertSame(twice.first, shared.tree.parent)
    }

    @Test
    fun aStoreDeclaredUnderTwoParentsFailsAtTheSecondReadNamingBothParents() {
        val shared = DeclSessionStore()
        val first = DeclFirstOwner(shared)
        val second = DeclSecondOwner(shared)
        assertSame(shared, first.a)
        val error = assertFailsWith<IllegalStateException> { second.b }
        val message = assertNotNull(error.message)
        assertTrue("DeclSessionStore already belongs to DeclFirstOwner/a" in message, message)
        assertTrue("under DeclSecondOwner/b" in message, message)
        assertSame(first.tree.node, shared.tree.parent, "the first parent keeps it")
        assertSame(shared, first.tree.stores().last())
        assertFailsWith<IllegalStateException>("the refused child makes the second parent's tree refuse too") {
            second.tree.stores()
        }
    }

    @Test
    fun aFailedGroupLinksNoneOfItsStoresAndIsRetriedOnceTheConflictIsGone() {
        val taken = DeclSessionStore()
        val owner = DeclFirstOwner(taken)
        owner.a
        val fresh = DeclProfileStore()

        class Failing : Store<Failing>() {
            val b by stores { listOf(fresh, taken) }
        }
        val failing = Failing()
        assertFailsWith<IllegalStateException> { failing.b }
        assertNull(
            fresh.internalAttachment(treeMembershipKey)?.parentEdge?.value,
            "the store linked before the failure must be unlinked again",
        )
        assertNull(fresh.tree.parent)
        assertEquals("DeclProfile", fresh.tree.node.name)
        assertSame(owner.tree.node, taken.tree.parent, "the other parent's child is untouched")

        // Once the conflict is gone (the other parent disposed and released it), the same declaration attaches.
        owner.dispose()
        assertEquals(listOf<Store<*>>(fresh, taken), failing.b.stores)
        assertSame(failing.b, fresh.tree.parent)
        assertSame(failing.b, taken.tree.parent)
    }

    @Test
    fun aStoreDeclaredNowhereIsNotInTheTreeAndTwoParentsNeverSeeEachOthersChildren() {
        val parent = DeclParent()
        val other = DeclParent()
        val loose = DeclProfileStore()
        val tree = parent.tree
        assertNull(tree.nodeOf(loose))
        assertNull(tree.nodeOf(other.settingsStore))
        assertNotNull(tree.nodeOf(parent.settingsStore))
        assertTrue(other.settingsStore !in tree.stores())
        assertTrue(parent.settingsStore !in other.tree.stores())
    }

    @Test
    fun aDisposedStoreFailsWhenTheChildMaterializesNotAtDeclaration() {
        val dead = DeclSessionStore().apply { dispose() }

        class Holder : Store<Holder>() {
            val one by store { dead }
            val group by stores { listOf(dead) }
        }
        val holder = Holder()
        val single = assertFailsWith<IllegalStateException> { holder.one }
        assertTrue("Holder.one: the child lambda returned a disposed store" in single.message!!, single.message)
        val grouped = assertFailsWith<IllegalArgumentException> { holder.group }
        assertTrue("Holder.group lists a disposed DeclSessionStore" in grouped.message!!, grouped.message)
    }

    @Test
    fun aGroupLeafNameDefaultsToClassNameMinusStoreAndNamesPinsItByClass() {
        val parent = DeclParent()
        assertEquals("DeclSettings", parent.settings.leafName(parent.settingsStore))
        assertEquals(NameOrigin.ClassName, parent.tree.nodeOf(parent.settingsStore)!!.nameOrigin)

        val pinnedStore = DeclSettingsStore()

        class Pinning : Store<Pinning>() {
            val settings by stores(names = mapOf(DeclSettingsStore::class to "prefs")) { listOf(pinnedStore) }
        }
        val pinning = Pinning()
        assertEquals("prefs", pinning.settings.leafName(pinnedStore))
        assertEquals(NameOrigin.Pinned, pinning.tree.nodeOf(pinnedStore)!!.nameOrigin)
        val unlisted = assertFailsWith<IllegalArgumentException> { pinning.settings.leafName(DeclProfileStore()) }
        assertTrue("is not listed under group 'settings' of 'Pinning'" in unlisted.message!!, unlisted.message)
    }

    @Test
    fun aPinThatTheGroupDoesNotListIsRefused() {
        class Pinning : Store<Pinning>() {
            val settings by stores(names = mapOf(DeclProfileStore::class to "p")) { listOf(DeclSettingsStore()) }
        }
        val error = assertFailsWith<IllegalArgumentException> { Pinning().settings }
        assertTrue("pins DeclProfileStore, which the group does not list" in error.message!!, error.message)
    }

    @Test
    fun namedPinsAStoreChildsName() {
        class Pinning : Store<Pinning>() {
            val settings by store(named = "prefs") { DeclSettingsStore() }
        }
        val parent = Pinning()
        val node = assertNotNull(parent.tree.nodeOf(parent.settings))
        assertEquals("prefs", node.name)
        assertEquals(NameOrigin.Pinned, node.nameOrigin)
        assertEquals(listOf("prefs"), parent.tree.children.map { it.name })
        val empty = assertFailsWith<IllegalArgumentException> { parent.store(named = "") { DeclSettingsStore() } }
        assertTrue("must not be empty" in empty.message!!, empty.message)
    }

    @Test
    fun aParentlessStoreIsNamedByItsClassMinusStore() {
        val parent = DeclParent()
        assertEquals("DeclParent", parent.tree.node.name)
        assertEquals(NameOrigin.ClassName, parent.tree.node.nameOrigin)
        assertNull(parent.tree.parent)
        assertEquals("LeafNode(DeclParent)", parent.tree.node.toString())
        val loose = DeclSettingsStore()
        assertEquals("DeclSettings", loose.tree.node.name)
        assertEquals("LeafNode(DeclParent/session)", parent.tree.nodeOf(parent.session).toString())
    }

    @Test
    fun duplicateLeafNamesInOneGroupFailUnlessPinnedApart() {
        class SameClass : Store<SameClass>() {
            val two by stores { listOf(DeclSessionStore(), DeclSessionStore()) }
        }
        val sameClass = assertFailsWith<IllegalArgumentException> { SameClass().two }
        assertTrue("two leaves would be named 'DeclSession'" in sameClass.message!!, sameClass.message)
        // A pin names a class, so it cannot tell two instances apart: the message says so.
        assertTrue("as its own store { } child" in sameClass.message!!, sameClass.message)
        assertTrue("pin one" !in sameClass.message!!, sameClass.message)

        class Collide : Store<Collide>() {
            val two by stores { listOf(DeclDupA.SessionStore(), DeclDupB.SessionStore()) }
        }
        val collision = assertFailsWith<IllegalArgumentException> { Collide().two }
        assertTrue("has two leaves named 'Session'" in collision.message!!, collision.message)

        class Apart : Store<Apart>() {
            val two by stores(names = mapOf(DeclDupB.SessionStore::class to "other")) {
                listOf(DeclDupA.SessionStore(), DeclDupB.SessionStore())
            }
        }
        assertEquals(listOf("Session", "other"), Apart().two.leaves.map { it.name })
    }

    @Test
    fun aStoreWithNoSimpleNameFailsInAGroupUnlessPinned() {
        assertNull(defaultLeafName(null))
        assertNull(defaultLeafName(""))
        assertEquals("Settings", defaultLeafName("SettingsStore"))
        assertEquals("Store", defaultLeafName("Store"))
        assertEquals("Widget", defaultLeafName("Widget"))

        val anonymous = object : NodeStore() {}

        class Unpinned : Store<Unpinned>() {
            val group by stores { listOf(anonymous) }
        }
        val error = assertFailsWith<IllegalStateException> { Unpinned().group }
        assertTrue("has no simple name" in error.message!!, error.message)

        class Pinned : Store<Pinned>() {
            val group by stores(names = mapOf(anonymous::class to "anon")) { listOf(anonymous) }
        }
        assertEquals(listOf("anon"), Pinned().group.leaves.map { it.name })
    }

    @Test
    fun storesAreListedReceiverFirstThenInDeclarationOrderThenKeyedCreationOrder() {
        val parent = DeclParent()
        val outer = parent.session
        val t2 = outer.threads.create("2")
        val t1 = outer.threads.create("1")
        val tree = parent.tree
        val inner = outer.inner.stores.single()
        assertEquals(
            listOf(parent, parent.settingsStore, parent.profileStore, outer, inner, t2, t1),
            tree.stores(),
        )
        val outerNode = assertNotNull(tree.nodeOf(outer))
        assertEquals(listOf(outer, inner, t2, t1), tree.stores(outerNode))
        assertEquals(listOf<Store<*>>(t2, t1), tree.stores(outer.threads))
        assertEquals(listOf<Store<*>>(t1), tree.stores(tree.nodeOf(t1)!!))
    }

    @Test
    fun materializationRunsNoStateInitializerAndFiresOnAttachedOncePerStore() {
        val listener = DeclCountingListener()
        val settings = DeclSettingsStore()

        class Listened : Store<Listened>() {
            init {
                internalAddMembershipListener(listener)
            }

            val a by stores { listOf(settings, DeclProfileStore()) }
        }
        val parent = Listened()
        assertTrue(listener.attached.isEmpty(), "declaring a group announces nothing")
        parent.a
        assertEquals(0, settings.initializerRuns, "materializing a group must not materialize a child's states")
        assertEquals(listOf("DeclSettings", "DeclProfile"), listener.attached.map { it.name })
        assertTrue(listener.detached.isEmpty())
        assertSame(parent.tree.nodeOf(settings), listener.attached[0])
        parent.a
        parent.tree.children
        assertEquals(2, listener.attached.size, "a materialized child is never announced again")
    }

    @Test
    fun siblingChildNamesMustBeUnique() {
        class Clash : Store<Clash>() {
            val a by store { DeclSessionStore() }
            val b by store(named = "a") { DeclProfileStore() }
        }
        val error = assertFailsWith<IllegalStateException> { Clash() }
        assertTrue("Clash already has a child named 'a'" in error.message!!, error.message)
    }

    @Test
    fun aDeclarationMadeThroughAnotherStoreIsRefused() {
        val first = DeclParent()
        val declaration = first.store { DeclProfileStore() }
        val error = assertFailsWith<IllegalArgumentException> { DeclThief(declaration) }
        val message = assertNotNull(error.message)
        assertTrue("'stolen' on DeclThief was declared through DeclParent.store/stores" in message, message)
    }
}
