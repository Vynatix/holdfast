@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FrameAccount(
    initial: Long = 0,
) : Store<FrameAccount>() {
    val balance by state { initial }
}

private class FrameAudit : Store<FrameAudit>() {
    val entries by state { emptyList<String>() }
}

private class RecordingMiddleware<V : Store<V>>(
    private val label: String,
    private val log: MutableList<String>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "$label:started:${context.transaction.frameId}"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        log += "$label:completed:${context.transaction.frameId}"
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        log += "$label:error:${error.message}"
    }
}

class FrameEnrollmentTest {
    @Test fun unenrolledActionInsideFrameThrowsAndRollsBackEverything() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val c = FrameAccount(initial = 0)
        val e =
            assertFailsWith<UnenrolledStoreException> {
                atomic(a, b) {
                    a.action { balance update { it - 30 } }
                    c.action { balance update { it + 30 } }
                }
            }
        assertTrue("FrameAccount" in (e.message ?: ""), "message names the store: ${e.message}")
        assertTrue("FramePolicy.AllowUnenrolled" in (e.message ?: ""), "message names the opt-out")
        assertEquals(100L, a.balance.value, "enrolled store rolled back")
        assertEquals(0L, c.balance.value, "unenrolled store untouched")
    }

    @Test fun unenrolledBareMutateInsideFrameThrows() {
        val a = FrameAccount()
        val b = FrameAccount()
        val c = FrameAccount()
        val e =
            assertFailsWith<UnenrolledStoreException> {
                atomic(a, b) {
                    c { balance mutate 5L }
                }
            }
        assertTrue("via mutate" in (e.message ?: ""), "bare mutate is attributed to mutate, not the synthesized action: ${e.message}")
        assertEquals(0L, c.balance.value)
    }

    @OptIn(StoreInternalApi::class)
    @Test
    fun frameMessagesCarryInstanceIdentity() {
        val a = FrameAccount()
        val b = FrameAccount()
        val c = FrameAccount()
        val e =
            assertFailsWith<UnenrolledStoreException> {
                atomic(a, b) {
                    c.action { balance mutate 1L }
                }
            }
        val msg = e.message ?: ""
        assertTrue("FrameAccount#${c.lockOrderKey}" in msg, "offender rendered as SimpleName#lockOrderKey: $msg")
        assertTrue("FrameAccount#${a.lockOrderKey}" in msg, "participants rendered with instance identity: $msg")
        assertTrue("via action" in msg, "entry point named: $msg")
    }

    @Test fun unenrolledMutateViaEnclosingActionIsCaught() {
        // The sneakiest escape: c has an ACTIVE transaction from an enclosing
        // action, so `c.balance mutate` inside the frame body stages directly
        // into that outer transaction without passing through `action` — and
        // would commit with it no matter what the frame does.
        val a = FrameAccount()
        val b = FrameAccount()
        val c = FrameAccount()
        val outer =
            c.action {
                atomic(a, b) {
                    c { balance mutate 1L }
                }
            }
        assertIs<TransactionResult.Error>(outer)
        assertIs<UnenrolledStoreException>(outer.exception)
        assertEquals(0L, c.balance.value)
        assertEquals(0L, a.balance.value)
    }

    @Test fun allowUnenrolledReproducesIndependentSideTransaction() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val c = FrameAccount(initial = 0)
        val r =
            atomic(a, b, policy = FramePolicy.AllowUnenrolled) {
                a.action { balance update { it - 30 } }
                c.action { balance update { it + 1 } }
                error("frame aborts after the side transaction")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(100L, a.balance.value, "frame rolled back")
        assertEquals(1L, c.balance.value, "side transaction committed independently")
    }

    @Test fun readsOfUnenrolledStoresStayLegal() {
        val a = FrameAccount()
        val b = FrameAccount()
        val c = FrameAccount(initial = 42)
        val r =
            atomic(a, b) {
                val seed = c.balance.value
                a.action { balance mutate seed + 1 }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(43L, a.balance.value)
    }
}

class FrameEscalationTest {
    @Test fun innerActionErrorAbortsTheWholeFrame() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val r =
            atomic(a, b) {
                a.action { balance update { it - 30 } }
                b.action { error("boom") }
                "unreached"
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals("boom", r.exception.message)
        assertEquals(100L, a.balance.value, "store a rolled back with the frame")
        assertEquals(0L, b.balance.value)
    }

