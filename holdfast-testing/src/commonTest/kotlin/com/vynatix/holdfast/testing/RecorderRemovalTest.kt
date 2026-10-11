package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.concurrency.parallel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class RrStore : Store<RrStore>() {
    val n by state { 0 }
}

private class RrCounting : Middleware<RrStore>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<RrStore>) {
        started++
    }
}

/** Teardown removes the recorder alone, and `track` is one handle per store under concurrency. */
class RecorderRemovalTest {
    @Test
    fun teardownLeavesUserMiddlewareInstalledAndStillDetachesTheRecorder() {
        val store = RrStore()
        val before = RrCounting().also { store.middlewares(it) }
        var after: RrCounting? = null
        storeTest {
            val handle = track(store)
            after = RrCounting().also { store.middlewares(it) }
            handle.action { n mutate 1 }
            assertEquals(2, handle.timeline.count { it is TransactionEvent })
        }
        store action { n mutate 2 }
        assertEquals(2, before.started, "installed before track: still there")
        assertEquals(2, after!!.started, "installed after track: still there")
        storeTest {
            assertTrue(store.timeline.isEmpty(), "the recorder came off: a fresh scope starts empty")
        }
    }

    @Test
    fun concurrentTrackCallsYieldOneHandlePerStore() =
        storeTest {
            val store = RrStore()
            val handles = parallel(8) { track(store) }
            for (handle in handles) assertSame(handles.first(), handle)
            assertEquals(1, allTrackedHandles().size)
        }
}
