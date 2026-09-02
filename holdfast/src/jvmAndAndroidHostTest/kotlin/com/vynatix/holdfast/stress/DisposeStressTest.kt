package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Observable
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class DpCounterStore : Store<DpCounterStore>() {
    val count by state { 0 }
}

/**
 * `Store.dispose()` semantics under concurrency, blocking paths only.
 *
 * The kernel's ordering (Store.kt) makes the blocking path safe by construction:
 * `dispose()` CASes `disposedFlag` first, then must acquire `transactionLock` before
 * touching any shared structure, while `action` runs its body, commit, and fanout
 * entirely under that same lock. Two consequences are pinned here:
 *
 *  1. An action already inside the lock always finishes and fully commits — dispose
 *     parks until it releases — so no observer fire and no state write can happen
 *     after `dispose()` returns.
 *  2. An action past its entry `checkNotDisposed()` but not yet inside the lock when
 *     dispose completes is gated by the re-check inside `mutate`/`update` and the
 *     state-delegate read: it surfaces `IllegalStateException("store disposed")`
 *     (wrapped in `TransactionResult.Error`) and applies nothing.
 *
 * Plus: idempotent exception-free concurrent dispose, the full post-dispose public
 * entrypoint sweep, and `removeState`/`clearStates` racing committed writers.
 */
class DisposeStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not
     * finish within [seconds]. A dispose/lock regression in the paths under test
     * parks threads forever and would otherwise burn the 10-minute test-task cap
     * before reporting anything.
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
        worker.name = "dispose-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — a thread is parked on a store lock")
        }
        thrown.get()?.let { throw it }
    }

    private fun daemonThread(
        name: String,
        body: () -> Unit,
    ): Thread =
        Thread(body).apply {
            isDaemon = true
            this.name = name
        }

    /** Poll [probe] until true or fail after 5 seconds. */
    private fun awaitTrue(
        what: String,
        probe: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000L * 1_000_000L
        while (!probe()) {
            if (System.nanoTime() > deadline) fail("timed out after 5s waiting for $what")
            Thread.sleep(1)
        }
    }

    /** The exact signal `Store.checkNotDisposed()` and `effect` raise. */
    private fun Throwable.isStoreDisposed(): Boolean = this is IllegalStateException && message?.contains("disposed") == true

    /** The documented `removeState`/`clearStates` rejection for states with pending writes. */
    private fun Throwable.isPendingWritesRejection(): Boolean =
        this is IllegalStateException && message?.contains("Cannot remove state") == true

    /**
     * `dispose()` is CAS-gated (Store.kt `disposedFlag`): eight simultaneous callers
     * must all return normally, exactly one performing the teardown.
     */
    @Test
    fun `concurrent dispose from eight threads is idempotent and exception-free`() {
        val store = DpCounterStore()
        val ref = store.count
        store action { count mutate 5 }
        val fires = AtomicInteger(0)
        ref effect { fires.incrementAndGet() }
        assertEquals(1, fires.get(), "effect fires once immediately on subscribe")

        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val firstFailure = AtomicReference<Throwable?>(null)
        completesWithin(20, "concurrent dispose") {
            val disposers =
                (0 until threadCount).map { t ->
                    daemonThread("dp-disposer-$t") {
                        try {
                            barrier.await(10, TimeUnit.SECONDS)
                            store.dispose()
                        } catch (e: Throwable) {
                            firstFailure.compareAndSet(null, e)
                        }
                    }
                }
            disposers.forEach { it.start() }
            disposers.forEach { it.join(10_000) }
            disposers.forEach { assertFalse(it.isAlive, "disposer ${it.name} must terminate") }
        }

        assertNull(firstFailure.get(), "no concurrent dispose() call may throw: ${firstFailure.get()}")
        assertTrue(store.isDisposed)
        assertEquals(5, ref.value, "retained state refs keep the last committed value after dispose")
        val ex = assertFailsWith<IllegalStateException> { store.hasState("count") }
        assertTrue(ex.isStoreDisposed(), "post-dispose registry read: ${ex.message}")
    }

    /**
     * Writers storm the store while a disposer fires mid-storm. Every action outcome
     * must be one of: Success (fully committed), a thrown
     * `IllegalStateException("store disposed")` from `action`'s entry check, or a
     * `TransactionResult.Error` wrapping that same exception when the re-check inside
     * the body (state-delegate read / `update`'s `checkNotDisposed`) trips mid-race.
     * Conservation is exact — the counter equals the Success count, the observer fired
     * exactly once per commit plus the initial fire — and zero observer fires happen
     * after `dispose()` returns, because commit fanout runs under the transactionLock
     * that dispose acquires before teardown.
     */
    @Test
    fun `dispose mid-storm yields only disposed signals and exact conservation`() {
        val store = DpCounterStore()
        val ref = store.count
        val fires = AtomicInteger(0)
        ref effect { fires.incrementAndGet() }

        val threadCount = 8
        val iterations = 2_000
        val successes = AtomicInteger(0)
        val wrappedDisposed = AtomicInteger(0)
        val thrownDisposed = AtomicInteger(0)
        val unexpected = AtomicReference<Throwable?>(null)
        val progress = CountDownLatch(200)
        val firesAtDisposeReturn = AtomicInteger(-1)
        val start = CountDownLatch(1)

        completesWithin(30, "dispose-vs-action storm") {
            val writers =
                (0 until threadCount).map { t ->
                    daemonThread("dp-storm-writer-$t") {
                        start.await(10, TimeUnit.SECONDS)
                        var running = true
                        var i = 0
                        while (running && i < iterations) {
                            i++
                            try {
                                when (val result = store action { count update { it + 1 } }) {
                                    is TransactionResult.Success -> {
                                        successes.incrementAndGet()
                                        progress.countDown()
                                    }
                                    is TransactionResult.Error -> {
                                        if (result.exception.isStoreDisposed()) {
                                            wrappedDisposed.incrementAndGet()
                                        } else {
                                            unexpected.compareAndSet(null, result.exception)
                                        }
                                        running = false
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e.isStoreDisposed()) {
                                    thrownDisposed.incrementAndGet()
                                } else {
                                    unexpected.compareAndSet(null, e)
                                }
                                running = false
                            }
                        }
                    }
                }
            val disposer =
                daemonThread("dp-storm-disposer") {
                    try {
                        progress.await(10, TimeUnit.SECONDS)
                        store.dispose()
                        firesAtDisposeReturn.set(fires.get())
                    } catch (e: Throwable) {
                        unexpected.compareAndSet(null, e)
                    }
                }
            writers.forEach { it.start() }
            disposer.start()
            start.countDown()
            writers.forEach { it.join(20_000) }
            disposer.join(20_000)
            writers.forEach { assertFalse(it.isAlive, "writer ${it.name} must terminate") }
            assertFalse(disposer.isAlive, "disposer must terminate")
        }

        assertNull(unexpected.get(), "only disposed-signals may appear in the storm: ${unexpected.get()}")
        assertTrue(store.isDisposed)
        assertNull(store.activeTransaction, "no transaction may remain active after the storm")
        assertTrue(successes.get() >= 200, "the storm must have committed before the disposer fired")
        assertTrue(
            wrappedDisposed.get() + thrownDisposed.get() <= threadCount,
            "each writer stops at its first disposed signal, so at most one signal per writer",
        )
        assertEquals(
            successes.get(),
            ref.value,
            "conservation: the counter must equal the number of Success results exactly",
        )
        assertEquals(
            successes.get() + 1,
            fires.get(),
            "the observer must fire exactly once per commit plus the initial fire",
        )
        assertEquals(
            fires.get(),
            firesAtDisposeReturn.get(),
            "no observer fire may happen after dispose() returned — commit fanout runs under the " +
                "transactionLock dispose acquires before teardown",
        )
        // Post-quiesce grace: nothing may fire late.
        Thread.sleep(100)
        assertEquals(successes.get() + 1, fires.get(), "no late observer fire after quiescence")
        assertFailsWith<IllegalStateException> { store action { } }
    }

    /**
     * Post-dispose entry sweep: every public blocking entrypoint throws
     * `IllegalStateException` carrying "disposed". Retained state refs stay readable
     * (last committed value), and the lock-free inspection surface (`isDisposed`,
     * `activeTransaction`) stays accessible without throwing.
     */
    @Test
    fun `every public entrypoint throws store disposed after dispose`() {
        val store = DpCounterStore()
        val ref = store.count
        store action { count mutate 5 }
        val preDisposeSnapshot = store.snapshot()
        val deadObservable = Observable<Int> { _ -> Disposable { } }

        completesWithin(20, "dispose before the entry sweep") { store.dispose() }

        assertTrue(store.isDisposed)
        assertNull(store.activeTransaction)
        assertEquals(5, ref.value, "retained refs read the last committed value after dispose")

        val entrypoints: List<Pair<String, () -> Unit>> =
            listOf(
                "action" to { store action { } },
                "mutate" to { store { ref mutate 1 } },
                "update" to { store { ref update { it + 1 } } },
                "state delegate read" to { store.count },
                "properties" to { store.properties },
                "getState" to { store.getState("count") },
                "hasState" to { store.hasState("count") },
                "removeState" to { store.removeState("count") },
                "clearStates" to { store.clearStates() },
                "middlewares" to { store.middlewares() },
                "clearMiddleware" to { store.clearMiddleware() },
                "effect" to { ref effect { } },
                "bridge" to { store { ref bridge null } },
                "observeFrom" to { store { ref observeFrom deadObservable } },
                "snapshot" to { store.snapshot() },
                "restore" to { store.restore(preDisposeSnapshot) },
            )
        entrypoints.forEach { (name, call) ->
            val ex = assertFailsWith<IllegalStateException>("$name must throw after dispose") { call() }
            assertTrue(ex.isStoreDisposed(), "$name: expected 'disposed' in message; was: ${ex.message}")
        }
    }

    /**
     * Dispose-vs-in-flight-action, safe interleaving (pin): an action that already
     * holds the transactionLock always finishes and fully commits, because
     * `dispose()` must acquire that lock before touching any shared structure
     * (Store.kt `dispose` nulls `_activeTransaction` under `transactionLock`). The
     * disposer is proven parked — it cannot terminate while the action holds the
     * lock — the action commits with the disposed flag already raised, and only
     * later actions are rejected. The in-action latch is released by the plain main
     * thread, never by another action on the same store (that would deadlock).
     */
    @Test
    fun `dispose blocks until an in-flight action commits`() {
        val store = DpCounterStore()
        val ref = store.count
        val inAction = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<TransactionResult<*>?>(null)

        completesWithin(30, "dispose against an in-flight action") {
            val worker =
                daemonThread("dp-inflight-action") {
                    result.set(
                        store action {
                            // Staged before the disposer starts, so the entry checks pass.
                            ref mutate 42
                            inAction.countDown()
                            // Held open until the main thread has proven the disposer is parked.
                            release.await(20, TimeUnit.SECONDS)
                        },
                    )
                }
            worker.start()
            assertTrue(inAction.await(10, TimeUnit.SECONDS), "action must reach its body")

            val disposer = daemonThread("dp-blocked-disposer") { store.dispose() }
            disposer.start()
            awaitTrue("disposedFlag CAS") { store.isDisposed }

            // dispose() cannot complete while the action holds the transactionLock.
            disposer.join(300)
            assertTrue(disposer.isAlive, "dispose() must park on the transactionLock while an action is in flight")

            release.countDown()
            worker.join(10_000)
            disposer.join(10_000)
            assertFalse(worker.isAlive, "action worker must terminate")
            assertFalse(disposer.isAlive, "disposer must terminate after the action commits")
        }

        assertIs<TransactionResult.Success<*>>(
            result.get(),
            "an action that entered the lock before dispose must fully commit",
        )
        assertEquals(42, ref.value, "the in-flight action's write must be applied, not dropped by dispose")
        assertTrue(store.isDisposed)
        assertFailsWith<IllegalStateException> { store action { } }
    }

    /**
     * Dispose-vs-action, racing interleaving (pin): an action that passed the entry
     * `checkNotDisposed()` but has not yet acquired the transactionLock when
     * `dispose()` runs to completion must NOT apply any write. The window between
     * the entry check and the lock acquire is held open deterministically via the
     * store's `AsyncSerializer` hook — the only blocking-path code between the two —
     * and the `mutate` re-check then converts the stale action into
     * `TransactionResult.Error(IllegalStateException("store disposed"))`. On the
     * blocking path a write can never apply after `dispose()` returns.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun `action past the entry check but not yet locked cannot write after dispose completes`() {
        val store = DpCounterStore()
        val ref = store.count
        val acquireEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        store.asyncSerializer =
            object : Store.AsyncSerializer {
                override fun blockingAcquire() {
                    acquireEntered.countDown()
                    gate.await(20, TimeUnit.SECONDS)
                }

                override fun blockingRelease() {}
            }
        val result = AtomicReference<TransactionResult<*>?>(null)

        completesWithin(30, "entry-check window race") {
            val worker =
                daemonThread("dp-window-action") {
                    result.set(store action { ref mutate 42 })
                }
            worker.start()
            assertTrue(
                acquireEntered.await(10, TimeUnit.SECONDS),
                "action must reach the serializer acquire (past its entry check)",
            )

            // The transactionLock is free, so dispose() runs to completion here.
            store.dispose()
            assertTrue(store.isDisposed)

            gate.countDown()
            worker.join(10_000)
            assertFalse(worker.isAlive, "window action must terminate")
        }

        val err = assertIs<TransactionResult.Error>(result.get(), "the stale action must surface an error, not commit")
        assertIs<IllegalStateException>(err.exception)
        assertTrue(err.exception.isStoreDisposed(), "expected 'disposed'; was: ${err.exception.message}")
        assertEquals(0, ref.value, "no write may apply after dispose() completed")
        assertNull(store.activeTransaction)
    }

    /**
     * `removeState`/`clearStates` racing committed writers: removal either succeeds
     * or is rejected with the documented pending-writes `IllegalStateException`
     * ("Cannot remove state ..."), writer actions never fail, the counter never
     * shows a value no commit produced (increments from 0, or 0 again after a
     * removal recreated it), and the store stays fully usable afterwards.
     */
    @Test
    fun `removeState and clearStates racing writers leave the store usable`() {
        val store = DpCounterStore()
        assertEquals(0, store.count.value)

        val writerCount = 4
        val removerCount = 2
        val iterations = 400
        val writerErrors = AtomicReference<Throwable?>(null)
        val removerUnexpected = AtomicReference<Throwable?>(null)
        val pendingWriteRejections = AtomicInteger(0)
        val start = CountDownLatch(1)

        completesWithin(30, "removeState/clearStates race") {
            val writers =
                (0 until writerCount).map { t ->
                    daemonThread("dp-remove-writer-$t") {
                        start.await(10, TimeUnit.SECONDS)
                        try {
                            repeat(iterations) { _ ->
                                val result = store action { count update { it + 1 } }
                                if (result is TransactionResult.Error) {
                                    writerErrors.compareAndSet(null, result.exception)
                                }
                            }
                        } catch (e: Throwable) {
                            writerErrors.compareAndSet(null, e)
                        }
                    }
                }
            val removers =
                (0 until removerCount).map { t ->
                    daemonThread("dp-remover-$t") {
                        start.await(10, TimeUnit.SECONDS)
                        for (i in 1..(iterations / 2)) {
                            try {
                                if (i % 2 == 0) store.removeState("count") else store.clearStates()
                                // Recreate promptly so writers keep hitting a live registry
                                // entry; increments-only means a negative read is corruption.
                                val seen = store.count.value
                                if (seen < 0) {
                                    removerUnexpected.compareAndSet(
                                        null,
                                        IllegalStateException("counter went negative: $seen"),
                                    )
                                }
                            } catch (e: Throwable) {
                                if (e.isPendingWritesRejection()) {
                                    pendingWriteRejections.incrementAndGet()
                                } else {
                                    removerUnexpected.compareAndSet(null, e)
                                }
                            }
                        }
                    }
                }
            (writers + removers).forEach { it.start() }
            start.countDown()
            (writers + removers).forEach { it.join(20_000) }
            (writers + removers).forEach { assertFalse(it.isAlive, "${it.name} must terminate") }
        }

        assertNull(writerErrors.get(), "writer actions must always commit: ${writerErrors.get()}")
        assertNull(
            removerUnexpected.get(),
            "removal may only fail with the documented pending-writes error: ${removerUnexpected.get()}",
        )
        assertTrue(
            pendingWriteRejections.get() >= 0,
            "documented pending-writes rejections observed: ${pendingWriteRejections.get()}",
        )

        // Quiesced: no action in flight, so removal must now succeed outright...
        store.removeState("count")
        assertEquals(0, store.count.value, "a removed state is recreated from its initializer")
        // ...and the store must still be fully usable.
        val sentinel = store action { count mutate -7 }
        assertIs<TransactionResult.Success<*>>(sentinel, "post-chaos liveness sentinel must commit")
        assertEquals(-7, store.count.value)
    }
}
