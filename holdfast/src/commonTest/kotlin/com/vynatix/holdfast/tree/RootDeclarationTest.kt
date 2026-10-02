@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    root: DeclRoot,
) : Store<DeclThreadStore>(root.threads.at(id)) {
    val title by state { "thread $id" }
}

private class DeclRoot(
    val settingsStore: DeclSettingsStore = DeclSettingsStore(),
    val profileStore: DeclProfileStore = DeclProfileStore(),
    val sessionStore: DeclSessionStore = DeclSessionStore(),
    name: String? = null,
) : Root(name) {
    val settings by branch(settingsStore, profileStore)
    val session by branch(sessionStore)
    val threads by keyed<String, DeclThreadStore>(under = session)
}

private class CountingListener : LeafMembershipListener() {
    val attached = ArrayList<LeafNode>()
    val detached = ArrayList<LeafNode>()

    override fun onAttached(leaf: LeafNode) {
        attached += leaf
    }

    override fun onDetached(leaf: LeafNode) {
        detached += leaf
    }
}

/** Declaration-time behaviour of a root: T1 (registration at declaration), U7 (naming), T7.3 (one root per store). */
class RootDeclarationTest {
    @Test
    fun branchesRegisterAtDeclarationAndAreNamedByTheirProperties() {
        val root = DeclRoot()
        assertEquals(listOf("settings", "session", "threads"), root.nodes.filter { it !is Root && it !is LeafNode }.map { it.name })
        assertEquals(NameOrigin.Property, root.settings.nameOrigin)
        assertSame(root, root.settings.parent)
        assertSame(root, root.settings.root)
        assertEquals(listOf(root.settingsStore, root.profileStore), root.settings.stores)
    }

    @Test
    fun underNestsAKeyedBranchAndABranchBeneathABranch() {
        class Nested : Root() {
            val outer by branch(DeclSessionStore())
            val inner by branch(DeclProfileStore(), under = outer)
            val keyed by keyed<String, DeclThreadStore>(under = outer)
        }
        val root = Nested()
        assertSame(root.outer, root.inner.parent)
        assertSame(root.outer, root.keyed.parent)
        assertTrue(root.inner.isUnder(root.outer))
        assertTrue(root.inner.isUnder(root))
        assertTrue(!root.outer.isUnder(root.inner))
    }

    @Test
    fun aStoreListedUnderTwoBranchesFailsFastNamingBoth() {
        val shared = DeclSessionStore()
        val error =
            assertFailsWith<IllegalStateException> {
                object : Root("twice") {
                    val first by branch(shared)
                    val second by branch(shared)
                }
            }
        assertTrue("'first'" in error.message!! && "'second'" in error.message!!, error.message)
        assertTrue("twice" in error.message!!, error.message)
    }

    @Test
    fun aStoreListedInTwoRootsFailsFastNamingBothRoots() {
        val shared = DeclSessionStore()

        class First : Root("first-root") {
            val a by branch(shared)
        }
        First()
        val error =
            assertFailsWith<IllegalStateException> {
                object : Root("second-root") {
                    val b by branch(shared)
                }
            }
        assertTrue("first-root" in error.message!! && "second-root" in error.message!!, error.message)
    }

    @Test
    fun aFailedDeclarationDetachesWhatTheRootAlreadyAttached() {
        val taken = DeclSessionStore()

        class Owner : Root() {
            val a by branch(taken)
        }
        Owner()
        val fresh = DeclProfileStore()
        assertFailsWith<IllegalStateException> {
            object : Root("failing") {
                val b by branch(fresh, taken)
            }
        }
        assertNull(fresh.internalAttachment(treeMembershipKey), "the store attached before the failure must be detached again")
        assertNotNull(taken.internalAttachment(treeMembershipKey), "the other root's membership is untouched")
    }

    @Test
    fun aStoreListedNowhereIsNotInTheTreeAndTwoRootsNeverSeeEachOthersLeaves() {
        val root = DeclRoot()
        val other = DeclRoot()
        val loose = DeclProfileStore()
        assertNull(root.nodeOf(loose))
        assertNull(root.nodeOf(other.settingsStore))
        assertNotNull(root.nodeOf(root.settingsStore))
        assertTrue(other.settingsStore !in root.children(root))
    }

    @Test
    fun aDisposedStoreFailsAtDeclaration() {
        val dead = DeclSessionStore().apply { dispose() }
        val error =
            assertFailsWith<IllegalArgumentException> {
                object : Root() {
                    val a by branch(dead)
                }
            }
        assertTrue("disposed" in error.message!!, error.message)
    }

