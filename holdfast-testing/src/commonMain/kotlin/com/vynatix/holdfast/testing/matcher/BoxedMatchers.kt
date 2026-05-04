package com.vynatix.holdfast.testing.matcher

import com.vynatix.hallmark.Boxed
import com.vynatix.holdfast.State

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
 * mismatch points at the validator that produced the boxed value.
 *
 * Throws [AssertionError] if the unboxed primitive does not equal [primitive].
 */
infix fun <P : Any, T : Boxed<P>> State<T>.shouldBeBoxedAs(primitive: P) {
    val box = this.value
    val unboxed = box.value
    if (unboxed != primitive) {
        val wrapper = box::class.simpleName ?: "Boxed"
        throw AssertionError("Boxed mismatch: expected=$primitive actual=$unboxed (from $wrapper)")
    }
}
