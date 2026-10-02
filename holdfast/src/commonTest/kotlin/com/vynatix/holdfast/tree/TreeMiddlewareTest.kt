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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class TmLeafStore : Store<TmLeafStore>() {
    val n by state { 0 }
}

private class TmKeyedStore(
    id: String,
    root: TmRoot,
) : Store<TmKeyedStore>(root.keyed.at(id)) {
    val title by state { "t $id" }

    init {
        // A transaction inside the factory bracket: before the store is attached.
        action { title mutate "from init" }
    }
}

private class TmRoot : Root("tm") {
    val a = TmLeafStore()
    val b = TmLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
    val keyed by keyed<String, TmKeyedStore>()

    fun lateBranch(): BranchDeclaration = branch(TmLeafStore()).named("late")
}

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

/** T4: a root middleware sees every leaf transaction with its node, always outermost, on leaves attached now or later. */
class TreeMiddlewareTest {
    @Test
    fun aRootMiddlewareSeesEveryLeafTransactionWithItsNode() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        root.a action { n mutate 1 }
        root.b action { n mutate 2 }
        val k = root.keyed.create("k1") { TmKeyedStore(it, root) }
        k action { title mutate "x" }
        assertEquals(
            listOf(
                "tree started a",
                "tree completed a",
                "tree started b",
                "tree completed b",
                "tree started k1",
                "tree completed k1",
            ),
            trace.events,
        )
        assertEquals(listOf<Store<*>>(root.a, root.a, root.b, root.b, k, k), trace.stores)
        assertTrue(trace.frameIds.all { it == null }, "plain actions carry no frame id")
    }

    @Test
    fun itIsOutermostRegardlessOfRegistrationOrderIncludingOnAKeyedStoreCreatedAfterInstall() {
        val root = TmRoot()
        val events = mutableListOf<String>()
        root.a.middlewares(Consumer("before", events))
        val trace = TraceInto(events)
        root.middlewares(trace)
        root.a.middlewares(Consumer("after", events))
        root.a action {
            events += "body"
            n mutate 1
        }
        assertEquals(
            listOf("tree started a", "after started", "before started", "body", "before completed", "after completed", "tree completed a"),
            events,
        )
        events.clear()
        val k = root.keyed.create("k") { TmKeyedStore(it, root) }
        k.middlewares(Consumer("own", events))
        k action { events += "body" }
        assertEquals(listOf("tree started k", "own started", "body", "own completed", "tree completed k"), events)
    }

    @Test
    fun ringOrderFollowsInstallerOrderAfterRemoveAndReinstall() {
        val root = TmRoot()
        val events = mutableListOf<String>()
        val x = TraceInto(events, "X")
        val y = TraceInto(events, "Y")
        root.middlewares(x, y)
        root.a action { }
        assertEquals(listOf("Y started a", "X started a", "X completed a", "Y completed a"), events)
        events.clear()
        assertTrue(root.removeMiddleware(x))
        root.middlewares(x)
        root.a action { }
        assertEquals(listOf("X started a", "Y started a", "Y completed a", "X completed a"), events, "re-installed: outermost now")
        assertEquals(listOf<TreeMiddleware>(y, x), root.treeMiddleware.installedMiddleware)
    }

    @Test
    fun itSeesErrorsThrownByLeafLocalMiddleware() {
        val root = TmRoot()
        val events = mutableListOf<String>()
        root.a.middlewares(Consumer("local", events, veto = true))
        root.middlewares(TraceInto(events))
        val result = root.a action { n mutate 1 }
        assertIs<TransactionResult.Error>(result)
        assertEquals(listOf("tree started a", "local started", "local completed", "local error", "tree error a"), events)
        assertEquals(0, root.a.n.value)
    }

    @Test
    fun itAppliesToStoresAttachedLater() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        val late = root.lateBranch().provideDelegate(root, TmRoot::pair).getValue(root, TmRoot::pair)
        val lateStore = late.stores.single()
        lateStore action { }
        assertEquals(listOf("tree started TmLeaf", "tree completed TmLeaf"), trace.events)
    }

    @Test
    fun itSeesSavepointsImplicitMutateAndDerivedRecompute() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        root.a action {
            n mutate 1
            root.a action { n mutate 2 }
        }
        assertEquals(listOf("tree started a", "tree started a", "tree completed a", "tree completed a"), trace.events)
        trace.events.clear()
        root.a { n mutate 3 }
        assertEquals(listOf("tree started a", "tree completed a"), trace.events, "a bare mutate is a one-shot action")
        trace.events.clear()
        val doubled = root.a.derivedState(root.a.n) { n.value * 2 }
        assertEquals(6, doubled.value)
        root.a action { n mutate 4 }
        assertEquals(8, doubled.value)
        assertEquals(
            listOf("tree started a", "tree completed a", "tree started a", "tree completed a"),
            trace.events,
            "the action, then the derived state's recompute on the same leaf",
        )
        doubled.dispose()
    }

    @Test
    fun itSeesRestoreAndResetFramesWithNodesSharingOneFrameId() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        val before = root.snapshot()
        root.a action { n mutate 1 }
        trace.events.clear()
        trace.frameIds.clear()
        root.restore(before).getOrThrow()
        assertEquals(listOf("tree started a", "tree started b", "tree completed a", "tree completed b"), trace.events)
        val frame = trace.frameIds.distinct().single()
        assertNotNull(frame)
        assertTrue(frame.startsWith("atomic-"))
        trace.events.clear()
        trace.frameIds.clear()
        root.reset(root.pair).getOrThrow()
        assertEquals(listOf("tree started a", "tree started b", "tree completed a", "tree completed b"), trace.events)
        assertEquals(1, trace.frameIds.distinct().size)
    }

    @Test
    fun aCompletedThrowOnTheLastLeafRollsEveryLeafBackAndFiresTheTreeErrorHookForEach() {
        val root = TmRoot()
        val trace = Trace(vetoCompletedOf = "b")
        root.middlewares(trace)
        val result =
            atomic(root.a, root.b) {
                root.a { n mutate 1 }
                root.b { n mutate 2 }
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(0, root.a.n.value)
        assertEquals(0, root.b.n.value)
        assertEquals(
            listOf("tree started a", "tree started b", "tree completed a", "tree completed b", "tree error b", "tree error a"),
            trace.events,
        )
    }

    @Test
    fun inboundBridgeWritesAreNeverSeen() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        val bridge = TmBridge()
        root.a { n bridge bridge }
        bridge.deliver(9)
        assertEquals(9, root.a.n.value)
        assertEquals(emptyList<String>(), trace.events)
    }

    @Test
    fun transactionsRunInsideAFactoryPrecedeAttachAndAreNotSeen() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        val k = root.keyed.create("k") { TmKeyedStore(it, root) }
        assertEquals("from init", k.title.value)
        assertEquals(emptyList<String>(), trace.events)
        k action { title mutate "later" }
        assertEquals(listOf("tree started k", "tree completed k"), trace.events)
    }

    @Test
    fun clearMiddlewareOnALeafKeepsTreeMiddleware() {
        val root = TmRoot()
        val events = mutableListOf<String>()
        root.a.middlewares(Consumer("local", events))
        root.middlewares(TraceInto(events))
        root.a.clearMiddleware()
        root.a action { }
        assertEquals(listOf("tree started a", "tree completed a"), events)
    }

    @Test
    fun removeMiddlewareStopsNewObservationsOnEveryLeafAndAnswersWhetherItWasInstalled() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        val k = root.keyed.create("k") { TmKeyedStore(it, root) }
        assertTrue(root.removeMiddleware(trace))
        assertTrue(!root.removeMiddleware(trace), "not installed any more")
        root.a action { }
        k action { }
        assertEquals(emptyList<String>(), trace.events)
        assertEquals(emptyList<TreeMiddleware>(), root.treeMiddleware.installedMiddleware)
        assertTrue(root.treeMiddleware.adaptersOf(root.a).isEmpty())
    }

    @Test
    fun middlewaresInsideALeafActionAFrameBodyOrATreeHookThrowsWithoutDeadlock() {
        val root = TmRoot()
        val trace = Trace()
        var fromAction: Throwable? = null
        root.a.action { fromAction = runCatching { root.middlewares(trace) }.exceptionOrNull() }.getOrThrow()
        assertContains(assertIs<IllegalStateException>(fromAction).message!!, "from inside a transaction of its leaf 'a'")
        var fromFrame: Throwable? = null
        atomic(root.b) { fromFrame = runCatching { root.middlewares(trace) }.exceptionOrNull() }.getOrThrow()
        assertContains(assertIs<IllegalStateException>(fromFrame).message!!, "atomic(...) frame")
        var fromHook: Throwable? = null
        val hooking =
            object : TreeMiddleware() {
                override fun onTransactionStarted(
                    node: StoreNode,
                    context: Middleware.MiddlewareContext<*>,
                ) {
                    fromHook = runCatching { root.removeMiddleware(this) }.exceptionOrNull()
                }
            }
        root.middlewares(hooking)
        root.a.action { }.getOrThrow()
        assertIs<IllegalStateException>(fromHook)
        assertTrue(root.removeMiddleware(hooking))
        assertEquals(emptyList<String>(), trace.events)
    }

    @Test
    fun aHookThrowingInStartedRollsBackTheLeafAction() {
        val root = TmRoot()
        val trace = Trace(throwInStartedOf = "a")
        root.middlewares(trace)
        val result = root.a action { n mutate 1 }
        assertIs<TransactionResult.Error>(result)
        assertContains(result.exception.message!!, "started veto on a")
        assertEquals(0, root.a.n.value)
        assertEquals(listOf("tree started a", "tree error a"), trace.events)
        root.b.action { n mutate 1 }.getOrThrow()
        assertEquals(1, root.b.n.value, "the other leaf is unaffected")
    }

    @Test
    fun aThousandKeyedCreateAndDisposeCyclesLeaveNoAdapters() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        val baseline = root.treeMiddleware.memberCount
        repeat(1_000) { i ->
            val k = root.keyed.create("k$i") { TmKeyedStore(it, root) }
            k action { title mutate "x" }
            val adapters = root.treeMiddleware.adaptersOf(k)
            k.dispose()
            assertTrue(adapters.all { it.isRetired })
        }
        assertEquals(baseline, root.treeMiddleware.memberCount)
        assertEquals(2_000, trace.events.size)
    }

    @Test
    fun theRootsOwnSettleTransactionsAreNeverSeen() {
        val root = TmRoot()
        val trace = Trace()
        root.middlewares(trace)
        var settles = 0
        val watch = root.value effect { settles++ }
        root.a action { n mutate 1 }
        assertEquals(2, settles)
        assertEquals(listOf("tree started a", "tree completed a"), trace.events)
        watch.dispose()
    }

    @Test
    fun aRootsDisposeRemovesTheRingFromEveryLeafAndKeepsTheLeavesOwnMiddleware() {
        val root = TmRoot()
        val events = mutableListOf<String>()
        root.a.middlewares(Consumer("local", events))
        val trace = TraceInto(events)
        root.middlewares(trace)
        root.dispose()
        root.a action { }
        assertEquals(listOf("local started", "local completed"), events)
        assertTrue(!root.removeMiddleware(trace), "answers false on a disposed root")
        assertFailsWith<IllegalStateException> { root.middlewares(trace) }
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
