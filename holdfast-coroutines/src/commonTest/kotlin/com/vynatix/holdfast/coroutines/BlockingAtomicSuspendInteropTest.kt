package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class InteropAccount(
    initial: Long = 0,
) : Store<InteropAccount>() {
    val balance by state { initial }
}

/**
 * F1 regression suite: blocking `atomic(...)` must serialize against
 * in-flight suspending work via the store's AsyncSerializer, and the
 * serializer's blocking side must be thread-reentrant once installed.
 */
class BlockingAtomicSuspendInteropTest {
    @Test fun blockingAtomicWaitsForAnInFlightSuspendAction() =
        runBlocking {
            val v = InteropAccount()
            val bodyEntered = CompletableDeferred<Unit>()

            val suspending =
                async(Dispatchers.Default) {
                    v.suspendAction {
                        balance mutate 1L
                        bodyEntered.complete(Unit)
                        // Hold the serializer across a suspension point so the
                        // concurrent atomic below overlaps the in-flight body.
                        delay(150)
                        balance mutate 2L
                    }
                }
            bodyEntered.await()
            val framed =
                async(Dispatchers.Default) {
                    // Bare mutate inside the frame body: pre-F1 this opened a root
                    // that clobbered the suspending transaction's activeTransaction
                    // slot, cross-contaminating the two transactions' writes.
                    atomic(v) {
                        v { balance update { it + 10 } }
                    }
                }
            assertIs<TransactionResult.Success<*>>(suspending.await())
            assertIs<TransactionResult.Success<*>>(framed.await())
            // Serialized outcome: suspendAction commits 2, then atomic adds 10.
            assertEquals(12L, v.balance.value, "atomic must wait for the suspendAction commit")
        }

    @Test fun nestedBlockingActionsStayReentrantAfterASuspendActionInstalledTheSerializer() =
        runBlocking {
            val v = InteropAccount()
            // Install the serializer.
            assertIs<TransactionResult.Success<*>>(v.suspendAction { balance mutate 1L })
            // Pre-fix: the inner action's blockingAcquire re-tryLock'd the mutex
            // with the shared spin owner and kotlinx threw a raw ISE.
            val r =
                v.action {
                    v.action { balance mutate 2L }
                    balance update { it + 1 }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(3L, v.balance.value)
        }

    @Test fun atomicInsideActionSavepointsWithTheSerializerInstalled() =
        runBlocking {
            val v = InteropAccount()
            assertIs<TransactionResult.Success<*>>(v.suspendAction { balance mutate 1L })
            val r =
                v.action {
                    // atomic re-acquires the serializer this action already holds
                    // (thread-reentrant), and opens a savepoint of this action's txn.
                    atomic(v) { v { balance mutate 5L } }
                    balance update { it + 100 }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(105L, v.balance.value)
        }

    @Test fun actionInsideAtomicBodyStaysReentrantWithTheSerializerInstalled() =
        runBlocking {
            val v = InteropAccount()
            assertIs<TransactionResult.Success<*>>(v.suspendAction { balance mutate 1L })
            val r =
                atomic(v) {
                    v.action { balance update { it + 5 } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(6L, v.balance.value)
        }
}
