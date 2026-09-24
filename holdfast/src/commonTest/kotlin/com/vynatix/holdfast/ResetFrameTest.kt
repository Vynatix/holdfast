@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Issue #20, R4 inside atomic(...) frames, and the #21 `reset(node)` shape:
// several stores reset inside one frame, one transaction per store.

private class Wallet : Store<Wallet>() {
    val balance by state { 100L }
    val note by state { "" }
}

private class Ledger : Store<Ledger>() {
    val entries by state { emptyList<String>() }
    val count by state { entries.value.size }
}

/** `audit`'s initializer fails while [failInit] is set. */
private class FlakyWallet : Store<FlakyWallet>() {
    var failInit = false
    val balance by state { 100L }
    val audit by state {
        check(!failInit) { "audit initializer fails" }
        "clean"
    }
}

private class Outsider : Store<Outsider>() {
    val flag by state { false }
}

/** Records every transaction a store's middleware sees, with its frame id. */
private class FrameLog<V : Store<V>>(
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
        log += "$label:error:${context.transaction.frameId}"
    }
}

private fun dirty(
    wallet: Wallet,
    ledger: Ledger,
) {
    wallet action {
        balance mutate 40L
        note mutate "spent"
    }
    ledger action {
        entries mutate listOf("coffee", "rent")
        count mutate 2
    }
}

class ResetFrameTest {
    @Test fun aResetInsideAFrameCommitsWithTheFrame() {
        val wallet = Wallet()
        val ledger = Ledger()
        dirty(wallet, ledger)

        val r =
            atomic(wallet, ledger) {
                wallet.reset().getOrThrow()
                ledger.action { entries update { it + "refund" } }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(100L, wallet.balance.value)
        assertEquals("", wallet.note.value)
        assertEquals(listOf("coffee", "rent", "refund"), ledger.entries.value)
    }

    @Test fun aResetInsideAFrameRollsBackWithTheFrame() {
        val wallet = Wallet()
        val ledger = Ledger()
        dirty(wallet, ledger)
        val fired = mutableListOf<Long>()
        val sub = wallet.balance effect { fired += this }
        fired.clear()

        val r =
            atomic(wallet, ledger) {
                wallet.reset().getOrThrow()
                assertEquals(100L, wallet.balance.value, "the frame reads the reset value")
                error("abort")
            }
        sub.dispose()
        assertIs<TransactionResult.Error>(r)
        assertEquals(40L, wallet.balance.value, "the frame's rollback discarded the reset")
        assertEquals("spent", wallet.note.value)
        assertEquals(emptyList(), fired)
    }

    @Test fun aFailingResetEscalatesAndRollsTheFrameBack() {
        val wallet = FlakyWallet()
        val ledger = Ledger()
        wallet action {
            balance mutate 40L
            audit mutate "dirty"
        }
        ledger action { entries mutate listOf("coffee") }
        wallet.failInit = true

        val r =
            atomic(wallet, ledger) {
                ledger.action { entries mutate emptyList() }
                wallet.reset()
            }
        assertIs<TransactionResult.Error>(r, "the reset's Error escalated out of the frame body")
        assertEquals("audit initializer fails", r.exception.message)
        assertEquals(listOf("coffee"), ledger.entries.value, "every participant rolled back")
        assertEquals(40L, wallet.balance.value, "balance's reset, staged before audit threw, rolled back")
    }

    @Test fun stagingResetsIntoFrameRootsIsOneTransactionPerStore() {
        val wallet = Wallet()
        val ledger = Ledger()
        dirty(wallet, ledger)
        val log = mutableListOf<String>()
        wallet.middlewares(FrameLog("wallet", log))
        ledger.middlewares(FrameLog("ledger", log))
        val balances = mutableListOf<Long>()
        val counts = mutableListOf<Int>()
        val subs = listOf(wallet.balance effect { balances += this }, ledger.count effect { counts += this })
        balances.clear()
        counts.clear()

        // The #21 reset(node) shape: materialize every leaf outside the frame,
        // then stage each leaf's reset into its own frame root.
        wallet.materializeDeclaredStates()
        ledger.materializeDeclaredStates()
        val r =
            atomic(wallet, ledger) {
                wallet.stageResetOfDeclaredStates(assertNotNull(wallet.activeTransaction))
                ledger.stageResetOfDeclaredStates(assertNotNull(ledger.activeTransaction))
            }
        subs.forEach { it.dispose() }

        assertIs<TransactionResult.Success<*>>(r)
        val frameId = assertNotNull(r.transaction.frameId)
        assertEquals(
            listOf(
                "wallet:started:$frameId",
                "ledger:started:$frameId",
                "wallet:completed:$frameId",
                "ledger:completed:$frameId",
            ),
            log,
            "each store saw exactly one transaction: its frame root, no savepoint",
        )
        assertEquals(100L, wallet.balance.value)
        assertEquals("", wallet.note.value)
        assertEquals(emptyList(), ledger.entries.value)
        assertEquals(0, ledger.count.value, "count read the reset entries, not the committed ones")
        assertEquals(listOf(100L), balances, "one fire per changed state")
        assertEquals(listOf(0), counts)
    }

    @Test fun stagingResetsIntoFrameRootsRollsBackTogether() {
        val wallet = Wallet()
        val ledger = Ledger()
        dirty(wallet, ledger)
        wallet.materializeDeclaredStates()
        ledger.materializeDeclaredStates()

        val r =
            atomic(wallet, ledger) {
                wallet.stageResetOfDeclaredStates(assertNotNull(wallet.activeTransaction))
                ledger.stageResetOfDeclaredStates(assertNotNull(ledger.activeTransaction))
                error("abort")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(40L, wallet.balance.value)
        assertEquals(listOf("coffee", "rent"), ledger.entries.value)
        assertEquals(2, ledger.count.value)
    }

    @Test fun stagingIntoATransactionThatIsNotTheStoresActiveOneIsRefused() {
        val wallet = Wallet()
        val ledger = Ledger()
        wallet.materializeDeclaredStates()
        val r =
            atomic(wallet, ledger) {
                // ledger's root is not wallet's active transaction.
                wallet.stageResetOfDeclaredStates(assertNotNull(ledger.activeTransaction))
            }
        assertIs<TransactionResult.Error>(r)
        assertIs<IllegalStateException>(r.exception)
    }

    @Test fun resettingAStoreTheFrameDoesNotEnrollThrows() {
        val wallet = Wallet()
        val outsider = Outsider()
        outsider action { flag mutate true }
        assertFailsWith<UnenrolledStoreException> {
            atomic(wallet) { outsider.reset() }
        }
        assertTrue(outsider.flag.value, "the unenrolled store was not reset")
    }
}
