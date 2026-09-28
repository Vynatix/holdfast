@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.Root
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
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

private class SmRoot(
    remote: suspend () -> List<String> = { listOf("fetched") },
) : Root("sm") {
    val a = SmLeafStore()
    val b = SmLeafStore()
    val feed = SmFeedStore(remote)
    val pair by branch(a, b).named(a, "a").named(b, "b")
    val feeds by branch(feed).named(feed, "feed")
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
            val root = SmRoot()
            val trace = SmTrace()
            root.middlewares(trace)
            root.a action { n mutate 1 }
            val blocking = trace.events.toList()
            trace.events.clear()
            root.a
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
            val root = SmRoot()
            val trace = SmTrace(throwInStarted = true)
            root.middlewares(trace)
            root.a.suspendAction { n mutate 5 }.getOrThrow()
            assertEquals(5, root.a.n.value, "a run-caught hook does not abort the suspending body")
            assertEquals(listOf("started a", "completed a"), trace.events)
        }

    @Test
    fun removeAndDisposeDuringAParkedSuspendActionStillDeliverTheTerminalHook() =
        runBlocking {
            val root = SmRoot()
            val trace = SmTrace()
            root.middlewares(trace)
            val gate = CompletableDeferred<Unit>()
            val parked =
                launch {
                    root.a
                        .suspendAction {
                            gate.await()
                            n mutate 1
                        }.getOrThrow()
                }
            yield()
            assertEquals(listOf("started a"), trace.events)
            assertTrue(root.removeMiddleware(trace), "a suspending holder is not a blocking owner: removal is allowed")
            gate.complete(Unit)
            parked.join()
            assertEquals(listOf("started a", "completed a"), trace.events)

            val second = SmTrace()
            root.middlewares(second)
            val gate2 = CompletableDeferred<Unit>()
            val parked2 =
                launch {
                    root.b
                        .suspendAction {
                            gate2.await()
                            n mutate 1
                        }.getOrThrow()
                }
            yield()
            root.dispose()
            gate2.complete(Unit)
            parked2.join()
            assertEquals(listOf("started b", "completed b"), second.events, "the observation it started still ends after dispose")
        }

    @Test
    fun suspendAtomicRootsAndInFrameSuspendActionSavepointsAreSeen() =
        runBlocking {
            val root = SmRoot()
            val trace = SmTrace()
            root.middlewares(trace)
            suspendAtomic(root.a, root.b) {
                root.a { n mutate 1 }
                root.b.suspendAction { n mutate 2 }.getOrThrow()
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
            val root = SmRoot()
            val trace = SmTrace()
            root.middlewares(trace)
            root.feed.hydration.hydrate(this)
            root.feed.hydration.awaitSettled()
            assertEquals(listOf("fetched"), root.feed.items.value)
            assertTrue(trace.events.count { it == "started feed" } >= 2, "the seed and the adopt: ${trace.events}")
            assertTrue(trace.events.none { "a" == it.substringAfter(' ') || "b" == it.substringAfter(' ') })
        }
}
