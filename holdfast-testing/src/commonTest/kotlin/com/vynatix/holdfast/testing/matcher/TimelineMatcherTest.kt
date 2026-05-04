@file:OptIn(HoldfastInternalApi::class)

package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.HoldfastInternalApi
import com.vynatix.holdfast.testing.MiddlewareCompleted
import com.vynatix.holdfast.testing.MiddlewareErrored
import com.vynatix.holdfast.testing.MiddlewareStarted
import com.vynatix.holdfast.testing.TransactionCommitted
import com.vynatix.holdfast.testing.TransactionErrored
import com.vynatix.holdfast.testing.TransactionRolledBack
import com.vynatix.holdfast.testing.TransactionStarted
import com.vynatix.holdfast.testing.HoldfastEvent
import com.vynatix.holdfast.testing.internal.Recorder
import com.vynatix.holdfast.testing.vaultTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TimelineCountVault : Holdfast<TimelineCountVault>() {
    val count by state { 0 }
    val name by state { "x" }
}

class TimelineMatcherTest {

    // --------------------------------------------------------------------
    // Synthetic-timeline helpers — exercise predicate matching + combinators
    // without depending on Issue 06's middleware-instrumentation gaps.
    // --------------------------------------------------------------------

    private fun txn(id: String): Transaction = Transaction.createForExternal(id, ownerThreadId = 0L)
    private fun started(id: String, t: Long = 0L) = TransactionStarted(transaction = txn(id), timestamp = t)
    private fun committed(id: String, t: Long = 0L) = TransactionCommitted(transaction = txn(id), timestamp = t)
    private fun rolledBack(id: String, t: Long = 0L) = TransactionRolledBack(transaction = txn(id), timestamp = t)
    private fun errored(id: String, cause: Throwable = IllegalStateException("boom"), t: Long = 0L) = TransactionErrored(
        transaction = txn(id),
        cause = cause,
        timestamp = t,
    )

    // -------- shouldFire (set membership) --------

    @Test
    fun shouldFirePassesWhenAllPredicatesMatch() {
        val timeline = listOf<HoldfastEvent>(started("a"), committed("a"))
        timeline shouldFire {
            started
            committed
        }
    }

    @Test
    fun shouldFireIgnoresOrder() {
        // Even reversed, set-membership doesn't care.
        val timeline = listOf<HoldfastEvent>(committed("a"), started("a"))
        timeline shouldFire {
            started
            committed
        }
    }

    @Test
    fun shouldFireIgnoresUnmatchedEvents() {
        val timeline = listOf<HoldfastEvent>(started("a"), errored("a"), rolledBack("a"))
        // Only `started` is asserted; the errored/rolledBack events are ignored.
        timeline shouldFire { started }
    }

