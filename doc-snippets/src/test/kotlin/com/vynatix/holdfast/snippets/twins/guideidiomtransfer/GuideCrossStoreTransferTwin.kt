// Twin of GUIDE.md §14.10 "cross-store transfer" idiom. Compile-only.
package com.vynatix.holdfast.snippets.twins.guideidiomtransfer

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic

// Scaffold: the idiom's account store, named but not defined by the doc.
class AccountStore : Store<AccountStore>() {
    val balance by state { 0L }
}

// DOC-SNIPPET holdfast/GUIDE.md#45
fun AccountStore.transferTo(other: AccountStore, cents: Long) =
    atomic(this, other) {
        action { balance update { it - cents } }
        other.action { balance update { it + cents } }
    }
// DOC-SNIPPET-END
