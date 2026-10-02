@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.internalAttachment
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class KmSessionStore : Store<KmSessionStore>() {
    val token by state { "" }
}

private open class KmThreadStore(
    val id: String,
) : Store<KmThreadStore>() {
    val title by state { "thread $id" }

    init {
        // Store code running inside the factory is ordinary store code.
        action { title mutate "constructed $id" }
    }
}

/** No constructor-time action, so it can be created inside a Strict frame. */
private class KmQuietStore(
    val id: String,
) : Store<KmQuietStore>() {
    val title by state { "quiet $id" }
}

private class KmSubThreadStore(
    id: String,
) : KmThreadStore(id)

private sealed class KmEvent {
    data object Pinged : KmEvent()
}

private class KmEventfulStore(
    val id: String,
) : EventfulStore<KmEventfulStore, KmEvent>() {
    val n by state { 0 }
}

private class KmThrowingStore(
    id: String,
) : Store<KmThrowingStore>() {
    init {
        error("constructor of $id refused")
    }
}

/** A keyed store that declares keyed children of its own. */
private class KmFolderStore(
    val id: String,
) : Store<KmFolderStore>() {
    val files by stores<String, KmQuietStore> { KmQuietStore(it) }
}

/**
 * Each keyed branch's factory is declared once; a test that needs a key to
 * build something else installs a per-key hook ([threadHooks]) the declared
 * factory consults, and [threadRuns] counts every run of that factory.
 */
private class KmParent : Store<KmParent>() {
    var threadRuns = 0
    val threadHooks = HashMap<String, (String) -> KmThreadStore>()
    var throwingRuns = 0

    val session by store { KmSessionStore() }
    val threads by stores<String, KmThreadStore> { id ->
        threadRuns++
        threadHooks[id]?.invoke(id) ?: KmThreadStore(id)
    }
    val eventful by stores<String, KmEventfulStore> { KmEventfulStore(it) }
    val throwing by stores<String, KmThrowingStore> { id ->
        throwingRuns++
        KmThrowingStore(id)
    }
    val quiet by stores<String, KmQuietStore> { KmQuietStore(it) }
    val folders by stores<String, KmFolderStore> { KmFolderStore(it) }

    /**
     * Declared as `stores<String, KmSubThreadStore>` while its factory builds
     * plain [KmThreadStore]s: only an unchecked cast of the class literal
     * gets there, which is what the factory's runtime class check is for.
     */
    @Suppress("UNCHECKED_CAST")
    val wrongClass by keyedDeclaration(
        String::class,
        KmSubThreadStore::class as KClass<KmThreadStore>,
        null,
    ) { KmThreadStore(it) }
}

private class KmRecordingListener : LeafMembershipListener() {
    val events = ArrayList<String>()

    override fun onAttached(leaf: LeafNode) {
        events += "attached:${leaf.name}"
    }

    override fun onDetached(leaf: LeafNode) {
        events += "detached:${leaf.name}"
    }
}

/** T3: keyed stores are made by the branch's declared factory, join once it returned, and leave on dispose. */
class KeyedMembershipTest {
    @Test
    fun createReturnsTheLiveStoreAndLookupIsNullAfterDispose() {
        val parent = KmParent()
        val t = parent.threads.create("a")
        val found: KmThreadStore? = parent.threads["a"]
        assertSame(t, found)
        assertEquals("constructed a", t.title.value)
        assertEquals(mapOf("a" to t), parent.threads.entries)
        val leaf = assertNotNull(parent.tree.nodeOf(t))
        assertSame(parent.threads, leaf.parent)
        assertEquals("a", leaf.key)
        assertSame(leaf, t.tree.node)

        t.dispose()
        assertNull(parent.threads["a"])
        assertNull(parent.tree.nodeOf(t))
        assertTrue(parent.threads.entries.isEmpty())
        assertEquals("a", leaf.name, "a disposed store's node keeps the place it had")
    }

    @Test
    fun aFactoryReturningAStoreThatAlreadyHasAParentFailsAndLeavesNoEntry() {
        val parent = KmParent()
        val other = KmParent()
        val foreign = other.threads.create("f")
        parent.threadHooks["f"] = { foreign }
        val error = assertFailsWith<IllegalStateException> { parent.threads.create("f") }
        val message = assertNotNull(error.message)
        assertTrue("KmThreadStore already belongs to KmParent/threads" in message, message)
        assertTrue("a store has one parent" in message, message)
        assertNull(parent.threads["f"])
        assertTrue(parent.threads.entries.isEmpty())
        assertSame(foreign, other.threads["f"])
        assertSame(other.threads, foreign.tree.parent, "the store stays where it was")
    }

