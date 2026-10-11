package com.vynatix.holdfast

import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class DefusalCounter : Store<DefusalCounter>() {
    val n by state { 0 }
    val m by state { 0 }

    /** A subclass mutation method: row 2 from inside bare invoke, row 3 called directly. */
    fun bump() {
        n mutate 1
    }
}

private sealed interface DefusalEvent {
    data object A : DefusalEvent
}

private class DefusalEmitter : EventfulStore<DefusalEmitter, DefusalEvent>() {
    val n by state { 0 }
}

private class DefusalDelegating private constructor(
    private val support: EventfulSupport<DefusalEvent>,
) : Store<DefusalDelegating>(),
    Eventful<DefusalEvent> by support {
    constructor() : this(EventfulSupport())

    init {
        support.bindStore(this)
    }

    val n by state { 0 }
}

/**
 * The issue-005 defusal matrix, as named rows: `mutate`/`update`/`emit`
 * inside a bare `store { }` invoke block throw a pinned teaching
 * [IllegalStateException] to the caller (A1), dynamically through subclass
 * methods too (A2); entries that own a transaction started inside the block
 * stay legal and stage (A3, the precedence rule); a bare invoke inside an
 * action stages (A4); the guard is per-thread and cross-store (A5),
 * depth-safe and restore-on-throw (A6); wiring and reads inside the block
 * stay legal (A8); the empirical partial-commit probe no longer reproduces
 * (A9); `invoke` on a disposed store throws "disposed", and disposed beats
 * teaching (A10). The emit foreign-transaction row (A7) needs a real second
 * thread and lives in `jvmAndAndroidHostTest`
 * (`BareInvokeEmitForeignTransactionTest`).
 */
class BareInvokeDefusalTest {
    // A1 ---------------------------------------------------------------------

    @Test
    fun a1MutateInsideBareInvokeThrowsToTheCaller() =
        storeTest {
            val v = track(DefusalCounter()).store
            val thrown = assertFailsWith<IllegalStateException> { v { n mutate 1 } }
            assertPinnedDefusal(thrown)
            assertEquals(0, v.n.value, "nothing staged, nothing committed")
        }

    @Test
    fun a1UpdateInsideBareInvokeThrowsToTheCaller() =
        storeTest {
            val v = track(DefusalCounter()).store
            val thrown = assertFailsWith<IllegalStateException> { v { n update { it + 1 } } }
            assertPinnedDefusal(thrown)
            assertEquals(0, v.n.value)
        }

    @Test
    fun a1EmitInsideBareInvokeThrowsToTheCaller() =
        storeTest {
            val v = track(DefusalEmitter()).store
            val thrown = assertFailsWith<IllegalStateException> { v { emit(DefusalEvent.A) } }
            assertPinnedDefusal(thrown)
        }

    @Test
    fun a1EmitInsideBareInvokeThrowsOnTheEventfulSupportDelegatePath() =
        storeTest {
            val v = track(DefusalDelegating()).store
            val thrown = assertFailsWith<IllegalStateException> { v { emit(DefusalEvent.A) } }
            assertPinnedDefusal(thrown)
        }

    // A2 ---------------------------------------------------------------------

    @Test
    fun a2SubclassMutationMethodFromBareInvokeThrowsDirectCallOneShots() =
        storeTest {
            val v = track(DefusalCounter()).store
            val thrown = assertFailsWith<IllegalStateException> { v { bump() } }
            assertPinnedDefusal(thrown)
            assertEquals(0, v.n.value, "the refused write committed nothing")
            // Row 3: the same method called directly synthesizes the atomic
            // one-shot action and succeeds.
            v.bump()
            assertEquals(1, v.n.value)
        }

    // A3 (row 4 — the precedence rule) ---------------------------------------

    @Test
    fun a3ActionStartedInsideBareInvokeIsLegalAndStages() =
        storeTest {
            val v = track(DefusalCounter()).store
            v {
                action { n mutate 1 }.shouldBeSuccess()
                action { n update { it + 1 } }.shouldBeSuccess()
            }
            assertEquals(2, v.n.value)
        }

    @Test
    fun a3SyncAtomicStagingViaBareInvokeStaysLegal() =
        storeTest {
            val v = track(DefusalCounter()).store
            atomic(v) {
                v {
                    n mutate 3
                    n update { it * 2 }
                }
            }.shouldBeSuccess()
            assertEquals(6, v.n.value)
        }

    // A4 (row 5) --------------------------------------------------------------

    @Test
    fun a4BareInvokeInsideActionIsLegalAndStages() =
        storeTest {
            val v = track(DefusalCounter()).store
            val result: TransactionResult<Unit> =
                v action {
                    m mutate 2
                    val stagedByInvoke: Unit = v { n mutate 1 }
                    stagedByInvoke
                }
            result.shouldBeSuccess()
            assertEquals(1, v.n.value)
            assertEquals(2, v.m.value)
        }

