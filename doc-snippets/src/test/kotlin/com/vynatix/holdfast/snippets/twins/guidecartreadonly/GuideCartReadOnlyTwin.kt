// Twin of GUIDE.md §9.6 "read-only derived" idiom. Compile-only.
package com.vynatix.holdfast.snippets.twins.guidecartreadonly

import com.vynatix.holdfast.Store

// Scaffold: the recipe's domain types, named but not defined by the doc.
typealias Money = Long

data class Line(val price: Money, val qty: Int)

// DOC-SNIPPET holdfast/GUIDE.md#15
class CartStore : Store<CartStore>() {
    val items by state { emptyList<Line>() }
    fun total(): Money = items.value.sumOf { it.price * it.qty }
}
// DOC-SNIPPET-END
