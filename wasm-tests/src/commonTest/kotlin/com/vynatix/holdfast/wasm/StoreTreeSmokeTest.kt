@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.coroutines.HydrateAllReport
import com.vynatix.holdfast.coroutines.Hydration
import com.vynatix.holdfast.coroutines.asFlow
import com.vynatix.holdfast.coroutines.hydrateAll
import com.vynatix.holdfast.coroutines.hydrator
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalTransactionLockFree
import com.vynatix.holdfast.tree.KeyedBranch
import com.vynatix.holdfast.tree.KeyedDisposal
import com.vynatix.holdfast.tree.NameOrigin
import com.vynatix.holdfast.tree.NamingIssue
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeIdentified
import com.vynatix.holdfast.tree.TreeMiddleware
import com.vynatix.holdfast.tree.TreeSnapshot
import com.vynatix.holdfast.tree.group
import com.vynatix.holdfast.tree.keyed
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TreeLeafStore : Store<TreeLeafStore>() {
    var initializerRuns = 0
    val n by state(codec = IntCodec) {
        initializerRuns++
        0
    }
}

private class TreeAlphaStore : Store<TreeAlphaStore>() {
    val a by state(codec = IntCodec) { 1 }
}

private class TreeBetaStore : Store<TreeBetaStore>() {
    val b by state(codec = IntCodec) { 2 }
}

private class TreeRoomStore(
    id: String,
) : Store<TreeRoomStore>() {
    val title by state(codec = StringCodec) { "room $id" }
}

private interface TreeDraft {
    val text: State<String>

    fun edit(value: String)
}

private class TreeAppStore : Store<TreeAppStore>() {
    var settingsRuns = 0
    val own by state(codec = IntCodec) { 0 }
    val settings by store {
        settingsRuns++
        TreeLeafStore()
    }
    val prefs by store(named = "prefs-v2") { TreeLeafStore() }
    val session by group { listOf(TreeAlphaStore(), TreeBetaStore() named "beta") }
    val rooms by keyed<String, TreeRoomStore> { id -> TreeRoomStore(id) }
    val draft by store<TreeDraft> {
        object : NodeStore(), TreeDraft {
            override val text by state(codec = StringCodec) { "" }

            override fun edit(value: String) {
                text mutate value
            }
        }
    }
}

/** A group over a store handed in from outside: the lambda only lists it. */
private class TreeAnonGroupParent(
    private val member: Store<*>,
) : Store<TreeAnonGroupParent>() {
    val members by group { listOf(member) }
}

private class TreeAnonPinnedParent(
    private val member: Store<*>,
) : Store<TreeAnonPinnedParent>() {
    val members by group { listOf(member named "anon") }
}

/** A declaration whose lambda reads the declaration itself. */
private class TreeLoopStore : Store<TreeLoopStore>() {
    val c: TreeLeafStore by store { c }
}

/** `x`'s initializer materializes `settings`, whose constructor reads `x`. */
private class TreeCycParent : Store<TreeCycParent>() {
    val x: State<Int> by state { settings.n.value }
    val settings by store { TreeCycChild(this) }
}

private class TreeCycChild(
    parent: TreeCycParent,
) : Store<TreeCycChild>() {
    val n by state { 1 }

    init {
        parent.x.value
    }
}

private class TreeKeyedLoopStore : Store<TreeKeyedLoopStore>() {
    val loop: KeyedBranch<String, TreeRoomStore> by keyed<String, TreeRoomStore> { id -> loop.getOrCreate(id) }

    /** Every key but "base" needs "base" first: a nested create for ANOTHER key, never a cycle. */
    val linked: KeyedBranch<String, TreeRoomStore> by keyed<String, TreeRoomStore> { id ->
        if (id != "base") linked.getOrCreate("base")
        TreeRoomStore(id)
    }
}

/** A child lambda needing a keyed key whose factory needs the child: a mixed same-thread cycle. */
private class TreeMixedLoopStore : Store<TreeMixedLoopStore>() {
    val c: TreeRoomStore by store {
        k.getOrCreate("x")
        TreeRoomStore("c")
    }
    val k: KeyedBranch<String, TreeRoomStore> by keyed<String, TreeRoomStore> { id ->
        c
        TreeRoomStore(id)
    }
}

