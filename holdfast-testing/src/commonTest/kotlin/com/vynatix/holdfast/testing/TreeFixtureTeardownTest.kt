@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.tree.Root
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class TdLeafStore : Store<TdLeafStore>() {
    val n by state { 0 }
}

private class TdRoot : Root("td") {
    val a = TdLeafStore()
    val b = TdLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
}

/** Vetoes every `completed` while [armed], with a message that quotes no value. */
private class TdVeto<V : Store<V>> : Middleware<V>() {
    var armed = false

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        if (armed) error("rejected by policy")
    }
}

/** The tree fixture's teardown: what the reset does with a held leaf, and how failures are reported. */
class TreeFixtureTeardownTest {
    /**
     * The teardown reset is one frame over every leaf, taken through each
     * leaf's serializer. A leaf a parked `suspendAction` still holds (un-joined
     * work is not waited for) must skip the reset: waiting would spin on the
     * test thread — the only thread that could resume the body and release
     * the leaf. The body runs on its own thread so a regression fails this
     * test instead of hanging it.
     */
    @Test
    fun teardownSkipsTheResetWhenAParkedSuspendActionHoldsALeaf() {
        val root = TdRoot()
        val gate = CompletableDeferred<Unit>()
        val finished =
            runBlocking {
                val run =
                    CoroutineScope(Dispatchers.Default).launch {
                        storeTest {
                            trackTree(root)
                            root.a action { n mutate 5 }
                            backgroundScope.launch { root.a.suspendAction { gate.await() } }
                            yield() // the body parks inside the leaf and still holds it when teardown runs
                        }
                    }
                withTimeoutOrNull(20.seconds) { run.join() }
            }
        assertNotNull(finished, "teardown hung: the tree reset waited for a leaf a parked suspendAction holds")
        assertEquals(5, root.a.n.value, "the reset was skipped, so the held leaf keeps the body's value")
        gate.complete(Unit)
    }

    @Test
    fun aVetoedResetAndAnUnconsumedErrorAreReportedTogether() {
        val root = TdRoot()
        val veto = TdVeto<TdLeafStore>().also { root.b.middlewares(it) }
        val failure =
            assertFailsWith<AssertionError> {
                storeTest {
                    val tree = trackTree(root)
                    tree.handle(root.a).action { error("left unconsumed") }
                    root.b action { n mutate 1 }
                    veto.armed = true
                }
            }
        val message = failure.message!!
        assertContains(message, "1 unconsumed TransactionResult.Error")
        assertContains(message, "left unconsumed")
        assertContains(message, "could not reset")
        assertContains(message, "leaf 'b'")
        assertTrue(
            message.indexOf("unconsumed") < message.indexOf("could not reset"),
            "the unconsumed errors are reported first, then the reset",
        )
        veto.armed = false
    }
}
