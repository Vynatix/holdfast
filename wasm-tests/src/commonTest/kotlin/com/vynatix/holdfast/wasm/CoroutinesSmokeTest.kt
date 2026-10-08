@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class, ExperimentalCoroutinesApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.coroutines.Hydration
import com.vynatix.holdfast.coroutines.InMemorySuspendingKvStore
import com.vynatix.holdfast.coroutines.SuspendingBridge
import com.vynatix.holdfast.coroutines.SuspendingMiddlewareHooks
import com.vynatix.holdfast.coroutines.asFlow
import com.vynatix.holdfast.coroutines.asStateFlow
import com.vynatix.holdfast.coroutines.awaitValue
import com.vynatix.holdfast.coroutines.bridge
import com.vynatix.holdfast.coroutines.first
import com.vynatix.holdfast.coroutines.hydrateEach
import com.vynatix.holdfast.coroutines.hydrator
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.coroutines.suspendingBridge
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.keyedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first as flowFirst

private class CoroutinesCounter : Store<CoroutinesCounter>() {
    val n by state { 0 }
    val s by state { "init" }
    val total by derivedState(n) { n.value * 10 }
}

private class CoroutinesLeft : Store<CoroutinesLeft>() {
    val a by state { 0 }
}

private class CoroutinesRight : Store<CoroutinesRight>() {
    val b by state { 0 }
}

private class CoroutinesHost : Store<CoroutinesHost>() {
    val y by state { 0 }
}

private class CoroutinesPlain : Store<CoroutinesPlain>() {
    val n by state { 0 }
}

private class CoroutinesDocs : Store<CoroutinesDocs>() {
    val trigger by state { 0 }
    val docs by keyedState<String, Int> { key -> key.length }
}

private class CoroutinesEvents : EventfulStore<CoroutinesEvents, String>(extraBufferCapacity = 0) {
    val n by state { 0 }
}

/** Records every sync hook: "started", "completed", "cancelled" or "error ExceptionClass". */
private class CoroutinesLog<V : Store<V>> : Middleware<V>() {
    val events = mutableListOf<String>()

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        events += "started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        events += "completed"
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        events += if (error is CancellationException) "cancelled" else "error ${error::class.simpleName}"
    }
}

/** A middleware whose suspending hooks suspend (virtual time) before they record. */
private class CoroutinesHooks<V : Store<V>>(
    private val name: String,
    private val log: MutableList<String>,
) : Middleware<V>(),
    SuspendingMiddlewareHooks<V> {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        log += "$name.started"
    }

    override suspend fun onTransactionStartedAsync(context: MiddlewareContext<V>) {
        delay(3)
        log += "$name.startedAsync"
    }

    override suspend fun onTransactionCompletedAsync(context: MiddlewareContext<V>) {
        delay(3)
        log += "$name.completedAsync"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        log += "$name.completed"
    }
}

/** A remote whose n-th (1-based) fetch is answered by [answer]; counts its fetches. */
private class CoroutinesRemote<T>(
    private val answer: suspend (call: Int) -> T,
) {
    var fetches = 0
        private set

    suspend fun fetch(): T = answer(++fetches)
}

private class CoroutinesFeed(
    remote: suspend () -> List<String>,
) : Store<CoroutinesFeed>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    var baseRuns = 0
    val hydration =
        hydrator {
            base {
                baseRuns++
                items mutate listOf("seed")
            }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

/** A hydrated store whose `base { }` fails with [failure], when given. */
private class CoroutinesSpecced(
    failure: String?,
) : Store<CoroutinesSpecced>() {
    val remote by state(tags = setOf(StateTag.Remote)) { 0 }
    val hydration =
        hydrator {
            base { if (failure != null) error(failure) }
            refresh { 1 } adopt { remote mutate it }
        }
}

private const val COROUTINES_OVERLAY_KEY = "coroutines.overlay"

private class CoroutinesReader(
    kv: InMemorySuspendingKvStore,
) : Store<CoroutinesReader>() {
    val pinned by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val items by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "" }
    val hydration =
        hydrator {
            overlay(kv, COROUTINES_OVERLAY_KEY)
            base { items mutate "seed" }
            refresh { "fresh" } adopt { fetched -> items mutate fetched }
        }
}

