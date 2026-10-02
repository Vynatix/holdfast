@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private open class TmLeafStore : Store<TmLeafStore>() {
    val n by state { 0 }
}

/** The group's two leaves need distinct classes: a group pins its leaf names by exact class. */
private class TmAStore : TmLeafStore()

private class TmBStore : TmLeafStore()

private class TmKeyedStore(
    id: String,
) : Store<TmKeyedStore>() {
    val title by state { "t $id" }

    init {
        // A transaction inside the factory: before the store is attached.
        action { title mutate "from init" }
    }
}

private class TmParent : Store<TmParent>() {
    val own by state { 0 }
    val pair by stores(names = mapOf(TmAStore::class to "a", TmBStore::class to "b")) { listOf(TmAStore(), TmBStore()) }
    val keyed by stores<String, TmKeyedStore> { TmKeyedStore(it) }

    val a: TmLeafStore get() = pair.stores[0] as TmLeafStore
    val b: TmLeafStore get() = pair.stores[1] as TmLeafStore

    /** Only a property to hand `provideDelegate` for [lateChild]: its name is the late child's. */
    val late: Int = 0

    fun lateChild(): TmLeafStore =
        store { TmLeafStore() }
            .provideDelegate(this, TmParent::late)
            .getValue(this, TmParent::late)
}

/** A mid-tree store: a store of its own, one child of its own. */
private class TmMidStore : Store<TmMidStore>() {
    val n by state { 0 }
    val leaf by store(named = "deep") { TmLeafStore() }
}

private class TmGrandParent : Store<TmGrandParent>() {
    val mid by store { TmMidStore() }
    val side by store { TmLeafStore() }
}

/** The tree middleware [this] installed itself (`tree.middlewares` on its own handle). */
private fun Store<*>.tmInstalled(): List<TreeMiddleware> = internalAttachment(treeMembershipKey)?.installed.orEmpty()

/** Records every hook as `"<phase> <node>"`, with the frame id per event, and can veto. */
private class Trace(
    private val tag: String = "tree",
    private val vetoCompletedOf: String? = null,
    private val throwInStartedOf: String? = null,
) : TreeMiddleware() {
    val events = mutableListOf<String>()
    val frameIds = mutableListOf<String?>()
    val stores = mutableListOf<Store<*>>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        record("started", node, context)
        if (node.name == throwInStartedOf) error("started veto on ${node.name}")
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        record("completed", node, context)
        if (node.name == vetoCompletedOf) error("completed veto on ${node.name}")
    }

    override fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
        record("error", node, context)
    }

    private fun record(
        phase: String,
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "$tag $phase ${node.name}"
        frameIds += context.transaction.frameId
        stores += context.store
    }
}

private class Consumer<V : Store<V>>(
    private val tag: String,
    private val events: MutableList<String>,
    private val veto: Boolean = false,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        events += "$tag started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        events += "$tag completed"
        if (veto) error("$tag veto")
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        events += "$tag error"
    }
}

private class TmBridge : Bridge<Int> {
    private var inbound: ((Int) -> Unit)? = null

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable { inbound = null }
    }

    override fun publish(value: Int): Boolean = true

    fun deliver(value: Int) {
        inbound?.invoke(value)
    }
}

/**
 * T4: a store's tree middleware sees every transaction of the store itself
 * and of every store under it, with its node, always outermost, on stores
 * attached now or later; a parent's ring wraps a child's.
 */
class TreeMiddlewareTest {
    @Test
    fun aParentsMiddlewareSeesEveryMemberTransactionWithItsNodeTheParentsOwnIncluded() {
        val parent = TmParent()
        val trace = Trace()
        parent.tree.middlewares(trace)
        parent action { own mutate 1 }
        parent.a action { n mutate 1 }
        parent.b action { n mutate 2 }
        val k = parent.keyed.create("k1")
        k action { title mutate "x" }
        assertEquals(
            listOf(
                "tree started TmParent",
                "tree completed TmParent",
                "tree started a",
                "tree completed a",
                "tree started b",
                "tree completed b",
                "tree started k1",
                "tree completed k1",
            ),
            trace.events,
        )
        assertEquals(listOf<Store<*>>(parent, parent, parent.a, parent.a, parent.b, parent.b, k, k), trace.stores)
        assertTrue(trace.frameIds.all { it == null }, "plain actions carry no frame id")
    }

    @Test
    fun itIsOutermostRegardlessOfRegistrationOrderIncludingOnAKeyedStoreCreatedAfterInstall() {
        val parent = TmParent()
        val a = parent.a
        val events = mutableListOf<String>()
        a.middlewares(Consumer("before", events))
        val trace = TraceInto(events)
        parent.tree.middlewares(trace)
        a.middlewares(Consumer("after", events))
        a action {
            events += "body"
            n mutate 1
        }
        assertEquals(
            listOf("tree started a", "after started", "before started", "body", "before completed", "after completed", "tree completed a"),
            events,
        )
        events.clear()
        val k = parent.keyed.create("k")
        k.middlewares(Consumer("own", events))
        k action { events += "body" }
        assertEquals(listOf("tree started k", "own started", "body", "own completed", "tree completed k"), events)
    }

