// Twin of GUIDE.md §9.6 "auto-recomputed derived" idiom. Compile-only.
package com.vynatix.holdfast.snippets.twins.guidecartauto

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect

// Scaffold: the recipe's domain types, named but not defined by the doc.
typealias Money = Long

data class Line(val price: Money, val qty: Int)

val Long.Companion.Zero: Money get() = 0L

@Suppress("unused")
private class AutoCartStore : Store<AutoCartStore>() {
    val items by state { emptyList<Line>() }
    val total by state { Money.Zero }

    // DOC-SNIPPET holdfast/GUIDE.md#17
    init {
        items effect {
            val current = this   // the effect's payload: the committed List<Line>
            action { total mutate current.sumOf { it.price * it.qty } }
        }
    }
    // DOC-SNIPPET-END
}
