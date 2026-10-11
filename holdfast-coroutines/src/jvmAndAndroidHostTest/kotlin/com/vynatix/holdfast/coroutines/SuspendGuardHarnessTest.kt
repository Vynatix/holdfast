@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSuspendInFlightException
import com.vynatix.holdfast.testing.storeTest
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class HarnessGuardStore : Store<HarnessGuardStore>() {
    val count by state { 0L }
}

/**
 * Issue 003 — the `storeTest { }` harness surfaces the guard's throw RAW:
 * the throw comes from the serializer acquire, before any transaction (and
 * so any `TransactionResult.Error`) exists, so a tracked handle's teardown
 * must see nothing to consume — no unconsumed-Error failure — while the
 * exception itself reaches the test's own `catch`.
 */
@OptIn(StoreInternalApi::class)
class SuspendGuardHarnessTest {
    @Test
    fun `storeTest surfaces the guard throw raw with no unconsumed-Error interaction`() =
        storeTest {
            val s = HarnessGuardStore()
            track(s)
            s.asyncSerializer =
                MutexSerializer(
                    storeName = "HarnessGuardStore",
                    store = s,
                    budget = 150.milliseconds,
                )

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker =
                Thread {
                    runBlocking {
                        s.suspendAction<HarnessGuardStore, Unit> {
                            count mutate 1L
                            entered.countDown()
                            release.await()
                        }
                    }
                }
            worker.isDaemon = true
            worker.name = "suspend-guard-harness-holder"
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the suspending body never started")

            // Raw, not a TransactionResult.Error the handle would have to
            // consume; if teardown had an unconsumed-Error complaint, the
            // storeTest block itself would fail below this line.
            val outcome = runCatching { s action { count mutate count.value + 10L } }
            assertNull(outcome.getOrNull())
            assertIs<StoreSuspendInFlightException>(outcome.exceptionOrNull())

            release.countDown()
            worker.join(10_000)
            assertTrue(worker.isAlive.not(), "the holder thread never finished")
        }
}