    @Test
    fun ringOrderFollowsInstallerOrderAfterRemoveAndReinstall() {
        val parent = TmParent()
        val tree = parent.tree
        val events = mutableListOf<String>()
        val x = TraceInto(events, "X")
        val y = TraceInto(events, "Y")
        tree.middlewares(x, y)
        parent.a action { }
        assertEquals(listOf("Y started a", "X started a", "X completed a", "Y completed a"), events)
        events.clear()
        assertTrue(tree.removeMiddleware(x))
        tree.middlewares(x)
        parent.a action { }
        assertEquals(listOf("X started a", "Y started a", "Y completed a", "X completed a"), events, "re-installed: outermost now")
        assertEquals(listOf<TreeMiddleware>(y, x), parent.tmInstalled())
    }

    @Test
    fun theRootMostInstallerFiresStartedFirstAndAMidTreeInstallSitsInsideTheParentRing() {
        val grand = TmGrandParent()
        val mid = grand.mid
        val deep = mid.leaf
        val events = mutableListOf<String>()
        mid.tree.middlewares(TraceInto(events, "M"))
        grand.tree.middlewares(TraceInto(events, "G"))
        deep action { }
        assertEquals(
            listOf("G started deep", "M started deep", "M completed deep", "G completed deep"),
            events,
            "the root-most installer is outermost, whatever the install order",
        )
        events.clear()
        mid action { n mutate 1 }
        assertEquals(
            listOf("G started mid", "M started mid", "M completed mid", "G completed mid"),
            events,
            "the installing store is a member of its own ring",
        )
    }

    @Test
    fun aMidTreeInstallSeesOnlyItsSubtree() {
        val grand = TmGrandParent()
        val mid = grand.mid
        val deep = mid.leaf
        val side = grand.side
        val events = mutableListOf<String>()
        val midTrace = TraceInto(events, "M")
        mid.tree.middlewares(midTrace)
        side action { }
        grand action { }
        assertEquals(emptyList<String>(), events, "neither the parent nor a sibling is under the mid-tree store")
        mid action { }
        deep action { }
        assertEquals(
            listOf("M started mid", "M completed mid", "M started deep", "M completed deep"),
            events,
        )
        assertTrue(side.treeRingAdapters().isEmpty(), "a sibling of the installer carries nothing")
        assertTrue(grand.treeRingAdapters().isEmpty(), "the installer's parent carries nothing")
        assertEquals(listOf<TreeMiddleware>(midTrace), deep.treeRingAdapters().map { it.middleware })
    }

    @Test
    fun aMiddlewareInstalledOnAChildAndItsParentFiresOncePerTransactionAtTheParentsPosition() {
        val grand = TmGrandParent()
        val mid = grand.mid
        val deep = mid.leaf
        val events = mutableListOf<String>()
        val shared = TraceInto(events, "X")
        val inner = TraceInto(events, "Y")
        mid.tree.middlewares(shared, inner)
        grand.tree.middlewares(shared)
        deep action { }
        assertEquals(
            listOf("X started deep", "Y started deep", "Y completed deep", "X completed deep"),
            events,
            "once, outermost: at the parent's place, wrapping the child's own Y",
        )
        assertEquals(listOf<TreeMiddleware>(inner, shared), deep.treeRingAdapters().map { it.middleware })
        events.clear()
        assertTrue(grand.tree.removeMiddleware(shared))
        deep action { }
        assertEquals(
            listOf("Y started deep", "X started deep", "X completed deep", "Y completed deep"),
            events,
            "removed from the parent: back at the child's own place, still once",
        )
    }

    @Test
    fun removeMiddlewareOnADisposedInstallerAnswersFalse() {
        val grand = TmGrandParent()
        val mid = grand.mid
        val deep = mid.leaf
        val midTree = mid.tree
        val trace = Trace()
        midTree.middlewares(trace)
        mid.dispose()
        assertFalse(midTree.removeMiddleware(trace), "a disposed installer answers false, never throws")
        assertFailsWith<IllegalStateException> { midTree.middlewares(trace) }
        deep action { }
        assertEquals(emptyList<String>(), trace.events, "its dispose retired the ring on the released child")
    }

