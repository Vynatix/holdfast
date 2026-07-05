package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
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

/**
 * JVM-specific F5 regression suite: read-your-own-writes must follow a
 * suspending body across dispatcher hops (the marker gate in
 * `MutableState.value`), and must NOT widen visibility for concurrent plain
 * readers while a suspending body is in flight.
 */
class RyowAcrossDispatchTest {
    @Test fun suspendActionRyowSurvivesADispatcherHop() =
        runBlocking {
            val v = HopAccount(initial = 100)
            // The lost-update transfer repro: pre-F5, the update inside the
            // hopped section read the COMMITTED 100 (not the staged 70) and
            // committed 105, silently dropping the -30.
            val r =
                v.suspendAction {
                    balance update { it - 30 }
                    withContext(Dispatchers.Default) {
                        balance update { it + 5 }
                    }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(75L, v.balance.value, "staged write visible across the hop")
        }

    @Test fun suspendAtomicRyowSurvivesADispatcherHop() =
        runBlocking {
            val a = HopAccount(initial = 100)
            val b = HopAccount()
            val r =
                suspendAtomic(a, b) {
                    a { balance update { it - 30 } }
                    withContext(Dispatchers.Default) {
                        a { balance update { it + 5 } }
                        b { balance mutate 30L }
                    }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(75L, a.balance.value)
            assertEquals(30L, b.balance.value)
        }

    @Test fun nestedSuspendActionAcrossADispatcherHopSavepoints() =
        runBlocking {
            val v = HopAccount()
            val r =
                v.suspendAction {
                    balance mutate 1L
                    withContext(Dispatchers.Default) {
                        v.suspendAction { balance update { it + 10 } }.getOrThrow()
                    }
                    balance update { it + 100 }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(111L, v.balance.value)
        }

    @Test fun concurrentPlainReaderStillSeesOnlyCommittedValues() =
        runBlocking {
            val v = HopAccount()
            val staged = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job =
                async(Dispatchers.Default) {
                    v.suspendAction {
                        balance mutate 42L
                        staged.complete(Unit)
                        release.await()
                    }
                }
            staged.await()
            // A dedicated raw thread (never a coroutine worker, so it cannot
            // coincide with the transaction's owner thread) must see only the
            // committed value while the suspending body is in flight.
            var readerValue = -1L
            val reader = thread { readerValue = v.balance.value }
            reader.join()
            release.complete(Unit)
            assertIs<TransactionResult.Success<*>>(job.await())
            assertEquals(0L, readerValue, "uncommitted staged value must not leak to plain readers")
            assertEquals(42L, v.balance.value)
        }
}