    @Test
    fun aFactoryReturningADisposedStoreFailsAndLeavesNoEntry() {
        val parent = KmParent()
        parent.threadHooks["dead"] = { KmThreadStore(it).apply { dispose() } }
        val error = assertFailsWith<IllegalStateException> { parent.threads.create("dead") }
        assertTrue("returned a disposed store" in error.message!!, error.message)
        assertNull(parent.threads["dead"])
    }

    @Test
    fun aThrowingConstructorLeavesNoEntryAndARetryRunsTheFactoryAgain() {
        val parent = KmParent()
        val error = assertFailsWith<IllegalStateException> { parent.throwing.create("t") }
        assertEquals("constructor of t refused", error.message)
        assertNull(parent.throwing["t"])
        assertTrue(parent.throwing.entries.isEmpty())
        assertEquals(1, parent.throwingRuns)

        // The key is free again: a retry runs the factory once more.
        assertFailsWith<IllegalStateException> { parent.throwing.create("t") }
        assertEquals(2, parent.throwingRuns)
        // And a well-behaved store on another branch is unaffected.
        val t = parent.threads.create("fine")
        assertSame(t, parent.threads["fine"])
    }

    @Test
    fun aStoreAFailingFactoryBuiltIsLeftAloneAndTheTreeHoldsNoReferenceToIt() {
        val parent = KmParent()
        var leaked: KmThreadStore? = null
        parent.threadHooks["leak"] = { id ->
            leaked = KmThreadStore(id)
            error("factory failed after constructing")
        }
        assertFailsWith<IllegalStateException> { parent.threads.create("leak") }
        val built = assertNotNull(leaked)
        assertFalse(built.isDisposed, "the tree disposes nothing: the store a failing factory built is the factory's own")
        assertNull(built.internalAttachment(treeMembershipKey), "the tree never reached the store")
        assertNull(parent.threads["leak"])
        assertTrue(parent.tree.stores().none { it === built })
    }

    @Test
    fun duplicateKeysFailAtCreate() {
        val parent = KmParent()
        parent.threads.create("d")
        val error = assertFailsWith<IllegalStateException> { parent.threads.create("d") }
        assertTrue("already exists" in error.message!!, error.message)
        assertTrue("getOrCreate" in error.message!!, error.message)
        assertEquals(1, parent.threadRuns, "a refused create runs no factory")
    }

    @Test
    fun disposeFromInsideTheStoresOwnActionStillDetaches() {
        val parent = KmParent()
        val listener = KmRecordingListener()
        parent.internalAddMembershipListener(listener)
        val t = parent.threads.create("self")
        val result = t action { dispose() }
        assertIs<TransactionResult.Success<*>>(result)
        assertNull(parent.threads["self"])
        assertEquals(listOf("attached:self", "detached:self"), listener.events)
    }

    @Test
    fun anEventfulStoreIsAKeyedStoreLikeAnyOther() {
        val parent = KmParent()
        val e = parent.eventful.create("e")
        assertSame(e, parent.eventful["e"])
        e action {
            n mutate 1
            emit(KmEvent.Pinged)
        }
        assertEquals(1, e.n.value)
        assertSame(parent.eventful, e.tree.parent)
    }

    @Test
    fun theWrongStoreClassIsRejectedNamingBothClasses() {
        val parent = KmParent()
        val error = assertFailsWith<IllegalStateException> { parent.wrongClass.create("i") }
        val message = assertNotNull(error.message)
        assertTrue("KmSubThreadStore" in message && "returned KmThreadStore" in message, message)
        assertNull(parent.wrongClass["i"])
    }

    @Test
    fun aSubclassOfTheDeclaredTypeIsAccepted() {
        val parent = KmParent()
        parent.threadHooks["sub"] = { KmSubThreadStore(it) }
        val sub = parent.threads.create("sub")
        assertSame(sub, parent.threads["sub"])
        assertIs<KmSubThreadStore>(parent.threads["sub"])
    }

    @Test
    fun aNestedCreateInsideAFactoryAttachesTheInnerStoreFirst() {
        val parent = KmParent()
        val listener = KmRecordingListener()
        parent.internalAddMembershipListener(listener)
        parent.threadHooks["outer"] = { id ->
            parent.threads.create("inner")
            KmThreadStore(id)
        }
        val outer = parent.threads.create("outer")
        assertEquals(listOf("attached:inner", "attached:outer"), listener.events)
        assertSame(outer, parent.threads["outer"])
        assertNotNull(parent.threads["inner"])
    }

