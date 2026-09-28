package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Sixteen states: enough distinct pending writes to grow a transaction's buffer past its initial capacity. */
private class SixteenStates : Store<SixteenStates>() {
    val s0 by state { 0 }
    val s1 by state { 0 }
    val s2 by state { 0 }
    val s3 by state { 0 }
    val s4 by state { 0 }
    val s5 by state { 0 }
    val s6 by state { 0 }
    val s7 by state { 0 }
    val s8 by state { 0 }
    val s9 by state { 0 }
    val s10 by state { 0 }
    val s11 by state { 0 }
    val s12 by state { 0 }
    val s13 by state { 0 }
    val s14 by state { 0 }
    val s15 by state { 0 }

    val all: List<State<Int>>
        get() = listOf(s0, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13, s14, s15)
}

private const val ROUNDS = 200
private const val VALUES_PER_ROUND = 20

/**
 * A `suspendAction` records the thread it started on as its transaction's
 * owner, and while it holds the store a bare `mutate` from ANY thread stages
 * into its transaction. So while its body is parked in
 * `withContext(Dispatchers.IO) { x mutate v }`, a read of `x.value` on the
 * original thread passes the owner gate and peeks at the pending writes the
 * IO thread is putting — the one place a transaction's buffer is read and
 * written by two threads at once (PR #22 review, F4). Each level's buffer is
 * read under its pending lock, like the writes into it, so the peek sees
 * every write that landed before it and never walks a map mid-resize.
 *
 * A data race has no deterministic test; this is a stress guard: many rounds
 * of an owner-thread reader against an IO-thread writer, checking that no
 * read throws, that what the reader sees never goes backwards (a write that
 * landed is never hidden by a later peek), and that every write commits.
 */
class SuspendActionPendingReadRaceTest {
    @Test fun ownerThreadReadsSeeForeignThreadWritesInOrderUnderASuspendAction() {
        val store = SixteenStates()
        val states = store.all
        try {
            hydrationWatchdog(120, "$ROUNDS rounds of owner-thread reads against IO-thread writes") {
                repeat(ROUNDS) { round -> store.raceOneRound(states, base = round * VALUES_PER_ROUND) }
            }
        } finally {
            store.dispose()
        }
    }

    /**
     * One `suspendAction` whose body writes `base + 1 .. base + VALUES_PER_ROUND`
     * into every state from an IO thread while a coroutine on the owner thread
     * reads every state, checking each read is at least the last one it saw
     * of that state — the committed [base] or a pending write — until the
     * writes are done; then the action commits and every state holds the last
     * value.
     */
    private fun SixteenStates.raceOneRound(
        states: List<State<Int>>,
        base: Int,
    ) {
        runBlocking {
            suspendAction {
                val ownerThread = Thread.currentThread()
                val writesDone = AtomicBoolean(false)
                coroutineScope {
                    val reader =
                        launch {
                            val last = IntArray(states.size) { base }
                            while (!writesDone.get()) {
                                assertSame(ownerThread, Thread.currentThread(), "the reader must read on the owner thread")
                                states.forEachIndexed { i, state ->
                                    val seen = state.value
                                    assertTrue(seen >= last[i], "state $i went from ${last[i]} back to $seen")
                                    last[i] = seen
                                }
                                // Let the body's continuation run once its IO block returns.
                                yield()
                            }
                        }
                    withContext(Dispatchers.IO) {
                        assertNotSame(ownerThread, Thread.currentThread(), "the writer must write from another thread")
                        for (v in 1..VALUES_PER_ROUND) {
                            for (state in states) state mutate base + v
                        }
                    }
                    writesDone.set(true)
                    reader.join()
                }
            }.getOrThrow()
        }
        states.forEachIndexed { i, state ->
            assertEquals(base + VALUES_PER_ROUND, state.value, "state $i did not commit the last write")
        }
    }
}
