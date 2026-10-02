@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreMembership
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.internalAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    root: KmRoot,
) : Store<KmThreadStore>(root.threads.at(id)) {
    val title by state { "thread $id" }

    init {
        // Leaf code running inside the factory is ordinary store code.
        action { title mutate "constructed $id" }
    }
}

/** No constructor-time action, so it can be created inside a Strict frame. */
private class KmQuietStore(
    val id: String,
    root: KmRoot,
) : Store<KmQuietStore>(root.quiet.at(id)) {
    val title by state { "quiet $id" }
}

private class KmTokenStore(
    token: StoreMembership<KmTokenStore>,
) : Store<KmTokenStore>(token)

private class KmSubThreadStore(
    id: String,
    root: KmRoot,
) : KmThreadStore(id, root)

private sealed class KmEvent {
    data object Pinged : KmEvent()
}

private class KmEventfulStore(
    val id: String,
    root: KmRoot,
) : EventfulStore<KmEventfulStore, KmEvent>(root.eventful.at(id)) {
    val n by state { 0 }
}

private class KmThrowingStore(
    id: String,
    root: KmRoot,
) : Store<KmThrowingStore>(root.throwing.at(id)) {
    init {
        error("constructor of $id refused")
    }
}

private class KmRoot : Root("km") {
    val session by branch(KmSessionStore())
    val threads by keyed<String, KmThreadStore>(under = session)
    val eventful by keyed<String, KmEventfulStore>()
    val throwing by keyed<String, KmThrowingStore>()
    val quiet by keyed<String, KmQuietStore>()
    val tokens by keyed<String, KmTokenStore>()
}

private class RecordingListener : LeafMembershipListener() {
    val events = ArrayList<String>()

    override fun onAttached(leaf: LeafNode) {
        events += "attached:${leaf.name}"
    }

    override fun onDetached(leaf: LeafNode) {
        events += "detached:${leaf.name}"
    }
}

/** T3: keyed stores join through the factory bracket and leave on dispose. */
class KeyedMembershipTest {
    @Test
    fun createReturnsTheLiveStoreAndLookupIsNullAfterDispose() {
        val root = KmRoot()
        val t = root.threads.create("a") { KmThreadStore(it, root) }
        val found: KmThreadStore? = root[root.threads, "a"]
        assertSame(t, found)
        assertEquals("constructed a", t.title.value)
        assertEquals(mapOf("a" to t), root.entries(root.threads))
        assertNotNull(root.nodeOf(t))
        assertSame(root.threads, root.nodeOf(t)!!.parent)
        assertEquals("a", root.nodeOf(t)!!.key)

        t.dispose()
        assertNull(root[root.threads, "a"])
        assertNull(root.nodeOf(t))
        assertTrue(root.entries(root.threads).isEmpty())
    }

    @Test
    fun atOutsideCreateThrowsATeachingError() {
        val root = KmRoot()
        val error = assertFailsWith<IllegalStateException> { KmThreadStore("bare", root) }
        assertTrue("threads.at(bare)" in error.message!!, error.message)
        assertTrue("create" in error.message!!, error.message)
        assertNull(root[root.threads, "bare"])
    }

    @Test
    fun aFactoryConstructingAnotherKeyFailsAndLeavesNoEntry() {
        val root = KmRoot()
        val error = assertFailsWith<IllegalStateException> { root.threads.create("x") { KmThreadStore("y", root) } }
        assertTrue("threads.at(y)" in error.message!!, error.message)
        assertNull(root[root.threads, "x"])
        assertNull(root[root.threads, "y"])
        assertTrue(root.entries(root.threads).isEmpty())
    }

    @Test
    fun aFactoryReturningAForeignInstanceFailsAndLeavesNoEntry() {
        val root = KmRoot()
        val other = KmRoot()
        val foreign = other.threads.create("f") { KmThreadStore(it, other) }
        val error = assertFailsWith<IllegalStateException> { root.threads.create("f") { foreign } }
        assertTrue("did not take" in error.message!!, error.message)
        assertNull(root[root.threads, "f"])
        assertSame(foreign, other[other.threads, "f"])
    }