private class TreeFeedStore(
    private val remote: suspend () -> List<String>,
) : Store<TreeFeedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { items mutate listOf("seed") }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

private class TreeHydratedParent : Store<TreeHydratedParent>() {
    val a by store { TreeFeedStore { listOf("a") } }
    val plain by store { TreeLeafStore() }
    val feeds by keyed<String, TreeFeedStore> { id -> TreeFeedStore { listOf(id) } }
}

/** A group listing a store it builds and one that already hangs under another parent. */
private class TreeThiefStore(
    private val victim: Store<*>,
) : Store<TreeThiefStore>() {
    var built: TreeLeafStore? = null
    val stolen by group { listOf(TreeLeafStore().also { built = it } named "fresh", victim named "victim") }
}

private class TreeOwnerStore : Store<TreeOwnerStore>() {
    val owned by keyed<String, TreeRoomStore> { TreeRoomStore(it) }
    val released by keyed<String, TreeRoomStore>(onParentDispose = KeyedDisposal.Release) { TreeRoomStore(it) }
}

private class TreeAuthoredStore : Store<TreeAuthoredStore>() {
    val draft by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
}

/** Persists through a class-named leaf of a property-named group, under a class-named receiver. */
private class TreePersistedApp : Store<TreePersistedApp>() {
    val authored = TreeAuthoredStore()
    val members by group { listOf(authored) }
}

private class TreeIdAppV1 :
    Store<TreeIdAppV1>(),
    TreeIdentified {
    override val treeId: String get() = "app"
    val members by group(named = "members") { listOf(TreeAuthoredStore() named "authored") }
    val prefs by store(named = "prefs") { TreeAuthoredStore() }
}

private class TreeIdRenamedAppV2 :
    Store<TreeIdRenamedAppV2>(),
    TreeIdentified {
    override val treeId: String get() = "app"
    val prefs by store(named = "prefs") { TreeAuthoredStore() }
}

private class TreeIdOther :
    Store<TreeIdOther>(),
    TreeIdentified {
    override val treeId: String get() = "other"
    val prefs by store(named = "prefs") { TreeAuthoredStore() }
}

private class TreeTrace : TreeMiddleware() {
    val events = mutableListOf<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "completed ${node.name}"
    }

    override fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
        events += "error ${node.name}"
    }
}

private class TreeBoom : RuntimeException("boom")

/**
 * The store tree on wasmJs: first-read attach (`ChildEntry.attachLock`),
 * keyed construction locks, the per-thread materialization mark stack
 * (`platform/MaterializingLocal`) and settle scopes (`platform/SettleLocal`),
 * both plain globals on wasmJs, the seqlock-validated listings and the node
 * names derived from `::class.simpleName`. The JVM run is the control.
 */
class StoreTreeSmokeTest {
    @Test
    fun aChildAttachesOnFirstReadAndTheTreeListsItInDeclarationOrder() {
        val app = TreeAppStore()
        assertEquals(0, app.settingsRuns, "declaring a child runs nothing")
        val settings = app.settings
        assertEquals(1, app.settingsRuns)
        assertSame(settings, app.settings)
        assertEquals(1, app.settingsRuns, "the lambda runs once")
        assertSame(app.tree.node, settings.tree.parent)
        assertEquals(listOf("settings", "prefs-v2", "session", "rooms", "draft"), app.tree.children().map { it.name })
        assertEquals(NameOrigin.Property, app.tree.nodeOf(settings)!!.nameOrigin)
        assertEquals(NameOrigin.Pinned, app.tree.nodeOf(app.prefs)!!.nameOrigin)
        val leaves = app.session.stores.map { app.tree.nodeOf(it)!! }
        assertEquals(listOf("TreeAlpha", "beta"), leaves.map { it.name }, "class name minus Store, or the pin")
        assertEquals(listOf(NameOrigin.ClassName, NameOrigin.Pinned), leaves.map { it.nameOrigin })
        val stores = app.tree.stores()
        assertSame<Store<*>>(app, stores.first(), "the receiver first")
        assertEquals(6, stores.size, "app, settings, prefs, two group leaves, draft")
        assertEquals(0, settings.initializerRuns, "materializing a child runs no state initializer")
        assertEquals("TreeApp", app.tree.node.name, "an undeclared receiver is named by its class minus Store")
    }

