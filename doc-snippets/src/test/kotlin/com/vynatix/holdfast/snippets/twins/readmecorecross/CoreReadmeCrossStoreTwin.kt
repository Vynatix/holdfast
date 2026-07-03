// Twin of holdfast/README.md's "Cross-store transactions" example. Executes
// the block and asserts the printed output and final balances the doc claims.
package com.vynatix.holdfast.snippets.twins.readmecorecross

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.onError
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreReadmeCrossStoreTwin {
    @Test
    fun crossStoreExampleExecutesWithClaimedOutput() {
        val printed = capturePrintln {
            // DOC-SNIPPET holdfast/README.md#1
            class AccountStore(initial: Long = 0) : Store<AccountStore>() {
                val balance by state { initial }
            }

            val accountA = AccountStore(initial = 100)
            val accountB = AccountStore()

            // Both stores commit together, or neither does.
            val transfer = atomic(accountA, accountB) {
                accountA.action { balance update { it - 30 } }
                accountB.action { balance update { it + 30 } }
                "transferred"                                    // body's value flows into Success
            }
            when (transfer) {
                is TransactionResult.Success -> println(transfer.value)   // transferred — A=70, B=30
                is TransactionResult.Error   -> println("rolled back: ${transfer.exception}")
            }

            // A throw anywhere in the body rolls back EVERY participant:
            // the debit below never commits because the credit leg failed.
            val failed = atomic(accountA, accountB) {
                accountA.action { balance update { it - 50 } }
                error("credit leg failed")
            }
            failed.onError { println("rolled back: ${it.exception.message}") }   // rolled back: credit leg failed
            // accountA.balance.value is still 70 — the staged debit was discarded.
            // DOC-SNIPPET-END
            assertEquals(70L, accountA.balance.value)
            assertEquals(30L, accountB.balance.value)
        }
        assertEquals(listOf("transferred", "rolled back: credit leg failed"), printed)
    }
}
