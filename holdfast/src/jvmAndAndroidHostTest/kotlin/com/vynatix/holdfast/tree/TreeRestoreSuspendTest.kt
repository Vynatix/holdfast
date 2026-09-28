@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class SusLeftStore : Store<SusLeftStore>() {
    val n by state { 0 }
}

private class SusRightStore : Store<SusRightStore>() {
    val m by state { 0 }
}

private class SusRoot : Root("sus") {
    val left = SusLeftStore()
    val right = SusRightStore()
    val pair by branch(left, right)
}

/** `Root.restore`/`Root.reset` and suspending entries: refused inside, waited out from outside. */
class TreeRestoreSuspendTest {
    @Test
    fun restoreAndResetInsideASuspendingEntryFailFastInsteadOfSpinning() {
        val root = SusRoot()
        root.left action { n mutate 7 }
        val tree = root.snapshot()
        root.left action { n mutate 8 }
        val refused = mutableListOf<Pair<String, Throwable?>>()

        fun attempt(where: String) {
            refused += "restore/$where" to runCatching { root.restore(tree) }.exceptionOrNull()
            refused += "reset/$where" to runCatching { root.reset() }.exceptionOrNull()
        }

        completesWithin(30, "restore/reset inside suspending entries") {
            runBlocking {
                root.left.suspendAction { attempt("suspendAction on a target") }.getOrThrow()
                suspendAtomic(root.left, root.right) { attempt("suspendAtomic over the targets") }.getOrThrow()
                root.left
                    .suspendAction {
                        yield()
                        attempt("suspendAction, resumed")
                    }.getOrThrow()
                root.left
                    .suspendAction {
                        withContext(Dispatchers.Default) { attempt("suspendAction, another thread") }
                    }.getOrThrow()
            }
        }
        assertEquals(8, refused.size)
        for ((where, failure) in refused) {
            val ise = assertIs<IllegalStateException>(failure, where)
            assertContains(ise.message!!, "suspendAction or suspendAtomic body", message = where)
        }
        assertEquals(8, root.left.n.value, "nothing restored or reset")
    }

    @Test
    fun aRestoreWhileAForeignSuspendActionHoldsTheSerializerWaitsThenCommits() {
        val root = SusRoot()
        root.left action { n mutate 7 }
        root.right action { m mutate 3 }
        val tree = root.snapshot()
        root.left action { n mutate 8 }

        val holding = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val restoreDone = CountDownLatch(1)
        val outcome = AtomicReference<Result<Unit>>()
        completesWithin(30, "a restore behind a parked suspendAction") {
            runBlocking {
                val holder =
                    async(Dispatchers.Default) {
                        root.right.suspendAction {
                            holding.countDown()
                            release.await()
                            m mutate 4
                        }
                    }
                assertTrue(holding.await(10, TimeUnit.SECONDS))
                val restorer =
                    Thread {
                        outcome.set(
                            runCatching {
                                root.restore(tree).getOrThrow()
                                Unit
                            },
                        )
                        restoreDone.countDown()
                    }.apply {
                        isDaemon = true
                        start()
                    }
                // The restore is parked on the right store's serializer: it has not run.
                assertTrue(!restoreDone.await(300, TimeUnit.MILLISECONDS), "the restore waits for the suspendAction")
                assertEquals(8, root.left.n.value, "the left store is not touched while the frame waits")
                release.complete(Unit)
                holder.await().getOrThrow()
                assertTrue(restoreDone.await(10, TimeUnit.SECONDS))
                restorer.join()
            }
        }
        outcome.get().getOrThrow()
        assertEquals(7, root.left.n.value)
        assertEquals(3, root.right.m.value, "the restore committed after the suspendAction's own commit")
    }
}
