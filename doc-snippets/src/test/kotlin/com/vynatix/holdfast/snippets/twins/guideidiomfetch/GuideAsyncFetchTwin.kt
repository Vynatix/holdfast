// Twin of GUIDE.md §14.10 "Async transactional fetch". Compile-only.
package com.vynatix.holdfast.snippets.twins.guideidiomfetch

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.suspendAction

// Scaffold: the idiom's domain types, named but not defined by the doc.
enum class Status { Loading, Loaded }

private class FeedStore : Store<FeedStore>() {
    val status by state { Status.Loaded }
    val items by state { emptyList<String>() }
}

@Suppress("ClassName")
private object api {
    suspend fun fetch(): List<String> = emptyList()
}

@Suppress("unused", "UNUSED_VARIABLE")
private suspend fun asyncTransactionalFetch() {
    val holdfast = FeedStore()
    // DOC-SNIPPET holdfast/GUIDE.md#48
    val r = holdfast.suspendAction {
        status mutate Status.Loading
        val data = api.fetch()                  // suspending I/O
        items mutate (items.value + data)
        status mutate Status.Loaded
        data
    }
    // DOC-SNIPPET-END
}
