package com.vynatix.holdfast.stress

import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.fail

private class AtAccountStore(
    initial: Long = 0,
) : Store<AtAccountStore>() {
    val balance by state { initial }
}

/**
 * Cross-store `atomic()` in-memory 2PC under a concurrent transfer mesh.
 *
 * `atomic(...)` de-duplicates its participants and sorts them by `Store.lockOrderKey`
 * (Atomic.kt), acquires each store's transaction lock in that global order, fires ALL
 * stores' middleware `completed` hooks before ANY store commits, then commits per store
 * in lock order. These tests pin the caller-visible consequences: exact conservation
 * across mixed success/failure/argument-order frames, savepoint semantics of nested
 * frames, and the no-partial-commit guarantee of a completed-phase veto.
 *
 * Known in-memory-2PC limitation, deliberately NOT asserted against here: during the
 * per-store commit loop (phase 4) a non-participant thread can legitimately observe
 * store A already committed while store B is not yet — the stores commit sequentially
 * in lock order. Every conservation assert therefore runs at quiescence, after all
 * workers have joined; no test reads cross-store consistency mid-frame from a foreign
 * thread.
 */
class AtomicConservationStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not finish
     * within [seconds]. A lock-ordering or serializer regression in the frame paths under
     * test would otherwise burn the 10-minute test-task cap before reporting anything.
     */
    private fun completesWithin(
        seconds: Long,
        what: String,
        body: () -> Unit,
    ) {
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val worker =
            Thread {
                try {
                    body()
                } catch (e: Throwable) {
                    thrown.set(e)
                } finally {
                    done.countDown()
                }
            }
        worker.isDaemon = true
        worker.name = "atomic-conservation-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — a frame is deadlocked or spinning")
        }
        thrown.get()?.let { throw it }
    }

    /** Start [threadCount] named daemon workers, release them together, and join them all. */
    private fun runWorkers(
        threadCount: Int,
        namePrefix: String,
        body: (Int) -> Unit,
    ) {
        val start = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>(null)
        val workers =
            (0 until threadCount).map { t ->
                Thread {
                    try {
                        start.await(10, TimeUnit.SECONDS)
                        body(t)
                    } catch (e: Throwable) {
                        firstFailure.compareAndSet(null, e)
                    }
                }.apply {
                    isDaemon = true
                    name = "$namePrefix-$t"
                }
            }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        firstFailure.get()?.let { throw it }
    }

    /**
     * Six mesh threads run seeded random transfers between four accounts: random ordered
     * pair, random amount 1..10, random ARGUMENT order — `atomic(a, b)` and `atomic(b, a)`
     * must behave identically because atomic sorts by lock-order key internally — with
     * some iterations nesting the transfer in an inner same-participant frame, and every
     * 5th iteration throwing AFTER both writes are staged. Two mixer threads meanwhile
     * hammer the same accounts with net-zero single-store actions.
     *
     * The accounting is exact, not probabilistic: an injected iteration MUST return Error
     * carrying the injected exception (both writes rolled back), and every other iteration
     * MUST return Success — there is no other failure source (no middleware, no observers,
     * no contract violations, and lock contention only blocks, never fails). Each account
     * must end at initial-plus-committed-deltas, and the grand total at exactly 4 x 1000.
     */
    @Test
    fun `seeded transfer mesh with injected failures and net-zero mixers conserves every account exactly`() {
        completesWithin(60, "transfer mesh storm") {
            val stores = List(4) { AtAccountStore(initial = 1_000) }
            // First delegate read registers each state before any cross-thread race.
            stores.forEach { assertEquals(1_000L, it.balance.value) }

            val meshThreads = 6
            val meshIterations = 400
            val mixerThreads = 2
            val mixerIterations = 1_500

            val expectedDelta = AtomicLongArray(stores.size)
            val successFrames = AtomicInteger(0)
            val errorFrames = AtomicInteger(0)

            runWorkers(meshThreads + mixerThreads, "at-mesh") { t ->
                if (t < meshThreads) {
                    val rnd = Random(1_000L + t)
                    repeat(meshIterations) { i ->
                        val fromIdx = rnd.nextInt(stores.size)
                        var toIdx = rnd.nextInt(stores.size)
                        if (toIdx == fromIdx) toIdx = (toIdx + 1) % stores.size
                        val from = stores[fromIdx]
                        val to = stores[toIdx]
                        val amount = (rnd.nextInt(10) + 1).toLong()
                        val swapArgs = rnd.nextBoolean()
                        val inject = i % 5 == 0
                        val nested = i % 7 == 3
                        val transfer: () -> Unit = {
                            from.action { balance update { it - amount } }
                            to.action { balance update { it + amount } }
                            // Thrown AFTER both writes are staged: rollback must discard both.
                            if (inject) error("injected-transfer-failure")
                        }
                        val argFirst = if (swapArgs) to else from
                        val argSecond = if (swapArgs) from else to
                        val result: TransactionResult<*> =
                            if (nested) {
                                // Inner same-participant frame: savepoint merge on success;
                                // an injected inner throw escalates (Strict enclosing policy)
                                // and aborts the outer frame with the same exception.
                                atomic(argFirst, argSecond) { atomic(from, to, body = transfer) }
                            } else {
                                atomic(argFirst, argSecond, body = transfer)
                            }
                        if (inject) {
                            val err =
                                assertIs<TransactionResult.Error>(
                                    result,
                                    "thread $t iteration $i: injected frame must report Error",
                                )
                            assertEquals(
                                "injected-transfer-failure",
                                err.exception.message,
                                "thread $t iteration $i: the Error must carry the injected exception",
                            )
                            errorFrames.incrementAndGet()
                        } else {
                            assertIs<TransactionResult.Success<*>>(
                                result,
                                "thread $t iteration $i: a clean transfer frame must commit",
                            )
                            successFrames.incrementAndGet()
                            expectedDelta.addAndGet(fromIdx, -amount)
                            expectedDelta.addAndGet(toIdx, amount)
                        }
                    }
                } else {
                    repeat(mixerIterations) { i ->
                        val mixer = stores[(t + i) % stores.size]
                        val r =
                            mixer action {
                                balance update { it + 1 }
                                balance update { it - 1 }
                            }
                        assertIs<TransactionResult.Success<*>>(r, "net-zero mixer action must commit")
                    }
                }
            }

            val expectedInjected = meshThreads * (meshIterations / 5)
            assertEquals(expectedInjected, errorFrames.get(), "every injected frame, and only those, may fail")
            assertEquals(
                meshThreads * meshIterations - expectedInjected,
                successFrames.get(),
                "every non-injected frame must have committed",
            )
            for (i in stores.indices) {
                assertEquals(
                    1_000L + expectedDelta.get(i),
                    stores[i].balance.value,
                    "account $i must equal its initial balance plus the committed transfer deltas",
                )
            }
            assertEquals(4_000L, stores.sumOf { it.balance.value }, "total money must be conserved")

            // Liveness sentinel: the stores still accept and apply a plain action.
            val sentinel = stores[0] action { balance update { it + 7 } }
            assertIs<TransactionResult.Success<*>>(sentinel, "store must stay live after the storm")
            assertEquals(1_000L + expectedDelta.get(0) + 7L, stores[0].balance.value)
        }
    }

    /**
     * A nested frame over the same participants opens SAVEPOINTS of the outer roots:
     * its commit merges into the enclosing scope (visible to owner-thread reads before
     * the outer commit), and only the outer commit applies anything to state.
     */
    @Test
    fun `nested frame commit merges into the outer frame as a savepoint`() {
        completesWithin(15, "nested savepoint merge") {
            val a = AtAccountStore(initial = 1_000)
            val b = AtAccountStore(initial = 1_000)
            assertEquals(1_000L, a.balance.value)
            assertEquals(1_000L, b.balance.value)

            val r =
                atomic(a, b) {
                    a.action { balance update { it - 100 } }
                    val inner =
                        atomic(a, b) {
                            a.action { balance update { it - 50 } }
                            b.action { balance update { it + 50 } }
                        }
                    assertIs<TransactionResult.Success<*>>(inner, "inner frame commits as a savepoint merge")
                    assertEquals(
                        850L,
                        a.balance.value,
                        "owner-thread read must see the merged inner debit before the outer commit",
                    )
                    b.action { balance update { it + 100 } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(850L, a.balance.value)
            assertEquals(1_150L, b.balance.value)
            assertEquals(2_000L, a.balance.value + b.balance.value, "merge must conserve the total")
        }
    }

    /**
     * Escalation contract (Atomic.kt): a nested frame's body throw rolls back the inner
     * savepoints, and — because the ENCLOSING policy is Strict — the inner Error is
     * RETHROWN (the same exception instance) out of the inner `atomic` call, aborting the
     * outer frame. Everything staged by the outer frame before the inner one rolls back too.
     */
    @Test
    fun `inner frame error escalates under the default policy and rolls back the whole frame`() {
        completesWithin(15, "nested escalation rollback") {
            val a = AtAccountStore(initial = 1_000)
            val b = AtAccountStore(initial = 1_000)
            assertEquals(1_000L, a.balance.value)
            assertEquals(1_000L, b.balance.value)

            val boom = IllegalStateException("inner-frame-boom")
            val r =
                atomic(a, b) {
                    a.action { balance update { it - 100 } }
                    atomic(a, b) {
                        a.action { balance update { it - 50 } }
                        b.action { balance update { it + 50 } }
                        throw boom
                    }
                    fail("unreached: the inner Error must escalate as a throw under FramePolicy.Strict")
                }
            val err = assertIs<TransactionResult.Error>(r)
            assertSame(boom, err.exception, "the outer Error must carry the inner exception instance")
            assertEquals(1_000L, a.balance.value, "outer frame's own staged debit rolled back with the escalation")
            assertEquals(1_000L, b.balance.value)
        }
    }

    /**
     * With `FramePolicy.TolerateInnerErrors` on the ENCLOSING frame, a failing nested
     * frame RETURNS its Error instead of rethrowing (Atomic.kt checks the enclosing
     * policy, not the inner frame's own). The inner savepoints are discarded, the outer
     * frame stays alive, and only the outer writes commit.
     */
    @Test
    fun `tolerant outer policy returns the inner error and commits only the outer writes`() {
        completesWithin(15, "tolerated inner frame error") {
            val a = AtAccountStore(initial = 1_000)
            val b = AtAccountStore(initial = 1_000)
            assertEquals(1_000L, a.balance.value)
            assertEquals(1_000L, b.balance.value)

            val r =
                atomic(a, b, policy = FramePolicy.TolerateInnerErrors) {
                    a.action { balance update { it - 100 } }
                    val inner =
                        atomic(a, b) {
                            a.action { balance update { it - 50 } }
                            error("tolerated-inner-boom")
                        }
                    assertIs<TransactionResult.Error>(
                        inner,
                        "under a tolerant enclosing policy the inner Error is returned, not rethrown",
                    )
                    b.action { balance update { it + 100 } }
                }
            assertIs<TransactionResult.Success<*>>(r, "the outer frame commits despite the tolerated inner error")
            assertEquals(900L, a.balance.value, "outer debit committed; the inner -50 savepoint was discarded")
            assertEquals(1_100L, b.balance.value)
            assertEquals(2_000L, a.balance.value + b.balance.value)
        }
    }

    /**
     * What the code actually contracts for "inner throw with outer catch": escalation is
     * implemented as a rethrow of the inner exception through the outer body, so an outer
     * body that catches it converts escalation into tolerate-style handling — the outer
     * frame stays alive and commits its own writes, while the inner frame's writes remain
     * rolled back either way.
     */
    @Test
    fun `an outer body may catch the escalated inner exception and keep the frame alive`() {
        completesWithin(15, "caught escalation") {
            val a = AtAccountStore(initial = 1_000)
            val b = AtAccountStore(initial = 1_000)
            assertEquals(1_000L, a.balance.value)
            assertEquals(1_000L, b.balance.value)

            val boom = IllegalStateException("escalated-then-caught")
            val r =
                atomic(a, b) {
                    a.action { balance update { it - 100 } }
                    try {
                        atomic(a, b) {
                            b.action { balance update { it + 999 } }
                            throw boom
                        }
                    } catch (e: IllegalStateException) {
                        assertSame(boom, e, "the escalated throw is the inner exception instance")
                    }
                    b.action { balance update { it + 100 } }
                }
            assertIs<TransactionResult.Success<*>>(r, "catching the escalation keeps the outer frame alive")
            assertEquals(900L, a.balance.value)
            assertEquals(1_100L, b.balance.value, "the inner +999 stayed rolled back")
        }
    }

    /**
     * Frame middleware phasing: ALL participants' `onTransactionCompleted` hooks fire
     * before ANY participant commits (Atomic.kt executeBody phase 2 vs phase 3). A veto —
     * a completed hook throwing on the LAST lock-order store, which reads the staged
     * value through owner-thread read-your-own-writes — must therefore roll back all four
     * participants with no partial commit, even though the first three stores were fully
     * "completed" when the veto fired. A follow-up benign frame proves the same middleware
     * lets clean frames through and the stores stayed live.
     */
    @Test
    fun `completed-phase veto on the last lock-order participant rolls back all four stores`() {
        completesWithin(15, "completed-phase veto") {
            // Construction order gives strictly ascending lockOrderKeys, so stores[3] is
            // always the LAST store to commit in the frame's lock order.
            val stores = List(4) { AtAccountStore(initial = 1_000) }
            stores.forEach { assertEquals(1_000L, it.balance.value) }

            val vetoMarker = -777L
            val vetoCount = AtomicInteger(0)
            stores[3].middlewares(
                object : Middleware<AtAccountStore>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<AtAccountStore>) {
                        // The frame root is still installed and owned by this thread, so
                        // this read sees the staged (pre-commit) value.
                        if (context.store.balance.value == vetoMarker) {
                            vetoCount.incrementAndGet()
                            error("veto-on-last-participant")
                        }
                    }
                },
            )

            val vetoed =
                atomic(stores[0], stores[1], stores[2], stores[3]) {
                    // Bare mutate stages directly into each frame root without opening a
                    // nested action, so the store middleware fires ONLY at frame phases.
                    stores[0] { balance mutate 990L }
                    stores[1] { balance mutate 1_004L }
                    stores[2] { balance mutate 1_006L }
                    stores[3] { balance mutate vetoMarker }
                }
            val err = assertIs<TransactionResult.Error>(vetoed, "the veto must abort the frame")
            assertEquals("veto-on-last-participant", err.exception.message)
            assertEquals(1, vetoCount.get(), "the veto hook fired exactly once")
            assertEquals(
                listOf(1_000L, 1_000L, 1_000L, 1_000L),
                stores.map { it.balance.value },
                "no participant may commit when the last store's completed hook vetoes the frame",
            )

            val benign =
                atomic(stores[0], stores[1], stores[2], stores[3]) {
                    stores[0] { balance mutate 990L }
                    stores[3] { balance mutate 1_010L }
                }
            assertIs<TransactionResult.Success<*>>(benign, "a non-vetoed frame must still commit")
            assertEquals(1, vetoCount.get(), "the benign frame's staged value must not trip the veto")
            assertEquals(listOf(990L, 1_000L, 1_000L, 1_010L), stores.map { it.balance.value })
            assertEquals(4_000L, stores.sumOf { it.balance.value }, "the benign transfer conserves the total")
        }
    }
}
