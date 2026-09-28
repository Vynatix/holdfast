package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.testing.StoreHandle
import com.vynatix.holdfast.testing.internal.PrivilegedHooks
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
 *     MyStore::name shouldEqual "Hilde"
 *     MyStore::age  shouldEqual 30
 * }
 * ```
 */
class StateMatcher<V : Store<V>> internal constructor(
    internal val store: V,
) {
    /**
     * Assertions captured by the builder, keyed by property reference. Each
     * value is the user-supplied expected `T` for the [State] returned by
     * `prop.get(store).value`. Order is insertion order so failure messages
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
 * states on the store are ignored — only the fields touched in [builder] are
 * checked.
 *
 * Throws [AssertionError] listing each mismatch as
 * `"<state-name>: expected=<X> actual=<Y>"` joined by newlines. A
 * `StateTag.Secret` state is compared like any other, but its mismatch line
 * shows neither value: `"<state-name>: does not match (a Secret state: values
 * withheld)"`.
 *
 * Use [shouldMatchExactly] when every declared state must be asserted.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldMatch(builder: StateMatcher<V>.() -> Unit) {
    val sm = StateMatcher(store).apply(builder)
    val mismatches = collectMismatches(sm)
    if (mismatches.isNotEmpty()) {
        throw AssertionError("State mismatch:\n${mismatches.joinToString("\n")}")
    }
}

/**
 * Strict state-matcher: same as [shouldMatch], but additionally requires every
 * state currently registered on the store (i.e. every entry in
 * [Store.properties]) to have an assertion in [builder]. States that are
 * registered but not asserted produce an [AssertionError] listing them
 * alphabetically.
 *
 * KMP note: a Store declares every state when it is constructed, but
 * materializes one only when it is first needed — on the first delegate read
 * of its property, or by `snapshot()`/`restore()`/`reset()`. `shouldMatchExactly`
 * checks `store.properties.keys`, which lists materialized states only, so a
 * declared-but-never-touched state is invisible to the matcher. In practice
 * this is benign — tests reach this matcher only after exercising the store
 * (which touches every state of interest), and listing a never-touched state
 * in [builder] materializes it anyway, because `prop.get(store)` reads it.
 * After a `reset()` every declared state is materialized, so
 * `shouldMatchExactly` must assert all of them.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldMatchExactly(builder: StateMatcher<V>.() -> Unit) {
    val sm = StateMatcher(store).apply(builder)

    val declaredStateNames = store.properties.keys
    val assertedStateNames =
        sm.expected.keys
            .map { it.name }
            .toSet()
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
 * store and [other], requires they cover the same state names — every state
 * each store declares, read or not, and every keyed state family (the
 * backing states of `derived` are not state names) — and asserts each named
 * state has the same current `value`, and each keyed state family the same
 * live keys with the same `value` per key.
 *
 * Why `value` (post-`transformer.get`) instead of the raw snapshot entries:
 * raw entries are an internal-only field on [com.vynatix.holdfast.StoreSnapshot]
 * (used by `restore` to round-trip without re-running the transformer). For a
 * test-level "do these vaults look the same?" check, comparing the user-visible
 * `value` is more useful — for symmetric transformers it is identical to the
 * raw value, and for asymmetric ones (e.g.
 * [com.vynatix.holdfast.crypto.EncryptingTransformer]) it compares plaintext
 * rather than ciphertext, which is what tests almost always want.
 *
 * Throws [AssertionError] on a state-name set mismatch or on any value
 * mismatch; a `StateTag.Secret` state's mismatch line shows neither value. A
 * keyed state family's mismatch line never shows a key (a key can be data): a
 * key-set mismatch gives the two entry counts, and a value mismatch the two
 * values without the key — neither value for a Secret family. A name that is
 * neither a state nor a family of both stores fails rather than passing
 * unchecked.
 */
infix fun <V : Store<V>> StoreHandle<V>.shouldMatchSnapshotOf(other: V) {
    val mySnap = store.snapshot()
    val otherSnap = other.snapshot()

    if (mySnap.stateNames != otherSnap.stateNames) {
        val onlyMine = (mySnap.stateNames - otherSnap.stateNames).sorted()
        val onlyOther = (otherSnap.stateNames - mySnap.stateNames).sorted()
        val parts =
            buildList {
                if (onlyMine.isNotEmpty()) add("only in this: ${onlyMine.joinToString()}")
                if (onlyOther.isNotEmpty()) add("only in other: ${onlyOther.joinToString()}")
            }
        throw AssertionError("Snapshot state-name mismatch — ${parts.joinToString("; ")}")
    }

    val mismatches = mySnap.stateNames.sorted().mapNotNull { name -> snapshotMismatch(store, other, name) }
    if (mismatches.isNotEmpty()) {
        throw AssertionError("Snapshot mismatch:\n${mismatches.joinToString("\n")}")
    }
}

/** [shouldMatchSnapshotOf]'s line for [name], a state or keyed state family of both stores, or `null` when equal. */
@OptIn(ExperimentalStoreApi::class)
private fun snapshotMismatch(
    mine: Store<*>,
    other: Store<*>,
    name: String,
): String? {
    val state = mine.getState(name)
    if (state != null || other.getState(name) != null) {
        val myValue = state?.value
        val theirValue = other.getState(name)?.value
        return when {
            myValue == theirValue -> null
            state != null && PrivilegedHooks.isSecret(state) -> secretMismatch(name)
            else -> "$name: this=$myValue other=$theirValue"
        }
    }
    val myFamily = PrivilegedHooks.keyedFamily(mine, name)
    val theirFamily = PrivilegedHooks.keyedFamily(other, name)
    return if (myFamily != null && theirFamily != null) {
        familyMismatch(name, LinkedHashMap(myFamily.entries), LinkedHashMap(theirFamily.entries))
    } else {
        "$name: cannot be compared (neither a state nor a keyed state family of both stores)"
    }
}

/**
 * The mismatch line for keyed state family [name], whose live entries are
 * [mine] and [theirs], or `null` when they hold the same keys with equal
 * values. Never names a key; a Secret family's line shows no value either.
 */
private fun familyMismatch(
    name: String,
    mine: Map<Any?, State<*>>,
    theirs: Map<Any?, State<*>>,
): String? {
    val differing = mine.entries.firstOrNull { (key, entry) -> entry.value != theirs[key]?.value }
    return when {
        mine.keys != theirs.keys ->
            "$name: keyed entries differ (this has ${mine.size} entries, other has ${theirs.size}; keys withheld)"
        differing == null -> null
        PrivilegedHooks.isSecret(differing.value) -> secretMismatch(name)
        else -> {
            val theirValue = theirs[differing.key]?.value
            "$name: an entry's value differs (key withheld): this=${differing.value.value} other=$theirValue"
        }
    }
}

/**
 * Walk [sm]'s captured assertions in insertion order, returning a list of
 * `"<state-name>: expected=<X> actual=<Y>"` strings — one per mismatch, a
 * Secret state's without its values. An empty list means every assertion
 * passed.
 */
private fun <V : Store<V>> collectMismatches(sm: StateMatcher<V>): List<String> =
    sm.expected.mapNotNull { (prop, expectedValue) ->
        val state = prop.get(sm.store)
        val actualValue = state.value
        when {
            actualValue == expectedValue -> null
            PrivilegedHooks.isSecret(state) -> secretMismatch(prop.name)
            else -> "${prop.name}: expected=$expectedValue actual=$actualValue"
        }
    }

/** A mismatch line for Secret state [name]: neither value, since either may be the secret. */
private fun secretMismatch(name: String): String = "$name: does not match (a Secret state: values withheld)"
