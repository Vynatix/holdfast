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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private class GdLeafStore : Store<GdLeafStore>() {
    val n by state { 0 }
}

/** A store outside the tree: its entries are bystanders to the root's leaves. */
private class GdBystanderStore : Store<GdBystanderStore>() {
    val m by state { 0 }
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
 * snapshotted per transaction. The probe is the settle scope the entry
 * carries across dispatch, which cannot tell the holder's body from another
 * entry on the same thread: beside a parked suspending holder the change is
 * refused, conservatively, from inside any entry on this thread, and allowed
 * from outside every entry. The thread-hopping shape (a nested `withContext`
 * on another dispatcher inside the body) holds on JVM/Android only — on iOS
 * the nested `withContext` replaces the scope's carrier — and lives in
 * `TreeMiddlewareGuardDispatcherSwitchTest` under `jvmAndAndroidHostTest`.
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
            assertContains(failure.message!!, "while an entry is open on this thread")
            assertContains(failure.message!!, "leaf 'a' is held by a suspendAction or suspendAtomic body")
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

    /**
     * The conservative side of the probe: an entry of a store outside the
     * tree, on the thread beside a parked suspending holder of a leaf, is
     * refused too — the guard cannot tell it from the holder's body — and
     * the message says what the guard knows (an entry is open here, the leaf
     * is held) without claiming the caller is inside that body.
     */
    @Test
    fun installFromABystanderEntryBesideAParkedSuspendActionIsRefusedConservatively() =
        runBlocking {
            val root = GdRoot()
            val trace = GdTrace()
            val bystander = GdBystanderStore()
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
            var refused: Throwable? = null
            bystander.action { refused = runCatching { root.middlewares(trace) }.exceptionOrNull() }.getOrThrow()
            val failure = assertIs<IllegalStateException>(refused, "an entry beside a parked holder is refused")
            val message = failure.message!!
            assertContains(message, "while an entry is open on this thread")
            assertContains(message, "leaf 'a' is held by a suspendAction or suspendAtomic body")
            assertFalse("from inside a suspendAction" in message, "the message must not place the caller in that body")
            gate.complete(Unit)
            parked.join()
            root.middlewares(trace)
            root.a action { }
            assertEquals(listOf("started a", "completed a"), trace.events, "allowed from outside every entry")
        }
}
