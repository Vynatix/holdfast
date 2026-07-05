package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.FrameInteropException
import com.vynatix.holdfast.FrameLockOrderException
import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class SuspendFrameAccount(
    initial: Long = 0,
) : Store<SuspendFrameAccount>() {
    val balance by state { initial }
}

private class FrameRecordingMiddleware(
    private val label: String,
    private val log: MutableList<String>,
) : Middleware<SuspendFrameAccount>() {
    override fun onTransactionStarted(context: MiddlewareContext<SuspendFrameAccount>) {
        log += "$label:started:${context.transaction.frameId}"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<SuspendFrameAccount>) {
        log += "$label:completed:${context.transaction.frameId}"
    }

    override fun onTransactionError(
        context: MiddlewareContext<SuspendFrameAccount>,
        error: Throwable,
    ) {
        log += "$label:error:${error.message}"
    }
}

class SuspendFrameInteropTest {
    @Test fun blockingActionOnAParticipantFailsFastInsteadOfDeadlocking() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            val e =
                assertFailsWith<FrameInteropException> {
                    suspendAtomic(a, b) {
                        a.action { balance update { it - 30 } }
                    }
                }
            assertTrue("mutate" in (e.message ?: ""), "message names the working alternative: ${e.message}")
            assertEquals(100L, a.balance.value, "frame rolled back")
        }

    @Test fun blockingAtomicOnAParticipantInsideSuspendFrameFailsFast() =
        runBlocking {
            val a = SuspendFrameAccount()
            val b = SuspendFrameAccount()
            assertFailsWith<FrameInteropException> {
                suspendAtomic(a, b) {
                    atomic(a) { a { balance mutate 1L } }
                }
            }
            assertEquals(0L, a.balance.value)
        }

    @Test fun unenrolledMutateInsideSuspendFrameThrows() =
        runBlocking {
            val a = SuspendFrameAccount()
            val b = SuspendFrameAccount()
            val c = SuspendFrameAccount()
            assertFailsWith<UnenrolledStoreException> {
                suspendAtomic(a, b) {
                    c { balance mutate 5L }
                }
            }
            assertEquals(0L, c.balance.value)
        }

    @Test fun unenrolledSuspendActionInsideSuspendFrameThrows() =
        runBlocking {
            val a = SuspendFrameAccount()
            val b = SuspendFrameAccount()
            val c = SuspendFrameAccount()
            assertFailsWith<UnenrolledStoreException> {
                suspendAtomic(a, b) {
                    c.suspendAction { balance mutate 5L }
                }
            }
            assertEquals(0L, c.balance.value)
        }

    @Test fun allowUnenrolledRunsAnIndependentSuspendAction() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            val c = SuspendFrameAccount(initial = 0)
            val r =
                suspendAtomic(a, b, policy = FramePolicy.AllowUnenrolled) {
                    c.suspendAction { balance update { it + 1 } }
                    error("frame aborts after the side transaction")
                }
            assertIs<TransactionResult.Error>(r)
            assertEquals(100L, a.balance.value, "frame rolled back")
            assertEquals(1L, c.balance.value, "side transaction committed independently")
        }
}

class SuspendFrameSavepointTest {
    @Test fun suspendActionOnAParticipantJoinsTheFrameAsASavepoint() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            val seenA = mutableListOf<Long>()
            val subA = a { balance effect { seenA.add(this) } }
            seenA.clear()
            val r =
                suspendAtomic(a, b) {
                    a.suspendAction { balance update { it - 30 } }
                    b.suspendAction { balance update { it + 30 } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(70L, a.balance.value)
            assertEquals(30L, b.balance.value)
            assertEquals(listOf(70L), seenA, "observer fired once, at frame commit — not at savepoint commit")
            subA.dispose()
        }

    @Test fun innerSuspendActionErrorAbortsTheWholeFrame() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            val r =
                suspendAtomic(a, b) {
                    a.suspendAction { balance update { it - 30 } }
                    b.suspendAction { error("boom") }
                    "unreached"
                }
            assertIs<TransactionResult.Error>(r)
            assertEquals("boom", r.exception.message)
            assertEquals(100L, a.balance.value)
            assertEquals(0L, b.balance.value)
        }