/**
 * A suspending bridge whose publish suspends ([delayMillis] of virtual time),
 * then runs [afterResume] — inside the store's suspending commit, after a
 * resumption.
 */
private class CoroutinesDelayingBridge(
    private val delayMillis: Long,
    private val afterResume: (Int) -> Unit,
) : SuspendingBridge<Int> {
    val published = mutableListOf<Int>()

    override suspend fun publishAwaited(value: Int) {
        delay(delayMillis)
        published += value
        afterResume(value)
    }

    override fun publish(value: Int): Boolean = true

    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }
}

private fun Store<*>.coroutinesFailures(): MutableList<Throwable> =
    mutableListOf<Throwable>().also { failures -> uncaughtObserverHandler = { failures += it } }

/** No core thread-local slot is left installed on the test's own coroutine. */
private fun coroutinesAssertNoSlotLeaked(where: String) {
    assertNull(SettleScopes.current(), "settle scope leaked $where")
    assertNull(FrameMarkers.current(), "frame marker leaked $where")
    assertNull(FanoutMarkers.current(), "fanout marker leaked $where")
}

private fun TransactionResult<*>.coroutinesRefusal(): Throwable = assertIs<TransactionResult.Error>(this).exception

/**
 * `:holdfast-coroutines` on wasmJs, with the JVM as control. On wasmJs the
 * frame marker and the settle scope travel with a suspending entry through
 * `SlotBracketingInterceptor` (there is no `ThreadContextElement`), as the
 * fanout marker does on every platform; every thread-local slot is one
 * global, `currentThreadId()` is 0 for everyone and `Dispatchers.Default` is
 * the one JS event loop.
 */
class CoroutinesSmokeTest {
    // The body's and the suspending middleware hooks' delays run on runTest's
    // virtual clock: on wasmJs the settle scope's interceptor stands in for
    // the test dispatcher and must forward its Delay. The commit's
    // derivedState recompute is a non-suspending transaction of its own
    // (tryTopLevelAction), so it fires the sync hooks only.
    @Test
    fun aSuspendActionCommitsOnceAcrossSuspensionsOnVirtualTime() =
        runTest {
            val store = CoroutinesCounter()
            val hooks = mutableListOf<String>()
            store.middlewares(CoroutinesHooks("A", hooks), CoroutinesHooks("B", hooks))
            val seen = mutableListOf<Int>()
            val sub = store.n effect { seen += this }
            val start = currentTime
            var inBody: Any? = null
            var fanoutInBody: Any? = "unset"
            val result =
                store.suspendAction {
                    hooks += "body"
                    n mutate 1
                    delay(10)
                    n mutate n.value + 10
                    delay(10)
                    inBody = SettleScopes.current()
                    fanoutInBody = FanoutMarkers.current()
                    s mutate "done"
                    "returned ${n.value}"
                }
            assertEquals("returned 11", assertIs<TransactionResult.Success<String>>(result).value)
            assertEquals(32, currentTime - start, "20 ms of body delays plus four 3 ms async hooks")
            assertEquals(11, store.n.value)
            assertEquals("done", store.s.value)
            assertEquals(110, store.total.value)
            assertEquals(listOf(0, 11), seen, "the observer saw one commit")
            assertNotNull(inBody, "the entry's settle scope is installed after a resumption")
            assertNull(fanoutInBody, "no fanout marker in the body")
            assertEquals(
                listOf(
                    "B.started",
                    "B.startedAsync",
                    "A.started",
                    "A.startedAsync",
                    "body",
                    "A.completedAsync",
                    "A.completed",
                    "B.completedAsync",
                    "B.completed",
                    // The total recompute.
                    "B.started",
                    "A.started",
                    "A.completed",
                    "B.completed",
                ),
                hooks,
            )
            coroutinesAssertNoSlotLeaked("after the entry")
            sub.dispose()
        }

