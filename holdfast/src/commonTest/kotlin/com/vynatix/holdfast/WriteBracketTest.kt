@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A value whose `equals` runs [probe]: a `distinct` state compares its current
 * value with the new one while its commit applies, inside the write bracket.
 */
private class Probed(
    val n: Int,
    private val probe: () -> Unit,
) {
    override fun equals(other: Any?): Boolean {
        probe()
        return other is Probed && other.n == n
    }

    override fun hashCode(): Int = n
}

private class BracketFirst : Store<BracketFirst>() {
    val plain by state { 0 }
    var probe: () -> Unit = {}
    val watched by state(distinct = true) { Probed(0) { probe() } }
}

private class BracketSecond : Store<BracketSecond>() {
    val plain by state { 0 }
    var probe: () -> Unit = {}
    val watched by state(distinct = true) { Probed(0) { probe() } }
}

private class BracketThird : Store<BracketThird>() {
    val plain by state { 0 }
}

/** Records whether a participant's middleware heard of an error. */
private class ErrorHook<V : Store<V>> : Middleware<V>() {
    var errors = 0

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        errors++
    }
}

private fun MutableState<*>.bracketOpen(): Boolean = writesBegun.value != writesEnded.value

private fun bracketsBalanced(vararg states: State<*>): Boolean = states.none { (it as MutableState<*>).bracketOpen() }

/**
 * One write bracket spans a frame's whole apply pass (issue #20, R9; plan
 * decision D7): every participant's states are bracketed before any of them is
 * assigned and closed only after the last one is — the multi-store cut a
 * consistent read relies on — and the brackets balance whether the frame
 * commits or its apply fails.
 */
class WriteBracketTest {
    @Test fun oneBracketSpansEveryParticipantOfAFrame() {
        val first = BracketFirst()
        val second = BracketSecond()
        assertTrue(first.lockOrderKey < second.lockOrderKey)
        val firstPlain = first.plain as MutableState<*>
        val secondPlain = second.plain as MutableState<*>
        // Materialize outside the frame, so the probes run only in the apply pass.
        first.watched.value
        second.watched.value
        var laterOpenWhileFirstApplies = false
        var earlierOpenWhileSecondApplies = false
        first.probe = { laterOpenWhileFirstApplies = secondPlain.bracketOpen() }
        second.probe = { earlierOpenWhileSecondApplies = firstPlain.bracketOpen() }

        atomic(first, second) {
            first {
                plain mutate 1
                watched mutate Probed(1) {}
            }
            second {
                plain mutate 2
                watched mutate Probed(2) {}
            }
        }.getOrThrow()

        assertTrue(laterOpenWhileFirstApplies, "the second participant's bracket opened before the first applied")
        assertTrue(earlierOpenWhileSecondApplies, "the first participant's bracket stays open until the second applied")
        assertTrue(bracketsBalanced(first.plain, first.watched))
        assertTrue(bracketsBalanced(second.plain, second.watched))
        assertEquals(1, first.plain.value)
        assertEquals(2, second.plain.value)
    }

    /**
     * A `distinct` state's `equals` that throws fails the apply: the frame
     * reports it, and every bracket it opened is closed, so a consistent read
     * of the participants completes instead of waiting forever.
     */
    @Test fun theFrameBracketBalancesWhenTheApplyFails() {
        val first = BracketFirst()
        val second = BracketSecond()
        first.watched.value
        second.watched.value
        second.probe = { error("equals failed") }
        val firstSaw = mutableListOf<Int>()
        var initial = true
        val watch = first.plain effect { if (initial) initial = false else firstSaw += this }
        var firstRoot: Transaction? = null
        var secondRoot: Transaction? = null

        val r =
            atomic(first, second) {
                first {
                    plain mutate 1
                    firstRoot = activeTransaction
                }
                second {
                    plain mutate 2
                    watched mutate Probed(2) {}
                    secondRoot = activeTransaction
                }
            }
        watch.dispose()

        val error = assertIs<TransactionResult.Error>(r)
        val failure = assertIs<TransactionException>(error.exception)
        assertTrue("state-apply" in failure.message.orEmpty(), failure.message)
        assertTrue(bracketsBalanced(first.plain, first.watched))
        assertTrue(bracketsBalanced(second.plain, second.watched))
        assertEquals(1, first.plain.value, "the participant applied before the failure stays applied")
        assertEquals(listOf(1), firstSaw, "and fans out, once")
        assertEquals(TransactionStatus.Committed, assertNotNull(firstRoot).status)
        assertEquals(TransactionStatus.Failed, assertNotNull(secondRoot).status)
        val cut = captureConsistent(listOf(first, second))
        assertEquals(1, cut[0][first.plain])
    }

