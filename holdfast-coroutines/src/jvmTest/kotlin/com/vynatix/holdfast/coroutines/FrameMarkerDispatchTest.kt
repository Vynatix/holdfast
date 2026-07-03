package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class HopAccount(
    initial: Long = 0,
) : Store<HopAccount>() {
    val balance by state { initial }
}

/**
 * JVM-specific: the frame marker rides a `ThreadContextElement`, so
 * enforcement must follow the body across dispatcher hops AND must not leak
 * to unrelated coroutines that time-share the same dispatcher threads.
 */
class FrameMarkerDispatchTest {
    @Test fun enforcementFollowsTheBodyAcrossDispatcherHops() =
        runBlocking {
            val a = HopAccount()
            val b = HopAccount()
            val c = HopAccount()
            assertFailsWith<UnenrolledStoreException> {
                suspendAtomic(a, b) {
                    withContext(Dispatchers.Default) {
                        c { balance mutate 1L }
                    }
                }
            }
            assertEquals(0L, c.balance.value)
        }

    @Test fun markerDoesNotLeakToConcurrentCoroutinesOnTheSameDispatcher() =
        runBlocking {
            val a = HopAccount()
            val b = HopAccount()
            val c = HopAccount()
            val frameEntered = CompletableDeferred<Unit>()
            val bystanderDone = CompletableDeferred<Unit>()

            val frame =
                async(Dispatchers.Default) {
                    suspendAtomic(a, b) {
                        frameEntered.complete(Unit)
                        // Suspend mid-body so the bystander runs while the frame
                        // is active — its thread-local slot must be clean.
                        bystanderDone.await()
                        a { balance mutate 1L }
                    }
                }
            val bystander =
                async(Dispatchers.Default) {
                    frameEntered.await()
                    val r = c.action { balance update { it + 7 } }
                    bystanderDone.complete(Unit)
                    r
                }
            assertIs<TransactionResult.Success<*>>(bystander.await())
            assertIs<TransactionResult.Success<*>>(frame.await())
            assertEquals(7L, c.balance.value)
            assertEquals(1L, a.balance.value)
        }
}
