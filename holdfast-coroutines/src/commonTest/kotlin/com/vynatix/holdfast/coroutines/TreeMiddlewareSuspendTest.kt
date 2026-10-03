@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.KeyedDisposal
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class SmLeafStore : Store<SmLeafStore>() {
    val n by state { 0 }
}

private class SmFeedStore(
    private val remote: suspend () -> List<String>,
) : Store<SmFeedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { items mutate listOf("seed") }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

private class SmParent(
    remote: suspend () -> List<String> = { listOf("fetched") },
) : Store<SmParent>() {
    val a by store(onParentDispose = KeyedDisposal.Release) { SmLeafStore() }
    val b by store(onParentDispose = KeyedDisposal.Release) { SmLeafStore() }
    val feed by store { SmFeedStore(remote) }
}

private class SmTrace(
    private val throwInStarted: Boolean = false,
) : TreeMiddleware() {
    val events = mutableListOf<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
        if (throwInStarted) error("hook failed")
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

/** Tree middleware on the suspending path: the same trace as the blocking one, terminal hooks under removal and dispose. */
class TreeMiddlewareSuspendTest {
    @Test
    fun theTraceIsIdenticalAcrossActionAndSuspendAction() =
        runBlocking {
            val parent = SmParent()
            val a = parent.a
            val trace = SmTrace()
            parent.tree.middlewares(trace)
            a action { n mutate 1 }
            val blocking = trace.events.toList()
            trace.events.clear()
            a
                .suspendAction {
                    n mutate 2
                    yield()
                }.getOrThrow()
            assertEquals(blocking, trace.events)
            assertEquals(listOf("started a", "completed a"), blocking)
        }

    @Test
    fun aHookThrowIsIsolatedOnTheSuspendPath() =
        runBlocking {
            val parent = SmParent()
            val a = parent.a
            val trace = SmTrace(throwInStarted = true)
            parent.tree.middlewares(trace)
            a.suspendAction { n mutate 5 }.getOrThrow()
            assertEquals(5, a.n.value, "a run-caught hook does not abort the suspending body")
            assertEquals(listOf("started a", "completed a"), trace.events)
        }

    @Test
    fun removeAndDisposeDuringAParkedSuspendActionStillDeliverTheTerminalHook() =
        runBlocking {
            val parent = SmParent()
            val tree = parent.tree
            val a = parent.a
            val b = parent.b
            val trace = SmTrace()
            tree.middlewares(trace)
            val gate = CompletableDeferred<Unit>()
            val parked =
                launch {
                    a
                        .suspendAction {
                            gate.await()
                            n mutate 1
                        }.getOrThrow()
                }
            yield()
            assertEquals(listOf("started a"), trace.events)
            assertTrue(tree.removeMiddleware(trace), "a suspending holder is not a blocking owner: removal is allowed")
            gate.complete(Unit)
            parked.join()
            assertEquals(listOf("started a", "completed a"), trace.events)

            val second = SmTrace()
            tree.middlewares(second)
            val gate2 = CompletableDeferred<Unit>()
            val parked2 =
                launch {
                    b
                        .suspendAction {
                            gate2.await()
                            n mutate 1
                        }.getOrThrow()
                }
            yield()
            parent.dispose()
            gate2.complete(Unit)
            parked2.join()
            // The terminal hook still reaches the released child's node, whose
            // name the parent's dispose reset to its class-derived one.
            assertEquals(listOf("started b", "completed SmLeaf"), second.events, "the observation it started still ends after dispose")
            b action { }
            assertEquals(2, second.events.size, "the parent's dispose retired its ring on the released child")
        }

    @Test
    fun suspendAtomicRootsAndInFrameSuspendActionSavepointsAreSeen() =
        runBlocking {
            val parent = SmParent()
            val a = parent.a
            val b = parent.b
            val trace = SmTrace()
            parent.tree.middlewares(trace)
            suspendAtomic(a, b) {
                a { n mutate 1 }
                b.suspendAction { n mutate 2 }.getOrThrow()
            }.getOrThrow()
            assertEquals(
                listOf("started a", "started b", "started b", "completed b", "completed a", "completed b"),
                trace.events,
                "two frame roots, and the savepoint the in-frame suspendAction opened on b",
            )
        }

    @Test
    fun hydrationSeedAndAdoptAreSeenWithTheirNode() =
        runBlocking {
            val parent = SmParent()
            val feed = parent.feed
            val trace = SmTrace()
            parent.tree.middlewares(trace)
            feed.hydration.hydrate(this)
            feed.hydration.awaitSettled()
            assertEquals(listOf("fetched"), feed.items.value)
            assertTrue(trace.events.count { it == "started feed" } >= 2, "the seed and the adopt: ${trace.events}")
            assertTrue(trace.events.all { it.substringAfter(' ') == "feed" }, "no other member ran anything: ${trace.events}")
        }
}