    @Test fun tolerateInnerErrorsRestoresCheckTheResultSemantics() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val r =
            atomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                a.action { balance update { it - 30 } }
                val inner = b.action { error("boom") }
                assertIs<TransactionResult.Error>(inner)
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(70L, a.balance.value, "frame committed despite tolerated inner error")
        assertEquals(0L, b.balance.value)
    }

    @Test fun contractViolationsEscalateEvenWhenInnerErrorsAreTolerated() {
        val a = FrameAccount()
        val b = FrameAccount()
        val c = FrameAccount()
        assertFailsWith<UnenrolledStoreException> {
            atomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                // The violation happens INSIDE an inner action, whose Error
                // result would otherwise be tolerated and silently dropped.
                a.action { c { balance mutate 1L } }
            }
        }
        assertEquals(0L, a.balance.value)
        assertEquals(0L, c.balance.value)
    }

    @Test fun combinedPolicyGrantsBothOptOuts() {
        val p = FramePolicy.AllowUnenrolled + FramePolicy.TolerateInnerErrors
        assertTrue(p.allowUnenrolled)
        assertTrue(p.tolerateInnerErrors)
        assertEquals(FramePolicy.Strict, FramePolicy.Strict + FramePolicy.Strict)
    }
}

class FrameFanoutTest {
    @Test fun observersMayWriteUnenrolledStoresDuringCommitFanout() {
        // Named regression for the enforcement window: the frame marker must be
        // cleared before observer fanout, so a post-commit reaction that writes
        // to a foreign store keeps working exactly as it did pre-enforcement.
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val audit = FrameAudit()
        val sub =
            a {
                balance effect {
                    if (this == 70L) audit.action { entries update { it + "a=70" } }
                }
            }
        val r =
            atomic(a, b) {
                a.action { balance update { it - 30 } }
                b.action { balance update { it + 30 } }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(listOf("a=70"), audit.entries.value)
        sub.dispose()
    }

    @Test fun observersOnTheOwnerThreadSeePendingSiblingValuesDuringFanout() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        var bValueDuringAFanout = -1L
        val sub =
            a {
                balance effect {
                    if (this == 70L) bValueDuringAFanout = b.balance.value
                }
            }
        atomic(a, b) {
            a.action { balance update { it - 30 } }
            b.action { balance update { it + 30 } }
        }
        // Commits apply sequentially in lock order, but reads from the frame's
        // owner thread go through the still-active root's pending writes
        // (read-your-own-writes during fanout) — so an observer on A checking
        // the cross-store invariant sees B's about-to-be-committed value, and
        // the invariant holds at every fanout point.
        assertEquals(30L, bValueDuringAFanout)
        assertEquals(30L, b.balance.value)
        sub.dispose()
    }
}

