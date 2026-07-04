package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.testing.StoreEvent

// ============================================================================
// Internal runners — shared between handle-receiver and list-receiver
// combinator overloads. The combinators in [TimelineCombinators] only build
// the [TimelineMatcher] from the user's lambda; this file owns the actual
// match-and-throw logic so failure-message formatting lives in one place.
// ============================================================================

/**
 * Set-membership runner. For each predicate in [matcher], require at least
 * one matching event in the timeline. Events that no predicate matches are
 * ignored (lenient — only the asserted set has to be covered).
 *
 * Throws [IllegalArgumentException] for vacuous matchers (empty predicate
 * list or dangling `middleware<M>()` builder) — see [TimelineMatcher.validate].
 */
internal fun List<StoreEvent>.runShouldFire(matcher: TimelineMatcher<*>) {
    matcher.validate("shouldFire")
    val unsatisfied = matcher.predicates.filter { p -> none { e -> p.matches(e) } }
    if (unsatisfied.isNotEmpty()) {
        throw AssertionError(buildShouldFireMessage(unsatisfied))
    }
}

/**
 * Loose-order runner. Walk predicates in declaration order; for each, scan
 * the timeline forward from the previous predicate's match position. Allows
 * unmatched events between matched ones.
 */
internal fun List<StoreEvent>.runShouldFireInOrder(matcher: TimelineMatcher<*>) {
    matcher.validate("shouldFireInOrder")
    var startIdx = 0
    for ((i, p) in matcher.predicates.withIndex()) {
        var matchIdx = -1
        for (idx in startIdx until size) {
            if (p.matches(this[idx])) {
                matchIdx = idx
                break
            }
        }
        if (matchIdx < 0) {
            throw AssertionError(
                "shouldFireInOrder: predicate $i (${p.description}) did not match any event " +
                    "from index $startIdx onward (timeline size=$size)",
            )
        }
        startIdx = matchIdx + 1
    }
}

/**
 * Strict-consecutive runner. Find an anchor index where predicate 0 matches;
 * verify every subsequent predicate matches the immediately following event.
 * Tries each anchor in turn so a contiguous run anywhere in the timeline
 * succeeds.
 */
internal fun List<StoreEvent>.runShouldFireInExactOrder(matcher: TimelineMatcher<*>) {
    matcher.validate("shouldFireInExactOrder")
    val first = matcher.predicates[0]
    val candidates = indices.filter { idx -> first.matches(this[idx]) }
    if (candidates.isEmpty()) {
        throw AssertionError(
            "shouldFireInExactOrder: predicate 0 (${first.description}) did not match any event",
        )
    }

    var lastFailure: AssertionError? = null
    for (anchor in candidates) {
        val failure = matchExactRunAt(anchor, matcher)
        if (failure == null) return // success — all predicates matched a consecutive run
        lastFailure = failure
    }
    // No anchor produced a clean run — surface the failure for the LAST attempted
    // anchor (most likely the user's intended position) so the message points at
    // the closest miss.
    throw lastFailure ?: AssertionError("shouldFireInExactOrder: no consecutive run matched")
}

/**
 * Negation runner. Collect every predicate-event pair where the predicate
 * matches; any non-empty result is a failure listing the unwanted matches.
 *
 * Additionally rejects middleware predicates on a real-handle receiver
 * ([TimelineMatcher.vaultRef] non-null) with [UnsupportedOperationException]:
 * user middleware lifecycle events are not captured in v1, so
 * `shouldNotFire { middleware<UserMw>().errored }` would pass vacuously
 * forever. Synthetic `List<StoreEvent>` receivers stay permissive — matcher
 * self-tests build their own timelines and CAN contain middleware events.
 */
internal fun List<StoreEvent>.runShouldNotFire(matcher: TimelineMatcher<*>) {
    matcher.validate("shouldNotFire")
    if (matcher.vaultRef != null && matcher.predicates.any { it.isMiddlewarePredicate() }) {
        throw UnsupportedOperationException(
            "shouldNotFire { middleware<...> }: user middleware lifecycle events are not captured " +
                "in v1, so this assertion would pass vacuously. Assert positively on recorder " +
                "self-events, or match middleware events on a synthetic List<StoreEvent> timeline.",
        )
    }
    val matched: List<Pair<EventPredicate, StoreEvent>> =
        matcher.predicates.flatMap { p ->
            filter { e -> p.matches(e) }.map { e -> p to e }
        }
    if (matched.isNotEmpty()) {
        throw AssertionError(buildShouldNotFireMessage(matched))
    }
}

// --------------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------------

private fun EventPredicate.isMiddlewarePredicate(): Boolean =
    this is MiddlewareStartedPredicate || this is MiddlewareCompletedPredicate || this is MiddlewareErroredPredicate

private fun List<StoreEvent>.matchExactRunAt(
    anchor: Int,
    matcher: TimelineMatcher<*>,
): AssertionError? {
    matcher.predicates.forEachIndexed { i, p ->
        val targetIdx = anchor + i
        val failure = exactRunFailureAt(targetIdx, i, p)
        if (failure != null) return failure
    }
    return null
}

private fun List<StoreEvent>.exactRunFailureAt(
    targetIdx: Int,
    predicateIdx: Int,
    p: EventPredicate,
): AssertionError? =
    when {
        targetIdx >= size ->
            AssertionError(
                "shouldFireInExactOrder: ran out of events at predicate $predicateIdx (${p.description}) — " +
                    "expected event at index $targetIdx but timeline size is $size",
            )
        !p.matches(this[targetIdx]) ->
            AssertionError(
                "shouldFireInExactOrder: at index $targetIdx, expected ${p.description} " +
                    "but got ${this[targetIdx]::class.simpleName}",
            )
        else -> null
    }

private fun buildShouldFireMessage(unsatisfied: List<EventPredicate>): String =
    buildString {
        append("shouldFire: ")
        append(unsatisfied.size)
        append(" predicate(s) did not match any event:")
        unsatisfied.forEach { p ->
            append('\n')
            append("  - ")
            append(p.description)
        }
    }

private fun buildShouldNotFireMessage(matched: List<Pair<EventPredicate, StoreEvent>>): String =
    buildString {
        append("shouldNotFire: ")
        append(matched.size)
        append(" predicate(s) unexpectedly matched:")
        matched.forEach { (p, e) ->
            append('\n')
            append("  - ")
            append(p.description)
            append(" matched ")
            append(e::class.simpleName)
        }
    }