    @Test
    fun itSeesErrorsThrownByMemberLocalMiddleware() {
        val parent = TmParent()
        val a = parent.a
        val events = mutableListOf<String>()
        a.middlewares(Consumer("local", events, veto = true))
        parent.tree.middlewares(TraceInto(events))
        val result = a action { n mutate 1 }
        assertIs<TransactionResult.Error>(result)
        assertEquals(listOf("tree started a", "local started", "local completed", "local error", "tree error a"), events)
        assertEquals(0, a.n.value)
    }

    @Test
    fun itAppliesToStoresAttachedLater() {
        val parent = TmParent()
        val trace = Trace()
        parent.tree.middlewares(trace)
        val late = parent.lateChild()
        late action { }
        assertEquals(listOf("tree started late", "tree completed late"), trace.events)
    }

    @Test
    fun itSeesSavepointsImplicitMutateAndDerivedRecompute() {
        val parent = TmParent()
        val a = parent.a
        val trace = Trace()
        parent.tree.middlewares(trace)
        a action {
            n mutate 1
            a action { n mutate 2 }
        }
        assertEquals(listOf("tree started a", "tree started a", "tree completed a", "tree completed a"), trace.events)
        trace.events.clear()
        a { n mutate 3 }
        assertEquals(listOf("tree started a", "tree completed a"), trace.events, "a bare mutate is a one-shot action")
        trace.events.clear()
        val doubled = a.derivedState(a.n) { n.value * 2 }
        assertEquals(6, doubled.value)
        a action { n mutate 4 }
        assertEquals(8, doubled.value)
        assertEquals(
            listOf("tree started a", "tree completed a", "tree started a", "tree completed a"),
            trace.events,
            "the action, then the derived state's recompute on the same store",
        )
        doubled.dispose()
    }

    @Test
    fun itSeesRestoreAndResetFramesWithNodesSharingOneFrameId() {
        val parent = TmParent()
        val tree = parent.tree
        val trace = Trace()
        tree.middlewares(trace)
        val before = tree.snapshot()
        parent.a action { n mutate 1 }
        trace.events.clear()
        trace.frameIds.clear()
        tree.restore(before).getOrThrow()
        assertEquals(
            listOf(
                "tree started TmParent",
                "tree started a",
                "tree started b",
                "tree completed TmParent",
                "tree completed a",
                "tree completed b",
            ),
            trace.events,
            "the receiver is a frame participant like its children",
        )
        val frame = trace.frameIds.distinct().single()
        assertNotNull(frame)
        assertTrue(frame.startsWith("atomic-"))
        trace.events.clear()
        trace.frameIds.clear()
        tree.reset(parent.pair).getOrThrow()
        assertEquals(listOf("tree started a", "tree started b", "tree completed a", "tree completed b"), trace.events)
        assertEquals(1, trace.frameIds.distinct().size)
    }