    @Test
    fun anAnonymousStoreInAGroupHasNoClassNameAndMustBePinned() {
        val anonymous = object : NodeStore() {}
        val error = assertFailsWith<IllegalStateException> { TreeAnonGroupParent(anonymous).members }
        assertTrue("has no simple name" in error.message.orEmpty(), error.message)
        assertFalse(anonymous.isDisposed, "a store that existed before the run is never disposed")
        val pinned = TreeAnonPinnedParent(anonymous)
        assertEquals(listOf("anon"), pinned.members.stores.map { pinned.tree.nodeOf(it)!!.name })
    }

    @Test
    fun anAnonymousReceiverIsNamedStoreOnTheWire() {
        val anon =
            object : NodeStore() {
                val profile by store { TreeLeafStore() }
            }
        anon.profile action { n mutate 3 }
        assertEquals("Store", anon.tree.node.name)
        assertEquals(NameOrigin.ClassName, anon.tree.node.nameOrigin)
        val text = anon.tree.snapshot().encode()
        assertTrue(text.startsWith("""{"format":"holdfast.tree","v":1,"receiver":"Store","""), text)
    }

    @Test
    fun anInlineNodeStoreChildAndAKeyedEntryRoundTripThroughEncodeDecodeAndRestore() {
        val app = TreeAppStore()
        app.draft.edit("kept")
        app.settings action { n mutate 7 }
        app action { own mutate 3 }
        app.rooms.create("r1") action { title mutate "first" }
        val captured = app.tree.snapshot()
        assertEquals("kept", captured[app.draft.text])
        assertEquals(7, captured[app.settings.n])
        val text = captured.encode()

        val fresh = TreeAppStore()
        val decoded = fresh.tree.decode(text)
        assertTrue(decoded.unresolvedPaths.isEmpty(), "${decoded.unresolvedPaths}")
        assertEquals(setOf("r1"), decoded.pendingKeys(fresh.rooms))
        val room = fresh.rooms.create("r1")
        assertIs<TransactionResult.Success<*>>(fresh.tree.restore(decoded))
        assertEquals("kept", fresh.draft.text.value)
        assertEquals(7, fresh.settings.n.value)
        assertEquals(3, fresh.own.value)
        assertEquals(0, fresh.prefs.n.value)
        assertEquals("first", room.title.value)
        assertEquals(text, fresh.tree.snapshot().encode(), "the restored tree encodes as the captured one")
    }

    // The mark stack is one global on wasmJs: a throw must pop every mark, or
    // the retry prints a longer chain and an unrelated first read sees a cycle.
    @Test
    fun aChildLambdaNeedingItsOwnDeclarationThrowsTheCycleMessageAndStaysRetryable() {
        val loop = TreeLoopStore()
        val message = assertFailsWith<IllegalStateException> { loop.c }.message.orEmpty()
        assertTrue(message.startsWith("Materialization cycle: child declaration 'TreeLoopStore.c'"), message)
        assertTrue("child declaration 'TreeLoopStore.c' → child declaration 'TreeLoopStore.c'" in message, message)
        val again = assertFailsWith<IllegalStateException> { loop.c }.message
        assertEquals(message, again, "the mark stack unwound: the same chain again, not a longer one")
        assertFailsWith<IllegalStateException> { loop.tree.children() }
        // An unrelated first read on this thread is no cycle.
        val app = TreeAppStore()
        assertEquals(0, app.settings.n.value)
    }

    @Test
    fun anInitializerChildInitializerCyclePrintsBothKindsInOrderAndStaysRetryable() {
        val parent = TreeCycParent()
        val message = assertFailsWith<IllegalStateException> { parent.x.value }.message.orEmpty()
        assertTrue(message.startsWith("Materialization cycle"), message)
        assertTrue(
            "state initializer 'TreeCycParent.x' → child declaration 'TreeCycParent.settings' → " +
                "state initializer 'TreeCycParent.x'" in message,
            message,
        )
        // x's latch was released and the child entry is retryable: the same chain again.
        val again = assertFailsWith<IllegalStateException> { parent.x.value }.message
        assertEquals(message, again)
    }

