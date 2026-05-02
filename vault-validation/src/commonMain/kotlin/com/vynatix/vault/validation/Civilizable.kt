package com.vynatix.vault.validation

/**
 * A domain object created by civilizing a primitive [P] at the system boundary.
 *
 * The civilizing pattern (after [Uncivilized Primitives](https://github.com/osama-raddad/Uncivilized))
 * pushes primitive validation to the edge of the system: instead of passing raw
 * `String` / `Long` / etc. through your domain — risking primitive obsession and
 * uncaught invariant violations — you civilize the primitive into a typed
 * [Civilizable] once, and pass that typed object thereafter.
 *
 * Pair with [Civilizer] to perform the civilizing transformation, and with
 * [com.vynatix.vault.validation.CivilizingTransformer] to enforce the invariant on
 * every Vault write.
 */
interface Civilizable<out P> {
    /** The underlying primitive value — guaranteed to satisfy the civilizer's rules. */
    val value: P
}
