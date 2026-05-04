package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.testing.bridge.BridgeView

/**
 * Assert that [value] appears at least once in this view's published history.
 * Other published values are ignored — useful when only a single significant
 * publish matters and noise (e.g. a load-on-attach replay echo) is expected.
 *
 * Equality uses `==` so structurally-equal values match regardless of identity.
 * Throws [AssertionError] listing the full published history when [value] is
 * absent, so failures pinpoint what was actually published.
 */
infix fun <T : Any> BridgeView<T>.shouldHavePublished(value: T) {
    val history = published
    if (value !in history) {
        throw AssertionError(
            "Bridge did not publish $value. Published history: $history",
        )
    }
}

/**
 * Assert that this view's published history is structurally equal to [values],
 * in order. Stricter than [shouldHavePublished]: extra publishes before, after,
 * or between [values] cause the assertion to fail.
 *
 * Failure message lists `expected=$values actual=$history` so a mismatch
 * pinpoints both expected and actual sequences.
 */
infix fun <T : Any> BridgeView<T>.shouldHavePublishedInOrder(values: List<T>) {
    val history = published
    if (history != values) {
        throw AssertionError(
            "Bridge published in wrong order: expected=$values actual=$history",
        )
    }
}

/**
 * Assert that the most recent publish on this view equals [value]. Returns
 * silently if [lastPublished] equals [value]; throws [AssertionError] otherwise.
 *
 * Use when only the final state matters (e.g. coalesced updates from a
 * distinct-only state); use [shouldHavePublishedInOrder] if every intermediate
 * publish is meaningful.
 */
infix fun <T : Any> BridgeView<T>.shouldHaveLastPublished(value: T) {
    val last = lastPublished
    if (last != value) {
        throw AssertionError(
            "Bridge last published $last, expected $value (full history: $published)",
        )
    }
}
