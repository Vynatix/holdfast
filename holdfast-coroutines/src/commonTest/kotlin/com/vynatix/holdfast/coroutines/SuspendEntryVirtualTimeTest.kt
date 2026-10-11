package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class VirtualTimeLeft : Store<VirtualTimeLeft>() {
    val n by state { 0 }
}

private class VirtualTimeRight : Store<VirtualTimeRight>() {
    val m by state { 0 }
}

/**
 * A suspending entry's body runs on the caller's dispatcher clock: `delay` and
 * `withTimeout` inside it take their timer from the context's interceptor,
 * and the entry's own context machinery — a `ThreadContextElement` on
 * JVM/Android, the slot-carrying interceptor on iOS/wasmJs — must leave that
 * timer the dispatcher's. Under `runTest` the body's waits then advance the
 * virtual clock; they used to run on real time on iOS and wasmJs, where the
 * carrier had replaced the dispatcher as the interceptor without forwarding
 * its timer.
 */
class SuspendEntryVirtualTimeTest {
    @Test fun aDelayInASuspendActionBodyRunsOnTheDispatchersClock() =
        runTest {
            val store = VirtualTimeLeft()
            store
                .suspendAction {
                    delay(1_000)
                    n mutate 1
                }.getOrThrow()
            assertEquals(1_000, currentTime, "the body's delay advanced the test dispatcher's virtual time")
            assertEquals(1, store.n.value)
            store.dispose()
        }

    @Test fun aTimeoutInASuspendActionBodyRunsOnTheDispatchersClock() =
        runTest {
            val store = VirtualTimeLeft()
            var timedOut: Unit? = Unit
            store.suspendAction { timedOut = withTimeoutOrNull(1_000) { awaitCancellation() } }.getOrThrow()
            assertNull(timedOut, "the timeout fired")
            assertEquals(1_000, currentTime, "the timeout ran on the test dispatcher's virtual time")
            store.dispose()
        }

    @Test fun aDelayInASuspendAtomicBodyRunsOnTheDispatchersClock() =
        runTest {
            val a = VirtualTimeLeft()
            val b = VirtualTimeRight()
            suspendAtomic(a, b) {
                delay(1_000)
                a { n mutate 1 }
                b { m mutate 2 }
            }.getOrThrow()
            assertEquals(1_000, currentTime, "the frame body's delay advanced the test dispatcher's virtual time")
            assertEquals(1, a.n.value)
            assertEquals(2, b.m.value)
            a.dispose()
            b.dispose()
        }
}