    @Test
    fun aKeyedFactoryNeedingItsOwnKeyThrowsTheCycleMessageAndAnotherKeyNests() {
        val store = TreeKeyedLoopStore()
        val message = assertFailsWith<IllegalStateException> { store.loop.getOrCreate("a") }.message.orEmpty()
        assertTrue(message.startsWith("Materialization cycle"), message)
        assertTrue(
            "keyed factory 'TreeKeyedLoopStore.loop' → keyed factory 'TreeKeyedLoopStore.loop'" in message,
            message,
        )
        assertNull(store.loop["a"], "no entry is left behind")
        assertTrue(store.loop.entries().isEmpty())
        assertEquals(message, assertFailsWith<IllegalStateException> { store.loop.create("a") }.message)

        val x = store.linked.getOrCreate("x")
        assertEquals(listOf("base", "x"), store.linked.entries().map { it.key }, "the nested key attached first")
        assertSame(x, store.linked["x"])
        assertEquals("room base", store.linked["base"]!!.title.value)

        val mixed = TreeMixedLoopStore()
        val chain = assertFailsWith<IllegalStateException> { mixed.c }.message.orEmpty()
        assertTrue(chain.startsWith("Materialization cycle"), chain)
        assertTrue(
            "child declaration 'TreeMixedLoopStore.c' → keyed factory 'TreeMixedLoopStore.k' → " +
                "child declaration 'TreeMixedLoopStore.c'" in chain,
            chain,
        )
        assertTrue(mixed.k.entries().isEmpty(), "the inner factory published nothing")
        assertEquals(chain, assertFailsWith<IllegalStateException> { mixed.c }.message)
    }

    @Test
    fun aKeyedBranchCreatesFindsListsAndDisposesItsStores() {
        val app = TreeAppStore()
        val r1 = app.rooms.create("r1")
        assertSame(r1, app.rooms.getOrCreate("r1"))
        assertSame(r1, app.rooms["r1"])
        val r2 = app.rooms.getOrCreate("r2")
        assertEquals(listOf("r1", "r2"), app.rooms.entries().map { it.key })
        val node = app.tree.nodeOf(r1)!!
        assertEquals("r1", node.name)
        assertEquals(NameOrigin.Key, node.nameOrigin)
        assertTrue(r1.tree.parent === app.rooms, "a keyed store hangs under its branch")
        assertTrue(r1 in app.tree.stores())
        val duplicate = assertFailsWith<IllegalStateException> { app.rooms.create("r1") }
        assertTrue("already exists" in duplicate.message.orEmpty(), duplicate.message)

        assertTrue(app.rooms.dispose("r1"))
        assertTrue(r1.isDisposed)
        assertNull(app.rooms["r1"])
        assertFalse(app.rooms.dispose("r1"))
        assertFalse(r1 in app.tree.stores())

        val r3 = app.rooms.create("r3")
        app.rooms.disposeAll()
        assertTrue(r2.isDisposed)
        assertTrue(r3.isDisposed)
        assertTrue(app.rooms.entries().isEmpty())
        val again = app.rooms.create("r1")
        assertNotSame(r1, again)
        assertFalse(again.isDisposed)
    }

    @Test
    fun aRunWhoseAttachFailsDisposesWhatItBuiltAndNeverTheStoreThatExistedBefore() {
        val app = TreeAppStore()
        val settings = app.settings
        val thief = TreeThiefStore(settings)
        val message = assertFailsWith<IllegalStateException> { thief.stolen }.message.orEmpty()
        assertTrue("TreeLeafStore already belongs to TreeAppStore/settings" in message, message)
        assertTrue("under TreeThiefStore/stolen" in message, message)
        assertTrue("a store has one parent" in message, message)
        assertTrue(thief.built!!.isDisposed, "built by the failed run and held by nothing")
        assertFalse(settings.isDisposed, "it existed before the run")
        assertSame(app.tree.node, settings.tree.parent, "the first parent keeps it")
        // A tree operation materializes the subtree first, so it runs the lambda
        // again and is refused the same way; that run's own store is disposed too.
        val firstBuilt = thief.built
        assertEquals(message, assertFailsWith<IllegalStateException> { thief.tree.children() }.message)
        assertNotSame(firstBuilt, thief.built)
        assertTrue(thief.built!!.isDisposed)
        assertSame(app.tree.node, settings.tree.parent)
        assertSame<StoreNode?>(app.tree.nodeOf(settings), settings.tree.node)
    }

