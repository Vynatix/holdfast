package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.State
import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.testing.HoldfastHandle
import kotlin.reflect.KProperty1

/**
 * Builder receiver used by [shouldMatch] / [shouldMatchExactly] to collect
 * `(KProperty1, expectedValue)` pairs.
 *
 * The KMP-portable form: the user references each expected state via its
 * [KProperty1] reference. No reflection is needed beyond [KProperty1.get],
 * which lives in `kotlin-stdlib` (not the optional `kotlin-reflect`) and is
 * available on all targets.
 *
 * Use the [shouldEqual] infix to record an assertion:
 * ```
 * ctr shouldMatch {
 *     MyVault::name shouldEqual "Hilde"
 *     MyVault::age  shouldEqual 30
 * }
 * ```
 */
class StateMatcher<V : Holdfast<V>> internal constructor(internal val vault: V) {

    /**
     * Assertions captured by the builder, keyed by property reference. Each
     * value is the user-supplied expected `T` for the [State] returned by
     * `prop.get(vault).value`. Order is insertion order so failure messages
     * read in the order the user wrote them.
     */
    internal val expected: MutableMap<KProperty1<V, State<*>>, Any?> = mutableMapOf()

    /**
     * Record an assertion: the [State] referenced by this [KProperty1] should
     * have a current `value` equal to [value]. The actual comparison happens
     * when the surrounding [shouldMatch] / [shouldMatchExactly] runs.
     */
    infix fun <T : Any> KProperty1<V, State<T>>.shouldEqual(value: T) {
        @Suppress("UNCHECKED_CAST")
        expected[this as KProperty1<V, State<*>>] = value
    }
}

/**
 * Lenient state-matcher: captures the assertions inside [builder] and verifies
 * each named state's `value` is `==` to the supplied expected value. Other
 * states on the vault are ignored — only the fields touched in [builder] are
 * checked.
 *
 * Throws [AssertionError] listing each mismatch as
 * `"<state-name>: expected=<X> actual=<Y>"` joined by newlines.
 *
 * Use [shouldMatchExactly] when every declared state must be asserted.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldMatch(builder: StateMatcher<V>.() -> Unit) {
    val sm = StateMatcher(vault).apply(builder)
    val mismatches = collectMismatches(sm)
    if (mismatches.isNotEmpty()) {
        throw AssertionError("State mismatch:\n${mismatches.joinToString("\n")}")
    }
}

/**
 * Strict state-matcher: same as [shouldMatch], but additionally requires every
 * state currently registered on the vault (i.e. every entry in
 * [Holdfast.properties]) to have an assertion in [builder]. States that are
 * registered but not asserted produce an [AssertionError] listing them
 * alphabetically.
 *
 * KMP note: a Holdfast registers a state lazily, on the first delegate read of
 * its property. `shouldMatchExactly` checks `vault.properties.keys`, so a
 * declared-but-never-touched state is invisible to the matcher. In practice
 * this is benign — tests reach this matcher only after exercising the vault
 * (which touches every state of interest), and listing a never-touched state
 * in [builder] would fail to type-check anyway because `prop.get(vault)` would
 * register it.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldMatchExactly(builder: StateMatcher<V>.() -> Unit) {
    val sm = StateMatcher(vault).apply(builder)

    val declaredStateNames = vault.properties.keys
    val assertedStateNames = sm.expected.keys.map { it.name }.toSet()
    val unasserted = declaredStateNames - assertedStateNames
    if (unasserted.isNotEmpty()) {
        throw AssertionError(
            "shouldMatchExactly: states not asserted: ${unasserted.sorted().joinToString()}",
        )
    }

    val mismatches = collectMismatches(sm)
    if (mismatches.isNotEmpty()) {
        throw AssertionError("State mismatch:\n${mismatches.joinToString("\n")}")
    }
}

/**
 * Snapshot equality: takes [com.vynatix.holdfast.snapshot]s of both this handle's
 * vault and [other], requires they cover the same state names, and asserts
 * each named state has the same current `value`.
 *
 * Why `value` (post-`transformer.get`) instead of the raw snapshot entries:
 * raw entries are an internal-only field on [com.vynatix.holdfast.HoldfastSnapshot]
 * (used by `restore` to round-trip without re-running the transformer). For a
 * test-level "do these vaults look the same?" check, comparing the user-visible
 * `value` is more useful — for symmetric transformers it is identical to the
 * raw value, and for asymmetric ones (e.g.
 * [com.vynatix.holdfast.crypto.EncryptingTransformer]) it compares plaintext
 * rather than ciphertext, which is what tests almost always want.
 *
 * Throws [AssertionError] on a state-name set mismatch or on any value
 * mismatch.
 */
infix fun <V : Holdfast<V>> HoldfastHandle<V>.shouldMatchSnapshotOf(other: V) {
    val mySnap = vault.snapshot()
    val otherSnap = other.snapshot()

    if (mySnap.stateNames != otherSnap.stateNames) {
        val onlyMine = (mySnap.stateNames - otherSnap.stateNames).sorted()
        val onlyOther = (otherSnap.stateNames - mySnap.stateNames).sorted()
        val parts = buildList {
            if (onlyMine.isNotEmpty()) add("only in this: ${onlyMine.joinToString()}")
            if (onlyOther.isNotEmpty()) add("only in other: ${onlyOther.joinToString()}")
        }
        throw AssertionError("Snapshot state-name mismatch — ${parts.joinToString("; ")}")
    }

    val mismatches = mySnap.stateNames.sorted().mapNotNull { name ->
        val mine = vault.getState(name)?.value
        val theirs = other.getState(name)?.value
        if (mine == theirs) null else "$name: this=$mine other=$theirs"
    }
    if (mismatches.isNotEmpty()) {
        throw AssertionError("Snapshot mismatch:\n${mismatches.joinToString("\n")}")
    }
}

/**
 * Walk [sm]'s captured assertions in insertion order, returning a list of
 * `"<state-name>: expected=<X> actual=<Y>"` strings — one per mismatch. An
 * empty list means every assertion passed.
 */
private fun <V : Holdfast<V>> collectMismatches(sm: StateMatcher<V>): List<String> = sm.expected.mapNotNull { (prop, expectedValue) ->
    val actualValue = prop.get(sm.vault).value
    if (actualValue == expectedValue) null else "${prop.name}: expected=$expectedValue actual=$actualValue"
}