    @Test
    fun aSuspendActionRollsBackWhenItsBodyThrowsOrIsCancelled() =
        runTest {
            val store = CoroutinesCounter()
            val log = CoroutinesLog<CoroutinesCounter>()
            store.middlewares(log)
            store.suspendAction { n mutate 1 }.getOrThrow()
            // The first commit's derivedState recompute is a transaction of its own.
            assertEquals(listOf("started", "completed", "started", "completed"), log.events)
            log.events.clear()
            val seen = mutableListOf<Int>()
            val sub = store.n effect { seen += this }

            val thrown =
                store.suspendAction {
                    n mutate 99
                    delay(5)
                    s mutate "during"
                    error("boom")
                }
            val error = assertIs<TransactionResult.Error>(thrown)
            assertEquals("boom", error.exception.message)
            assertEquals(TransactionStatus.RolledBack, error.transaction.status)
            assertEquals(1 to "init", store.n.value to store.s.value)
            assertEquals(listOf("started", "error IllegalStateException"), log.events)
            log.events.clear()

            val parked = CompletableDeferred<Unit>()
            val job =
                launch {
                    store.suspendAction {
                        n mutate 77
                        parked.complete(Unit)
                        awaitCancellation()
                    }
                }
            parked.await()
            coroutinesAssertNoSlotLeaked("while another coroutine's entry is parked")
            job.cancelAndJoin()
            assertEquals(1, store.n.value, "the cancelled body rolled back")
            assertEquals(listOf("started", "cancelled"), log.events)
            assertEquals(listOf(1), seen, "no rolled-back write reached the observer")

            store.suspendAction { n mutate 5 }.getOrThrow()
            store.action { n mutate n.value + 1 }.getOrThrow()
            assertEquals(6, store.n.value, "both kinds of action take the released store")
            coroutinesAssertNoSlotLeaked("after the rollbacks")
            sub.dispose()
        }

    // A nested suspendAction on another store commits on its own, inside the
    // outer entry. One on the same store fails fast instead of waiting for
    // itself: suspendAction locks the store's mutex with the caller's Job as
    // owner, and kotlinx's Mutex throws "already locked by the specified
    // owner" for a second lock by that owner.
    @Test
    fun nestedSuspendActionsCommitOnAnotherStoreAndFailFastOnTheSameOne() =
        runTest {
            val left = CoroutinesLeft()
            val right = CoroutinesRight()
            var innerSeen = -1
            var ownWrite = -1
            left
                .suspendAction {
                    a mutate 1
                    delay(1)
                    right
                        .suspendAction {
                            delay(1)
                            b mutate 2
                        }.getOrThrow()
                    innerSeen = right.b.value
                    ownWrite = a.value
                }.getOrThrow()
            assertEquals(2, innerSeen, "the nested entry committed before the outer one resumed")
            assertEquals(1, ownWrite, "the outer body reads its own write")
            assertEquals(1 to 2, left.a.value to right.b.value)

            val same =
                left.suspendAction {
                    a mutate 10
                    delay(1)
                    left.suspendAction { a mutate 20 }.getOrThrow()
                }
            val error = assertIs<TransactionResult.Error>(same)
            assertIs<IllegalStateException>(error.exception)
            assertTrue("already locked" in error.exception.message.orEmpty(), error.exception.message)
            assertEquals(1, left.a.value, "the outer entry rolled back")
            left.suspendAction { a mutate 3 }.getOrThrow()
            assertEquals(3, left.a.value, "the store was released")
            coroutinesAssertNoSlotLeaked("after the nested entries")
        }

