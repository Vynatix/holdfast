// Twins of the root README's "An invariant enforced by a comment" section.
// Block #0 is the hand-written rollback the section replaces and block #1 is
// the Holdfast transfer; both execute. The last test proves the
// UnenrolledStoreException message the README quotes is the library's
// verbatim output, by reading the quoted block back out of README.md.
package com.vynatix.holdfast.snippets.twins.readmerootwhy

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.snippets.SnippetExtraction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

// Scaffold for block #0: the pre-Holdfast shape the README's grep finds.
private class LegacyAccounts(var balance: Long) {
    fun debit(amount: Long) {
        balance -= amount
    }

    fun refund(amount: Long) {
        balance += amount
    }
}

private class LegacyHistory(private val available: Boolean) {
    val entries = mutableListOf<String>()

    fun append(entry: String) {
        check(available) { "history unavailable" }
        entries += entry
    }
}

private fun handWrittenTransfer(
    accounts: LegacyAccounts,
    history: LegacyHistory,
    amount: Long,
    entry: String,
) {
    // DOC-SNIPPET README.md#0
    // balance and history must be updated together
    try {
        accounts.debit(amount)
        history.append(entry)
    } catch (e: Exception) {
        accounts.refund(amount)
    }
    // DOC-SNIPPET-END
}

// Scaffold for block #1 and the exception test: the stores the README names.
// Top-level private classes keep `simpleName` exactly as the message quotes it.
private class AccountStore : Store<AccountStore>() {
    val balance by state { 100L }
}

private class HistoryStore : Store<HistoryStore>() {
    val entries by state { emptyList<String>() }
}

private class BadgeStore : Store<BadgeStore>() {
    val unread by state { 0 }
}

class RootReadmeWhyTwin {
    @Test
    fun handWrittenRollbackCompensatesWhenTheSecondWriteFails() {
        val accounts = LegacyAccounts(balance = 100)
        val history = LegacyHistory(available = false)
        handWrittenTransfer(accounts, history, amount = 30, entry = "debit 30")
        assertEquals(100L, accounts.balance, "refund restored the debit")
        assertEquals(emptyList<String>(), history.entries)
    }

    @Test
    fun handWrittenRollbackAppliesBothWritesOnTheHappyPath() {
        val accounts = LegacyAccounts(balance = 100)
        val history = LegacyHistory(available = true)
        handWrittenTransfer(accounts, history, amount = 30, entry = "debit 30")
        assertEquals(70L, accounts.balance)
        assertEquals(listOf("debit 30"), history.entries)
    }

    @Test
    fun atomicTransferCommitsBothStores() {
        val accounts = AccountStore()
        val history = HistoryStore()
        val amount = 30L
        val entry = "debit 30"
        // DOC-SNIPPET README.md#1
        val result = atomic(accounts, history) {
            accounts.action { balance update { it - amount } }
            history.action { entries update { it + entry } }
            "transferred"
        }
        // DOC-SNIPPET-END
        assertIs<TransactionResult.Success<String>>(result)
        assertEquals("transferred", result.value)
        assertEquals(70L, accounts.balance.value)
        assertEquals(listOf("debit 30"), history.entries.value)
    }

    @Test
    fun forgettingToEnrollAStoreThrowsTheMessageTheReadmeQuotes() {
        // Construction order fixes the lock order, and the lock order is the
        // participant order the message prints: (AccountStore, HistoryStore).
        val accounts = AccountStore()
        val history = HistoryStore()
        val badges = BadgeStore()
        val thrown =
            assertFailsWith<UnenrolledStoreException> {
                atomic(accounts, history) {
                    accounts.action { balance update { it - 30L } }
                    badges.action { unread update { it + 1 } }
                }
            }
        assertEquals(readmeQuotedMessage(), thrown.message)
        assertEquals(100L, accounts.balance.value, "enrolled store rolled back")
        assertEquals(0, badges.unread.value, "unenrolled store untouched")
    }

    /**
     * The README wraps the message across lines inside a plain fence that
     * starts with the exception's simple name; joining the lines with single
     * spaces and dropping that prefix must give the message verbatim.
     */
    private fun readmeQuotedMessage(): String {
        val prefix = "UnenrolledStoreException: "
        val lines = File(SnippetExtraction.repoRoot, "README.md").readLines()
        val start = lines.indexOfFirst { it.startsWith(prefix) }
        if (start < 0) fail("README.md no longer quotes the UnenrolledStoreException message")
        val end = (start until lines.size).first { lines[it].trim() == "```" }
        return lines
            .subList(start, end)
            .joinToString(" ") { it.trim() }
            .removePrefix(prefix)
    }
}
