package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The iOS/wasmJs implementation of `withFanoutMarker`
 * ([withFanoutMarkerIntercepted]), exercised on the JVM, where it is otherwise
 * unused: those targets have no `ThreadContextElement` and cannot run tests on
 * this host. It must start the commit without a dispatch, keep the marker on
 * every resumption, and leave no marker behind for the caller or for other
 * coroutines sharing the thread.
 */
@OptIn(StoreInternalApi::class)
class FanoutMarkerInterceptedTest {
    private val roots = setOf(Transaction.createForExternal("marked", ownerThreadId = 0L))

    @Test fun theFirstSegmentRunsUndispatchedWithTheMarker() =
        runBlocking {
            var siblingRan = false
            // Queued on runBlocking's event loop: a dispatch before the block
            // starts would let it run first.
            launch { siblingRan = true }
            var seen: Set<Transaction>? = null
            var startedBeforeSibling = false
            withFanoutMarkerIntercepted(roots) {
                seen = FanoutMarkers.current()
                startedBeforeSibling = !siblingRan
            }
            assertSame(roots, seen)
            assertTrue(startedBeforeSibling, "the commit must start right away, not after a dispatch")
            assertNull(FanoutMarkers.current(), "no marker may outlive the block")
        }

    @Test fun theMarkerIsBackAfterEveryResumption() =
        runBlocking {
            val seen = mutableListOf<Set<Transaction>?>()
            withFanoutMarkerIntercepted(roots) {
                yield()
                seen += FanoutMarkers.current()
                withContext(Dispatchers.Default) { yield() }
                seen += FanoutMarkers.current()
            }
            assertEquals(listOf<Set<Transaction>?>(roots, roots), seen)
            assertNull(FanoutMarkers.current())
        }

    @Test fun anotherCoroutineOnTheSameThreadNeverSeesTheMarker() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val marked = async { withFanoutMarkerIntercepted(roots) { release.await() } }
            yield()
            // runBlocking is single-threaded: the bystander runs on the same
            // thread while the marked block is suspended.
            val bystanderSaw = FanoutMarkers.current()
            release.complete(Unit)
            marked.await()
            assertNull(bystanderSaw)
        }

    @Test fun aFailureInTheBlockPropagates() =
        runBlocking {
            val failure =
                assertFailsWith<IllegalStateException> {
                    withFanoutMarkerIntercepted(roots) {
                        yield()
                        error("commit failed")
                    }
                }
            assertEquals("commit failed", failure.message)
            assertNull(FanoutMarkers.current())
        }
}
