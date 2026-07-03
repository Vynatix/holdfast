// Twin of GUIDE.md §4.2 "action returns TransactionResult". Compile-only.
package com.vynatix.holdfast.snippets.twins.guideactionresult

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult

// Scaffold store with the states the fragment touches.
private class TodoStore : Store<TodoStore>() {
    val items by state { emptyList<String>() }
    val draft by state { "" }
}

// Scaffold stand-in for the logger the fragment names but does not define.
private fun log(error: Throwable) {}

@Suppress("unused")
private fun actionReturnsATypedTransactionResult() {
    val holdfast = TodoStore()
    // DOC-SNIPPET holdfast/GUIDE.md#3
    val result: TransactionResult<Unit> = holdfast action {
        items mutate listOf("a", "b")
        draft mutate "drafted"
    }
    when (result) {
        is TransactionResult.Success -> {}
        is TransactionResult.Error   -> log(result.exception)
    }
    // DOC-SNIPPET-END
}
