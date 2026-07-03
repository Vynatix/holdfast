// Twin of the root README's "Cross-store transactions" example. Compile-only.
package com.vynatix.holdfast.snippets.twins.readmerootcross

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic

// Scaffold: the account stores the example transfers between, named but not
// defined by the doc.
class AccountStore : Store<AccountStore>() {
    val balance by state { 0L }
}

@Suppress("unused", "UNUSED_VARIABLE")
private fun crossStoreTransfer(
    accountA: AccountStore,
    accountB: AccountStore,
    amount: Long,
) {
    // DOC-SNIPPET README.md#1
    val r = atomic(accountA, accountB) {
        accountA.action { balance update { it - amount } }
        accountB.action { balance update { it + amount } }
    }
    // DOC-SNIPPET-END
}
