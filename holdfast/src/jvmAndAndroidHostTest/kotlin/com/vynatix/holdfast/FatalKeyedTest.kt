@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61, keyed state families: a fatal error from a membership listener,
// an evicted entry's shutdown (a bridge's or an observeFrom subscription's
// dispose) or a deferred eviction is never reported. Every listener is still
// told, every evicted entry still shut down and announced, the commit's
// fanout still runs (sealed states first, then the shutdown reports, then the
// other states); then the fatal error propagates out of what announced or
// committed. KeyedRegistry.kt, KeyedCommit.kt, KeyedEviction.kt,
// MutableState.shutdownSilently.

private class FatalKeyedStore : Store<FatalKeyedStore>() {
    val other by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private class FatalKeyedHost : Store<FatalKeyedHost>()

/** Logs `label:added:key` / `label:evicted:key`, and throws [failure] from the event [failsOn] names. */
private class FatalMembership(
    private val label: String,
    private val log: MutableList<String>,
    private val failsOn: String? = null,
    private val failure: Throwable? = null,
) : KeyedMembershipListener {
    override fun onEntryAdded(
        family: String,
        key: Any,
        entry: State<*>,
    ) = record("added", key)

    override fun onEntryEvicted(
        family: String,
        key: Any,
        entry: State<*>,
    ) = record("evicted", key)

    private fun record(
        event: String,
        key: Any,
    ) {
        log += "$label:$event:$key"
        if (event == failsOn) throw checkNotNull(failure)
    }
}

/** Records publishes and disposes, keeps the inbound callback, and throws [disposeFails] from its subscription's dispose. */
private class FatalKeyedBridge(
    private val disposeFails: Throwable? = null,
) : Bridge<Int> {
    val published = CopyOnWriteArrayList<Int>()
    var disposes = 0
    var inbound: ((Int) -> Unit)? = null

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable {
            disposes++
            disposeFails?.let { throw it }
        }
    }

    override fun publish(value: Int): Boolean = published.add(value)
}

/**
 * An inbound source whose subscription's dispose logs [label] into [log], then
 * throws [fails]; the callback it was handed is kept in [inbound], when given.
 */
private fun subscriptionThatThrows(
    label: String,
    log: MutableList<String>,
    fails: Throwable,
    inbound: MutableList<(Int) -> Unit>? = null,
): Observable<Int> =
    Observable { observer ->
        inbound?.add(observer)
        Disposable {
            log += label
            throw fails
        }
    }

/** Throws [failure] from onTransactionStarted of a transaction whose id is [id] while [armed]. */
private class FatalStartGuard(
    private val id: String?,
    private val failure: Throwable,
) : Middleware<FatalKeyedStore>() {
    @Volatile var armed = true

    override fun onTransactionStarted(context: MiddlewareContext<FatalKeyedStore>) {
        if (armed && (id == null || context.transaction.id == id)) throw failure
    }
}