    @Test
    fun disposingTheParentDisposesOwnedKeyedStoresAndReleasesTheOthers() {
        val owner = TreeOwnerStore()
        val owned = owner.owned.create("a")
        val kept = owner.released.create("b")
        owner.dispose()
        assertTrue(owned.isDisposed, "KeyedDisposal.Dispose")
        assertFalse(kept.isDisposed, "KeyedDisposal.Release")
        assertNull(kept.tree.parent, "a released store is a subtree root")
        kept action { title mutate "still works" }
        assertEquals("still works", kept.title.value)
        assertEquals(listOf<Store<*>>(kept), kept.tree.stores())

        // Disposed inside another store's action: the owned stores dispose at that action's settle.
        val owner2 = TreeOwnerStore()
        val owned2 = owner2.owned.create("a")
        val bystander = TreeLeafStore()
        var disposedInside: Boolean? = null
        val result =
            bystander action {
                owner2.dispose()
                disposedInside = owned2.isDisposed
                n mutate 1
            }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(false, disposedInside, "never under the action's locks")
        assertTrue(owned2.isDisposed, "disposed once the action settled")
        assertEquals(1, bystander.n.value)
    }

    @Test
    fun aDisposedChildStaysTheParentsPropertyAndLeavesTheTree() {
        val app = TreeAppStore()
        val settings = app.settings
        settings.dispose()
        assertTrue(settings.isDisposed)
        assertSame(settings, app.settings, "the property is a stable val")
        assertEquals(1, app.settingsRuns, "never rebuilt")
        assertFalse(settings in app.tree.stores())
        assertEquals(5, app.tree.stores().size)
        val handle = assertFailsWith<IllegalStateException> { settings.tree }
        assertTrue("disposed" in handle.message.orEmpty(), handle.message)
        app.prefs action { n mutate 4 }
        assertEquals(4, app.tree.snapshot()[app.prefs.n])
    }

    @Test
    fun theTreeValueFollowsChildCommitsOncePerFrameAndNewKeyedStores() {
        val app = TreeAppStore()
        val tree = app.tree
        val seen = mutableListOf<TreeSnapshot>()
        val subscription = tree effect { seen += this }
        assertEquals(1, seen.size, "an effect fires at once")
        assertEquals(0, seen.last()[app.settings.n])

        app.settings action { n mutate 4 }
        assertEquals(2, seen.size)
        assertEquals(4, seen.last()[app.settings.n])

        atomic(app.settings, app.prefs) {
            app.settings { n mutate 5 }
            app.prefs { n mutate 6 }
        }.getOrThrow()
        assertEquals(3, seen.size, "one frame, one recompute")
        assertEquals(5, seen.last()[app.settings.n])
        assertEquals(6, seen.last()[app.prefs.n])

        val room = app.rooms.create("r")
        assertEquals(4, seen.size, "the attach recomputed once")
        assertEquals("room r", seen.last()[room.title])
        assertEquals("room r", tree.value[room.title])
        subscription.dispose()
        app.settings action { n mutate 9 }
        assertEquals(4, seen.size)
        assertEquals(9, tree.value[app.settings.n])
    }

    @Test
    fun treeMiddlewareSeesChildAndKeyedTransactionsUntilRemoved() {
        val app = TreeAppStore()
        val trace = TreeTrace()
        app.tree.middlewares(trace)
        app.settings action { n mutate 1 }
        assertEquals(listOf("started settings", "completed settings"), trace.events)
        trace.events.clear()

        val room = app.rooms.create("r")
        room action { title mutate "x" }
        assertEquals(listOf("started r", "completed r"), trace.events, "a store created later is covered")
        trace.events.clear()

        val failed =
            app.prefs action {
                n mutate 2
                throw TreeBoom()
            }
        assertIs<TransactionResult.Error>(failed)
        assertEquals(listOf("started prefs-v2", "error prefs-v2"), trace.events)
        assertEquals(0, app.prefs.n.value)
        trace.events.clear()

        assertTrue(app.tree.removeMiddleware(trace))
        app.settings action { n mutate 3 }
        assertTrue(trace.events.isEmpty(), "${trace.events}")
        assertFalse(app.tree.removeMiddleware(trace))
    }

