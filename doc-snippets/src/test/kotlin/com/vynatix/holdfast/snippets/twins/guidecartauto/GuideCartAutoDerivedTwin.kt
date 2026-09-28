// Twin of GUIDE.md §9.6 "auto-recomputed derived" idiom. Compile-only.
package com.vynatix.holdfast.snippets.twins.guidecartauto

import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.derived

// Scaffold: the recipe's domain types, named but not defined by the doc.
typealias Money = Long

data class Line(val price: Money, val qty: Int)

// DOC-SNIPPET holdfast/GUIDE.md#17
class CartStore : Store<CartStore>() {
    val items by state { emptyList<Line>() }
    private val totalAndSub = derived(items) { items.value.sumOf { it.price * it.qty } }
    val total: State<Money> get() = totalAndSub.first
}
// DOC-SNIPPET-END
