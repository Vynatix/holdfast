@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.Root
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class GdLeafStore : Store<GdLeafStore>() {
    val n by state { 0 }
}

private class GdRoot : Root("gd") {
    val a = GdLeafStore()
    val b = GdLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
}

private class GdTrace : TreeMiddleware() {
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
}

/**
 * `Root.middlewares`/`removeMiddleware` from inside a suspending body of a
 * leaf are refused like they are from inside a blocking one: the chain is
 * snapshotted per transaction. Beside a parked suspending holder, from
 * another coroutine, they are allowed, as beside a parked blocking action.
 */
class TreeMiddlewareGuardSuspendTest {
    @Test
    fun middlewaresInsideASuspendActionBodyIsRefused() =
        runBlocking {
            val root = GdRoot()
            val trace = GdTrace()
            var refused: Throwable? = null
            root.a
                .suspendAction {
                    refused = runCatching { root.middlewares(trace) }.exceptionOrNull()
                    yield()
                    n mutate 1
                }.getOrThrow()
            val failure = assertIs<IllegalStateException>(refused, "install inside a suspending body must throw")
            assertContains(failure.message!!, "from inside")
            assertContains(failure.message!!, "'a'")
            root.a action { }
            assertEquals(emptyList<String>(), trace.events, "nothing was installed")
        }

    @Test
    fun removeMiddlewareInsideASuspendActionBodyIsRefused() =
        runBlocking {
            val root = GdRoot()
            val trace = GdTrace()
            root.middlewares(trace)
            var refused: Throwable? = null
            root.a
                .suspendAction {
                    refused = runCatching { root.removeMiddleware(trace) }.exceptionOrNull()
                    yield()
                }.getOrThrow()
            assertIs<IllegalStateException>(refused, "removing from inside a suspending body must throw")
            root.b action { }
            val expected = listOf("started a", "completed a", "started b", "completed b")
            assertEquals(expected, trace.events, "still installed")
        }

    @Test
    fun middlewaresAfterADispatcherSwitchInsideTheBodyIsRefused() =
        runBlocking {
            val root = GdRoot()
            val trace = GdTrace()
            var refused: Throwable? = null
            root.a
                .suspendAction {
                    withContext(Dispatchers.Default) {
                        refused = runCatching { root.middlewares(trace) }.exceptionOrNull()
                    }
                }.getOrThrow()
            assertIs<IllegalStateException>(refused, "the body's entry is carried across dispatch")
            root.a action { }
            assertEquals(emptyList<String>(), trace.events)
        }

    @Test
    fun middlewaresInsideASuspendAtomicBodyIsRefused() =
        runBlocking {
            val root = GdRoot()
            val trace = GdTrace()
            var refused: Throwable? = null
            suspendAtomic(root.a, root.b) {
                refused = runCatching { root.middlewares(trace) }.exceptionOrNull()
                yield()
            }.getOrThrow()
            assertIs<IllegalStateException>(refused)
            root.a action { }
            assertEquals(emptyList<String>(), trace.events)
        }

    @Test
    fun installBesideAParkedSuspendActionIsAllowedAndDoesNotRetroApply() =
        runBlocking {
            val root = GdRoot()
            val trace = GdTrace()
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
            root.middlewares(trace)
            gate.complete(Unit)
            parked.join()
            assertEquals(emptyList<String>(), trace.events, "the parked chain was snapshotted before the install")
            root.a action { }
            assertEquals(listOf("started a", "completed a"), trace.events)
        }
}