    @Test
    fun resetRunsOverTheSubtreeAndIsRefusedInsideAnAction() {
        val app = TreeAppStore()
        app action { own mutate 9 }
        app.settings action { n mutate 1 }
        val room = app.rooms.create("r")
        room action { title mutate "changed" }
        app.draft.edit("typed")
        val report = app.tree.reset().getOrThrow()
        assertEquals(0, app.own.value)
        assertEquals(0, app.settings.n.value)
        assertEquals("room r", room.title.value)
        assertEquals("", app.draft.text.value)
        assertTrue(report.skipped.isEmpty(), "$report")
        assertTrue(app.tree.node in report.reset, "$report")

        app.settings action { n mutate 2 }
        app action { own mutate 3 }
        app.tree.reset(app.tree.nodeOf(app.settings)!!).getOrThrow()
        assertEquals(0, app.settings.n.value)
        assertEquals(3, app.own.value, "outside the node: untouched")

        var refusal: Throwable? = null
        app.prefs action { refusal = runCatching { app.tree.reset() }.exceptionOrNull() }
        assertIs<IllegalStateException>(refusal)
        assertContains(refusal!!.message.orEmpty(), "from inside an action")
        assertIs<TransactionResult.Success<*>>(app.tree.reset(), "the settle scope closed with the action")
        assertEquals(0, app.own.value)
    }

    @Test
    fun verifyPersistedNamesFlagsClassDerivedNamesAndATreeIdClearsThem() {
        val app = TreePersistedApp()
        val tree = app.tree
        val byNode = tree.verifyPersistedNames().associateBy { it.node }
        assertEquals(setOf(tree.node, app.members, tree.nodeOf(app.authored)!!), byNode.keys)
        assertEquals(NamingIssue.Kind.ReceiverNameIsClassDerived, byNode.getValue(tree.node).kind)
        assertEquals(NamingIssue.Kind.PropertyDerivedNameOnPersistedSubtree, byNode.getValue(app.members).kind)
        assertEquals(
            NamingIssue.Kind.ClassDerivedNameOnPersistedStore,
            byNode.getValue(tree.nodeOf(app.authored)!!).kind,
        )
        val refused =
            assertFailsWith<IllegalStateException> {
                tree.snapshot(scope = SnapshotScope.UserAuthored).encode()
            }
        assertContains(refused.message.orEmpty(), "is named by its store's class")

        assertEquals(emptyList<NamingIssue>(), TreeIdAppV1().tree.verifyPersistedNames())
    }

    @Test
    fun aTreeIdIdentifiesTheReceiverAcrossARenameAndAMismatchedDecodeThrows() {
        val v1 = TreeIdAppV1()
        v1.prefs action { draft mutate "dark" }
        val text = v1.tree.snapshot().encode()
        assertTrue(text.startsWith("""{"format":"holdfast.tree","v":1,"receiver":"app","""), text)

        val v2 = TreeIdRenamedAppV2()
        val decoded = v2.tree.decode(text)
        assertIs<TransactionResult.Success<*>>(v2.tree.restore(decoded))
        assertEquals("dark", v2.prefs.draft.value)

        val other = TreeIdOther()
        val failure = assertFailsWith<SnapshotFormatException> { other.tree.decode(text) }
        assertContains(failure.message.orEmpty(), "captured under 'app', decoding under 'other'")
        assertFalse("dark" in failure.message.orEmpty(), "never a value")
        assertEquals("", other.prefs.draft.value)
    }

