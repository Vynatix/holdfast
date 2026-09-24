package com.vynatix.holdfast

// The failure model of restore (issue #20, R1; plan D10): what a restore may
// skip, how it says what it skipped, and what it throws when it may not.

/**
 * How a [restore] treats snapshot entries it cannot restore — each one a
 * [RestoreIssue]. Whatever the policy, a restore either stages every entry it
 * restores in one transaction or changes nothing, and a state the snapshot
 * has no entry for keeps its value (its observers do not fire; a sterile
 * restore resets its [StateTag.Remote] states instead).
 *
 * Experimental (issue #20, R1).
 */
@ExperimentalStoreApi
enum class RestorePolicy {
    /** Every entry must restore: any [RestoreIssue] fails the restore, and nothing changes. */
    Strict,

    /**
     * Entries naming a state this store does not declare are skipped and
     * reported ([RestoreIssue.UnknownState]); any other issue fails the
     * restore, and nothing changes. The one-argument `restore(snapshot)` uses
     * this policy.
     */
    IgnoreUnknown,

    /** Every entry that can restore does; every other is skipped and reported. Never fails on an issue. */
    BestEffort,

    ;

    /** Whether this policy lets a restore go on past [issue]. */
    internal fun tolerates(issue: RestoreIssue): Boolean =
        when (this) {
            Strict -> false
            IgnoreUnknown -> issue is RestoreIssue.UnknownState
            BestEffort -> true
        }
}

/**
 * A snapshot entry a [restore] could not restore, and why. Names the state,
 * never its value.
 *
 * Experimental (issue #20, R1): later releases may add kinds of issue.
 */
@ExperimentalStoreApi
sealed class RestoreIssue {
    /** The name the snapshot holds the entry under. */
    abstract val stateName: String

    /** What is wrong, in words that never quote the value. */
    abstract val reason: String

    /** The snapshot names a state this store does not declare (a state renamed or removed since, say). */
    data class UnknownState(
        override val stateName: String,
    ) : RestoreIssue() {
        override val reason: String get() = "the store declares no state of this name"
    }

    /** A decoded snapshot holds text for a state declared without a codec, so nothing can decode it. */
    data class NoCodec(
        override val stateName: String,
    ) : RestoreIssue() {
        override val reason: String get() = "the snapshot holds its encoded text, and the state has no codec"
    }

    /** The entry cannot be turned into a value for the state: its codec threw, or it is not a single value. */
    data class Undecodable(
        override val stateName: String,
        override val reason: String,
    ) : RestoreIssue()

    /**
     * The value is of a class the state cannot hold: the snapshot holds a
     * [snapshotType] (a class name), and the state a [stateType]. See
     * [restore] for when values are checked.
     */
    data class TypeMismatch(
        override val stateName: String,
        val snapshotType: String,
        val stateType: String,
    ) : RestoreIssue() {
        override val reason: String
            get() = "the snapshot holds a value of class $snapshotType, and the state holds one of class $stateType"
    }
}

/**
 * What a successful [restore] did. Holds names only, never values.
 *
 * Experimental (issue #20, R1).
 */
@ExperimentalStoreApi
class RestoreReport internal constructor(
    /** The states whose snapshot value the restore staged. */
    val restored: Set<String>,
    /**
     * The states this store declares that kept their value because the
     * snapshot holds none for them: no entry at all, a state its store could
     * not encode ([StoreSnapshot.unencodableStateNames]), or a withheld one
     * (a Secret state's, in encoded text). A sterile restore's Remote states
     * are in [sterilized] instead, and a state listed here that a sterile
     * restore brought to life holds the value it computed from the restored
     * values (see [restore]).
     */
    val kept: Set<String>,
    /** The entries the restore skipped, which its [RestorePolicy] tolerated. */
    val issues: List<RestoreIssue>,
    /**
     * The [StateTag.Remote] states a sterile restore (`sterile = true`) reset
     * to their initial values, whatever the snapshot held for them — each
     * one, whether or not its value changed. Empty for any other restore.
     *
     * Experimental (issue #20, R3).
     */
    val sterilized: Set<String> = emptySet(),
) {
    override fun equals(other: Any?): Boolean =
        other is RestoreReport &&
            restored == other.restored &&
            kept == other.kept &&
            issues == other.issues &&
            sterilized == other.sterilized

    override fun hashCode(): Int {
        val names = restored.hashCode() * HASH_MULTIPLIER + kept.hashCode()
        return (names * HASH_MULTIPLIER + issues.hashCode()) * HASH_MULTIPLIER + sterilized.hashCode()
    }

    override fun toString(): String {
        val names = "restored=$restored, kept=$kept, sterilized=$sterilized"
        return "RestoreReport($names, issues=$issues)"
    }
}

/**
 * A [restore] refused a snapshot: under its [policy], these [issues] may not
 * be skipped. Nothing was changed. The message names each state and what is
 * wrong with its entry, never a value; there is no cause.
 *
 * Experimental (issue #20, R1).
 */
@ExperimentalStoreApi
class RestoreRejectedException internal constructor(
    storeName: String,
    val policy: RestorePolicy,
    val issues: List<RestoreIssue>,
) : IllegalStateException(
        "Cannot restore $storeName under RestorePolicy.$policy: " +
            issues.joinToString("; ") { "'${it.stateName}': ${it.reason}" } +
            ". Nothing was changed.",
    )

private const val HASH_MULTIPLIER = 31
