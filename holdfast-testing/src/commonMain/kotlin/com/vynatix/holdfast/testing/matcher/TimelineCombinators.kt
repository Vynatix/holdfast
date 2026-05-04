package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.testing.HoldfastEvent
import com.vynatix.holdfast.testing.HoldfastHandle

// ============================================================================
// Combinators (handle-receiver) — primary public surface for TimelineMatcher.
// ============================================================================

/**
 * Set-membership match: every predicate declared in [builder] must match at
 * least one event in this handle's [HoldfastHandle.timeline]. Order doesn't
 * matter; events not covered by any predicate are ignored.
 *
 * Failure: [AssertionError] listing each unmatched predicate's
 * [EventPredicate.description] on its own line.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldFire(builder: TimelineMatcher<V>.() -> Unit) {
    timeline.runShouldFire(TimelineMatcher(vaultRef = vault).apply(builder))
}

/**
 * Loose-order match: each predicate must match at least one event, and the
 * matched events must appear in declaration order. Unmatched events between
 * matched ones are allowed.
 *
 * Failure: [AssertionError] naming the first predicate that did not match
 * after the previous predicate's matched index.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldFireInOrder(builder: TimelineMatcher<V>.() -> Unit) {
    timeline.runShouldFireInOrder(TimelineMatcher(vaultRef = vault).apply(builder))
}

/**
 * Strict-order match: predicates must match a contiguous run of consecutive
 * events. Once the first predicate matches at index N, every subsequent
 * predicate at offset i must match the event at N + i.
 *
 * Failure: [AssertionError] naming the first index where the predicate's match
 * disagreed with the actual event.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldFireInExactOrder(builder: TimelineMatcher<V>.() -> Unit) {
    timeline.runShouldFireInExactOrder(TimelineMatcher(vaultRef = vault).apply(builder))
}

/**
 * Negation: no predicate may match any event. Useful for asserting an
 * undesired path (e.g. rolledBack, errored) was NOT taken.
 *
 * Failure: [AssertionError] listing each predicate-event pair that did match.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldNotFire(builder: TimelineMatcher<V>.() -> Unit) {
    timeline.runShouldNotFire(TimelineMatcher(vaultRef = vault).apply(builder))
}

// ============================================================================
// Combinators (list-receiver) — synthetic-timeline form for matcher self-tests.
//
// Predicates that need a vault context (e.g. `emitted(MyVault::count)`) throw
// [IllegalStateException] when invoked via these overloads — use the
// [HoldfastHandle]-receiver form for those.
// ============================================================================

/** [shouldFire] for synthetic timelines. */
infix fun List<HoldfastEvent>.shouldFire(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldFire(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}

/** [shouldFireInOrder] for synthetic timelines. */
infix fun List<HoldfastEvent>.shouldFireInOrder(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldFireInOrder(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}

/** [shouldFireInExactOrder] for synthetic timelines. */
infix fun List<HoldfastEvent>.shouldFireInExactOrder(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldFireInExactOrder(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}

/** [shouldNotFire] for synthetic timelines. */
infix fun List<HoldfastEvent>.shouldNotFire(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldNotFire(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}
