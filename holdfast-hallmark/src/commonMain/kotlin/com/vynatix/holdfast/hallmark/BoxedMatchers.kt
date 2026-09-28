package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.tags

/**
 * Assert that this state holds a [Boxed] wrapper whose primitive is `==` to
 * [primitive]. Useful for tests that want to verify validation produced the
 * expected primitive without naming the wrapper class:
 * ```
 * ctr.read { email } shouldBeBoxedAs "alice@example.com"
 * ```
 *
 * The check unboxes via [Boxed.value] (the single member on the interface);
 * the wrapper class's `simpleName` is included in the failure message so a
 * mismatch points at the validator that produced the boxed value. For a
 * `StateTag.Secret` state (see `State.tags`), the failure message shows
 * neither value, since either may be the secret.
 *
 * Throws [AssertionError] if the unboxed primitive does not equal [primitive].
 */
@OptIn(ExperimentalStoreApi::class)
infix fun <P : Any, T : Boxed<P>> State<T>.shouldBeBoxedAs(primitive: P) {
    val box = this.value
    val unboxed = box.value
    if (unboxed != primitive) {
        val wrapper = box::class.simpleName ?: "Boxed"
        val message =
            if (StateTag.Secret in tags) {
                "Boxed mismatch on a Secret state: values withheld (from $wrapper)"
            } else {
                "Boxed mismatch: expected=$primitive actual=$unboxed (from $wrapper)"
            }
        throw AssertionError(message)
    }
}
