@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.derivedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, the work a suspending entry runs once it has released its store:
// suspendAction's post-commit drain (SuspendAction.kt), the settle of the
// outermost entry (SettleAmbientContext.kt settlingInOwnScope, core
// SettleScope.settle), a nested suspending entry joining the carried scope
// (joiningCarriedScope), and suspendDerived's launched recompute
// (SuspendDerived.kt). A fatal error a recompute throws there propagates out
// of the entry that ran it — after the entry's own commit stood — and never
// reaches an uncaughtObserverHandler; one the body threw outranks it.

private class FssHost : Store<FssHost>() {
    val h by state { 0 }
}

private class FssSource : Store<FssSource>() {
    val src by state { 0 }
}

/** A store whose `scope` is [testScope]: suspendDerived launches its recomputes there. */
private class FssScoped(
    private val testScope: CoroutineScope,
) : Store<FssScoped>() {
    override val scope: CoroutineScope get() = testScope
    val src by state { 0 }
}

/** [src]'s value, or [armed] thrown while it is set and [src] is 1. */
private class Throwing1 {
    @Volatile var armed: Throwable? = null

    fun of(src: Int): Int {
        if (src == 1) armed?.let { throw it }
        return src
    }
}

class FatalSuspendSettleTest {
    @Test fun aDrainedRecomputeStackOverflowPropagatesAfterTheSuspendActionsCommit() =
        runBlocking<Unit> {
            val host = FssHost()
            val source = FssSource()
            val reportedHost = host.recordedReports()
            val reportedSource = source.recordedReports()
            val fatal = StackOverflowError()
            val compute = Throwing1()
            compute.armed = fatal
            // Legacy derived: a source commit on another store while the host is
            // held hands the recompute to the host's post-commit queue.
            val (mirror, subscription) = host.derived(source.src) { compute.of(source.src.value) }

            val thrown =
                assertPropagates<StackOverflowError> {
                    host.suspendAction {
                        h mutate 5
                        source action { src mutate 1 }
                    }
                }
            compute.armed = null

            assertSame(fatal, thrown, "the drained recompute's StackOverflowError, unreported")
            assertEquals(5, host.h.value, "the suspendAction's own commit applied before the drain")
            assertEquals(1, source.src.value)
            assertEquals(0, mirror.value, "the recompute rolled back")
            assertTrue(reportedHost.isEmpty() && reportedSource.isEmpty(), "never reported: $reportedHost")
            assertNothingInstalledOn(host)
            assertNothingLeftOnThisThread()
            assertIs<TransactionResult.Success<*>>(source action { src mutate 2 })
            assertEquals(2, mirror.value, "the derived recomputes again")
            assertIs<TransactionResult.Success<*>>(host.suspendAction { h mutate 6 })
            subscription.dispose()
        }

    @Test fun aBodyStackOverflowOutranksADrainedRecomputesStackOverflow() =
        runBlocking<Unit> {
            val host = FssHost()
            val source = FssSource()
            val reported = host.recordedReports()
            val bodyFatal = StackOverflowError()
            val computeFatal = StackOverflowError()
            val compute = Throwing1()
            compute.armed = computeFatal
            val (_, subscription) = host.derived(source.src) { compute.of(source.src.value) }

            val thrown =
                assertPropagates<StackOverflowError> {
                    host.suspendAction {
                        h mutate 5
                        source action { src mutate 1 }
                        throw bodyFatal
                    }
                }
            compute.armed = null

            assertSame(bodyFatal, thrown, "the body's own StackOverflowError stays primary")
            assertTrue(
                thrown.suppressedExceptions.any { it.unrecovered() === computeFatal },
                "the drained recompute's rides along as suppressed: ${thrown.suppressedExceptions}",
            )
            assertEquals(0, host.h.value, "the body rolled back")
            assertEquals(1, source.src.value, "the nested action on another store committed on its own")
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertNothingInstalledOn(host)
            assertNothingLeftOnThisThread()
            subscription.dispose()
        }