    // On wasmJs the settle scope and the frame marker's interceptors stand in
    // for the test dispatcher, so a withTimeout inside an entry fires on
    // virtual time only because they forward its Delay, invokeOnTimeout
    // included. A timeout around an entry cancels its body, which rolls back.
    @Test
    fun timeoutsInsideAndAroundSuspendingEntriesRunOnVirtualTime() =
        runTest {
            val left = CoroutinesLeft()
            val right = CoroutinesRight()
            var start = currentTime
            val inner =
                left.suspendAction {
                    val late = withTimeoutOrNull(30) { delay(100) }
                    a mutate 1
                    late
                }
            assertNull(inner.getOrThrow(), "the inner timeout fired first")
            assertEquals(30, currentTime - start)
            assertEquals(1, left.a.value)

            start = currentTime
            val framed =
                suspendAtomic(left, right) {
                    val late = withTimeoutOrNull(40) { delay(100) }
                    left { a mutate 2 }
                    right { b mutate 2 }
                    late
                }
            assertNull(framed.getOrThrow(), "the timeout inside the frame body fired first")
            assertEquals(40, currentTime - start)
            assertEquals(2 to 2, left.a.value to right.b.value)

            start = currentTime
            val around =
                withTimeoutOrNull(50) {
                    left.suspendAction {
                        a mutate 3
                        delay(1_000)
                    }
                }
            assertNull(around)
            assertEquals(50, currentTime - start)
            assertEquals(2, left.a.value, "the timed-out body rolled back")
            left.suspendAction { a mutate 4 }.getOrThrow()
            assertEquals(4, left.a.value)
            coroutinesAssertNoSlotLeaked("after the timeouts")
        }

    @Test
    fun suspendAtomicCommitsEveryParticipantOrNoneAndNestsAsSavepoints() =
        runTest {
            val left = CoroutinesLeft()
            val right = CoroutinesRight()
            val seen = mutableListOf<String>()
            val subs = listOf(left.a effect { seen += "a=$this" }, right.b effect { seen += "b=$this" })
            seen.clear()
            // A suspendAction on a participant runs as a savepoint of its frame root.
            val ok =
                suspendAtomic(left, right) {
                    left { a mutate 1 }
                    delay(5)
                    right.suspendAction { b mutate 1 }.getOrThrow()
                    "ok"
                }
            assertEquals("ok", ok.getOrThrow())
            assertEquals(1 to 1, left.a.value to right.b.value)
            assertEquals(listOf("a=1", "b=1"), seen, "fanout in lock order, once each")

            val failed =
                suspendAtomic(left, right) {
                    left { a mutate 2 }
                    right { b mutate 2 }
                    delay(5)
                    error("abort")
                }
            assertEquals("abort", assertIs<TransactionResult.Error>(failed).exception.message)
            assertEquals(1 to 1, left.a.value to right.b.value, "both rolled back")

            // A nested frame: the store the outer frame holds joins as a
            // savepoint; the one it adds gets a root of its own that commits at
            // the nested frame's exit, after which the outer frame's marker
            // polices again.
            val unenrolled =
                assertFailsWith<UnenrolledStoreException> {
                    suspendAtomic(left) {
                        left { a mutate 5 }
                        suspendAtomic(left, right) {
                            delay(1)
                            left { a mutate 6 }
                            right { b mutate 6 }
                        }.getOrThrow()
                        delay(1)
                        right { b mutate 7 }
                    }
                }
            assertTrue("CoroutinesRight" in unenrolled.message.orEmpty(), unenrolled.message)
            assertEquals(1, left.a.value, "the outer frame rolled back")
            assertEquals(6, right.b.value, "the nested frame committed its own participant")
            assertEquals(listOf("a=1", "b=1", "b=6"), seen)
            subs.forEach { it.dispose() }
            coroutinesAssertNoSlotLeaked("after the frames")
        }

