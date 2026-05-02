package com.vynatix.vault.validation

/**
 * A domain object created by civilizing a primitive [PRIMITIVE] at the system
 * boundary.
 *
 * The civilizing pattern (after [Uncivilized Primitives](https://github.com/osama-raddad/Uncivilized))
 * pushes primitive validation to the edge of the system: instead of passing raw
 * `String` / `Long` / etc. through your domain — risking primitive obsession and
 * uncaught invariant violations — you civilize the primitive into a typed
 * [Civilizable] once, and pass that typed object thereafter.
 */
interface Civilizable<PRIMITIVE> {
    val value: PRIMITIVE
}
