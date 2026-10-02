@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

private class CrStore : Store<CrStore>() {
    val x by state { 0 }
}

private const val BRACKET_HELD_MS = 200L
private const val JOIN_MS = 10_000L

/**
 * `CaptureStats.retries` counts every listing or cut a capture had to run
 * again — the cut retried on an open write bracket (or a moved stamp) as
 * much as the listing retried for an entry that came to life — which is
 * what `StoreTree.internalCutRetryCount` reports.
 */
class CutRetryCountTest {
    @Test
    fun aCutRetriedOnAnOpenBracketCountsItsRetries() =
        completesWithin(30, "a plain cut against an open bracket") {
            val store = CrStore()
            val stats = CaptureStats()
            val captured = cutAgainstOpenBracket(store, previous = null, stats)
            assertEquals(0, captured[store.x])
            assertTrue(stats.retries > 0, "the cut retried while the bracket was open: ${stats.retries}")
            assertEquals(1, stats.captured)
        }

    @Test
    fun aReusingCutRetriedOnAnOpenBracketCountsItsRetries() =
        completesWithin(30, "a reusing cut against an open bracket") {
            val store = CrStore()
            val first = captureConsistent(listOf(store))[0]
            val stats = CaptureStats()
            val captured = cutAgainstOpenBracket(store, previous = first, stats)
            assertTrue(stats.retries > 0, "the cut retried while the bracket was open: ${stats.retries}")
            // A bracket, once closed, has moved the state's counter: the capture is read anew, not reused.
            assertNotSame(first, captured)
            assertEquals(1, stats.captured)
            assertEquals(0, stats.reused)
        }

    /**
     * Hold a write bracket open on [CrStore.x] — what a commit does around its
     * apply pass — while another thread captures [store]; the capture completes
     * only once the bracket closes.
     */
    private fun cutAgainstOpenBracket(
        store: CrStore,
        previous: StoreSnapshot?,
        stats: CaptureStats,
    ): StoreSnapshot {
        val x = store.x as MutableState<*>
        val result = AtomicReference<StoreSnapshot?>(null)
        openWriteBracket(listOf(x))
        val reader =
            daemon("cut-reader") {
                val snapshots = captureConsistent(listOf(store), SnapshotScope.All, previous?.let { listOf(it) }, stats)
                result.set(snapshots[0])
            }
        try {
            reader.join(BRACKET_HELD_MS)
            assertTrue(reader.isAlive, "a cut never completes while a write bracket is open")
        } finally {
            closeWriteBracket(listOf(x))
        }
        reader.join(JOIN_MS)
        assertFalse(reader.isAlive, "the cut completed once the bracket closed")
        return checkNotNull(result.get())
    }
}
