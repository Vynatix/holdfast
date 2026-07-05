package com.vynatix.holdfast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class TxTestVault : Store<TxTestVault>() {
    val count by state { 0 }
    val label by state { "initial" }
}

private class FiveStateVault : Store<FiveStateVault>() {
    val a by state { 0 }
    val b by state { 0 }
    val c by state { 0 }
    val d by state { 0 }
    val e by state { 0 }
}

private class PartialCommitVault : Store<PartialCommitVault>() {
    val ok by state { 0 }
    val bad by
        state(
            transformer =
                object : Transformer<Int> {
                    override fun set(value: Int): Int = value

                    override fun get(value: Int): Int = if (value != 0) error("bad get") else value
                },
        ) { 0 }
}

class CommitFailureMessageTest {
    @Test
    fun commitFailureNamesStateAndListsAppliedStates() {
        // P1-partial-commit: when a top-level commit apply throws mid-fanout, the
        // Error message names the failing state and reports the earlier states that
        // were already applied (and stay committed — rollback never un-applies them).
        val v = PartialCommitVault()
        val r =
            v action {
                ok mutate 1 // applies cleanly first
                bad mutate 1 // transformer.get throws during apply
            }
        val err = assertIs<TransactionResult.Error>(r)
        val msg = err.exception.message ?: ""
        assertTrue(msg.contains("'bad'"), "names the failing state: $msg")
        assertTrue(msg.contains("already applied"), "reports applied-anyway states: $msg")
        assertEquals(1, v.ok.value, "the earlier state stayed committed (partial commit is real)")
    }
}