    @Test fun tolerateInnerErrorsRestoresCheckTheResultSemantics() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            val r =
                suspendAtomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                    a.suspendAction { balance update { it - 30 } }
                    val inner = b.suspendAction { error("boom") }
                    assertIs<TransactionResult.Error>(inner)
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(70L, a.balance.value)
            assertEquals(0L, b.balance.value)
        }
}

class SuspendFrameNestingTest {
    @Test fun nestedFrameRollbackDiscardsOnlyItsOwnWrites() =
        runBlocking {
            // Regression: a nested suspendAtomic used to stage its writes
            // directly into the outer frame's root, so a nested-frame failure
            // left phantom writes that committed with the outer frame.
            val a = SuspendFrameAccount(initial = 10)
            val b = SuspendFrameAccount(initial = 0)
            val r =
                suspendAtomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                    val inner =
                        suspendAtomic(a) {
                            a { balance mutate 55L }
                            error("nested frame fails after staging")
                        }
                    assertIs<TransactionResult.Error>(inner)
                    b { balance mutate 1L }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(10L, a.balance.value, "failed nested frame's writes did NOT leak into the outer commit")
            assertEquals(1L, b.balance.value)
        }

    @Test fun nestedFrameErrorEscalatesToTheOuterFrame() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 1)
            val b = SuspendFrameAccount(initial = 2)
            val r =
                suspendAtomic(a, b) {
                    a { balance mutate 5L }
                    suspendAtomic(b) { error("inner frame boom") }
                }
            assertIs<TransactionResult.Error>(r)
            assertEquals("inner frame boom", r.exception.message)
            assertEquals(1L, a.balance.value)
            assertEquals(2L, b.balance.value)
        }

    @Test fun nestedFrameLockOrderViolationFailsFast() =
        runBlocking {
            val a = SuspendFrameAccount() // lowest lockOrderKey
            val b = SuspendFrameAccount()
            val c = SuspendFrameAccount()
            assertFailsWith<FrameLockOrderException> {
                suspendAtomic(b, c) {
                    suspendAtomic(a) { a { balance mutate 1L } }
                }
            }
            assertEquals(0L, a.balance.value)
        }

    @Test fun nestedFrameIntroducingUnenrolledStoreThrowsUnderStrict() =
        runBlocking {
            val a = SuspendFrameAccount()
            val b = SuspendFrameAccount()
            val c = SuspendFrameAccount() // highest lockOrderKey — lock order fine; enrollment is not
            val e =
                assertFailsWith<UnenrolledStoreException> {
                    suspendAtomic(a, b) {
                        a { balance mutate 1L }
                        suspendAtomic(c) { c { balance mutate 3L } }
                    }
                }
            assertTrue("FramePolicy.AllowUnenrolled" in (e.message ?: ""), "message names the opt-out: ${e.message}")
            assertEquals(0L, a.balance.value, "outer frame rolled back")
            assertEquals(0L, c.balance.value, "nested frame never ran")
        }

    @Test fun allowUnenrolledNestedFrameCommitsIndependentlyOfOuterRollback() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount()
            val c = SuspendFrameAccount()
            val r =
                suspendAtomic(a, b, policy = FramePolicy.AllowUnenrolled) {
                    a { balance update { it - 30 } }
                    suspendAtomic(c) { c { balance mutate 7L } }
                    error("outer aborts after the nested frame committed")
                }
            assertIs<TransactionResult.Error>(r)
            assertEquals(100L, a.balance.value, "outer frame rolled back")
            assertEquals(7L, c.balance.value, "independent nested frame stayed committed")
        }
}

