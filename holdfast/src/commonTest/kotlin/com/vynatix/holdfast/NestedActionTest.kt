package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class NestedTwoStateVault : Store<NestedTwoStateVault>() {
    val state1 by state { "initial1" }
    val state2 by state { "initial2" }
}

private class NestedSingleStateVault : Store<NestedSingleStateVault>() {
    val n by state { 0 }
    val m by state { "init" }
}

class NestedActionSavepointTest {
    @Test
    fun outerActionFailureRollsBackMutationsMadeAfterNestedAction() {
        val v = NestedTwoStateVault()

        val result =
            v action {
                v action { state1 mutate "inner-only" }
                state2 mutate "outer-mutation-after-inner"
                error("outer fails")
            }

        assertIs<TransactionResult.Error>(result)
        assertEquals(
            "initial2",
            v.state2.value,
            "outer's post-nested mutation of state2 must roll back when outer throws",
        )
    }

    @Test
    fun outerActionFailureRollsBackMutationsBeforeAndAfterNestedAction() {
        val v = NestedTwoStateVault()

        val result =
            v action {
                state1 mutate "outer-before-inner"
                v action { state2 mutate "inner" }
                state1 mutate "outer-after-inner"
                error("outer fails")
            }

        assertIs<TransactionResult.Error>(result)
        assertEquals(
            "initial1",
            v.state1.value,
            "state1 must roll back across both pre- and post-nested mutations",
        )
        assertEquals(
            "initial2",
            v.state2.value,
            "state2 must roll back even though it was only touched inside the nested action",
        )
    }

    @Test
    fun nestedActionMutationsAreDiscardedWhenOuterActionFails() {
        val v = NestedSingleStateVault()

        val result =
            v action {
                v action { n mutate 99 }
                error("outer fails")
            }

        assertIs<TransactionResult.Error>(result)
        assertEquals(
            0,
            v.n.value,
            "savepoint semantics: outer rollback must discard the nested action's commit",
        )
    }

    @Test
    fun nestedActionFailureReturnsErrorWithoutPropagatingToOuterByDefault() {
        val v = NestedTwoStateVault()
        val capturedNested = atomic<TransactionResult.Error?>(null)

        val outer =
            v action {
                state1 mutate "outer1"
                val nested =
                    v action {
                        state2 mutate "nested1"
                        error("nested fails")
                    }
                if (nested is TransactionResult.Error) capturedNested.value = nested
                // Outer continues; the nested's exception was caught by the nested action's
                // `try { … } catch (e: Throwable)`. To propagate, outer must re-throw explicitly.
            }

        assertIs<TransactionResult.Success<*>>(
            outer,
            "outer succeeds because nested's exception was caught and turned into an Error result",
        )
        val nestedErr = capturedNested.value
        assertNotNull(nestedErr, "nested returned an Error result that the outer captured")
        assertEquals("nested fails", nestedErr.exception.message)
        assertEquals("outer1", v.state1.value, "outer's mutation committed")
        assertEquals("initial2", v.state2.value, "nested's mutation discarded by its own rollback")
    }

    @Test
    fun nestedActionFailureCanPropagateToOuterIfOuterReThrows() {
        val v = NestedTwoStateVault()
        val outer =
            v action {
                state1 mutate "outer1"
                val nested =
                    v action {
                        state2 mutate "nested1"
                        error("nested fails")
                    }
                // Outer explicitly opts in to propagation.
                if (nested is TransactionResult.Error) throw nested.exception
                state1 mutate "never-reached"
            }
        assertIs<TransactionResult.Error>(outer)
        assertEquals("nested fails", outer.exception.message)
        assertEquals("initial1", v.state1.value, "outer's mutations rolled back when re-thrown")
        assertEquals("initial2", v.state2.value, "nested's mutations rolled back as expected")
    }

    @Test
    fun nestedActionThatFailsDoesNotPolluteOuterTransactionWhenOuterDoesNotPropagate() {
        val v = NestedTwoStateVault()
        val result =
            v action {
                state1 mutate "outer1"
                v action { state2 mutate "good" } // commits, merged
                val nested2 =
                    v action {
                        state2 mutate "bad"
                        error("nested-2 fails")
                    } // discards nested-2 pending
                assertIs<TransactionResult.Error>(nested2)
            }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("outer1", v.state1.value)
        assertEquals(
            "good",
            v.state2.value,
            "nested-2's failure must not pollute outer's view of state2",
        )
    }

