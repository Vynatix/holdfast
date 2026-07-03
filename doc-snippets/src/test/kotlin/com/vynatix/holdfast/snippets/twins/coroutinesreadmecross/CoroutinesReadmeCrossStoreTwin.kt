// Twin of holdfast-coroutines/README.md's cross-store frame example.
// Compile-only.
package com.vynatix.holdfast.snippets.twins.coroutinesreadmecross

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic

// Scaffold: the account stores the example transfers between, named but not
// defined by the doc.
class AccountStore : Store<AccountStore>() {
    val balance by state { 0L }
}

@Suppress("unused", "UNUSED_VARIABLE")
private suspend fun crossStoreFrame(
    accountA: AccountStore,
    accountB: AccountStore,
    amount: Long,
) {
    // DOC-SNIPPET holdfast-coroutines/README.md#5
    val r = suspendAtomic(accountA, accountB) {
        accountA { balance update { it - amount } }   // stages into A's frame root
        accountB.suspendAction {                      // joins the frame as a savepoint
            balance update { it + amount }
        }
    }
    // DOC-SNIPPET-END
}
