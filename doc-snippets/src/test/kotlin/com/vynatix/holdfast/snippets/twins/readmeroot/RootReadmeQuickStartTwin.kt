// Twin of the root README quick start. Executes the block and asserts the
// output the doc claims in its inline comments.
package com.vynatix.holdfast.snippets.twins.readmeroot

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.onError
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals

// Scaffold: the quick start names an error handler without defining it. The
// happy path never reaches it; reaching it is a test failure.
private fun handle(exception: Throwable): Nothing =
    throw AssertionError("quick start must not reach the error branch", exception)

class RootReadmeQuickStartTwin {
    @Test
    fun quickStartExecutesWithClaimedOutput() {
        val printed = capturePrintln {
            // DOC-SNIPPET README.md#0
            class CounterStore : Store<CounterStore>() {
                val count by state { 0 }
                val label by state { "init" }
            }

            val counter = CounterStore()
            val sub = counter { count effect { println("count=$this") } }   // count=0

            val result = counter action {
                count update { it + 1 }
                label mutate "ready"
                "transitioned to ${count.value}"
            }
            when (result) {
                is TransactionResult.Success -> println(result.value)        // "transitioned to 1"
                is TransactionResult.Error   -> handle(result.exception)
            }

            // Failed transactions roll back atomically — observers never fire.
            // Don't drop the result: surface the failure.
            val failed = counter action {
                count mutate 99
                error("simulated")
            }
            failed.onError { println("rolled back: ${it.exception.message}") }   // rolled back: simulated
            // DOC-SNIPPET-END
        }
        assertEquals(
            listOf(
                "count=0",                // initial effect fire on subscribe
                "count=1",                // commit fanout of the successful action
                "transitioned to 1",      // Success branch prints the body's value
                "rolled back: simulated", // onError surfaces the failed transaction
            ),
            printed,
        )
    }
}
