// Twin of GUIDE.md §9.6 "stored derived" idiom. Compile-only.
package com.vynatix.holdfast.snippets.twins.guidecartstored

import com.vynatix.holdfast.Store

// Scaffold: the recipe's domain types, named but not defined by the doc.
typealias Money = Long

data class Line(val price: Money, val qty: Int)

val Long.Companion.Zero: Money get() = 0L

// DOC-SNIPPET holdfast/GUIDE.md#16
class CartStore : Store<CartStore>() {
    val items by state { emptyList<Line>() }
    val total by state { Money.Zero }
    fun add(line: Line) = action {
        items mutate items.value + line
        total mutate items.value.sumOf { it.price * it.qty }
    }
}
// DOC-SNIPPET-END