    @Test
    fun aSuspendActionOnAChildSettlesTheTreeValueOnceAndRefusesATreeResetInside() =
        runTest {
            val app = TreeAppStore()
            val tree = app.tree
            val seen = mutableListOf<TreeSnapshot>()
            val subscription = tree effect { seen += this }
            var refusal: Throwable? = null
            app.settings
                .suspendAction {
                    n mutate 5
                    yield()
                    refusal = runCatching { tree.reset() }.exceptionOrNull()
                    assertEquals(1, seen.size, "nothing settles inside the body")
                }.getOrThrow()
            assertEquals(2, seen.size)
            assertEquals(5, seen.last()[app.settings.n])
            assertIs<IllegalStateException>(refusal)
            assertContains(refusal!!.message.orEmpty(), "suspendAction")
            tree.reset().getOrThrow()
            assertEquals(0, app.settings.n.value)
            subscription.dispose()
        }

    // tree.reset() is refused while a settle scope is open on this thread. On
    // wasmJs that slot is one global shared by every coroutine, so a parked
    // suspendAction must hold its scope only while it runs (its slot-bracketing
    // interceptor), or the reset below would be refused as nested.
    @Test
    fun aParkedSuspendActionInAnotherCoroutineDoesNotMakeATreeOperationNested() =
        runTest {
            val busy = TreeLeafStore()
            val app = TreeAppStore()
            val gate = CompletableDeferred<Unit>()
            val holder =
                launch {
                    busy
                        .suspendAction {
                            n mutate 1
                            gate.await()
                        }.getOrThrow()
                }
            runCurrent() // The holder is parked inside its body.
            app.settings action { n mutate 2 }
            assertEquals(2, app.settings.n.value)
            val report = app.tree.reset().getOrThrow()
            assertTrue(report.skipped.isEmpty(), "$report")
            assertEquals(0, app.settings.n.value)
            val room = app.rooms.create("r")
            assertNotNull(app.tree.nodeOf(room))
            gate.complete(Unit)
            holder.join()
            assertEquals(1, busy.n.value)
        }

    // The guard refuses when a settle scope is open on this thread AND a
    // member's suspendingOwner is set: true inside the body, false beside a
    // parked one, whose scope is out of the (global, on wasmJs) slot.
    @Test
    fun treeMiddlewaresAreRefusedInsideAMembersSuspendActionAndAllowedBesideAParkedOne() =
        runTest {
            val app = TreeAppStore()
            val tree = app.tree
            val settings = app.settings
            val trace = TreeTrace()
            var refused: Throwable? = null
            settings
                .suspendAction {
                    yield()
                    refused = runCatching { tree.middlewares(trace) }.exceptionOrNull()
                    n mutate 1
                }.getOrThrow()
            val failure = assertIs<IllegalStateException>(refused)
            assertContains(failure.message.orEmpty(), "member 'settings' is held by a suspendAction or suspendAtomic body")
            settings action { n mutate 2 }
            assertTrue(trace.events.isEmpty(), "nothing was installed: ${trace.events}")

            val gate = CompletableDeferred<Unit>()
            val parked =
                launch {
                    settings
                        .suspendAction {
                            gate.await()
                            n mutate 3
                        }.getOrThrow()
                }
            runCurrent() // Parked inside its body, holding the store.
            tree.middlewares(trace)
            gate.complete(Unit)
            parked.join()
            assertTrue(trace.events.isEmpty(), "the parked chain was snapshotted before the install: ${trace.events}")
            assertEquals(3, settings.n.value)
            settings action { n mutate 4 }
            assertEquals(listOf("started settings", "completed settings"), trace.events)
        }

    @Test
    fun insideASuspendActionAKeyedCreateIsReadFreshAndSettlesOnceAfter() =
        runTest {
            val app = TreeAppStore()
            val tree = app.tree
            val seen = mutableListOf<TreeSnapshot>()
            val subscription = tree effect { seen += this }
            var insideTitle: String? = null
            var room: TreeRoomStore? = null
            app.settings
                .suspendAction {
                    yield()
                    room = app.rooms.create("r")
                    n mutate 8
                    insideTitle = tree.value[room!!.title]
                    yield()
                    assertEquals(1, seen.size, "nothing settles inside the body")
                }.getOrThrow()
            assertEquals("room r", insideTitle, "a read inside the entry answers a fresh capture")
            assertEquals(2, seen.size, "one settle after the body")
            assertEquals("room r", seen.last()[room!!.title])
            assertEquals(8, seen.last()[app.settings.n])
            subscription.dispose()
        }