class SuspendActionAtomicInteropTest {
    @Test fun suspendAtomicEnrollingTheHoldingStoreInsideSuspendActionFailsFast() =
        runBlocking {
            val a = SuspendFrameAccount()
            val r =
                a.suspendAction {
                    // Pre-F4 this threw kotlinx's raw "already locked by the
                    // specified owner" ISE (or deadlocked across coroutines).
                    val e = assertFailsWith<FrameInteropException> { suspendAtomic(a) { } }
                    assertTrue("Hoist" in (e.message ?: ""), "message teaches the hoist: ${e.message}")
                    balance mutate 1L
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(1L, a.balance.value)
        }

    @Test fun disjointSuspendAtomicInsideSuspendActionStillWorks() =
        runBlocking {
            val a = SuspendFrameAccount()
            val b = SuspendFrameAccount()
            val c = SuspendFrameAccount()
            val r =
                a.suspendAction {
                    val inner =
                        suspendAtomic(b, c) {
                            b { balance mutate 10L }
                            c { balance mutate 20L }
                        }
                    assertIs<TransactionResult.Success<*>>(inner)
                    balance mutate 1L
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(1L, a.balance.value)
            assertEquals(10L, b.balance.value)
            assertEquals(20L, c.balance.value)
        }

    @Test fun suspendActionOnAParticipantInsideSuspendAtomicStillSavepoints() =
        runBlocking {
            // Regression guard: the F4 pseudo-frame must not change the real
            // frame's savepoint path for enrolled participants.
            val a = SuspendFrameAccount()
            val b = SuspendFrameAccount()
            val r =
                suspendAtomic(a, b) {
                    a.suspendAction { balance mutate 5L }
                    b { balance mutate 6L }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(5L, a.balance.value)
            assertEquals(6L, b.balance.value)
        }
}

class SuspendFrameMiddlewareTest {
    @Test fun middlewareSeesFrameTransactionsWithASharedFrameId() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            val log = mutableListOf<String>()
            a.middlewares(FrameRecordingMiddleware("a", log))
            b.middlewares(FrameRecordingMiddleware("b", log))

            val r =
                suspendAtomic(a, b) {
                    a { balance mutate 70L }
                    b { balance mutate 30L }
                }
            assertIs<TransactionResult.Success<*>>(r)
            val frameId = assertNotNull(r.transaction.frameId)
            assertTrue(frameId.startsWith("suspendAtomic-"))
            assertEquals(
                listOf(
                    "a:started:$frameId",
                    "b:started:$frameId",
                    "a:completed:$frameId",
                    "b:completed:$frameId",
                ),
                log,
            )
        }

    @Test fun completedThrowOnTheLastStoreRollsBackEveryStore() =
        runBlocking {
            val a = SuspendFrameAccount(initial = 100)
            val b = SuspendFrameAccount(initial = 0)
            b.middlewares(
                object : Middleware<SuspendFrameAccount>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<SuspendFrameAccount>) {
                        error("validation failed on b")
                    }
                },
            )
            val r =
                suspendAtomic(a, b) {
                    a { balance mutate 70L }
                    b { balance mutate 30L }
                }
            assertIs<TransactionResult.Error>(r)
            assertEquals(100L, a.balance.value, "a rolled back even though its completed hook already ran")
            assertEquals(0L, b.balance.value)
        }
}

class SuspendFrameConservationTest {
    @Test fun randomConcurrentSuspendTransfersConserveTheTotalBalance() =
        runBlocking {
            val stores = List(3) { SuspendFrameAccount(initial = 1_000) }
            val workers = 4
            val perWorker = 150
            val jobs =
                List(workers) { w ->
                    async(Dispatchers.Default) {
                        repeat(perWorker) { i ->
                            val from = stores[(w + i) % stores.size]
                            val to = stores[(w + i + 1) % stores.size]
                            if (from === to) return@repeat
                            val amount = (i % 13).toLong()
                            suspendAtomic(from, to) {
                                from { balance update { it - amount } }
                                if (i % 19 == 0) error("simulated mid-transfer failure")
                                to { balance update { it + amount } }
                            }
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(3_000L, stores.sumOf { it.balance.value }, "cross-store invariant holds at quiescence")
        }
}

private class ThrowingSuspendBridge<T : Any> : Bridge<T> {
    override fun observe(observer: (T) -> Unit): Disposable = Disposable { }

    override fun publish(value: T): Boolean = throw RuntimeException("bridge refused")
}

class SuspendAtomicCommitFailureNamingTest {
    @Test fun commitFailureNamesTheFailingStore() =
        runBlocking {
            // The store's bridge publish throws during suspendingCommit; the
            // frame's Error must name it even though `transaction` is roots.last (F7).
            val a = SuspendFrameAccount(initial = 0)
            a { balance bridge ThrowingSuspendBridge() }
            val r = suspendAtomic(a) { a { balance mutate 1L } }
            assertIs<TransactionResult.Error>(r)
            val message = r.exception.message ?: ""
            assertTrue(
                "Commit failed for ${a.frameIdentity()}" in message,
                "commit-failure error should name the failing store, was: $message",
            )
        }
}
