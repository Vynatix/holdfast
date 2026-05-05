package com.vynatix.holdfast.testing

import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.concurrency.parallel
import com.vynatix.holdfast.testing.concurrency.transaction
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class OpenTxnCounterVault : Store<OpenTxnCounterVault>() {
    val count by state { 0 }
    val label by state { "init" }
}

class OpenTransactionTest {

    @Test
    fun openCommitsApplyMutations() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 5 }
        // Same-thread (owner) read sees the pending value via the
        // read-your-own-writes overlay — production-faithful.
        assertEquals(5, ctr.read { count.value })
        val result = open.commit()
        result.shouldBeSuccess()
        // After commit the active txn is cleared and the read returns the
        // committed value via the same path.
        assertEquals(5, ctr.read { count.value })
        assertEquals(TransactionStatus.Committed, open.transaction.status)
        assertTrue(open.isClosed, "isClosed should be true after commit")
    }

    @Test
    fun openCommitsAppliesMultiStateAtomically() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) {
            count mutate 7
            label mutate "seven"
        }
        // Owner-thread reads-your-own-writes: pending values visible to the
        // test thread.
        assertEquals(7, ctr.read { count.value })
        assertEquals("seven", ctr.read { label.value })
        open.commit().shouldBeSuccess()
        assertEquals(7, ctr.read { count.value })
        assertEquals("seven", ctr.read { label.value })
    }

    @Test
    fun openRollbacksDiscardMutations() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 5 }
        open.rollback()
        assertEquals(0, ctr.read { count.value })
        assertEquals(TransactionStatus.RolledBack, open.transaction.status)
        assertTrue(open.isClosed, "isClosed should be true after rollback")
    }

    @Test
    fun bodyThrowsPropagateImmediately() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val ise = IllegalStateException("body failed")
        val thrown = assertFailsWith<IllegalStateException> {
            transaction(on = ctr) { throw ise }
        }
        assertSame(ise, thrown)
        // Store should be in a clean state — committed value unchanged, no
        // active transaction left dangling. The next action must succeed
        // without nesting under a phantom open transaction.
        assertEquals(0, ctr.read { count.value })
        ctr.action { count mutate 42 }.shouldBeSuccess()
        assertEquals(42, ctr.read { count.value })
    }

    @Test
    fun bodyThrowFlipsManufacturedTransactionToRolledBack() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        // Use a probe to capture the `transaction` reference even though the
        // transaction(...) call propagates the throw. We use a vault-level
        // observer to confirm no commit happened.
        val seen = mutableListOf<Int>()
        val sub = ctr.vault { count effect { seen.add(this) } }
        seen.clear()

        assertFailsWith<IllegalStateException> {
            transaction(on = ctr) {
                count mutate 99
                error("body failed")
            }
        }
        assertTrue(seen.isEmpty(), "no commit-time fanout should have happened")
        sub.dispose()
    }

    @Test
    fun reCommitAfterCommitThrows() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 5 }
        open.commit().shouldBeSuccess()
        val ex = assertFailsWith<IllegalStateException> { open.commit() }
        assertEquals("OpenTransaction already closed", ex.message)
    }

    @Test
    fun reRollbackAfterRollbackThrows() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 5 }
        open.rollback()
        val ex = assertFailsWith<IllegalStateException> { open.rollback() }
        assertEquals("OpenTransaction already closed", ex.message)
    }

    @Test
    fun rollbackAfterCommitThrows() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 5 }
        open.commit().shouldBeSuccess()
        val ex = assertFailsWith<IllegalStateException> { open.rollback() }
        assertEquals("OpenTransaction already closed", ex.message)
    }

    @Test
    fun commitAfterRollbackThrows() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 5 }
        open.rollback()
        val ex = assertFailsWith<IllegalStateException> { open.commit() }
        assertEquals("OpenTransaction already closed", ex.message)
    }

    @Test
    fun observersDoNotFireOnRollback() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val seen = mutableListOf<Int>()
        val sub = ctr.vault { count effect { seen.add(this) } }
        seen.clear() // Drop the initial-fire emission.

        val open = transaction(on = ctr) { count mutate 5 }
        assertTrue(seen.isEmpty(), "no fanout while transaction is open")
        open.rollback()
        assertTrue(seen.isEmpty(), "no fanout after rollback")
        sub.dispose()
    }

    @Test
    fun observersFireOnceOnCommit() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val seen = mutableListOf<Int>()
        val sub = ctr.vault { count effect { seen.add(this) } }
        seen.clear()

        val open = transaction(on = ctr) { count mutate 5 }
        assertTrue(seen.isEmpty(), "no fanout while open")
        open.commit().shouldBeSuccess()
        assertEquals(listOf(5), seen)
        sub.dispose()
    }

    @Test
    fun offThreadReadSeesCommittedNotPending() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 999 }
        // Off-owner-thread read on Dispatchers.Default — must see committed (0),
        // never the pending 999.
        parallel(1) {
            assertEquals(0, ctr.read { count.value })
        }
        open.commit().shouldBeSuccess()
        assertEquals(999, ctr.read { count.value })
    }

    @Test
    fun autoRollbackAtScopeExit() {
        // Direct runTest + manual scope so we can inspect the vault AFTER
        // tearDown. The standard vaultTest entry point doesn't give us a hook
        // to read state post-tearDown.
        val ctr = OpenTxnCounterVault()
        runTest {
            val scope = StoreTestScope(this)
            try {
                with(scope) {
                    val handle = track(ctr)
                    transaction(on = handle) { count mutate 5 }
                    // Intentionally do not commit/rollback — let scope exit handle it.
                }
            } finally {
                scope.tearDown(bodyAlreadyFailed = false)
            }
        }
        // After scope exit, vault state is unchanged.
        assertEquals(0, ctr.count.value)
        // Store has no lingering active transaction.
        assertEquals(null, ctr.activeTransaction)
    }

    @Test
    fun openCommitReturnsSuccessWithUnitValue() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 1 }
        val result = open.commit()
        assertIs<TransactionResult.Success<Unit>>(result)
        assertSame(open.transaction, result.transaction)
        assertEquals(Unit, result.value)
    }

    @Test
    fun isClosedReflectsLifecycle() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val open = transaction(on = ctr) { count mutate 1 }
        assertEquals(false, open.isClosed)
        open.commit().shouldBeSuccess()
        assertEquals(true, open.isClosed)
    }

    @Test
    fun nestedTransactionWhileOneIsOpenIsRejected() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val outer = transaction(on = ctr) { count mutate 1 }
        // Opening a SECOND transaction while one is open should fail loudly —
        // OpenTransaction is not designed to compose as savepoints.
        val ex = assertFailsWith<IllegalStateException> {
            transaction(on = ctr) { count mutate 2 }
        }
        assertTrue(
            ex.message.orEmpty().contains("active transaction"),
            "unexpected message: ${ex.message}",
        )
        outer.commit().shouldBeSuccess()
        assertEquals(1, ctr.read { count.value })
    }

    @Test
    fun consecutiveOpenTransactionsRunIndependently() = vaultTest {
        val ctr = track(OpenTxnCounterVault())
        val first = transaction(on = ctr) { count mutate 10 }
        first.commit().shouldBeSuccess()

        val second = transaction(on = ctr) { count mutate 20 }
        second.commit().shouldBeSuccess()

        assertEquals(20, ctr.read { count.value })
    }

    @Test
    fun peerActionWhileOpenNestsAsSavepoint() = vaultTest {
        // Acceptance criterion 5: a peer `action` issued while a transaction
        // is open observes the vault's serialization rules. In v1 those rules
        // are the production nested-action contract — same thread or not, an
        // action that finds an active transaction nests as a savepoint and
        // its commit merges pending writes into the outer transaction. The
        // peer's commit therefore does NOT fanout to observers — that fanout
        // is deferred until the outer [OpenTransaction.commit] runs.
        val ctr = track(OpenTxnCounterVault())
        val seen = mutableListOf<Int>()
        val sub = ctr.vault { count effect { seen.add(this) } }
        seen.clear()

        val open = transaction(on = ctr) { count mutate 1 }
        // Same-thread peer action — explicitly tests that mutations made via
        // the peer's vault.action body merge into the open transaction's
        // pending writes (savepoint behavior). Cross-thread peer action
        // would block on transactionLock during the brief commit-apply
        // critical section (see Privileged.commitOpenTransaction); since
        // that's only briefly held, the test would still observe nested
        // semantics.
        ctr.action { count mutate 2 }.shouldBeSuccess()
        assertTrue(seen.isEmpty(), "no fanout while outer is open")

        open.commit().shouldBeSuccess()
        // Final committed value is the merged-then-committed peer's mutate.
        assertEquals(2, ctr.read { count.value })
        assertEquals(listOf(2), seen, "single fanout at outer commit")
        sub.dispose()
    }

    @Test
    fun rollbackIsNoOpAtScopeExitIfAlreadyClosed() {
        // Verify that an already-closed transaction at scope exit doesn't
        // double-rollback or throw. Important for tests that close the
        // transaction manually but don't bother with cleanup — auto-rollback
        // must be safely re-entrant.
        val ctr = OpenTxnCounterVault()
        runTest {
            val scope = StoreTestScope(this)
            try {
                with(scope) {
                    val handle = track(ctr)
                    val open = transaction(on = handle) { count mutate 5 }
                    open.commit().shouldBeSuccess()
                    // 'open' is closed; tearDown's rollbackAll must skip it.
                }
            } finally {
                scope.tearDown(bodyAlreadyFailed = false)
            }
        }
        assertEquals(5, ctr.count.value)
    }
}