    @Test
    fun shouldFireFailsListsUnsatisfiedPredicates() {
        val timeline = listOf<HoldfastEvent>(started("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFire {
                started
                committed
                rolledBack
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "shouldFire")
        assertContains(msg, "2 predicate(s)")
        assertContains(msg, "any transaction committed")
        assertContains(msg, "any transaction rolledBack")
    }

    @Test
    fun shouldFireAcceptsEmptyBuilderAsNoOp() {
        // Vacuously true — no predicates means nothing to match.
        emptyList<HoldfastEvent>() shouldFire { }
    }

    // -------- shouldFireInOrder (loose order) --------

    @Test
    fun shouldFireInOrderPasses() {
        val timeline = listOf<HoldfastEvent>(started("a"), committed("a"))
        timeline shouldFireInOrder {
            started
            committed
        }
    }

    @Test
    fun shouldFireInOrderAllowsEventsBetween() {
        // Loose: matched events need not be consecutive.
        val timeline = listOf<HoldfastEvent>(
            started("a"),
            errored("a"), // "noise" between matched events
            rolledBack("a"),
            committed("a"),
        )
        timeline shouldFireInOrder {
            started
            committed
        }
    }

    @Test
    fun shouldFireInOrderFailsOnReorder() {
        val timeline = listOf<HoldfastEvent>(committed("a"), started("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFireInOrder {
                started
                committed
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "shouldFireInOrder")
        // After matching `started` at index 1, there is no `committed` from index 2 onward.
        assertContains(msg, "any transaction committed")
    }

    @Test
    fun shouldFireInOrderFailsWhenPredicateMissing() {
        val timeline = listOf<HoldfastEvent>(started("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFireInOrder {
                started
                committed
            }
        }
        assertContains(err.message.orEmpty(), "any transaction committed")
    }

    // -------- shouldFireInExactOrder (strict consecutive) --------

    @Test
    fun shouldFireInExactOrderPasses() {
        val timeline = listOf<HoldfastEvent>(started("a"), committed("a"))
        timeline shouldFireInExactOrder {
            started
            committed
        }
    }

    @Test
    fun shouldFireInExactOrderFindsMatchingRunInLargerTimeline() {
        // Anchored anywhere in the timeline; what matters is contiguity once anchored.
        val timeline = listOf<HoldfastEvent>(
            errored("noise"),
            started("a"),
            committed("a"),
            errored("noise2"),
        )
        timeline shouldFireInExactOrder {
            started
            committed
        }
    }

    @Test
    fun shouldFireInExactOrderFailsOnNonConsecutive() {
        // started then committed, but with an event between — strict mode rejects.
        val timeline = listOf<HoldfastEvent>(
            started("a"),
            errored("a"),
            committed("a"),
        )
        val err = assertFailsWith<AssertionError> {
            timeline shouldFireInExactOrder {
                started
                committed
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "shouldFireInExactOrder")
        assertContains(msg, "expected any transaction committed")
        // The failing event at the targeted index is TransactionErrored.
        assertContains(msg, "TransactionErrored")
    }

    @Test
    fun shouldFireInExactOrderFailsWhenAnchorNotFound() {
        val timeline = listOf<HoldfastEvent>(committed("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFireInExactOrder {
                started
                committed
            }
        }
        assertContains(err.message.orEmpty(), "predicate 0")
    }

    @Test
    fun shouldFireInExactOrderFailsWhenRunsOutOfEvents() {
        val timeline = listOf<HoldfastEvent>(started("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFireInExactOrder {
                started
                committed
            }
        }
        val msg = err.message.orEmpty()
        // Either "ran out of events" or another descriptive miss — depends on candidate count.
        assertContains(msg, "shouldFireInExactOrder")
    }

    // -------- shouldNotFire --------

    @Test
    fun shouldNotFirePassesOnAbsence() {
        val timeline = listOf<HoldfastEvent>(started("a"), committed("a"))
        timeline shouldNotFire {
            rolledBack
            errored
        }
    }

    @Test
    fun shouldNotFireFailsOnPresence() {
        val timeline = listOf<HoldfastEvent>(
            started("a"),
            errored("a", IllegalStateException("boom")),
            rolledBack("a"),
        )
        val err = assertFailsWith<AssertionError> {
            timeline shouldNotFire {
                rolledBack
                errored
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "shouldNotFire")
        assertContains(msg, "2 predicate(s)")
        assertContains(msg, "any transaction rolledBack")
        assertContains(msg, "any transaction errored")
    }

    // -------- ID-targeted variants --------

    @Test
    fun startedWithIdMatchesOnlyTargetTransaction() {
        val timeline = listOf<HoldfastEvent>(
            started("a"),
            started("b"),
            committed("a"),
            committed("b"),
        )
        timeline shouldFire {
            started("a")
            committed("b")
        }
    }

    @Test
    fun startedWithIdFailsOnMissingId() {
        val timeline = listOf<HoldfastEvent>(started("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFire {
                started("nope")
            }
        }
        assertContains(err.message.orEmpty(), "transaction 'nope' started")
    }

    @Test
    fun rolledBackWithIdMatches() {
        val timeline = listOf<HoldfastEvent>(rolledBack("X"))
        timeline shouldFire { rolledBack("X") }
    }

    @Test
    fun erroredWithIdMatches() {
        val timeline = listOf<HoldfastEvent>(errored("X"))
        timeline shouldFire { errored("X") }
    }

    // -------- Predicate descriptions in failure messages --------

    @Test
    fun unmatchedAnonymousPredicateDescriptionIsHelpful() {
        val err = assertFailsWith<AssertionError> {
            emptyList<HoldfastEvent>() shouldFire { started }
        }
        assertContains(err.message.orEmpty(), "any transaction started")
    }

    @Test
    fun unmatchedIdPredicateDescriptionIncludesId() {
        val err = assertFailsWith<AssertionError> {
            emptyList<HoldfastEvent>() shouldFire { started("foo") }
        }
        assertContains(err.message.orEmpty(), "transaction 'foo' started")
    }

    // -------- Middleware predicates against synthetic events --------

    @Test
    fun middlewareInstancePredicateMatchesByReference() {
        val mw = Recorder<TimelineCountVault>(com.vynatix.holdfast.testing.Capture.All)
        val timeline = listOf<HoldfastEvent>(
            MiddlewareStarted(middleware = mw, transaction = txn("a"), timestamp = 0L),
            MiddlewareCompleted(middleware = mw, transaction = txn("a"), timestamp = 1L),
        )
        timeline shouldFire {
            middleware(mw).started
            middleware(mw).completed
        }
    }

    @Test
    fun middlewareErroredPredicateMatches() {
        val mw = Recorder<TimelineCountVault>(com.vynatix.holdfast.testing.Capture.All)
        val timeline = listOf<HoldfastEvent>(
            MiddlewareStarted(middleware = mw, transaction = txn("a"), timestamp = 0L),
            MiddlewareErrored(middleware = mw, transaction = txn("a"), cause = IllegalStateException("e"), timestamp = 1L),
        )
        timeline shouldFireInOrder {
            middleware(mw).started
            middleware(mw).errored
        }
    }

    @Test
    fun middlewareInstancePredicateRejectsDifferentInstance() {
        val a = Recorder<TimelineCountVault>(com.vynatix.holdfast.testing.Capture.All)
        val b = Recorder<TimelineCountVault>(com.vynatix.holdfast.testing.Capture.All)
        val timeline = listOf<HoldfastEvent>(
            MiddlewareStarted(middleware = a, transaction = txn("t"), timestamp = 0L),
        )
        val err = assertFailsWith<AssertionError> {
            timeline shouldFire {
                middleware(b).started
            }
        }
        // Description still includes the (b) middleware's simpleName even though a was the actual.
        assertContains(err.message.orEmpty(), "middleware<")
    }

    // -------- KProperty1 predicates require vault context --------
    //
    // The list-receiver overload uses TimelineMatcher<Nothing>, which makes
    // emitted/bridgePublished/bridgeObserved type-unreachable from user code
    // (the KProperty1<Nothing, State<*>> constraint can't be satisfied with
    // any concrete vault property). The error message in
    // [TimelineMatcher.resolveState] is therefore a defensive guard rather
    // than something user code can hit through the public surface — exercised
    // only by direct internal callers.

    // --------------------------------------------------------------------
    // Integration: real vault + handle-receiver form
    // Exercises emitted(prop) which requires vault to resolve KProperty1.
    // --------------------------------------------------------------------

    @Test
    fun handleReceiverShouldFireInOrderForRealVault() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        ctr shouldFireInOrder {
            started
            emitted(TimelineCountVault::count)
            committed
        }
    }

    @Test
    fun handleReceiverShouldFireForRealVault() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        ctr shouldFire {
            started
            committed
        }
    }

    @Test
    fun handleReceiverShouldNotFireForRealVault() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        ctr shouldNotFire {
            rolledBack
            errored
        }
    }

    @Test
    fun handleReceiverShouldFireInExactOrderRecorderSelfEvents() = vaultTest {
        // The recorder pushes: TransactionStarted, MiddlewareStarted,
        // EmissionEvent(count), TransactionCommitted, MiddlewareCompleted.
        // Strict consecutive match for the lifecycle backbone.
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        // Strict consecutive run must enumerate every event in the actual
        // recorder stream. The recorder pushes for one mutate:
        //   TransactionStarted, MiddlewareStarted, EmissionEvent(count),
        //   TransactionCommitted, MiddlewareCompleted.
        ctr shouldFireInExactOrder {
            started
            middleware<Recorder<TimelineCountVault>>().started
            emitted(TimelineCountVault::count)
            committed
            middleware<Recorder<TimelineCountVault>>().completed
        }
    }

    @Test
    fun handleReceiverEmittedWithValueMatches() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 42 }.shouldBeSuccess()

