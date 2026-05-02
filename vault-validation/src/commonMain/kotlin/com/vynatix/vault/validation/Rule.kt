package com.vynatix.vault.validation

/**
 * A predicate over a primitive [P] used to decide whether the primitive is
 * acceptable for civilizing into a [Civilizable].
 *
 * Rules compose via [Condition]. A [Civilizer] tries each [Variation]'s
 * `condition.check(primitive, rule)` until one matches; the matched variation
 * then constructs the civilized object.
 */
fun interface Rule<in P> {
    fun validate(primitive: P): Boolean
}

/** A rule that always accepts. Useful as a no-op anchor in variation chains. */
fun <P> alwaysValid(): Rule<P> = Rule { true }

/** A rule that always rejects. Useful for tests and as a placeholder. */
fun <P> neverValid(): Rule<P> = Rule { false }
