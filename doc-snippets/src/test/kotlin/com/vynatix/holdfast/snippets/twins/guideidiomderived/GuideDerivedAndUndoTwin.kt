// Twins of GUIDE.md §14.10 "Auto-recomputed running total" and
// "Snapshot-and-restore for undo". Compile-only.
package com.vynatix.holdfast.snippets.twins.guideidiomderived

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot

// Scaffold: the idioms' domain types, named but not defined by the doc.
data class Item(val amount: Long)

private class LedgerStore : Store<LedgerStore>() {
    val items by state { emptyList<Item>() }
}

@Suppress("ClassName", "ktlint")
private object uiTotal {
    var value: Long = 0
}

@Suppress("unused", "UNUSED_VARIABLE")
private fun autoRecomputedRunningTotal() {
    val holdfast = LedgerStore()
    // DOC-SNIPPET holdfast/GUIDE.md#46
    val (total, dispose) = holdfast.derived(holdfast.items) { items.value.sumOf { it.amount } }
    val sub = holdfast { total effect { uiTotal.value = this } }
    // later: sub.dispose() ; dispose.dispose()
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun snapshotAndRestoreForUndo() {
    val holdfast = LedgerStore()
    // DOC-SNIPPET holdfast/GUIDE.md#47
    val undoStack = ArrayDeque<StoreSnapshot>()
    fun saveCheckpoint() { undoStack.addLast(holdfast.snapshot()) }
    fun undo() = undoStack.removeLastOrNull()?.let { holdfast.restore(it) }
    // DOC-SNIPPET-END
}