class ActionLifecycleTest {
    @Test
    fun successfulActionReturnsSuccessWithCommittedTransaction() {
        val v = TxTestVault()
        val result = v action { count mutate 1 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(TransactionStatus.Committed, result.transaction.status)
    }

    @Test
    fun failingActionReturnsErrorWithRolledBackTransactionAndOriginalCause() {
        val v = TxTestVault()
        val result =
            v action {
                count mutate 1
                error("boom")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(TransactionStatus.RolledBack, result.transaction.status)
        assertEquals("boom", result.exception.message)
    }

    @Test
    fun failingActionRollsBackAllStateChangesToPreActionValues() {
        val v = TxTestVault()
        v action { count mutate 5 }

        val result =
            v action {
                count mutate 99
                label mutate "should-revert"
                error("intentional")
            }

        assertIs<TransactionResult.Error>(result)
        assertEquals(5, v.count.value, "count must roll back to its pre-action value (5)")
        assertEquals("initial", v.label.value, "label must roll back to its initializer default")
    }

    @Test
    fun emptyActionReturnsSuccessWithCommittedTransaction() {
        val v = TxTestVault()
        val result = v action { /* no mutations, no throws */ }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(TransactionStatus.Committed, result.transaction.status)
    }

    @Test
    fun actionThatCatchesItsOwnExceptionReturnsSuccess() {
        val v = TxTestVault()
        val result =
            v action {
                try {
                    count mutate 5
                    error("intentional but caught")
                } catch (_: Throwable) {
                    count mutate 10
                }
            }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(
            10,
            v.count.value,
            "caught-and-handled exception must not trigger rollback; mutate 10 wins",
        )
    }

    @Test
    fun actionTransactionIdIsNonEmpty() {
        val v = TxTestVault()
        val result = v action { count mutate 1 }
        assertIs<TransactionResult.Success<*>>(result)
        assertTrue(
            result.transaction.id.isNotEmpty(),
            "transaction id must be a non-empty string for diagnostic purposes",
        )
    }

    @Test
    fun actionThatThrowsErrorSubclassRollsBackLikeException() {
        val v = TxTestVault()
        val result =
            v action {
                count mutate 5
                throw AssertionError("simulated Error subclass")
            }
        assertIs<TransactionResult.Error>(result)
        assertIs<AssertionError>(result.exception)
        assertEquals(
            0,
            v.count.value,
            "Error (not just Exception) subclasses must also trigger rollback",
        )
    }

    @Test
    fun transactionEndTimeIsSetAfterCommit() {
        val v = TxTestVault()
        val result = v action { count mutate 1 }
        assertIs<TransactionResult.Success<*>>(result)
        val endTime = result.transaction.endTime
        assertNotNull(endTime, "endTime must be populated after commit")
        assertTrue(endTime > 0L, "endTime must be a positive epoch-millis; got $endTime")
    }

    @Test
    fun transactionEndTimeIsSetAfterRollback() {
        val v = TxTestVault()
        val result =
            v action {
                count mutate 1
                error("rollback")
            }
        assertIs<TransactionResult.Error>(result)
        assertNotNull(result.transaction.endTime, "endTime must be populated after rollback")
    }
}

class MultiMutationInActionTest {
    @Test
    fun successfulActionWithRepeatedMutationsAppliesLastWriteWins() {
        val v = TxTestVault()
        v action {
            count mutate 1
            count mutate 2
            count mutate 3
        }
        assertEquals(3, v.count.value)
    }

    @Test
    fun failingActionWithRepeatedMutationsRevertsToValueBeforeAction() {
        val v = TxTestVault()
        v action { count mutate 7 }
        assertEquals(7, v.count.value)

        v action {
            count mutate 100
            count mutate 200
            count mutate 300
            error("rollback")
        }

        assertEquals(
            7,
            v.count.value,
            "rollback must restore value before the action, not any intermediate write",
        )
    }

    @Test
    fun actionMutatingFiveStatesAtomicallyCommitsOrRollsBackTogether() {
        val v = FiveStateVault()

        // Failing action: all five mutations roll back together.
        val rollback =
            v action {
                a mutate 1
                b mutate 2
                c mutate 3
                d mutate 4
                e mutate 5
                error("rollback")
            }
        assertIs<TransactionResult.Error>(rollback)
        assertEquals(0, v.a.value)
        assertEquals(0, v.b.value)
        assertEquals(0, v.c.value)
        assertEquals(0, v.d.value)
        assertEquals(0, v.e.value)

        // Successful action: all five commit together.
        v action {
            a mutate 10
            b mutate 20
            c mutate 30
            d mutate 40
            e mutate 50
        }
        assertEquals(10, v.a.value)
        assertEquals(20, v.b.value)
        assertEquals(30, v.c.value)
        assertEquals(40, v.d.value)
        assertEquals(50, v.e.value)
    }
}

class MutateOutsideActionTest {
    @Test
    fun mutateOutsideActionWrapsInImplicitTransactionAndFiresMiddleware() {
        val v = TxTestVault()
        var middlewareInvocations = 0
        v.middlewares(
            object : Middleware<TxTestVault>() {
                override fun onTransactionStarted(context: MiddlewareContext<TxTestVault>) {
                    middlewareInvocations++
                }
            },
        )

        with(v) { count mutate 42 }

        assertEquals(42, v.count.value)
        assertEquals(
            1,
            middlewareInvocations,
            "mutate outside an action must wrap in an implicit transaction so middleware fires",
        )
    }

    @Test
    fun mutateOutsideActionAppliedValueIsObservableAfterCall() {
        val v = TxTestVault()
        with(v) { count mutate 99 }
        assertEquals(99, v.count.value)
        with(v) { label mutate "set-via-implicit-tx" }
        assertEquals("set-via-implicit-tx", v.label.value)
    }

    @Test
    fun mutateOutsideActionFiringObserverEvenWithEmptyMiddlewareList() {
        val v = TxTestVault()
        val seen = mutableListOf<Int>()
        val d = v { count effect { seen.add(this) } }
        seen.clear()

        with(v) { count mutate 42 }

        assertEquals(listOf(42), seen, "implicit txn must fire observers post-commit")
        d.dispose()
    }

    @Test
    fun mutateOutsideActionFromOffOwnerThreadAlsoWraps() =
        runBlocking {
            val v = TxTestVault()
            async(Dispatchers.Default) {
                with(v) { count mutate 7 }
            }.await()
            assertEquals(7, v.count.value)
        }

    @Test
    fun mutateInsideBareInvokeThrows() {
        // P1-invoke-nonatomic: a bare `store { }` opens no transaction; a direct
        // mutation there fails loudly instead of committing piecemeal.
        val v = TxTestVault()
        val ex =
            assertFailsWith<IllegalStateException> {
                v { count mutate 1 }
            }
        assertTrue(ex.message?.contains("bare") == true, "teaching message mentions bare invoke; was: ${ex.message}")
        // The context-only, non-mutating uses stay legal.
        val d = v { count effect { } }
        // And the action form works.
        v action { count mutate 5 }
        assertEquals(5, v.count.value)
        d.dispose()
    }

    @Test
    fun updateInsideBareInvokeThrows() {
        val v = TxTestVault()
        assertFailsWith<IllegalStateException> {
            v { count update { it + 1 } }
        }
    }
}

class TransactionStatusGuardsTest {
    @Test
    fun rollbackCalledOnAlreadyCommittedTransactionIsNoOp() {
        val v = TxTestVault()
        val result = v action { count mutate 5 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(5, v.count.value)

        runCatching { result.transaction.rollback() }

        assertEquals(
            5,
            v.count.value,
            "state must remain at 5; rollback on a Committed transaction must be a no-op",
        )
        assertEquals(
            TransactionStatus.Committed,
            result.transaction.status,
            "Committed status must be sticky against spurious rollback() calls",
        )
    }

    @Test
    fun commitCalledOnAlreadyRolledBackTransactionIsNoOp() {
        val v = TxTestVault()
        val result =
            v action {
                count mutate 5
                error("rollback")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(TransactionStatus.RolledBack, result.transaction.status)

        runCatching { result.transaction.commit() }

        assertEquals(
            TransactionStatus.RolledBack,
            result.transaction.status,
            "RolledBack status must be sticky against spurious commit() calls",
        )
    }

    @Test
    fun repeatedRollbackOnSameTransactionDoesNotReplayEffects() {
        val v = TxTestVault()
        v action { count mutate 1 }

        val seen = mutableListOf<Int>()
        val d = v { count effect { seen.add(this) } }
        seen.clear()

        val result =
            v action {
                count mutate 99
                error("force rollback")
            }
        assertIs<TransactionResult.Error>(result)
        val sizeAfterFirstRollback = seen.size
        runCatching { result.transaction.rollback() }
        val sizeAfterSecondRollback = seen.size

        assertEquals(
            sizeAfterFirstRollback,
            sizeAfterSecondRollback,
            "calling rollback() a second time must not re-fire effects; seen=$seen",
        )
        d.dispose()
    }

    @Test
    fun commitCalledTwiceOnAnAlreadyCommittedTransactionIsNoOpOnSecondCall() {
        val v = TxTestVault()
        val result = v action { count mutate 5 }
        assertIs<TransactionResult.Success<*>>(result)

        // Store.action already called commit(); second call hits the status guard.
        runCatching { result.transaction.commit() }

        assertEquals(5, v.count.value)
        assertEquals(TransactionStatus.Committed, result.transaction.status)
    }

    @Test
    fun mutateOnAlreadyCommittedTransactionThrowsIllegalStateException() {
        val v = TxTestVault()
        val result =
            v action {
                val txn = v.activeTransaction!!
                count mutate 1
                txn.commit() // status -> Committed (early manual commit)
                count mutate 2 // expected to throw
            }
        assertIs<TransactionResult.Error>(result)
        assertIs<IllegalStateException>(result.exception)
        assertTrue(
            result.exception.message?.contains("Committed") == true,
            "error message must mention the closed status; got: ${result.exception.message}",
        )
    }

    @Test
    fun mutateOnAlreadyRolledBackTransactionThrowsIllegalStateException() {
        val v = TxTestVault()
        val result =
            v action {
                val txn = v.activeTransaction!!
                count mutate 1
                txn.rollback() // status -> RolledBack
                count mutate 2 // expected to throw
            }
        assertIs<TransactionResult.Error>(result)
        assertIs<IllegalStateException>(result.exception)
        assertTrue(
            result.exception.message?.contains("RolledBack") == true,
            "error message must mention the closed status; got: ${result.exception.message}",
        )
    }
}

class NamedActionTest {
    @Test
    fun actionWithNameThreadsTheNameIntoTheTransactionId() {
        val v = TxTestVault()
        val result = v.action("apply-discount") { count mutate 1 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("apply-discount", result.transaction.id)
    }

    @Test
    fun unnamedActionFallsBackToLambdaDerivedId() {
        val v = TxTestVault()
        val result = v action { count mutate 1 }
        assertIs<TransactionResult.Success<*>>(result)
        // Not the explicit name — the lambda-class / random-UUID fallback.
        assertNotNull(result.transaction.id)
    }
}
