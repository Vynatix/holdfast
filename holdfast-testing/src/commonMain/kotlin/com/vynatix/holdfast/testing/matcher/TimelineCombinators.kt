package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.StoreEvent
import com.vynatix.holdfast.testing.StoreHandle

// ============================================================================
// Combinators (handle-receiver) — primary public surface for TimelineMatcher.
// ============================================================================

/**
 * Set-membership match: every predicate declared in [builder] must match at
 * least one event in this handle's [StoreHandle.timeline]. Order doesn't
 * matter; events not covered by any predicate are ignored.
 *
 * Refuses never-recordable predicates (see
 * [refuseNeverRecordablePredicates]) with a teaching error before matching.
 *
 * Failure: [AssertionError] listing each unmatched predicate's
 * [EventPredicate.description] on its own line.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldFire(builder: TimelineMatcher<V>.() -> Unit) {
    val matcher = TimelineMatcher<V>(vaultRef = store).apply(builder)
    refuseNeverRecordablePredicates(matcher)
    timeline.runShouldFire(matcher)
}

/**
 * Loose-order match: each predicate must match at least one event, and the
 * matched events must appear in declaration order. Unmatched events between
 * matched ones are allowed.
 *
 * Refuses never-recordable predicates (see
 * [refuseNeverRecordablePredicates]) with a teaching error before matching.
 *
 * Failure: [AssertionError] naming the first predicate that did not match
 * after the previous predicate's matched index.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldFireInOrder(builder: TimelineMatcher<V>.() -> Unit) {
    val matcher = TimelineMatcher<V>(vaultRef = store).apply(builder)
    refuseNeverRecordablePredicates(matcher)
    timeline.runShouldFireInOrder(matcher)
}

/**
 * Strict-order match: predicates must match a contiguous run of consecutive
 * events. Once the first predicate matches at index N, every subsequent
 * predicate at offset i must match the event at N + i.
 *
 * Refuses never-recordable predicates (see
 * [refuseNeverRecordablePredicates]) with a teaching error before matching.
 *
 * Failure: [AssertionError] naming the first index where the predicate's match
 * disagreed with the actual event.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldFireInExactOrder(builder: TimelineMatcher<V>.() -> Unit) {
    val matcher = TimelineMatcher<V>(vaultRef = store).apply(builder)
    refuseNeverRecordablePredicates(matcher)
    timeline.runShouldFireInExactOrder(matcher)
}

/**
 * Negation: no predicate may match any event. Useful for asserting an
 * undesired path (e.g. rolledBack, errored) was NOT taken.
 *
 * Refuses never-recordable predicates (see
 * [refuseNeverRecordablePredicates]) with a teaching error before matching —
 * without the refusal this combinator would PASS vacuously for a predicate
 * whose events can never be recorded.
 *
 * Failure: [AssertionError] listing each predicate-event pair that did match.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldNotFire(builder: TimelineMatcher<V>.() -> Unit) {
    val matcher = TimelineMatcher<V>(vaultRef = store).apply(builder)
    refuseNeverRecordablePredicates(matcher)
    timeline.runShouldNotFire(matcher)
}

// ============================================================================
// Never-recordable refusal — handle-receiver combinators only.
// ============================================================================

/**
 * Refuse [matcher]'s predicates whose events can never appear on a recorded
 * timeline, so they fail with a teaching error instead of passing vacuously
 * ([shouldNotFire]) or dying as a generic "did not match" ([shouldFire]).
 * Two categories exist on a tracked handle:
 *
 *  - `middleware { }` predicates the recorder's self-events cannot satisfy:
 *    the recorder is the only middleware whose lifecycle events are recorded
 *    (user middlewares are not wrapped — no public `:holdfast` hook in v1),
 *    so a predicate that cannot match the recorder can never match at all.
 *  - `bridgePublished` / `bridgeObserved` predicates on a state whose bridge
 *    the recorder never wrapped: only bridges on states visible at `track(v)`
 *    time are wrapped, so such a state can never produce bridge events.
 *
 * Skipped under [com.vynatix.holdfast.testing.Capture.None] (recording is
 * opted out of entirely, documented as such) and for the synthetic
 * `List<StoreEvent>` receivers (a hand-built timeline may legitimately hold
 * events a recorder could not produce). Mirrors how Secret value-matchers
 * refuse with a teaching error instead of comparing `Redacted`.
 */
