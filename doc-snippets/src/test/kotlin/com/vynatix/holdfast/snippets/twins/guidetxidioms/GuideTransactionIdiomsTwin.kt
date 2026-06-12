// Twins of GUIDE.md §9.7–§9.9 transaction idioms. Compile-only.
package com.vynatix.holdfast.snippets.twins.guidetxidioms

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult

// Scaffold store covering the states the idioms touch.
private class IdiomStore : Store<IdiomStore>() {
    val a by state { 0 }
    val b by state { 0 }
    val c by state { 0 }
    val x by state { 0 }
    val count by state { 0 }
}

// Scaffold stand-ins for §9.8's risk check, named but not defined by the doc.
private val BAD = "bad"

private fun riskCheck(): String = "ok"

@Suppress("unused")
private fun readYourOwnWritesInsideAnAction() {
    val holdfast = IdiomStore()
    // DOC-SNIPPET holdfast/GUIDE.md#18
    holdfast action {
        count mutate 5
        val seen = count.value          // == 5 on this thread, even pre-commit
        count mutate seen + 10          // == 15 stored
    }
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun savepointSemantics() {
    val holdfast = IdiomStore()
    // DOC-SNIPPET holdfast/GUIDE.md#19
    holdfast action {                      // T_outer
        a mutate 1
        val inner = holdfast action {      // T_inner (parent = T_outer)
            b mutate 2
        }
        // inner is TransactionResult.Success — pendingWrites {b->2} merged into T_outer
        if (riskCheck() == BAD) error("abort")
        c mutate 3
    }
    // On outer commit: a=1, b=2, c=3 — observers fire once each.
    // On error("abort"): nothing committed; observers see no change.
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun recoveringFromAnInnerError() {
    val holdfast = IdiomStore()
    // DOC-SNIPPET holdfast/GUIDE.md#20
    holdfast action {
        a mutate 1
        val inner = runCatching { holdfast action { b mutate 2; error("flake") } }
        // inner.exception is set; b's pending was discarded by inner's rollback.
        // Outer continues with a's pending intact.
        c mutate 3
    }
    // Final: a=1, c=3, b=initial.
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun idempotentRollback() {
    val holdfast = IdiomStore()
    // DOC-SNIPPET holdfast/GUIDE.md#21
    val res = holdfast action { x mutate 1 }
    // later, somewhere else:
    if (res is TransactionResult.Success) res.transaction.rollback()
    // no-op: already Committed.
    // DOC-SNIPPET-END
}