private fun Store<*>.keyedReports(): MutableList<Throwable> {
    val seen = CopyOnWriteArrayList<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

class FatalKeyedTest {
    /** KeyedRegistry.kt:140: every listener hears Added once, the edges are told, then it propagates. */
    @Test fun anAddedListenersStackOverflowStillTellsTheOthersAndAnnouncesOnce() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        val host = FatalKeyedHost()
        var computes = 0
        val follower = host.derivedStateOverStores("follower", sourceStores = listOf(store)) { ++computes }
        val before = computes
        val log = CopyOnWriteArrayList<String>()
        val fatal = StackOverflowError()
        store.internalObserveKeyedMembership(FatalMembership("l1", log, failsOn = "added", failure = fatal))
        store.internalObserveKeyedMembership(FatalMembership("l2", log))

        val thrown = assertFailsWith<StackOverflowError> { store.docs["k"] }

        assertSame(fatal, thrown)
        assertEquals(listOf("l1:added:k", "l2:added:k"), log.toList())
        assertTrue(computes > before, "the store's whole-store follower recomputed")
        val entry = assertNotNull(store.docs.getOrNull("k"), "the entry was created")
        assertSame(entry, store.docs["k"], "a second get returns the same entry and does not throw")
        assertEquals(listOf("l1:added:k", "l2:added:k"), log.toList(), "nothing was announced twice")
        assertEquals(emptyList(), reported.toList())
        assertNull(SettleScopes.current())
        assertNull(NoWriteRegion.current())
        follower.dispose()
    }

    /** KeyedRegistry.kt:195: a handler overflowing while reporting a listener's failure is held too. */
    @Test fun aHandlerThatOverflowsReportingAListenerFailureStillTellsTheOthers() {
        val store = FatalKeyedStore()
        val fatal = StackOverflowError()
        val handed = CopyOnWriteArrayList<Throwable>()
        store.uncaughtObserverHandler = {
            handed += it
            throw fatal
        }
        val log = CopyOnWriteArrayList<String>()
        val failure = IllegalStateException("listener failed")
        store.internalObserveKeyedMembership(FatalMembership("l1", log, failsOn = "added", failure = failure))
        store.internalObserveKeyedMembership(FatalMembership("l2", log))

        val thrown = assertFailsWith<StackOverflowError> { store.docs["k"] }

        assertSame(fatal, thrown)
        assertEquals(listOf<Throwable>(failure), handed.toList())
        assertEquals(listOf("l1:added:k", "l2:added:k"), log.toList())
    }

    /** KeyedRegistry.kt:179, KeyedCommit.kt:94: the evicting commit fans out, then the fatal error propagates. */
    @Test fun anEvictedListenersStackOverflowIsThrownAfterTheCommitsFanout() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        val log = CopyOnWriteArrayList<String>()
        val fatal = StackOverflowError()
        store.internalObserveKeyedMembership(FatalMembership("l1", log, failsOn = "evicted", failure = fatal))
        store.internalObserveKeyedMembership(FatalMembership("l2", log))
        store.docs["k"]
        val otherSeen = mutableListOf<Int>()
        val effect = store.other effect { otherSeen += this }

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    docs.evict("k")
                    other mutate 1
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf(0, 1), otherSeen, "the commit's fanout ran")
        assertEquals(listOf("l1:added:k", "l2:added:k", "l1:evicted:k", "l2:evicted:k"), log.toList())
        assertNull(store.docs.getOrNull("k"), "the eviction committed")
        assertEquals(1, store.other.value)
        assertNull(store.activeTransaction)
        log.clear()
        store.docs["k"]
        assertEquals(listOf("l1:added:k", "l2:added:k"), log.toList(), "a fresh entry, announced once")
        assertEquals(emptyList(), reported.toList())
        effect.dispose()
    }

    /** KeyedRegistry.kt:294, MutableState.kt:534: every subscription is disposed, the fatal error is primary. */
    @Test fun anEvictedEntrysSubscriptionsAreAllDisposedAndTheStackOverflowIsPrimary() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        val disposed = CopyOnWriteArrayList<String>()
        val ordinary = IllegalStateException("first dispose failed")
        val fatal = StackOverflowError()
        store { docs["k"] observeFrom subscriptionThatThrows("first", disposed, ordinary) }
        store { docs["k"] observeFrom subscriptionThatThrows("second", disposed, fatal) }

        val thrown = assertFailsWith<StackOverflowError> { store action { docs.evict("k") } }

        assertSame(fatal, thrown)
        assertTrue(thrown.suppressed.any { it === ordinary }, "the ordinary failure rides along")
        assertEquals(listOf("first", "second"), disposed.toList())
        assertEquals(emptyList(), reported.toList(), "neither failure was reported")
        assertNull(store.docs.getOrNull("k"))
        assertNull(store.activeTransaction)
    }

    /** MutableState.kt:534: a fatal inbound shutdown is never demoted below an ordinary bridge failure. */
    @Test fun aBridgedEntrysShutdownMakesTheStackOverflowPrimaryAndDetachesTheBridge() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        val ordinary = IllegalStateException("bridge dispose failed")
        val fatal = StackOverflowError()
        val bridge = FatalKeyedBridge(disposeFails = ordinary)
        val disposed = CopyOnWriteArrayList<String>()
        store { docs["k"] bridge bridge }
        store { docs["k"] observeFrom subscriptionThatThrows("inbound", disposed, fatal) }

        val thrown = assertFailsWith<StackOverflowError> { store action { docs.evict("k") } }

        assertSame(fatal, thrown)
        assertTrue(thrown.suppressed.any { it === ordinary })
        assertEquals(1, bridge.disposes)
        assertEquals(listOf("inbound"), disposed.toList())
        assertEquals(emptyList(), reported.toList())
        checkNotNull(bridge.inbound)(9)
        assertNull(store.docs.getOrNull("k"), "a later inbound value is dropped")
        store action { docs["k"] mutate 4 }
        assertEquals(emptyList(), bridge.published.toList(), "the detached bridge is never published to")
    }

    /** MutableState.kt:534 through dispose(): the same shutdown, then dispose throws the fatal error last. */
    @Test fun disposingABridgedEntryThrowsTheStackOverflowAtTheEnd() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        val ordinary = IllegalStateException("bridge dispose failed")
        val fatal = StackOverflowError()
        val bridge = FatalKeyedBridge(disposeFails = ordinary)
        val disposed = CopyOnWriteArrayList<String>()
        store { docs["k"] bridge bridge }
        store { docs["k"] observeFrom subscriptionThatThrows("inbound", disposed, fatal) }

        val thrown = assertFailsWith<StackOverflowError> { store.dispose() }

        assertSame(fatal, thrown)
        assertTrue(thrown.suppressed.any { it === ordinary })
        assertTrue(store.isDisposed)
        assertEquals(1, bridge.disposes)
        assertEquals(listOf("inbound"), disposed.toList())
        assertEquals(emptyList(), reported.toList())
        store.dispose()
    }

    /** KeyedCommit.kt:94/108: sealed observers, then the ordinary shutdown report, then the others, then the throw. */
    @Test fun twoEvictedEntriesAreShutDownAndAnnouncedAndTheOrdinaryFailureReported() {
        val store = FatalKeyedStore()
        val order = CopyOnWriteArrayList<String>()
        val reported = CopyOnWriteArrayList<Throwable>()
        store.uncaughtObserverHandler = {
            order += "report"
            reported += it
        }
        val log = CopyOnWriteArrayList<String>()
        store.internalObserveKeyedMembership(FatalMembership("l", log))
        val fatal = StackOverflowError()
        val ordinary = IllegalStateException("B's dispose failed")
        val disposed = CopyOnWriteArrayList<String>()
        val inbound = CopyOnWriteArrayList<(Int) -> Unit>()
        store { docs["A"] observeFrom subscriptionThatThrows("A", disposed, fatal, inbound) }
        store { docs["B"] observeFrom subscriptionThatThrows("B", disposed, ordinary, inbound) }
        val phase = store.internalSealedState("phase", 0, "only this test stages it")
        var sealedInitial = true
        val sealedEffect = phase effect { if (sealedInitial) sealedInitial = false else order += "sealed" }
        var otherInitial = true
        val otherEffect = store.other effect { if (otherInitial) otherInitial = false else order += "other" }

        val thrown =
            assertFailsWith<StackOverflowError> {
                store action {
                    docs.evict("A")
                    docs.evict("B")
                    internalStageSealed(phase, 1)
                    other mutate 1
                }
            }

        assertSame(fatal, thrown)
        assertEquals(listOf("sealed", "report", "other"), order.toList(), "the fanout order is kept")
        assertEquals(listOf<Throwable>(ordinary), reported.toList(), "only the ordinary failure was reported")
        assertEquals(listOf("A", "B"), disposed.toList(), "both entries were shut down")
        assertTrue("l:evicted:A" in log && "l:evicted:B" in log, "both evictions were announced: $log")
        inbound.forEach { it(9) }
        assertNull(store.docs.getOrNull("A"), "neither evicted entry takes a later inbound value")
        assertNull(store.docs.getOrNull("B"))
        assertEquals(1, phase.value)
        assertEquals(1, store.other.value)
        assertNull(store.activeTransaction)
        sealedEffect.dispose()
        otherEffect.dispose()
    }

    /** KeyedEviction.kt:181: a deferred eviction's fatal error leaves the drain; the entry stays live. */
    @Test fun aDeferredEvictionsStackOverflowIsThrownToTheDrainAndKeepsTheEntry() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        val fatal = StackOverflowError()
        val guard = FatalStartGuard(id = "Evict", failure = fatal)
        store.middlewares(guard)
        store.docs["k"]
        // From the store's own commit fanout, so the eviction is deferred (D16).
        val evictor = store.other effect { if (this == 1) store.docs.evict("k") }

        val thrown = assertFailsWith<StackOverflowError> { store action { other mutate 1 } }

        assertSame(fatal, thrown)
        assertNotNull(store.docs.getOrNull("k"), "the deferred eviction rolled back")
        assertEquals(1, store.other.value)
        assertEquals(emptyList(), reported.toList())
        assertNull(store.activeTransaction)
        assertNull(SettleScopes.current())
        guard.armed = false
        evictor.dispose()
        store action { other mutate 2 }
        assertEquals(2, store.other.value)
    }

    /** KeyedEviction.kt:62: a bare evict's one-shot action propagates a middleware's fatal error. */
    @Test fun aBareEvictPropagatesAMiddlewaresStackOverflow() {
        val store = FatalKeyedStore()
        val reported = store.keyedReports()
        store.docs["k"]
        val fatal = StackOverflowError()
        store.middlewares(FatalStartGuard(id = null, failure = fatal))

        val thrown = assertFailsWith<StackOverflowError> { store.docs.evict("k") }

        assertSame(fatal, thrown)
        assertNotNull(store.docs.getOrNull("k"), "nothing was evicted")
        assertEquals(emptyList(), reported.toList())
        assertNull(store.activeTransaction)
    }
}
