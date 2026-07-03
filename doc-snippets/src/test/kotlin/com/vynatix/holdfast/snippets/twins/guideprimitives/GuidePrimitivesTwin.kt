// Twins of GUIDE.md §4 "The Seven Primitives" fragments. Compile-only.
package com.vynatix.holdfast.snippets.twins.guideprimitives

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Middleware.MiddlewareContext
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import java.io.File

// Scaffold store covering the states the §4 fragments touch.
private class PrimitivesStore : Store<PrimitivesStore>() {
    val count by state { 0 }
    val a by state { 0 }
    val b by state { 0 }
    val items by state { emptyList<String>() }
}

// Scaffold stand-ins for helpers the fragments name but do not define.
private fun log(message: String) {}

private object Json {
    fun encodeToString(value: List<String>): String = value.joinToString(",")
}

@Suppress("unused")
private fun nestedActionsFormSavepoints() {
    val holdfast = PrimitivesStore()
    // DOC-SNIPPET holdfast/GUIDE.md#4
    holdfast action {           // T_outer
        a mutate 1
        holdfast action {       // T_inner with parent = T_outer
            b mutate 2
        }                    // T_inner.commit merges {b->2} into T_outer
        error("outer fails") // discards both {a->1} and {b->2}
    }
    // a.value == initial, b.value == initial.
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun mutateBuffersIntoTheActiveTransaction() {
    val holdfast = PrimitivesStore()
    // DOC-SNIPPET holdfast/GUIDE.md#5
    holdfast action {
        count mutate count.value + 1
    }
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun effectSubscribesAndDisposes() {
    val holdfast = PrimitivesStore()
    // DOC-SNIPPET holdfast/GUIDE.md#6
    val sub = holdfast { count effect { println("count=$this") } }
    // → count=0   (initial)
    holdfast action { count mutate 5 }
    // → count=5
    sub.dispose()
    holdfast action { count mutate 6 }
    // (no output — disposed)
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun bridgeConnectsAnExternalSystem() {
    val holdfast = PrimitivesStore()
    // DOC-SNIPPET holdfast/GUIDE.md#7
    val persistence = object : Bridge<List<String>> {
        private val cb = mutableListOf<(List<String>) -> Unit>()
        override fun observe(observer: (List<String>) -> Unit): Disposable {
            cb.add(observer); return Disposable { cb.remove(observer) }
        }
        override fun publish(value: List<String>): Boolean {
            File("todos.json").writeText(Json.encodeToString(value)); return true
        }
    }
    holdfast { items bridge persistence }
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun middlewaresWrapEveryTransaction() {
    val holdfast = PrimitivesStore()
    // DOC-SNIPPET holdfast/GUIDE.md#8
    class Logger<V : Store<V>> : Middleware<V>() {
        override fun onTransactionStarted(c: MiddlewareContext<V>) =
            log("→ ${c.transaction.id}")
        override fun onTransactionCompleted(c: MiddlewareContext<V>) =
            log("✓ ${c.transaction.id}")
        override fun onTransactionError(c: MiddlewareContext<V>, e: Throwable) =
            log("✗ ${c.transaction.id}: $e")
    }

    holdfast.middlewares(Logger())
    // DOC-SNIPPET-END
}
