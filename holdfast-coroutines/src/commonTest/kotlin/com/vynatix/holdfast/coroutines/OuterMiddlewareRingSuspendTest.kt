@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalSetOuterMiddleware
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The outer middleware ring (issue #21 plan, PR 21-1, decision U12) is
 * outermost on `suspendAction` and `suspendAtomic` too: both route through
 * [Store.snapshotMiddleware], which already appends the ring after the
 * consumer list (pinned for the blocking path by `MiddlewareRingTest` in
 * `:holdfast`). This test pins that the suspending paths reach the same
 * snapshot.
 */
private class RingSuspendVault : Store<RingSuspendVault>() {
    val n by state { 0 }
}

private class RingLogMiddleware(
    private val tag: String,
    private val log: MutableList<String>,
) : Middleware<RingSuspendVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<RingSuspendVault>) {
        log.add("$tag.started")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<RingSuspendVault>) {
        log.add("$tag.completed")
    }
}

class OuterMiddlewareRingSuspendTest {
    @Test
    fun ringIsOutermostOnSuspendAction() =
        runBlocking {
            val v = RingSuspendVault()
            val log = mutableListOf<String>()
            v.middlewares(RingLogMiddleware("CONSUMER", log))
            v.internalSetOuterMiddleware(listOf(RingLogMiddleware("RING", log)))

            v.suspendAction {
                log.add("BODY")
                n mutate 1
            }

            assertEquals(
                listOf("RING.started", "CONSUMER.started", "BODY", "CONSUMER.completed", "RING.completed"),
                log,
            )
        }

    @Test
    fun ringIsOutermostOnSuspendAtomic() =
        runBlocking {
            val v = RingSuspendVault()
            val log = mutableListOf<String>()
            v.middlewares(RingLogMiddleware("CONSUMER", log))
            v.internalSetOuterMiddleware(listOf(RingLogMiddleware("RING", log)))

            suspendAtomic(v) {
                log.add("BODY")
                v { n mutate 1 }
            }

            assertEquals(
                listOf("RING.started", "CONSUMER.started", "BODY", "CONSUMER.completed", "RING.completed"),
                log,
            )
        }
}