private fun <V : Store<V>> StoreHandle<V>.refuseNeverRecordablePredicates(matcher: TimelineMatcher<V>) {
    if (captureMode is Capture.None) return
    for (predicate in matcher.predicates) {
        when (predicate) {
            is MiddlewareStartedPredicate ->
                refuseUnrecordableMiddleware(predicate.classMatch, predicate.instanceMatch, predicate.label)
            is MiddlewareCompletedPredicate ->
                refuseUnrecordableMiddleware(predicate.classMatch, predicate.instanceMatch, predicate.label)
            is MiddlewareErroredPredicate ->
                refuseUnrecordableMiddleware(predicate.classMatch, predicate.instanceMatch, predicate.label)
            is BridgePublishedPredicate ->
                refuseUnwrappedBridge(predicate.target, predicate.propName, "bridgePublished")
            is BridgeObservedPredicate ->
                refuseUnwrappedBridge(predicate.target, predicate.propName, "bridgeObserved")
            else -> {}
        }
    }
}

/**
 * Throw for a middleware predicate whose scope ([classMatch]/[instanceMatch])
 * cannot match this handle's recorder — the only source of middleware events
 * on a recorded timeline — naming why and what to assert instead.
 */
private fun <V : Store<V>> StoreHandle<V>.refuseUnrecordableMiddleware(
    classMatch: ((Middleware<*>) -> Boolean)?,
    instanceMatch: Middleware<*>?,
    label: String,
) {
    val recorder = recorder ?: return
    if (middlewareMatches(recorder, classMatch, instanceMatch)) return
    error(
        "middleware<$label> can never match this timeline: the harness's recorder is the only middleware " +
            "whose started/completed/errored events are recorded — user middlewares are not wrapped (no public " +
            ":holdfast hook for that in v1), so their lifecycle events never appear. Assert on the transaction " +
            "lifecycle (started/committed/rolledBack/errored), or record invocations inside your middleware " +
            "and assert on that recording.",
    )
}

/**
 * Throw for a bridge predicate on [state] whose bridge the recorder never
 * wrapped — only bridges on states visible at `track(v)` time are wrapped, so
 * its publishes and inbound values can never reach the timeline.
 */
private fun <V : Store<V>> StoreHandle<V>.refuseUnwrappedBridge(
    state: State<*>,
    propName: String,
    surface: String,
) {
    if (isBridgeRecordable(state)) return
    error(
        "$surface($propName) can never match this timeline: the recorder records bridge events only for " +
            "bridges it wrapped when the store was tracked, and this state's bridge was not wrapped (none is " +
            "attached, it was attached after track(v), or the state was not materialized then — touch the state " +
            "once first). Attach the bridge and read the state before track(v), or assert on a RecordingBridge " +
            "or LatchedBridge you hold yourself.",
    )
}

// ============================================================================
// Combinators (list-receiver) — synthetic-timeline form for matcher self-tests.
//
// Predicates that need a store context (e.g. `emitted(MyStore::count)`) throw
// [IllegalStateException] when invoked via these overloads — use the
// [StoreHandle]-receiver form for those.
// ============================================================================

/** [shouldFire] for synthetic timelines. */
infix fun List<StoreEvent>.shouldFire(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldFire(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}

/** [shouldFireInOrder] for synthetic timelines. */
infix fun List<StoreEvent>.shouldFireInOrder(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldFireInOrder(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}

/** [shouldFireInExactOrder] for synthetic timelines. */
infix fun List<StoreEvent>.shouldFireInExactOrder(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldFireInExactOrder(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}

/** [shouldNotFire] for synthetic timelines. */
infix fun List<StoreEvent>.shouldNotFire(builder: TimelineMatcher<Nothing>.() -> Unit) {
    runShouldNotFire(TimelineMatcher<Nothing>(vaultRef = null).apply(builder))
}