        ctr shouldFire {
            emitted(TimelineCountVault::count, 42)
        }
    }

    @Test
    fun handleReceiverEmittedWithWrongValueFails() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        val err = assertFailsWith<AssertionError> {
            ctr shouldFire {
                emitted(TimelineCountVault::count, 99)
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "emitted(count)")
        assertContains(msg, "newValue=99")
    }

    @Test
    fun handleReceiverDistinguishesStatesByReference() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        // count was mutated — emitted(count) matches.
        ctr shouldFire { emitted(TimelineCountVault::count) }

        // name was NOT mutated — emitted(name) doesn't match.
        val err = assertFailsWith<AssertionError> {
            ctr shouldFire { emitted(TimelineCountVault::name) }
        }
        assertContains(err.message.orEmpty(), "emitted(name)")
    }

    @Test
    fun handleReceiverErroredPathFiresRollbackAndErrored() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action<Unit> { throw IllegalStateException("boom") }
            .shouldBeError<IllegalStateException>()

        // Both errored and rolledBack should be present (recorder synthesises rolledBack).
        ctr shouldFire {
            started
            errored
            rolledBack
        }

        // And no commit.
        ctr shouldNotFire { committed }
    }

    @Test
    fun handleReceiverExposesSameVaultRefAcrossPredicates() = vaultTest {
        // Two emitted predicates against different states — both resolve via the
        // handle's vault, so ordering of declaration doesn't break resolution.
        val ctr = track(TimelineCountVault())
        ctr.action {
            count mutate 1
            name mutate "y"
        }.shouldBeSuccess()

        ctr shouldFire {
            emitted(TimelineCountVault::count)
            emitted(TimelineCountVault::name)
        }
    }

    @Test
    fun handleReceiverShouldFireInExactOrderFailureMessage() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        // Real timeline contains MiddlewareStarted between TransactionStarted and EmissionEvent;
        // attempting (started, emitted) in strict mode should fail because of the intervening event.
        val err = assertFailsWith<AssertionError> {
            ctr shouldFireInExactOrder {
                started
                emitted(TimelineCountVault::count)
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "shouldFireInExactOrder")
    }

    @Test
    fun handleReceiverShouldNotFireFailsWhenEventDidFire() = vaultTest {
        val ctr = track(TimelineCountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()

        val err = assertFailsWith<AssertionError> {
            ctr shouldNotFire { committed }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "shouldNotFire")
        assertContains(msg, "any transaction committed")
        assertContains(msg, "TransactionCommitted")
    }

    // -------- Empty-builder edge cases --------

    @Test
    fun emptyBuilderForAllCombinatorsIsNoOp() {
        val timeline = listOf<HoldfastEvent>(started("a"))
        timeline shouldFire { }
        timeline shouldFireInOrder { }
        timeline shouldFireInExactOrder { }
        timeline shouldNotFire { }
    }

    @Test
    fun assertContainsTimelineSizeInOrderFailure() {
        val timeline = listOf<HoldfastEvent>(started("a"))
        val err = assertFailsWith<AssertionError> {
            timeline shouldFireInOrder {
                started
                committed
            }
        }
        // Failure mentions the size so the user sees how many events were available.
        assertTrue(err.message.orEmpty().contains("size=1"), "message=${err.message}")
    }
}