    @Test
    fun theTreeValueFlowEmitsOneTreePerSuspendAtomicFrame() =
        runTest {
            val app = TreeAppStore()
            val tree = app.tree
            val settings = app.settings
            val prefs = app.prefs
            val trees = mutableListOf<TreeSnapshot>()
            val collector = launch { tree.asFlow().take(3).toList(trees) }
            runCurrent()
            suspendAtomic(settings, prefs) {
                settings { n mutate 1 }
                yield()
                prefs { n mutate 1 }
            }.getOrThrow()
            runCurrent()
            suspendAtomic(settings, prefs) {
                settings { n mutate 2 }
                prefs { n mutate 2 }
            }.getOrThrow()
            runCurrent()
            collector.join()
            assertEquals(listOf(0, 1, 2), trees.map { it[settings.n] })
            assertEquals(listOf(0, 1, 2), trees.map { it[prefs.n] })
        }

    @Test
    fun hydrateAllDrivesEveryHydratorOfTheSubtreeOnEachStoresOwnScope() =
        runTest {
            val parent = TreeHydratedParent()
            val tree = parent.tree
            // Materialized first, so the keyed store sorts after them (lockOrderKey order).
            assertEquals(listOf("a", "plain", "feeds"), tree.children().map { it.name })
            val k = parent.feeds.create("k")
            val report = tree.hydrateAll()
            assertEquals(listOf("TreeHydratedParent", "a", "plain", "k"), report.entries.map { it.node.name })
            assertTrue(report.isHealthy, "$report")
            assertEquals(listOf("a"), parent.a.items.value)
            assertEquals(listOf("k"), k.items.value)
            assertEquals(listOf<Store<*>>(parent, parent.plain), report.skipped.map { it.store })
            assertEquals(
                Hydration.Hydrated,
                assertIs<HydrateAllReport.Outcome.Ran>(report.entries.first { it.store === parent.a }.outcome).hydration,
            )
        }

    /**
     * tree.reset() takes its frame's stores in `lockOrderKey` order: the
     * receiver first, then `settings`, held here by a suspendAction parked on
     * another dispatcher. The JVM waits the holder out, then resets both.
     * wasmJs refuses at `settings` (issue #27: the holder can only resume once
     * this call returns), after it has taken the receiver, so the refusal must
     * release the receiver's lock and root transaction and commit nothing.
     * (BlockingWaitTest's tree variant holds the receiver, refused before
     * anything is taken.) Both outcomes hold whatever the holder's timing.
     */
    @Test
    fun aTreeResetBesideAParkedHolderOfALaterParticipantWaitsOrReleasesTheOnesItTook() =
        runTest {
            val app = TreeAppStore()
            val settings = app.settings
            app action { own mutate 9 }
            val holding = CompletableDeferred<Unit>()
            val holder =
                launch(Dispatchers.Default) {
                    settings
                        .suspendAction {
                            n mutate 5
                            holding.complete(Unit)
                            delay(TREE_HOLDER_PARK_MILLIS)
                        }.getOrThrow()
                }
            holding.await()
            val outcome = runCatching { app.tree.reset() }
            assertTrue(app.internalTransactionLockFree(), "the receiver's transaction lock was released")
            assertNull(app.activeTransaction, "the receiver's frame root was uninstalled")
            holder.join()
            val refused = outcome.exceptionOrNull()
            if (refused == null) {
                assertIs<TransactionResult.Success<*>>(outcome.getOrThrow())
                assertEquals(0, app.own.value)
                assertEquals(0, settings.n.value, "the reset ran after the holder committed")
            } else {
                assertIs<IllegalStateException>(refused)
                assertContains(refused.message.orEmpty(), "suspendAction")
                assertEquals(9, app.own.value, "nothing of the frame committed")
                assertEquals(5, settings.n.value, "the holder still committed")
                assertIs<TransactionResult.Success<*>>(app.tree.reset(), "once the holder finished")
                assertEquals(0, app.own.value)
                assertEquals(0, settings.n.value)
            }
        }
}

private const val TREE_HOLDER_PARK_MILLIS = 50L