    // Enrollment is policed through the frame marker, which on wasmJs the
    // interceptor installs again on every resumption of the frame body — and
    // which no other coroutine on the shared thread may see meanwhile.
    @Test
    fun frameEnrollmentIsPolicedAfterASuspensionAndOnlyInsideTheFrame() =
        runTest {
            val left = CoroutinesLeft()
            val right = CoroutinesRight()
            val outsider = CoroutinesPlain()
            val framePark = CompletableDeferred<Unit>()
            val outsiderDone = CompletableDeferred<TransactionResult<Unit>>()
            launch {
                framePark.await()
                // Runs while the frame body is parked on this same thread.
                outsiderDone.complete(outsider.action { n mutate 7 })
            }
            val unenrolled =
                assertFailsWith<UnenrolledStoreException> {
                    suspendAtomic(left, right) {
                        left { a mutate 1 }
                        framePark.complete(Unit)
                        outsiderDone.await()
                        delay(1)
                        outsider.action { n mutate 8 }
                    }
                }
            assertTrue("CoroutinesPlain" in unenrolled.message.orEmpty(), unenrolled.message)
            assertIs<TransactionResult.Success<Unit>>(outsiderDone.await(), "a coroutine outside the frame is not policed")
            assertEquals(7, outsider.n.value)
            assertEquals(0, left.a.value, "the frame rolled back")
            suspendAtomic(left, right) { left { a mutate 3 } }.getOrThrow()
            assertEquals(3, left.a.value, "the participants were released")
            coroutinesAssertNoSlotLeaked("after the frames")
        }

    // A blocking action on the store from inside its own suspending commit is
    // refused as a write into a transaction that has already applied (D16)
    // instead of waiting for the commit it runs in: from an observer, and
    // from a bridge's publishAwaited after it resumed, where only the fanout
    // marker (carried by SlotBracketingInterceptor) still says so. An eviction
    // from that commit is deferred, and runs once the suspendAction has
    // released the store (its post-commit drain).
    @Test
    fun writesFromASuspendingCommitAreRefusedOrDeferred() =
        runTest {
            val store = CoroutinesCounter()
            val failures = store.coroutinesFailures()
            val refused = mutableListOf<TransactionResult<Unit>>()
            val bridge = CoroutinesDelayingBridge(delayMillis = 5) { refused += store.action { s mutate "nested" } }
            store.action { n bridge bridge }.getOrThrow()
            val observed = mutableListOf<TransactionResult<Unit>>()
            var initial = true
            val sub =
                store.n effect {
                    if (initial) initial = false else observed += store.action { s mutate "observer" }
                }

            store.suspendAction { n mutate 1 }.getOrThrow()

            sub.dispose()
            assertEquals(listOf(1), bridge.published, "the publish was awaited")
            val fromObserver = assertIs<IllegalStateException>(observed.single().coroutinesRefusal())
            assertTrue("already applied" in fromObserver.message.orEmpty(), fromObserver.message)
            val fromBridge = assertIs<IllegalStateException>(refused.single().coroutinesRefusal())
            assertTrue("already applied" in fromBridge.message.orEmpty(), fromBridge.message)
            assertEquals("init", store.s.value)
            assertEquals(emptyList(), failures.map { it.message })

            val docs = CoroutinesDocs()
            val docsFailures = docs.coroutinesFailures()
            docs.action { this.docs["abc"] mutate 42 }.getOrThrow()
            val evicting = docs.trigger effect { if (this == 1) docs.docs.evict("abc") }
            docs
                .suspendAction {
                    trigger mutate 1
                    delay(1)
                }.getOrThrow()
            evicting.dispose()
            assertTrue("abc" !in docs.docs, "evicted once the suspending call released the store")
            assertEquals(3, docs.docs["abc"].value, "a new entry from the initializer")
            assertEquals(emptyList(), docsFailures.map { it.message })
            coroutinesAssertNoSlotLeaked("after the suspending commits")
        }

    // A derived state whose sources a suspendAction and a suspendAction on
    // another store nested in it change recomputes once, when the outermost
    // entry has released everything: the nested entry joins the outer one's
    // settle scope, which follows the body across resumptions.
    @Test
    fun derivedStatesSettleOnceAfterTheOutermostSuspendingEntry() =
        runTest {
            val left = CoroutinesLeft()
            val right = CoroutinesRight()
            val host = CoroutinesHost()
            var computes = 0
            val pair =
                host.derivedState(left.a, right.b) {
                    computes++
                    left.a.value to right.b.value
                }
            val seen = mutableListOf<Pair<Int, Int>>()
            val sub = pair effect { seen += this }
            var inside: Pair<Int, Int>? = null
            left
                .suspendAction {
                    a mutate 1
                    delay(1)
                    right
                        .suspendAction {
                            delay(1)
                            b mutate 2
                        }.getOrThrow()
                    delay(1)
                    inside = pair.value
                }.getOrThrow()
            assertEquals(0 to 0, inside, "not recomputed while the outer entry was open")
            assertEquals(1 to 2, pair.value)
            assertEquals(2, computes, "the initial compute, then one recompute for both commits")
            assertEquals(listOf(0 to 0, 1 to 2), seen)
            sub.dispose()
            pair.dispose()
            coroutinesAssertNoSlotLeaked("after the settle")
        }

