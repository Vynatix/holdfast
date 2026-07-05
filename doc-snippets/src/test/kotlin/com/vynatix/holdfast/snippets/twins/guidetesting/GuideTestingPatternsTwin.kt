// Twins of GUIDE.md §11 "Testing Patterns". These are real tests: the blocks
// declare @Test functions, so they compile AND run on jvmTest.
package com.vynatix.holdfast.snippets.twins.guidetesting

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Middleware.MiddlewareContext
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Scaffold: the store the patterns exercise.
class CounterStore : Store<CounterStore>() {
    val count by state { 0 }
}

class GuideTestingPatternsTwin {
    // DOC-SNIPPET holdfast/GUIDE.md#23
    @Test fun mutationFiresObserverOnce() {
        val v = CounterStore()
        val seen = mutableListOf<Int>()
        val sub = v { count effect { seen.add(this) } }
        seen.clear()
        v action { count mutate 5 }
        assertEquals(listOf(5), seen)
        sub.dispose()
    }
    // DOC-SNIPPET-END

    // DOC-SNIPPET holdfast/GUIDE.md#24
    @Test fun rolledBackMutationsAreInvisible() {
        val v = CounterStore()
        val seen = mutableListOf<Int>()
        val sub = v { count effect { seen.add(this) } }
        seen.clear()
        v action {
            count mutate 99
            error("rollback")
        }
        assertEquals(emptyList<Int>(), seen)
        assertEquals(0, v.count.value)
        sub.dispose()
    }
    // DOC-SNIPPET-END

    // DOC-SNIPPET holdfast/GUIDE.md#25
    @Test fun bareMutateFiresMiddleware() {
        val v = CounterStore()
        var calls = 0
        v.middlewares(object : Middleware<CounterStore>() {
            override fun onTransactionStarted(c: MiddlewareContext<CounterStore>) { calls++ }
        })
        with(v) { count mutate 42 } // standalone mutate via the store receiver
        assertEquals(1, calls)
    }
    // DOC-SNIPPET-END

    // DOC-SNIPPET holdfast/GUIDE.md#26
    @Test fun foreignStateRejected() {
        val a = CounterStore()
        val b = CounterStore()
        val foreign = a.count
        val r = b action { foreign mutate 99 }
        assertIs<TransactionResult.Error>(r)
        assertEquals(0, a.count.value)
    }
    // DOC-SNIPPET-END

    // DOC-SNIPPET holdfast/GUIDE.md#27
    @Test fun noLostUpdatesUnder8Threads() = runBlocking {
        val v = CounterStore()
        val workers = 8; val perWorker = 200
        coroutineScope {
            repeat(workers) {
                launch(Dispatchers.Default) {
                    repeat(perWorker) {
                        v action { count mutate count.value + 1 }
                    }
                }
            }
        }
        assertEquals(workers * perWorker, v.count.value)
    }
    // DOC-SNIPPET-END
}