    @Test
    fun threeLevelNestingCommitsAtomically() {
        val v = NestedTwoStateVault()
        v action {
            state1 mutate "level-1"
            v action {
                state1 mutate "level-2"
                v action {
                    state1 mutate "level-3"
                }
            }
        }
        assertEquals(
            "level-3",
            v.state1.value,
            "deepest level's commit wins after merge through all three levels",
        )
    }

    @Test
    fun threeLevelNestingRollsBackAllOnRootFailure() {
        val v = NestedTwoStateVault()
        val result =
            v action {
                state1 mutate "level-1"
                v action {
                    state1 mutate "level-2"
                    v action {
                        state1 mutate "level-3"
                    }
                }
                error("root fails")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(
            "initial1",
            v.state1.value,
            "root rollback discards merged pending from all three levels",
        )
    }

    @Test
    fun siblingNestedActionsMergeIntoOuterPendingInOrder() {
        val v = NestedTwoStateVault()
        v action {
            v action { state1 mutate "first-sibling" }
            v action { state1 mutate "second-sibling" }
        }
        assertEquals(
            "second-sibling",
            v.state1.value,
            "second sibling's merge overwrites the first's",
        )
    }

    @Test
    fun nestedActionParentReferenceIsTheOuterTransaction() {
        val v = NestedTwoStateVault()
        val outerCapture = atomic<Transaction?>(null)
        val innerCapture = atomic<Transaction?>(null)

        v action {
            outerCapture.value = v.activeTransaction
            v action {
                innerCapture.value = v.activeTransaction
            }
        }

        val outer = outerCapture.value
        val inner = innerCapture.value
        assertNotNull(outer)
        assertNotNull(inner)
        assertSame(
            outer,
            inner.parent,
            "inner transaction's parent must be the outer transaction",
        )
        assertNull(outer.parent, "top-level outer transaction has no parent")
    }
}

class SavepointReadYourOwnWritesTest {
    @Test
    fun nestedActionReadsOwnPendingWriteAfterMutate() {
        val v = NestedTwoStateVault()
        v action {
            v action {
                state1 mutate "from-nested"
                assertEquals(
                    "from-nested",
                    state1.value,
                    "nested action reads its own pending write via state.value",
                )
            }
        }
    }

    @Test
    fun nestedActionReadsOuterActionsPendingWriteOfDifferentState() {
        val v = NestedTwoStateVault()
        v action {
            state1 mutate "set-by-outer"
            v action {
                // state1 wasn't touched in nested; getter walks chain to outer's pending.
                assertEquals(
                    "set-by-outer",
                    state1.value,
                    "nested reads outer's pending via parent chain",
                )
            }
        }
    }

    @Test
    fun outerActionAfterNestedReturnsSeesNestedMergedPending() {
        val v = NestedTwoStateVault()
        v action {
            v action { state1 mutate "from-nested" }
            assertEquals(
                "from-nested",
                state1.value,
                "outer sees the nested's merged pending after the nested returns",
            )
        }
    }