    @Test
    fun aKeyedStoresOwnKeyedChildrenJoinTheParentsSubtree() {
        val parent = KmParent()
        parent.session
        val listener = KmRecordingListener()
        parent.internalAddMembershipListener(listener)
        val folder = parent.folders.create("docs")
        val file = folder.files.create("readme")
        assertEquals(listOf("attached:docs", "attached:readme"), listener.events)
        assertSame(folder.files, parent.tree.nodeOf(file)?.parent)
        assertTrue(file in parent.tree.stores(parent.folders))
        folder.dispose()
        assertEquals(
            listOf("attached:docs", "attached:readme", "detached:docs", "detached:readme"),
            listener.events,
            "the folder's dispose releases its file and the parent hears both leave",
        )
        assertFalse(file.isDisposed, "the tree disposes nothing")
        assertNull(file.tree.parent, "the released file is a subtree root")
        assertEquals("KmQuiet", file.tree.node.name, "named by its class again")
        assertTrue(parent.tree.stores().none { it === file })
    }

    @Test
    fun createGetOrCreateAndLookupsOnADisposedParentThrow() {
        val parent = KmParent()
        val threads = parent.threads
        parent.dispose()
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { threads.create("x") }.message!!)
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { threads.getOrCreate("x") }.message!!)
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { threads["x"] }.message!!)
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { threads.entries }.message!!)
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { parent.threads }.message!!)
        assertEquals(0, parent.threadRuns)
    }

    @Test
    fun attachAndDetachReachTheListenerOncePerStoreAndNeverForAbandonedStores() {
        val parent = KmParent()
        val listener = KmRecordingListener()
        parent.internalAddMembershipListener(listener)
        val a = parent.threads.create("a")
        parent.threadHooks["bad"] = { id ->
            KmThreadStore(id)
            error("no")
        }
        assertFailsWith<IllegalStateException> { parent.threads.create("bad") }
        a.dispose()
        a.dispose()
        assertEquals(listOf("attached:a", "detached:a"), listener.events)
    }

    @Test
    fun createInsideAStrictAtomicBodyAttachesWithoutOpeningATransaction() {
        val parent = KmParent()
        val session = parent.session
        val result =
            atomic(session) {
                session { token mutate "t" }
                parent.quiet.create("in-frame")
            }
        assertIs<TransactionResult.Success<*>>(result)
        val q = assertNotNull(parent.quiet["in-frame"])
        assertEquals("quiet in-frame", q.title.value)
        assertEquals("t", session.token.value)
    }

    @Test
    fun entriesAreTypedAndOrderedByCreation() {
        val parent = KmParent()
        val b = parent.threads.create("b")
        val a = parent.threads.create("a")
        val entries: Map<String, KmThreadStore> = parent.threads.entries
        assertEquals(listOf("b", "a"), entries.keys.toList())
        assertEquals(listOf(b, a), entries.values.toList())
    }

    @Test
    fun getOrCreateReturnsTheExistingStoreWithoutRunningTheFactory() {
        val parent = KmParent()
        val first = parent.threads.getOrCreate("g")
        val second = parent.threads.getOrCreate("g")
        assertSame(first, second)
        assertEquals(1, parent.threadRuns)
    }

    @Test
    fun aSameThreadGetOrCreateCycleThrows() {
        val parent = KmParent()
        parent.threadHooks["c"] = { id ->
            parent.threads.getOrCreate("c")
            KmThreadStore(id)
        }
        val error = assertFailsWith<IllegalStateException> { parent.threads.getOrCreate("c") }
        assertTrue("cycle" in error.message!!, error.message)
        assertNull(parent.threads["c"])
        assertTrue(parent.threads.entries.isEmpty())
    }

    @Test
    fun aKeyedLeafIsNamedByItsKeyAndNeverPinned() {
        val parent = KmParent()
        val t = parent.threads.create("k1")
        val leaf = assertNotNull(parent.tree.nodeOf(t))
        assertEquals("k1", leaf.name)
        assertEquals(NameOrigin.Key, leaf.nameOrigin)
        assertEquals(listOf("threads", "k1"), leaf.pathUnder(parent.tree.node))
        assertEquals("LeafNode(KmParent/threads/k1)", leaf.toString())
    }
}
