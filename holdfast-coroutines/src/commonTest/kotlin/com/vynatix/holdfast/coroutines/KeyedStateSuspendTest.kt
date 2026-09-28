@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.keyedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SuspendDocs : Store<SuspendDocs>() {
    val tick by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

/**
 * Keyed-state entries under the suspending entries (issue #20, R7): writes
 * and evictions inside a `suspendAction` or `suspendAtomic` body stage into
 * its transaction and commit, or roll back, with it; an eviction from its
 * commit's fanout is deferred until the call has released the store (D16);
 * and only a write cancels a suspending body's eviction, never a `get` —
 * which could come from another coroutine on the body's thread.
 */
class KeyedStateSuspendTest {
    @Test fun anEvictionInASuspendActionCommitsOrRollsBackWithIt() =
        runBlocking<Unit> {
            val store = SuspendDocs()
            store.docs["a"]

            val failed =
                store.suspendAction {
                    docs.evict("a")
                    yield()
                    assertFalse("a" in docs)
                    error("the body fails")
                }
            assertIs<TransactionResult.Error>(failed)
            assertTrue("a" in store.docs)

            store.suspendAction {
                docs["b"] mutate 2
                docs.evict("a")
            }
            assertFalse("a" in store.docs)
            assertEquals(2, store.docs["b"].value)
        }

    @Test fun anEvictionFromASuspendingCommitsFanoutIsDeferred() =
        runBlocking<Unit> {
            val store = SuspendDocs()
            val entry = store.docs["a"]
            var reported: Throwable? = null
            store.uncaughtObserverHandler = { reported = it }
            store.tick effect { if (this == 1) store.docs.evict("a") }

            val r = store.suspendAction { tick mutate 1 }

            assertIs<TransactionResult.Success<Unit>>(r)
            assertNull(reported, "an eviction is the one write that defers instead of failing")
            assertFalse("a" in store.docs)
            assertEquals(0, entry.value, "the stale handle stays readable")
        }

    @Test fun aGetFromAnotherCoroutineOnTheBodysThreadDoesNotCancelItsEviction() =
        runBlocking<Unit> {
            val store = SuspendDocs()
            store.docs["a"]
            val parked = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val body =
                async {
                    store.suspendAction {
                        docs.evict("a")
                        parked.complete(Unit)
                        resume.await()
                    }
                }
            parked.await()

            store.docs["a"] // the body's thread, but not the body

            resume.complete(Unit)
            assertIs<TransactionResult.Success<Unit>>(body.await())
            assertFalse("a" in store.docs, "the body's eviction stood")
        }

    @Test fun insideASuspendingBodyOnlyAWriteCancelsAnEviction() =
        runBlocking<Unit> {
            val store = SuspendDocs()
            store.docs["a"]
            store.docs["b"]

            store.suspendAction {
                docs.evict("a")
                docs["a"]
            }
            assertFalse("a" in store.docs, "a get leaves the eviction staged")

            store.suspendAction {
                docs.evict("b")
                docs["b"] mutate 1
            }
            assertEquals(1, store.docs.getOrNull("b")?.value, "a write cancels it")
        }

    @Test fun evictionsInASuspendAtomicFrameCommitWithTheFrame() =
        runBlocking<Unit> {
            val left = SuspendDocs()
            val right = SuspendDocs()
            left.docs["x"]
            right.docs["x"]

            val failed =
                suspendAtomic(left, right) {
                    left.docs.evict("x")
                    right.docs.evict("x")
                    error("the frame fails")
                }
            assertIs<TransactionResult.Error>(failed)
            assertTrue("x" in left.docs && "x" in right.docs)

            suspendAtomic(left, right) {
                left.docs.evict("x")
                right { tick mutate 1 }
            }
            assertFalse("x" in left.docs)
            assertTrue("x" in right.docs)
            assertIs<TransactionResult.Success<Unit>>(atomic(left) { left.docs.evictAll() })
        }
}