    /**
     * An apply that fails on the middle one of three participants: the one
     * before it applied, fans out and commits; the failing one ends Failed;
     * the one after it never applies and rolls back. Only that last one's
     * middleware hears of the error — the others did not roll back.
     */
    @Test fun aFailedApplyCommitsTheEarlierParticipantsAndRollsBackTheLaterOnes() {
        val first = BracketFirst()
        val second = BracketSecond()
        val third = BracketThird()
        assertTrue(first.lockOrderKey < second.lockOrderKey && second.lockOrderKey < third.lockOrderKey)
        second.watched.value
        second.probe = { error("equals failed") }
        val firstHook = ErrorHook<BracketFirst>().also { first.middlewares(it) }
        val secondHook = ErrorHook<BracketSecond>().also { second.middlewares(it) }
        val thirdHook = ErrorHook<BracketThird>().also { third.middlewares(it) }
        val firstSaw = mutableListOf<Int>()
        val thirdSaw = mutableListOf<Int>()
        var firstInitial = true
        var thirdInitial = true
        val watches =
            listOf(
                first.plain effect { if (firstInitial) firstInitial = false else firstSaw += this },
                third.plain effect { if (thirdInitial) thirdInitial = false else thirdSaw += this },
            )
        val roots = arrayOfNulls<Transaction>(3)

        val r =
            atomic(first, second, third) {
                first {
                    plain mutate 1
                    roots[0] = activeTransaction
                }
                second {
                    plain mutate 2
                    watched mutate Probed(2) {}
                    roots[1] = activeTransaction
                }
                third {
                    plain mutate 3
                    roots[2] = activeTransaction
                }
            }
        watches.forEach { it.dispose() }

        val error = assertIs<TransactionResult.Error>(r)
        assertTrue("state-apply" in error.exception.message.orEmpty(), error.exception.message)
        assertEquals(1, first.plain.value)
        assertEquals(listOf(1), firstSaw)
        assertEquals(TransactionStatus.Committed, roots[0]?.status)
        assertEquals(TransactionStatus.Failed, roots[1]?.status)
        assertEquals(0, third.plain.value)
        assertEquals(emptyList(), thirdSaw)
        assertEquals(TransactionStatus.RolledBack, roots[2]?.status)
        assertEquals(
            listOf(0, 0, 1),
            listOf(firstHook.errors, secondHook.errors, thirdHook.errors),
            "only the participant that rolled back hears of it",
        )
        assertTrue(bracketsBalanced(first.plain, first.watched))
        assertTrue(bracketsBalanced(second.plain, second.watched))
        assertTrue(bracketsBalanced(third.plain))
    }

    /** A single transaction's apply pass balances its bracket on success and failure alike. */
    @Test fun aSingleCommitsBracketBalancesOnSuccessAndFailure() {
        val first = BracketFirst()
        first.watched.value
        first
            .action {
                plain mutate 1
                watched mutate Probed(1) { first.probe() }
            }.getOrThrow()
        assertTrue(bracketsBalanced(first.plain, first.watched))

        first.probe = { error("equals failed") }
        val r =
            first.action {
                plain mutate 2
                watched mutate Probed(2) {}
            }
        assertIs<TransactionResult.Error>(r)
        assertTrue(bracketsBalanced(first.plain, first.watched))
        assertIs<StoreSnapshot>(first.snapshot(), "a consistent read completes")
    }
}
