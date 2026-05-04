package com.vynatix.vault

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

private class NestedTwoStateVault : Vault<NestedTwoStateVault>() {
    val state1 by state { "initial1" }
    val state2 by state { "initial2" }
}

private class NestedSingleStateVault : Vault<NestedSingleStateVault>() {
    val n by state { 0 }
    val m by state { "init" }
}

class NestedActionSavepointTest {

    @Test
    fun outerActionFailureRollsBackMutationsMadeAfterNestedAction() {
        val v = NestedTwoStateVault()

        val result = v action {
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

        val result = v action {
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

        val result = v action {
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

        val outer = v action {
            state1 mutate "outer1"
            val nested = v action {
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
        val outer = v action {
            state1 mutate "outer1"
            val nested = v action {
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
        val result = v action {
            state1 mutate "outer1"
            v action { state2 mutate "good" } // commits, merged
            val nested2 = v action {
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
        val result = v action {
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
            val nestedResult = v action {
                state2 mutate "in-rolled-back-nested"
                v.activeTransaction!!.rollback()
            }
            // Action body returned; Vault.action's commit is no-op (status RolledBack).
            // Vault.action returns Success(txn) with status RolledBack.
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
            val captured = runBlocking {
                val v = NestedTwoStateVault()
                v action { state1 mutate "committed" }

                // Vault.action's lambda is non-suspend; we coordinate via atomic flags
                // and busy-wait so the lambda never tries to suspend.
                val nestedReached = atomic(false)
                val readerDone = atomic(false)
                val seen = atomic<String?>(null)

                val owner = async(ownerCtx) {
                    v action {
                        state1 mutate "outer-pending"
                        v action {
                            state1 mutate "nested-pending"
                            nestedReached.value = true
                            while (!readerDone.value) { /* spin */ }
                        }
                    }
                }

                val reader = async(readerCtx) {
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
        val d = v {
            n effect {
                if (this == 1) {
                    v action { m mutate "nested-from-effect" }
                }
            }
        }

        val result = v action {
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