    @Test
    fun aCompletedThrowOnTheLastMemberRollsEveryMemberBackAndFiresTheTreeErrorHookForEach() {
        val parent = TmParent()
        val a = parent.a
        val b = parent.b
        val trace = Trace(vetoCompletedOf = "b")
        parent.tree.middlewares(trace)
        val result =
            atomic(a, b) {
                a { n mutate 1 }
                b { n mutate 2 }
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(0, a.n.value)
        assertEquals(0, b.n.value)
        assertEquals(
            listOf("tree started a", "tree started b", "tree completed a", "tree completed b", "tree error b", "tree error a"),
            trace.events,
        )
    }

    @Test
    fun inboundBridgeWritesAreNeverSeen() {
        val parent = TmParent()
        val a = parent.a
        val trace = Trace()
        parent.tree.middlewares(trace)
        val bridge = TmBridge()
        a { n bridge bridge }
        bridge.deliver(9)
        assertEquals(9, a.n.value)
        assertEquals(emptyList<String>(), trace.events)
    }

    @Test
    fun transactionsRunInsideAFactoryPrecedeAttachAndAreNotSeen() {
        val parent = TmParent()
        val trace = Trace()
        parent.tree.middlewares(trace)
        val k = parent.keyed.create("k")
        assertEquals("from init", k.title.value)
        assertEquals(emptyList<String>(), trace.events)
        k action { title mutate "later" }
        assertEquals(listOf("tree started k", "tree completed k"), trace.events)
    }

    @Test
    fun clearMiddlewareOnAMemberKeepsTreeMiddleware() {
        val parent = TmParent()
        val a = parent.a
        val events = mutableListOf<String>()
        a.middlewares(Consumer("local", events))
        parent.tree.middlewares(TraceInto(events))
        a.clearMiddleware()
        a action { }
        assertEquals(listOf("tree started a", "tree completed a"), events)
    }

    @Test
    fun removeMiddlewareStopsNewObservationsOnEveryMemberAndAnswersWhetherItWasInstalled() {
        val parent = TmParent()
        val tree = parent.tree
        val trace = Trace()
        tree.middlewares(trace)
        val k = parent.keyed.create("k")
        assertTrue(tree.removeMiddleware(trace))
        assertFalse(tree.removeMiddleware(trace), "not installed any more")
        parent action { }
        parent.a action { }
        k action { }
        assertEquals(emptyList<String>(), trace.events)
        assertEquals(emptyList<TreeMiddleware>(), parent.tmInstalled())
        for (member in tree.stores()) assertTrue(member.treeRingAdapters().isEmpty(), "no adapter left on $member")
    }

    @Test
    fun middlewaresInsideAMemberActionAFrameBodyOrATreeHookThrowsWithoutDeadlock() {
        val parent = TmParent()
        val tree = parent.tree
        val a = parent.a
        val b = parent.b
        val trace = Trace()
        var fromAction: Throwable? = null
        a.action { fromAction = runCatching { tree.middlewares(trace) }.exceptionOrNull() }.getOrThrow()
        assertContains(assertIs<IllegalStateException>(fromAction).message!!, "from inside a transaction of its member 'a'")
        var fromFrame: Throwable? = null
        atomic(b) { fromFrame = runCatching { tree.middlewares(trace) }.exceptionOrNull() }.getOrThrow()
        assertContains(assertIs<IllegalStateException>(fromFrame).message!!, "atomic(...) frame")
        var fromHook: Throwable? = null
        val hooking =
            object : TreeMiddleware() {
                override fun onTransactionStarted(
                    node: StoreNode,
                    context: Middleware.MiddlewareContext<*>,
                ) {
                    fromHook = runCatching { tree.removeMiddleware(this) }.exceptionOrNull()
                }
            }
        tree.middlewares(hooking)
        a.action { }.getOrThrow()
        assertIs<IllegalStateException>(fromHook)
        assertTrue(tree.removeMiddleware(hooking))
        assertEquals(emptyList<String>(), trace.events)
    }

    @Test
    fun aHookThrowingInStartedRollsBackTheMemberAction() {
        val parent = TmParent()
        val a = parent.a
        val b = parent.b
        val trace = Trace(throwInStartedOf = "a")
        parent.tree.middlewares(trace)
        val result = a action { n mutate 1 }
        assertIs<TransactionResult.Error>(result)
        assertContains(result.exception.message!!, "started veto on a")
        assertEquals(0, a.n.value)
        assertEquals(listOf("tree started a", "tree error a"), trace.events)
        b.action { n mutate 1 }.getOrThrow()
        assertEquals(1, b.n.value, "the other member is unaffected")
    }

    @Test
    fun aThousandKeyedCreateAndDisposeCyclesLeaveNoAdapters() {
        val parent = TmParent()
        val tree = parent.tree
        val trace = Trace()
        tree.middlewares(trace)
        val baseline = tree.stores()
        assertTrue(baseline.all { it.treeRingAdapters().size == 1 }, "the receiver and both group members carry it")
        repeat(1_000) { i ->
            val k = parent.keyed.create("k$i")
            k action { title mutate "x" }
            val adapters = k.treeRingAdapters()
            assertEquals(1, adapters.size)
            k.dispose()
            assertTrue(adapters.all { it.isRetired })
        }
        assertEquals(baseline, tree.stores(), "every keyed store left with its dispose")
        assertTrue(baseline.all { it.treeRingAdapters().size == 1 })
        assertEquals(2_000, trace.events.size)
    }

    @Test
    fun theValuesOwnSettleTransactionsAreNeverSeen() {
        val parent = TmParent()
        val tree = parent.tree
        val trace = Trace()
        tree.middlewares(trace)
        var settles = 0
        val watch = tree effect { settles++ }
        parent.a action { n mutate 1 }
        assertEquals(2, settles)
        assertEquals(listOf("tree started a", "tree completed a"), trace.events)
        watch.dispose()
    }

    @Test
    fun aParentsDisposeRemovesItsRingFromEveryChildAndKeepsTheChildrensOwnMiddleware() {
        val parent = TmParent()
        val tree = parent.tree
        val a = parent.a
        val events = mutableListOf<String>()
        a.middlewares(Consumer("local", events))
        val trace = TraceInto(events)
        tree.middlewares(trace)
        parent.dispose()
        a action { }
        assertEquals(listOf("local started", "local completed"), events)
        assertFalse(tree.removeMiddleware(trace), "answers false on a disposed store")
        assertFailsWith<IllegalStateException> { tree.middlewares(trace) }
        assertFailsWith<IllegalStateException> { parent.tree }
    }
}

/** A [Trace] writing into a shared list, so consumer and tree hooks interleave in one record. */
private class TraceInto(
    private val shared: MutableList<String>,
    private val tag: String = "tree",
) : TreeMiddleware() {
    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        shared += "$tag started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        shared += "$tag completed ${node.name}"
    }

    override fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
        shared += "$tag error ${node.name}"
    }
}