    @Test
    fun flowsFollowCommits() =
        runTest {
            val store = CoroutinesCounter()
            val collected =
                async {
                    store.n
                        .asFlow()
                        .take(3)
                        .toList()
                }
            runCurrent()
            store.action { n mutate 1 }.getOrThrow()
            runCurrent()
            store.suspendAction { n mutate 2 }.getOrThrow()
            assertEquals(listOf(0, 1, 2), collected.await())

            val waiter = async { store.n.awaitValue(5) }
            val firstOver = async { store.total.first { it > 40 } }
            runCurrent()
            store
                .suspendAction {
                    n mutate 4
                    delay(1)
                    n mutate 5
                }.getOrThrow()
            assertEquals(5, waiter.await())
            assertEquals(50, firstOver.await(), "a derivedState source")

            val hot = store.n.asStateFlow(backgroundScope, SharingStarted.Eagerly)
            runCurrent()
            assertEquals(5, hot.value)
            store.action { n mutate 6 }.getOrThrow()
            runCurrent()
            assertEquals(6, hot.value)

            // The default scope is the store's Store.scope (Store.defaultScope:
            // Dispatchers.Default, the JS event loop on wasmJs), written from
            // there too. Whether its sharing coroutine subscribes before or
            // after the write, asFlow starts from the current value, so 7 arrives.
            val onDefault = store.n.asStateFlow()
            val seven = async { onDefault.flowFirst { it == 7 } }
            async(Dispatchers.Default) { store.suspendAction { n mutate 7 }.getOrThrow() }.await()
            assertEquals(7, seven.await())
        }

    @Test
    fun kvBridgesAndEventsAreAwaitedByTheSuspendingCommit() =
        runTest {
            val kv = InMemorySuspendingKvStore(mapOf("loaded" to "41"))
            val store = CoroutinesCounter()
            val awaiting = kv.suspendingBridge("n", IntCodec, scope = backgroundScope)
            val plain = kv.bridge("s", StringCodec, scope = backgroundScope)
            store
                .action {
                    n bridge awaiting
                    s bridge plain
                }.getOrThrow()

            // No runCurrent: the put ran inside the suspendAction's commit.
            store.suspendAction { n mutate 3 }.getOrThrow()
            assertEquals("3", kv.get("n"), "suspendAction awaited the publish")

            // A plain bridge publishes fire-and-forget on its scope.
            store.action { s mutate "fire-and-forget" }.getOrThrow()
            runCurrent()
            assertEquals("fire-and-forget", kv.get("s"))

            val loading = CoroutinesPlain()
            loading.action { n bridge kv.bridge("loaded", IntCodec, scope = backgroundScope) }.getOrThrow()
            runCurrent()
            assertEquals(41, loading.n.value, "loaded on attach")

            // Events drain with suspending emits, honoring SUSPEND back-pressure.
            val events = CoroutinesEvents()
            val received = mutableListOf<String>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { events.events.collect { received += it } }
            events
                .suspendAction {
                    emit("one")
                    n mutate 1
                    delay(1)
                    emit("two")
                }.getOrThrow()
            assertEquals(listOf("one", "two"), received, "both delivered before the entry returned")
            assertEquals(1, events.n.value)
            collector.cancelAndJoin()
            coroutinesAssertNoSlotLeaked("after the publishes")
        }