class FrameNestingTest {
    @Test fun outerRollbackDiscardsNestedFrameWrites() {
        // Regression: nested atomic used to COMMIT adopted roots at inner exit,
        // making the outer frame's rollback silently skip those writes.
        val a = FrameAccount(initial = 10)
        val b = FrameAccount(initial = 0)
        val r =
            atomic(a, b) {
                atomic(a) { a.action { balance update { it + 5 } } }
                error("outer aborts after nested frame committed")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(10L, a.balance.value, "nested frame's writes discarded by outer rollback")
    }

    @Test fun nestedFrameCommitMergesIntoOuterCommit() {
        val a = FrameAccount(initial = 1)
        val b = FrameAccount(initial = 2)
        val seenA = mutableListOf<Long>()
        val sub = a { balance effect { seenA.add(this) } }
        seenA.clear()
        val r =
            atomic(a, b) {
                atomic(a, b) {
                    a.action { balance update { it + 10 } }
                    b.action { balance update { it + 20 } }
                }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(11L, a.balance.value)
        assertEquals(22L, b.balance.value)
        assertEquals(listOf(11L), seenA, "observer fired once, at the OUTER frame's commit")
        sub.dispose()
    }

    @Test fun nestedFrameErrorEscalatesToTheOuterFrame() {
        val a = FrameAccount(initial = 1)
        val b = FrameAccount(initial = 2)
        val r =
            atomic(a, b) {
                a.action { balance update { it + 1 } }
                atomic(b) { error("inner frame boom") }
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals("inner frame boom", r.exception.message)
        assertEquals(1L, a.balance.value)
        assertEquals(2L, b.balance.value)
    }

    @Test fun nestedFrameLockOrderViolationFailsFast() {
        val a = FrameAccount() // lowest lockOrderKey
        val b = FrameAccount()
        val c = FrameAccount()
        val e =
            assertFailsWith<FrameLockOrderException> {
                atomic(b, c) {
                    atomic(a) { a.action { balance mutate 1L } }
                }
            }
        assertTrue("lockOrderKey" in (e.message ?: ""))
        assertEquals(0L, a.balance.value)
    }

    @Test fun nestedFrameIntroducingHigherKeyStoreIsAllowed() {
        val a = FrameAccount()
        val b = FrameAccount()
        val c = FrameAccount() // highest lockOrderKey — safe to introduce nested
        val r =
            atomic(a, b) {
                a.action { balance mutate 1L }
                atomic(c) { c.action { balance mutate 3L } }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1L, a.balance.value)
        assertEquals(3L, c.balance.value)
    }
}

class FrameMiddlewareTest {
    @Test fun middlewareSeesFrameTransactionsWithASharedFrameId() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val log = mutableListOf<String>()
        a.middlewares(RecordingMiddleware("a", log))
        b.middlewares(RecordingMiddleware("b", log))

        val r =
            atomic(a, b) {
                a { balance mutate 70L }
                b { balance mutate 30L }
            }
        assertIs<TransactionResult.Success<*>>(r)
        val frameId = assertNotNull(r.transaction.frameId)
        assertTrue(frameId.startsWith("atomic-"))
        assertEquals(
            listOf(
                "a:started:$frameId",
                "b:started:$frameId",
                "a:completed:$frameId",
                "b:completed:$frameId",
            ),
            log,
            "per-store middleware fires for the frame, correlated by one frameId",
        )
    }

    @Test fun completedThrowOnTheLastStoreRollsBackEveryStore() {
        val a = FrameAccount(initial = 100)
        val b = FrameAccount(initial = 0)
        val log = mutableListOf<String>()
        a.middlewares(RecordingMiddleware("a", log))
        b.middlewares(
            object : Middleware<FrameAccount>() {
                override fun onTransactionCompleted(context: MiddlewareContext<FrameAccount>) {
                    error("validation failed on b")
                }
            },
        )
        val r =
            atomic(a, b) {
                a { balance mutate 70L }
                b { balance mutate 30L }
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals("validation failed on b", r.exception.message)
        assertEquals(100L, a.balance.value, "a rolled back even though ITS completed hook already ran")
        assertEquals(0L, b.balance.value)
        assertTrue(log.any { it.startsWith("a:completed") }, "a's completed fired before the frame aborted")
        assertTrue(log.any { it.startsWith("a:error") }, "a's error hook fired on the frame rollback")
    }

    @Test fun startedThrowAbortsTheFrameBeforeTheBodyRuns() {
        val a = FrameAccount()
        val b = FrameAccount()
        b.middlewares(
            object : Middleware<FrameAccount>() {
                override fun onTransactionStarted(context: MiddlewareContext<FrameAccount>) {
                    error("b refuses to start")
                }
            },
        )
        var bodyRan = false
        val r =
            atomic(a, b) {
                bodyRan = true
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(false, bodyRan)
        assertEquals(0L, a.balance.value)
    }
}

class FrameObserverTest {
    @AfterTest fun cleanup() {
        FrameObservers.clear()
    }

    @Test fun frameObserverSeesStartAndCommitAsOneFrame() {
        val a = FrameAccount()
        val b = FrameAccount()
        val events = mutableListOf<String>()
        FrameObservers.register(
            object : FrameObserver {
                override fun onFrameStarted(
                    frameId: String,
                    participants: List<Store<*>>,
                ) {
                    events += "started:${participants.size}"
                }

                override fun onFrameCommitted(frameId: String) {
                    events += "committed"
                }

                override fun onFrameRolledBack(
                    frameId: String,
                    cause: Throwable,
                ) {
                    events += "rolledBack:${cause.message}"
                }
            },
        )
        atomic(a, b) {
            a { balance mutate 1L }
        }
        atomic(a, b) { error("boom") }
        assertEquals(listOf("started:2", "committed", "started:2", "rolledBack:boom"), events)
    }
}

class FrameConservationTest {
    @Test fun randomConcurrentTransfersConserveTheTotalBalance() =
        runBlocking {
            val stores = List(3) { FrameAccount(initial = 1_000) }
            val workers = 4
            val perWorker = 250
            val jobs =
                List(workers) { w ->
                    async(Dispatchers.Default) {
                        repeat(perWorker) { i ->
                            val from = stores[(w + i) % stores.size]
                            val to = stores[(w + i + 1) % stores.size]
                            if (from === to) return@repeat
                            val amount = (i % 17).toLong()
                            atomic(from, to) {
                                from.action { balance update { it - amount } }
                                if (i % 23 == 0) error("simulated mid-transfer failure")
                                to.action { balance update { it + amount } }
                            }
                        }
                    }
                }
            jobs.awaitAll()
            val total = stores.sumOf { it.balance.value }
            assertEquals(3_000L, total, "cross-store invariant holds at quiescence")
        }
}