    @Test
    fun nestedActionThatManuallyRollsBackHidesItsPendingFromOuter() {
        val v = NestedTwoStateVault()
        v action {
            val nestedResult =
                v action {
                    state2 mutate "in-rolled-back-nested"
                    v.activeTransaction!!.rollback()
                }
            // Action body returned; Store.action's commit is no-op (status RolledBack).
            // Store.action returns Success(txn) with status RolledBack.
            assertIs<TransactionResult.Success<*>>(nestedResult)
            assertEquals(
                "initial2",
                state2.value,
                "outer must not see the nested's manually-rolled-back pending",
            )
        }
        assertEquals("initial2", v.state2.value)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun readingFromOffOwnerThreadDuringNestedActionReturnsCommittedNotPending() {
        val ownerCtx = newSingleThreadContext("nested-owner")
        val readerCtx = newSingleThreadContext("nested-reader")
        try {
            val captured =
                runBlocking {
                    val v = NestedTwoStateVault()
                    v action { state1 mutate "committed" }

                    // Store.action's lambda is non-suspend; we coordinate via atomic flags
                    // and busy-wait so the lambda never tries to suspend.
                    val nestedReached = atomic(false)
                    val readerDone = atomic(false)
                    val seen = atomic<String?>(null)

                    val owner =
                        async(ownerCtx) {
                            v action {
                                state1 mutate "outer-pending"
                                v action {
                                    state1 mutate "nested-pending"
                                    nestedReached.value = true
                                    while (!readerDone.value) { /* spin */ }
                                }
                            }
                        }

                    val reader =
                        async(readerCtx) {
                            while (!nestedReached.value) { /* spin */ }
                            seen.value = v.state1.value
                            readerDone.value = true
                        }

                    owner.await()
                    reader.await()
                    seen.value
                }

            assertEquals(
                "committed",
                captured,
                "off-owner-thread reads must see the committed _value, not the pending writes",
            )
        } finally {
            ownerCtx.close()
            readerCtx.close()
        }
    }
}

class ActionInsideEffectTest {
    @Test
    fun effectThatTriggersNestedActionDoesNotBreakOuterTransactionRecording() {
        val v = NestedSingleStateVault()
        val d =
            v {
                n effect {
                    if (this == 1) {
                        v action { m mutate "nested-from-effect" }
                    }
                }
            }

        val result =
            v action {
                n mutate 1
                m mutate "outer-after-nested"
                error("rollback")
            }

        assertIs<TransactionResult.Error>(result)
        assertEquals(
            0,
            v.n.value,
            "n must roll back to 0; current=${v.n.value}",
        )
        assertEquals(
            "init",
            v.m.value,
            "m must roll back to 'init' even though an effect-triggered nested action ran",
        )
        d.dispose()
    }
}

/**
 * A nested action's commit merges its writes into the enclosing transaction's
 * buffers — unless that transaction was committed or rolled back by hand
 * meanwhile (issue #20, D16; PR #22 review): `Store.activeTransaction` (or a
 * middleware context) hands out the enclosing transaction, and a `commit()` on
 * it applies its own writes and closes its buffers. The merge used to land in
 * that closed buffer, the nested action returned Success, the enclosing
 * action's own commit was a no-op, and the nested write was gone without a
 * word — while the same `mutate` issued after the hand commit is refused. The
 * nested action must return Error instead, like every other write into a
 * finished transaction.
 */
class NestedActionIntoFinishedParentTest {
    /** The nested action failed to commit because its parent is finished: [fragment] names how. */
    private fun assertRefusedMerge(
        nested: TransactionResult<*>?,
        fragment: String,
    ) {
        val error = assertIs<TransactionResult.Error>(nested, "the nested action must not merge into a finished parent")
        val exception = assertIs<TransactionException>(error.exception)
        val cause = assertIs<IllegalStateException>(exception.cause)
        val message = assertNotNull(cause.message)
        assertTrue(fragment in message, "not the finished-transaction refusal: $message")
        assertTrue("Cannot merge nested transaction" in message, "unexpected: $message")
        assertEquals(TransactionStatus.Failed, error.transaction.status)
    }

    @Test
    fun nestedActionCommittingIntoAHandCommittedOuterReturnsError() {
        val v = NestedTwoStateVault()
        var nested: TransactionResult<*>? = null

        val outer =
            v action {
                val outerTxn = assertNotNull(v.activeTransaction)
                nested =
                    v action {
                        state2 mutate "x"
                        outerTxn.commit()
                    }
            }

        assertRefusedMerge(nested, "has already applied its writes")
        assertIs<TransactionResult.Success<*>>(outer, "the hand-committed outer's own commit is a no-op")
        assertEquals("initial2", v.state2.value, "the refused write never lands")
    }

    @Test
    fun nestedActionWhoseMiddlewareCommitsTheOuterReturnsError() {
        val v = NestedTwoStateVault()
        var outerTxn: Transaction? = null
        var innerTxn: Transaction? = null
        v.middlewares(
            object : Middleware<NestedTwoStateVault>() {
                // After the nested body, before its commit: finish the outer by hand.
                override fun onTransactionCompleted(context: MiddlewareContext<NestedTwoStateVault>) {
                    if (context.transaction === innerTxn) assertNotNull(outerTxn).commit()
                }
            },
        )
        var nested: TransactionResult<*>? = null

        v action {
            outerTxn = v.activeTransaction
            nested =
                v action {
                    innerTxn = v.activeTransaction
                    state2 mutate "x"
                }
        }

        assertRefusedMerge(nested, "has already applied its writes")
        assertEquals("initial2", v.state2.value)
    }