    @Test
    fun aHydratorWalksItsLifecycleAndConcurrentCallsSeedAndFetchOnce() =
        runTest {
            val sent = CompletableDeferred<List<String>>()
            val offline = IllegalStateException("offline")
            val remote =
                CoroutinesRemote { call ->
                    when (call) {
                        1 -> sent.await()
                        2 -> throw offline
                        else -> listOf("again")
                    }
                }
            val store = CoroutinesFeed(remote::fetch)
            val seen = mutableListOf<Hydration>()
            val sub = store.hydration.state effect { seen += this }
            assertEquals(Hydration.Detached, store.hydration.current)

            val calls = List(5) { launch { store.hydration.hydrate(this@runTest) } }
            calls.forEach { it.join() }
            assertEquals(Hydration.Seeded, store.hydration.current, "the seed committed before hydrate() returned")
            assertEquals(listOf("seed"), store.items.value)
            sent.complete(listOf("a", "b"))
            val settled = List(3) { async { store.hydration.awaitSettled() } }
            assertEquals(List(3) { Hydration.Hydrated }, settled.map { it.await() })
            assertEquals(listOf("a", "b"), store.items.value)
            assertEquals(1 to 1, store.baseRuns to remote.fetches, "five calls seeded and fetched once")
            store.hydration.hydrate(this)
            assertEquals(1 to 1, store.baseRuns to remote.fetches, "a call on Hydrated does nothing")
            assertEquals("Hydrator(CoroutinesFeed, Hydrated)", store.hydration.toString())

            store.hydration.invalidate().getOrThrow()
            assertEquals(Hydration.Detached, store.hydration.current)
            store.hydration.hydrate(this)
            assertEquals(Hydration.Failed(offline), store.hydration.awaitSettled())
            assertEquals(listOf("seed"), store.items.value, "a failed refresh keeps the seed")
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(listOf("again"), store.items.value)
            assertEquals(2 to 3, store.baseRuns to remote.fetches, "the retry refreshed only")

            store.action { store.hydration.stageInvalidate() }.getOrThrow()
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(
                listOf(
                    Hydration.Detached,
                    Hydration.Seeded,
                    Hydration.Hydrated,
                    Hydration.Detached,
                    Hydration.Seeded,
                    Hydration.Failed(offline),
                    Hydration.Seeded,
                    Hydration.Hydrated,
                    Hydration.Detached,
                ),
                seen,
            )
            sub.dispose()
            coroutinesAssertNoSlotLeaked("after hydration")
        }