    @Test
    fun aThrowingInitializerLeavesNoEntryAndARetrySucceeds() {
        val root = KmRoot()
        val error = assertFailsWith<IllegalStateException> { root.throwing.create("t") { KmThrowingStore(it, root) } }
        assertEquals("constructor of t refused", error.message)
        assertNull(root[root.throwing, "t"])
        assertTrue(root.entries(root.throwing).isEmpty())

        // The key is free again: a retry runs the factory once more.
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            root.throwing.create("t") {
                attempts++
                KmThrowingStore(it, root)
            }
        }
        assertEquals(1, attempts)
        // And a well-behaved store on another branch is unaffected.
        val t = root.threads.create("fine") { KmThreadStore(it, root) }
        assertSame(t, root[root.threads, "fine"])
    }

    @Test
    fun anAbandonedStoreIsDisposed() {
        val root = KmRoot()
        var leaked: KmThreadStore? = null
        assertFailsWith<IllegalStateException> {
            root.threads.create("leak") { id ->
                val s = KmThreadStore(id, root)
                leaked = s
                error("factory failed after constructing")
            }
        }
        assertTrue(leaked!!.isDisposed, "the store the failed factory constructed must be disposed")
        assertNull(leaked!!.internalAttachment(treeMembershipKey))
        assertNull(root[root.threads, "leak"])
    }

    @Test
    fun duplicateKeysFailAtCreate() {
        val root = KmRoot()
        root.threads.create("d") { KmThreadStore(it, root) }
        val error = assertFailsWith<IllegalStateException> { root.threads.create("d") { KmThreadStore(it, root) } }
        assertTrue("already exists" in error.message!!, error.message)
        assertTrue("getOrCreate" in error.message!!, error.message)
    }

    @Test
    fun disposeFromInsideTheStoresOwnActionStillDetaches() {
        val root = KmRoot()
        val listener = RecordingListener()
        root.internalAddMembershipListener(listener)
        val t = root.threads.create("self") { KmThreadStore(it, root) }
        val result = t action { dispose() }
        assertIs<TransactionResult.Success<*>>(result)
        assertNull(root[root.threads, "self"])
        assertEquals(listOf("attached:self", "detached:self"), listener.events)
    }

    @Test
    fun anEventfulStoreAttachesThroughTheMirroredConstructor() {
        val root = KmRoot()
        val e = root.eventful.create("e") { KmEventfulStore(it, root) }
        assertSame(e, root[root.eventful, "e"])
        e action {
            n mutate 1
            emit(KmEvent.Pinged)
        }
        assertEquals(1, e.n.value)
    }

    @Test
    fun theWrongStoreClassIsRejectedNamingBothClasses() {
        val root = KmRoot()

        class Impostor(
            token: StoreMembership<Impostor>,
        ) : Store<Impostor>(token)

        val error =
            assertFailsWith<IllegalStateException> {
                root.threads.create("i") {
                    @Suppress("UNCHECKED_CAST")
                    Impostor(root.threads.at(it) as StoreMembership<Impostor>) as KmThreadStore
                }
            }
        assertTrue("KmThreadStore" in error.message!! && "Impostor" in error.message!!, error.message)
        assertNull(root[root.threads, "i"])
    }

    @Test
    fun aSubclassOfTheDeclaredTypeIsAccepted() {
        val root = KmRoot()
        val sub = root.threads.create("sub") { KmSubThreadStore(it, root) }
        assertSame(sub, root[root.threads, "sub"])
        assertIs<KmSubThreadStore>(root[root.threads, "sub"])
    }

    @Test
    fun theTokenIsSingleUse() {
        val root = KmRoot()
        val error =
            assertFailsWith<IllegalStateException> {
                root.tokens.create("twice") {
                    val token = root.tokens.at(it)
                    KmTokenStore(token)
                    KmTokenStore(token)
                }
            }
        assertTrue("already used" in error.message!!, error.message)
        assertNull(root[root.tokens, "twice"])
    }

    @Test
    fun aTokenTakenByASecondStoreAfterTheFirstBoundIsRefused() {
        val root = KmRoot()
        val error =
            assertFailsWith<IllegalStateException> {
                root.tokens.create("second") {
                    val first = KmTokenStore(root.tokens.at(it))
                    KmTokenStore(root.tokens.at(it))
                    first
                }
            }
        assertTrue("already called" in error.message!!, error.message)
        assertNull(root[root.tokens, "second"])
    }

    @Test
    fun atCalledTwiceInsideOneCreateThrows() {
        val root = KmRoot()
        val error =
            assertFailsWith<IllegalStateException> {
                root.threads.create("two") {
                    root.threads.at(it)
                    KmThreadStore(it, root)
                }
            }
        assertTrue("already called" in error.message!!, error.message)
        assertNull(root[root.threads, "two"])
    }

    @Test
    fun aNestedCreateInsideAFactoryAttachesTheInnerStoreFirst() {
        val root = KmRoot()
        val listener = RecordingListener()
        root.internalAddMembershipListener(listener)
        val outer =
            root.threads.create("outer") { id ->
                root.threads.create("inner") { KmThreadStore(it, root) }
                KmThreadStore(id, root)
            }
        assertEquals(listOf("attached:inner", "attached:outer"), listener.events)
        assertSame(outer, root[root.threads, "outer"])
        assertNotNull(root[root.threads, "inner"])
    }

    @Test
    fun bareNestedConstructionInsideAFactoryFailsAndTheOuterCreateCleansUp() {
        val root = KmRoot()
        val error =
            assertFailsWith<IllegalStateException> {
                root.threads.create("outer") { id ->
                    KmThreadStore("stray", root)
                    KmThreadStore(id, root)
                }
            }
        assertTrue("threads.at(stray)" in error.message!!, error.message)
        assertNull(root[root.threads, "outer"])
        assertNull(root[root.threads, "stray"])
        assertTrue(root.entries(root.threads).isEmpty())
    }

    @Test
    fun createAndAtOnADisposedRootThrow() {
        val root = KmRoot()
        root.dispose()
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { root.threads.create("x") { KmThreadStore(it, root) } }.message!!)
        assertTrue("disposed" in assertFailsWith<IllegalStateException> { root.threads.at("x") }.message!!)
    }

    @Test
    fun attachAndDetachReachTheListenerOncePerStoreAndNeverForAbandonedStores() {
        val root = KmRoot()
        val listener = RecordingListener()
        root.internalAddMembershipListener(listener)
        val a = root.threads.create("a") { KmThreadStore(it, root) }
        runCatching {
            root.threads.create("bad") { id ->
                KmThreadStore(id, root)
                error("no")
            }
        }
        a.dispose()
        a.dispose()
        assertEquals(listOf("attached:a", "detached:a"), listener.events)
    }

    @Test
    fun createInsideAStrictAtomicBodyAttachesWithoutOpeningATransaction() {
        val root = KmRoot()
        val session = root.session.stores.single() as KmSessionStore
        val result =
            atomic(session) {
                session { token mutate "t" }
                root.quiet.create("in-frame") { KmQuietStore(it, root) }
            }
        assertIs<TransactionResult.Success<*>>(result)
        val q = root[root.quiet, "in-frame"]
        assertNotNull(q)
        assertEquals("quiet in-frame", q.title.value)
        assertEquals("t", session.token.value)
    }

    @Test
    fun entriesAreTypedAndOrderedByCreation() {
        val root = KmRoot()
        val b = root.threads.create("b") { KmThreadStore(it, root) }
        val a = root.threads.create("a") { KmThreadStore(it, root) }
        val entries: Map<String, KmThreadStore> = root.entries(root.threads)
        assertEquals(listOf("b", "a"), entries.keys.toList())
        assertEquals(listOf(b, a), entries.values.toList())
    }

    @Test
    fun getOrCreateReturnsTheExistingStoreWithoutRunningTheFactory() {
        val root = KmRoot()
        var runs = 0
        val first =
            root.threads.getOrCreate("g") {
                runs++
                KmThreadStore(it, root)
            }
        val second =
            root.threads.getOrCreate("g") {
                runs++
                KmThreadStore(it, root)
            }
        assertSame(first, second)
        assertEquals(1, runs)
    }

    @Test
    fun aSameThreadGetOrCreateCycleThrows() {
        val root = KmRoot()
        val error =
            assertFailsWith<IllegalStateException> {
                root.threads.getOrCreate("c") { id ->
                    root.threads.getOrCreate("c") { KmThreadStore(it, root) }
                    KmThreadStore(id, root)
                }
            }
        assertTrue("cycle" in error.message!!, error.message)
        assertNull(root[root.threads, "c"])
    }

    @Test
    fun aKeyedLeafIsNamedByItsKeyAndNeverPinned() {
        val root = KmRoot()
        val t = root.threads.create("k1") { KmThreadStore(it, root) }
        val leaf = root.nodeOf(t)!!
        assertEquals("k1", leaf.name)
        assertEquals(NameOrigin.Key, leaf.nameOrigin)
        assertEquals("km/session/threads/k1", leaf.path())
    }
}
