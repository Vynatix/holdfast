// Twin of GUIDE.md §3 "Quickstart". Compile-only.
package com.vynatix.holdfast.snippets.twins.guidequickstart

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect

@Suppress("unused")
private fun guideQuickstart() {
    // DOC-SNIPPET holdfast/GUIDE.md#1
    // 1. Define a holdfast.
    class TodoStore : Store<TodoStore>() {
        val items by state { emptyList<String>() }
        val draft by state { "" }
    }

    // 2. Create an instance.
    val holdfast = TodoStore()

    // 3. Subscribe.
    val sub = holdfast { items effect { println("items=$this") } }
    // fires immediately with the initial value: items=[]

    // 4. Mutate atomically.
    holdfast action {
        draft mutate "buy milk"
        items mutate items.value + draft.value
        draft mutate ""
    }
    // effect fires once per modified state, post-commit:
    //   items=[buy milk]

    // 5. Failed transactions roll back.
    holdfast action {
        items mutate listOf("never visible")
        error("simulated failure")
    }
    // effect fires zero times. items.value is still ["buy milk"].

    // 6. Cleanup.
    sub.dispose()
    // DOC-SNIPPET-END
}