    // A5 ---------------------------------------------------------------------

    @Test
    fun a5CrossStoreMutationFromInsideBareInvokeThrows() =
        storeTest {
            val a = track(DefusalCounter()).store
            val b = track(DefusalCounter()).store
            val thrown = assertFailsWith<IllegalStateException> { a { b.bump() } }
            // The guard is per-thread and scope-free: the message does not
            // claim which store's invoke armed it — pinned as byte-identical
            // to the same-store refusal.
            assertEquals(BARE_INVOKE_MUTATION_MESSAGE, thrown.message)
            assertEquals(0, b.n.value)
            assertEquals(0, a.n.value)
        }

    // A6 ---------------------------------------------------------------------

    @Test
    fun a6InnerInvokeDoesNotDisarmTheOuterScope() =
        storeTest {
            val v = track(DefusalCounter()).store
            val thrown =
                assertFailsWith<IllegalStateException> {
                    v {
                        v { n.value } // inner block returns; its finally must not disarm the outer
                        n mutate 1
                    }
                }
            assertPinnedDefusal(thrown)
        }

    @Test
    fun a6GuardIsRestoredAfterAThrowingBody() =
        storeTest {
            val v = track(DefusalCounter()).store
            val thrown =
                assertFailsWith<IllegalStateException> {
                    v {
                        n.value // materialize, wiring-style read
                        error("boom")
                    }
                }
            assertEquals("boom", thrown.message, "the body's own throw is not relabeled")
            // Not leaked: a standalone write right after works (scope
            // function — row 3, not invoke).
            with(v) { n mutate 5 }
            assertEquals(5, v.n.value)
            // Still armed for the NEXT invoke: defusal is per-block.
            val again = assertFailsWith<IllegalStateException> { v { n mutate 1 } }
            assertPinnedDefusal(again)
        }

    // A8 ---------------------------------------------------------------------

    @Test
    fun a8WiringAndReadsInsideBareInvokeStayLegal() =
        storeTest {
            val v = track(DefusalCounter()).store
            var fired = 0
            val read = v { n.value }
            assertEquals(0, read, "plain reads stay legal")
            val sub = v { n effect { fired++ } }
            fired = 0
            v { n bridge null }
            val inbound = v { n observeFrom Observable<Int> { _ -> Disposable { } } }
            with(v) { n mutate 7 }
            assertEquals(7, v.n.value)
            assertEquals(1, fired, "wired effect still observes commits made outside the block")
            inbound.dispose()
            sub.dispose()
        }

    // A9 ---------------------------------------------------------------------

    @Test
    fun a9TheEmpiricalPartialCommitProbeNoLongerReproducesThroughBareInvoke() =
        storeTest {
            val v = track(DefusalCounter()).store
            // The probe (USABILITY-ANALYSIS Theme 2): a mid-block throw used
            // to leave the earlier one-shot commits applied. Now the FIRST
            // mutate refuses, so the body never reaches its own throw.
            val thrown =
                assertFailsWith<IllegalStateException> {
                    v {
                        n mutate 1
                        m mutate 2
                        error("probe-throw")
                    }
                }
            assertPinnedDefusal(thrown)
            assertEquals(0, v.n.value, "no partial commit: first write refused")
            assertEquals(0, v.m.value)
        }

    // A10 --------------------------------------------------------------------

    @Test
    fun a10InvokeOnADisposedStoreThrowsDisposed() =
        storeTest {
            val v = track(DefusalCounter()).store
            v.dispose()
            val thrown = assertFailsWith<IllegalStateException> { v { n.value } }
            assertTrue(
                thrown.message?.contains("disposed") == true,
                "expected 'disposed' in message; was: ${thrown.message}",
            )
        }

    @Test
    fun a10DisposedBeatsTeachingForAMutationReachedThroughBareInvoke() =
        storeTest {
            val a = track(DefusalCounter()).store
            val b = DefusalCounter()
            b.dispose()
            // mutate's disposed check (writableState) runs before the
            // synthesis-branch defusal, so the refusal is "disposed", not
            // the teaching message — even though a's invoke armed the guard.
            val thrown = assertFailsWith<IllegalStateException> { a { b.bump() } }
            assertTrue(
                thrown.message?.contains("disposed") == true,
                "expected 'disposed' in message; was: ${thrown.message}",
            )
            assertTrue(thrown.message != BARE_INVOKE_MUTATION_MESSAGE)
        }
}

/** The defusal message, pinned: exact match plus literal fragments. */
private fun assertPinnedDefusal(thrown: IllegalStateException) {
    assertEquals(BARE_INVOKE_MUTATION_MESSAGE, thrown.message)
    assertTrue(
        "bare `store { }`" in (thrown.message ?: ""),
        "message must name the bare block; was: ${thrown.message}",
    )
    assertTrue(
        "`store action { }`" in (thrown.message ?: ""),
        "message must prescribe action; was: ${thrown.message}",
    )
}