    @Test fun aSettledRecomputeStackOverflowPropagatesFromTheEntryWhoseCommitStood() =
        runBlocking<Unit> {
            val host = FssHost()
            val source = FssSource()
            val reportedHost = host.recordedReports()
            val reportedSource = source.recordedReports()
            val fatal = StackOverflowError()
            val compute = Throwing1()
            compute.armed = fatal
            val computes = AtomicInteger()
            val mirror =
                source.derivedState(source.src) {
                    computes.incrementAndGet()
                    compute.of(src.value)
                }

            val thrown =
                assertPropagates<StackOverflowError> {
                    host.suspendAction {
                        h mutate 5
                        // Settles with the outermost entry: this suspendAction.
                        source action { src mutate 1 }
                    }
                }
            compute.armed = null

            assertSame(fatal, thrown, "the settle's StackOverflowError, unreported")
            assertEquals(2, computes.get(), "the initial compute, then the settle's recompute")
            assertEquals(5, host.h.value, "the suspendAction's own commit stands")
            assertEquals(1, source.src.value)
            assertEquals(0, mirror.value, "the recompute rolled back")
            assertTrue(reportedHost.isEmpty() && reportedSource.isEmpty(), "never reported: $reportedSource")
            assertNothingInstalledOn(host)
            assertNothingInstalledOn(source)
            assertNothingLeftOnThisThread()
            assertIs<TransactionResult.Success<*>>(source action { src mutate 2 })
            assertEquals(2, mirror.value, "the derived state recomputes again")
            mirror.dispose()
        }

    @Test fun aBodyOutOfMemoryErrorOutranksTheSettlesStackOverflow() =
        runBlocking<Unit> {
            val host = FssHost()
            val source = FssSource()
            val reportedHost = host.recordedReports()
            val reportedSource = source.recordedReports()
            val settleFatal = StackOverflowError()
            val bodyFatal = OutOfMemoryError("test")
            val compute = Throwing1()
            compute.armed = settleFatal
            val computes = AtomicInteger()
            val mirror =
                source.derivedState(source.src) {
                    computes.incrementAndGet()
                    compute.of(src.value)
                }

            val thrown =
                assertPropagates<OutOfMemoryError> {
                    host.suspendAction {
                        h mutate 5
                        source action { src mutate 1 }
                        throw bodyFatal
                    }
                }
            compute.armed = null

            assertSame(bodyFatal, thrown, "the body's fatal error stays primary")
            assertTrue(
                thrown.suppressedExceptions.any { it.unrecovered() === settleFatal },
                "the settle's rides along as suppressed: ${thrown.suppressedExceptions}",
            )
            assertEquals(2, computes.get(), "the settle still ran the recompute")
            assertEquals(0, host.h.value, "the body rolled back")
            assertEquals(0, mirror.value)
            assertTrue(reportedHost.isEmpty() && reportedSource.isEmpty(), "never reported: $reportedSource")
            assertNothingInstalledOn(host)
            assertNothingLeftOnThisThread()
            mirror.dispose()
        }

    @Test fun aNestedSuspendActionsStackOverflowRollsBothBackAndPropagatesOutOfTheOuterOne() =
        runBlocking<Unit> {
            val host = FssHost()
            val source = FssSource()
            val reportedHost = host.recordedReports()
            val reportedSource = source.recordedReports()
            val fatal = StackOverflowError()

            val thrown =
                assertPropagates<StackOverflowError> {
                    host.suspendAction {
                        h mutate 1
                        source.suspendAction {
                            src mutate 1
                            throw fatal
                        }
                    }
                }

            assertSame(fatal, thrown)
            assertEquals(0, host.h.value, "the outer action rolled back")
            assertEquals(0, source.src.value, "the nested action rolled back")
            assertTrue(reportedHost.isEmpty() && reportedSource.isEmpty(), "never reported: $reportedHost")
            assertNothingInstalledOn(host)
            assertNothingInstalledOn(source)
            assertNothingLeftOnThisThread()
            assertIs<TransactionResult.Success<*>>(host.suspendAction { source.suspendAction { src mutate 2 } })
            assertEquals(2, source.src.value)
        }

    @Test fun aSuspendDerivedComputeStackOverflowGoesToTheStoreScopesHandler() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val store = FssScoped(handled.scope)
                val reported = store.recordedReports()
                val log = CopyOnWriteArrayList<String>()
                val hooks = FatalSyncHooks<FssScoped>("m", log)
                store.middlewares(hooks)
                val fatal = StackOverflowError()
                val compute = Throwing1()
                val (mirror, subscription) = store.suspendDerived(store.src) { compute.of(src.value) }
                compute.armed = fatal

                assertIs<TransactionResult.Success<*>>(store action { src mutate 1 })

                assertSame(fatal, handled.first(), "the launched recompute failed with it, unwrapped")
                compute.armed = null
                assertEquals(0, mirror.value, "the recompute rolled back")
                assertTrue(
                    hooks.errors.any { it.unrecovered() === fatal },
                    "the recompute's suspendAction ran its error hooks: ${hooks.errors}",
                )
                assertTrue(reported.isEmpty(), "never reported: $reported")
                subscription.dispose()
            }
        }
}