    @Test
    fun leafNameDefaultsToClassNameMinusStoreAndNamedPinsIt() {
        val root = DeclRoot()
        assertEquals("DeclSettings", root.settings.leafName(root.settingsStore))
        assertEquals(NameOrigin.ClassName, root.nodeOf(root.settingsStore)!!.nameOrigin)

        val pinnedStore = DeclSettingsStore()

        class Pinning : Root() {
            val settings by branch(pinnedStore).named(pinnedStore, "prefs")
        }
        val pinning = Pinning()
        assertEquals("prefs", pinning.settings.leafName(pinnedStore))
        assertEquals(NameOrigin.Pinned, pinning.nodeOf(pinnedStore)!!.nameOrigin)
    }

    @Test
    fun namedWithoutAStorePinsTheBranchName() {
        class Pinning : Root() {
            val settings by branch(DeclSettingsStore()).named("prefs")
        }
        val root = Pinning()
        assertEquals("prefs", root.settings.name)
        assertEquals(NameOrigin.Pinned, root.settings.nameOrigin)
    }

    @Test
    fun rootNameComesFromTheConstructorArgumentElseTheClassName() {
        assertEquals("DeclRoot", DeclRoot().name)
        assertEquals(NameOrigin.ClassName, DeclRoot().nameOrigin)
        val named = DeclRoot(name = "app")
        assertEquals("app", named.name)
        assertEquals(NameOrigin.Pinned, named.nameOrigin)
        assertEquals("app", named.toString())
    }

    @Test
    fun duplicateLeafNamesInOneBranchFailUnlessPinned() {
        val error =
            assertFailsWith<IllegalStateException> {
                object : Root() {
                    val two by branch(DeclSessionStore(), DeclSessionStore())
                }
            }
        assertTrue("DeclSession" in error.message!!, error.message)

        val second = DeclSessionStore()

        class Pinned : Root() {
            val two by branch(DeclSessionStore(), second).named(second, "other")
        }
        assertEquals(listOf("DeclSession", "other"), Pinned().two.leaves.map { it.name })
    }

    @Test
    fun aNullSimpleNameFailsUnlessPinned() {
        assertNull(defaultLeafName(null))
        assertNull(defaultLeafName(""))
        assertEquals("Settings", defaultLeafName("SettingsStore"))
        assertEquals("Store", defaultLeafName("Store"))
        assertEquals("Widget", defaultLeafName("Widget"))
    }

    @Test
    fun childrenOrderIsListingThenNestedThenKeyedCreation() {
        val root = DeclRoot()
        val t2 = root.threads.create("2") { DeclThreadStore(it, root) }
        val t1 = root.threads.create("1") { DeclThreadStore(it, root) }
        assertEquals(listOf(root.settingsStore, root.profileStore, root.sessionStore, t2, t1), root.children(root))
        assertEquals(listOf(root.sessionStore, t2, t1), root.children(root.session))
        assertEquals(listOf(t2, t1), root.children(root.threads))
        assertEquals(listOf<Store<*>>(t1), root.children(root.nodeOf(t1)!!))
    }

    @Test
    fun branchDeclarationRunsNoLeafInitializerAndFiresOnAttachedOncePerStore() {
        val listener = CountingListener()
        val settings = DeclSettingsStore()

        class Listened : Root() {
            init {
                internalAddMembershipListener(listener)
            }

            val a by branch(settings, DeclProfileStore())
        }
        val root = Listened()
        assertEquals(0, settings.initializerRuns, "declaring a branch must not materialize a leaf's states")
        assertEquals(listOf("DeclSettings", "DeclProfile"), listener.attached.map { it.name })
        assertTrue(listener.detached.isEmpty())
        assertSame(root.nodeOf(settings), listener.attached[0])
    }

    @Test
    fun siblingBranchNamesMustBeUnique() {
        val error =
            assertFailsWith<IllegalStateException> {
                object : Root() {
                    val a by branch(DeclSessionStore())
                    val b by branch(DeclProfileStore()).named("a")
                }
            }
        assertTrue("already has a child named 'a'" in error.message!!, error.message)
    }

    @Test
    fun aDeclarationCreatedByAnotherRootIsRefused() {
        val first = DeclRoot()
        val declaration = BranchDeclaration(first, listOf(DeclProfileStore()), under = null)
        val error =
            assertFailsWith<IllegalArgumentException> {
                object : Root("other") {
                    val stolen by declaration
                }
            }
        assertTrue("other" in error.message!! && "DeclRoot" in error.message!!, error.message)
    }
}