    // hydrate() and awaitSettled() refuse to run inside any suspending entry,
    // and run once it has returned: on the given scope, on the store's own
    // (Store.defaultScope: the JS event loop on wasmJs), and again after a
    // cancellation of the scope running a refresh failed it.
    @Test
    fun hydrationRefusesInsideEntriesAndRunsOnEveryScope() =
        runTest {
            val store = CoroutinesFeed { listOf("fresh") }
            val other = CoroutinesPlain()
            other
                .suspendAction {
                    delay(1)
                    val inside = assertFailsWith<IllegalStateException> { store.hydration.hydrate(this@runTest) }
                    assertTrue("Cannot run hydrate()" in inside.message.orEmpty(), inside.message)
                    n mutate 1
                }.getOrThrow()
            suspendAtomic(other) {
                delay(1)
                assertFailsWith<IllegalStateException> { store.hydration.awaitSettled() }
            }.getOrThrow()
            assertEquals(Hydration.Detached, store.hydration.current)
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(listOf("fresh"), store.items.value)

            val onDefault = CoroutinesFeed { listOf("default-scope") }
            onDefault.hydration.hydrate()
            assertEquals(Hydration.Hydrated, onDefault.hydration.awaitSettled())
            assertEquals(listOf("default-scope"), onDefault.items.value)

            val gate = CompletableDeferred<List<String>>()
            var fetches = 0
            val cancelled =
                CoroutinesFeed {
                    fetches++
                    if (fetches == 1) gate.await() else listOf("retried")
                }
            val doomed = CoroutineScope(coroutineContext + Job())
            cancelled.hydration.hydrate(doomed)
            runCurrent() // the refresh is parked on the gate
            assertEquals(1, fetches)
            doomed.cancel()
            val failed = assertIs<Hydration.Failed>(cancelled.hydration.awaitSettled())
            assertIs<CancellationException>(failed.cause)
            cancelled.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, cancelled.hydration.awaitSettled())
            assertEquals(listOf("retried"), cancelled.items.value)
            coroutinesAssertNoSlotLeaked("after hydration")
        }

    @Test
    fun theOverlayPersistsUserAuthoredStatesAndPutsThemBackOnTheNextSeed() =
        runTest {
            val kv = InMemorySuspendingKvStore()
            val writer = CoroutinesReader(kv)
            writer.bindToScope(this)
            val failures = writer.coroutinesFailures()
            assertEquals(COROUTINES_OVERLAY_KEY, writer.hydration.overlayKey)
            writer.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, writer.hydration.awaitSettled())
            assertNull(kv.get(COROUTINES_OVERLAY_KEY), "neither the seed nor the adoption writes")

            // The overlay's writer runs on the store's scope, bound to this test.
            writer.action { pinned mutate "pinned-by-user" }.getOrThrow()
            advanceUntilIdle()
            val blob = assertNotNull(kv.get(COROUTINES_OVERLAY_KEY))
            assertTrue("pinned-by-user" in blob, blob)
            assertTrue("fresh" !in blob, "a Remote state is never persisted: $blob")

            val reader = CoroutinesReader(kv)
            reader.bindToScope(this)
            val readerFailures = reader.coroutinesFailures()
            reader.hydration.hydrate(this)
            assertEquals("pinned-by-user", reader.pinned.value, "the seed put the overlay back")
            assertEquals(Hydration.Hydrated, reader.hydration.awaitSettled())
            assertEquals("fresh", reader.items.value)

            reader.hydration.clearOverlay()
            assertNull(kv.get(COROUTINES_OVERLAY_KEY))
            assertEquals(emptyList(), (failures + readerFailures).map { it.message })
            writer.dispose()
            reader.dispose()
        }

    // hydrateEach seeds each hydrator and launches its refresh on the store's
    // scope (bound to this test), and a derivedState over two hydrators' state
    // follows them (each hydration commits on its own store). A failing seed
    // does not stop the rest; the first failure is thrown, the others suppressed.
    @Test
    fun hydrateEachSeedsEveryHydratorAndAggregatesFailures() =
        runTest {
            val x = CoroutinesFeed { listOf("x") }
            val y = CoroutinesFeed { listOf("y") }
            x.bindToScope(this)
            y.bindToScope(this)
            val host = CoroutinesHost()
            val allHydrated =
                host.derivedState(x.hydration.state, y.hydration.state) {
                    x.hydration.current == Hydration.Hydrated && y.hydration.current == Hydration.Hydrated
                }
            val flags = mutableListOf<Boolean>()
            val sub = allHydrated effect { flags += this }
            hydrateEach(x.hydration, y.hydration)
            assertEquals(Hydration.Seeded to Hydration.Seeded, x.hydration.current to y.hydration.current)
            assertEquals(Hydration.Hydrated, y.hydration.awaitSettled())
            assertEquals(Hydration.Hydrated, x.hydration.awaitSettled())
            assertEquals(listOf("x") to listOf("y"), x.items.value to y.items.value)
            assertEquals(listOf(false, true), flags)
            sub.dispose()
            allHydrated.dispose()

            val ok = CoroutinesSpecced(null)
            val bad1 = CoroutinesSpecced("first failure")
            val bad2 = CoroutinesSpecced("second failure")
            listOf(ok, bad1, bad2).forEach { it.bindToScope(this) }
            val thrown =
                assertFailsWith<IllegalStateException> { hydrateEach(ok.hydration, bad1.hydration, bad2.hydration) }
            assertEquals("first failure", thrown.message)
            assertEquals(listOf("second failure"), thrown.suppressedExceptions.map { it.message })
            assertEquals(Hydration.Hydrated, ok.hydration.awaitSettled())
            assertEquals(Hydration.Detached, bad1.hydration.current, "a failed seed stays Detached")
            assertEquals(Hydration.Detached, bad2.hydration.current)
            coroutinesAssertNoSlotLeaked("after hydrateEach")
        }
}