    @Test
    fun nestedActionCommittingIntoAHandRolledBackOuterReturnsError() {
        val v = NestedTwoStateVault()
        var nested: TransactionResult<*>? = null

        v action {
            state1 mutate "outer"
            val outerTxn = assertNotNull(v.activeTransaction)
            nested =
                v action {
                    state2 mutate "x"
                    outerTxn.rollback()
                }
        }

        assertRefusedMerge(nested, "has already been rolled back (status: RolledBack)")
        assertEquals("initial1", v.state1.value)
        assertEquals("initial2", v.state2.value)
    }

    /**
     * A closed ANCESTOR closes the whole chain: the innermost savepoint's
     * parent is still active, but the grandparent was rolled back by hand, so
     * the merge is refused and the message names the enclosing transaction.
     * The middle savepoint's own merge is then refused too, naming the
     * rolled-back transaction itself.
     */
    @Test
    fun nestedActionWhoseGrandparentWasHandRolledBackNamesTheEnclosingTransaction() {
        val v = NestedTwoStateVault()
        var innermost: TransactionResult<*>? = null
        var middle: TransactionResult<*>? = null

        v action {
            val outerTxn = assertNotNull(v.activeTransaction)
            middle =
                v action {
                    innermost =
                        v action {
                            state2 mutate "x"
                            outerTxn.rollback()
                        }
                }
        }

        assertRefusedMerge(innermost, "has already been rolled back (status: RolledBack of its enclosing transaction '")
        assertRefusedMerge(middle, "has already been rolled back (status: RolledBack)")
        assertEquals("initial2", v.state2.value)
    }

    /**
     * The frame composition: a participant an `atomic` shares with the
     * enclosing action is a savepoint whose merge runs in the frame's apply
     * pass (`applyFrameCommit`). Refused there — the enclosing transaction was
     * committed by hand from the frame body — the frame returns Error carrying
     * the refusal, while its fresh root has applied and fans out once; the
     * Failed savepoint is skipped by the frame's unwind (no second status
     * transition), and the enclosing action's own commit is a no-op.
     */
    @Test
    fun aFrameParticipantMergingIntoAHandCommittedEnclosingActionFailsTheFrame() {
        val a = NestedTwoStateVault()
        val b = NestedTwoStateVault()
        val fired = mutableListOf<String>()
        var initial = true
        val effect = b.state1.effect { if (initial) initial = false else fired += this }
        var frame: TransactionResult<*>? = null
        var savepoint: Transaction? = null
        var root: Transaction? = null

        val outer =
            a action {
                val outerTxn = assertNotNull(a.activeTransaction)
                frame =
                    atomic(a, b) {
                        a { state1 mutate "x" }
                        b { state1 mutate "y" }
                        savepoint = a.activeTransaction
                        root = b.activeTransaction
                        outerTxn.commit()
                    }
            }

        val error = assertIs<TransactionResult.Error>(frame, "the refused merge fails the frame")
        val refusal =
            generateSequence(error.exception) { it.cause }
                .filterIsInstance<IllegalStateException>()
                .firstOrNull()
        val message = assertNotNull(refusal?.message, "no refusal in the cause chain of ${error.exception}")
        assertTrue("Cannot merge nested transaction" in message, "unexpected: $message")
        assertTrue("has already applied its writes" in message, "unexpected: $message")
        assertEquals(TransactionStatus.Failed, assertNotNull(savepoint, "a's participant is a savepoint").status)
        assertEquals(TransactionStatus.Committed, assertNotNull(root, "b's participant is a fresh root").status)
        assertEquals("y", b.state1.value, "the fresh root applied")
        assertEquals(listOf("y"), fired, "b's observer fires once, from the frame's fanout")
        assertEquals("initial1", a.state1.value, "the refused write never lands")
        assertIs<TransactionResult.Success<*>>(outer, "the hand-committed outer's own commit is a no-op")
        effect.dispose()
    }
}
