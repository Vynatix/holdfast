package com.vynatix.holdfast

// State tags (issue #20, R3; plan D13): per-state policy the library enforces
// generically, declared with the state (`state(tags = setOf(StateTag.Secret))`)
// and read back through `State.tags` and `Store.taggedStates` (StateTags.kt).
//
// The tags are a closed set that is not exhaustive: only this module can
// subclass StateTag (its constructor is internal), and a `when` over tags
// needs an `else` branch, so a release that adds a tag breaks no source.

/**
 * A label a state carries from its declaration, which the library enforces
 * wherever the state's value could leave memory, reach a log, or be
 * overwritten by the wrong writer:
 *
 * ```
 * val token by state(tags = setOf(StateTag.Secret)) { "" }
 * val pins by state(codec = pinsCodec, tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
 * val feed by state(codec = feedCodec, tags = setOf(StateTag.Remote)) { emptyList<Item>() }
 * ```
 *
 * A state's tags never change. Read them with [State.tags]; find the states
 * of a store that carry one with [taggedStates]. Two combinations are refused
 * when the state is declared: [Secret] with [UserAuthored], and
 * [UserAuthored] with [Remote].
 *
 * The set of tags is closed — only Holdfast defines them — but not
 * exhaustive: match on them with an `else` branch, and a later release can
 * add a tag without breaking your code.
 *
 * Experimental (issue #20, R3).
 */
@ExperimentalStoreApi
abstract class StateTag internal constructor(
    private val name: String,
) {
    /** The tag's name, such as `Secret`. */
    final override fun toString(): String = name

    /**
     * A value that must never leave memory or reach a log. Reads stay
     * plaintext — `value`, observers, `effect` and `derived` see the value as
     * always — but the value is withheld everywhere it would be written out:
     *
     * - [StoreSnapshot.encode] writes it as `null` (under every
     *   [SnapshotScope], [SnapshotScope.Raw] included), and a state without a
     *   codec is listed as skipped, as any codec-less state is.
     * - A captured snapshot's [StoreSnapshot.render] shows `<redacted>` for
     *   it, and [StoreSnapshot.toString] and `MutableState.toString` show
     *   names, never a value. A [SnapshotScope.Raw] capture's
     *   [SnapshotEntry.Present] is the exception: its `toString` shows the
     *   value.
     * - A snapshot captured in any scope but [SnapshotScope.Raw] reads it as
     *   [Redacted] ([StoreSnapshot.entry], [StoreSnapshot.get]).
     * - `:holdfast-testing` timelines record [Redacted] for it, and the
     *   built-in middleware never shows it.
     *
     * A captured snapshot still holds the raw value in memory, so
     * `restore(snapshot)` puts it back: undo stays lossless. A `derived`
     * state with a Secret state among its sources is Secret too; a Secret
     * read in its `compute` but not listed as a source does not taint it.
     */
    @ExperimentalStoreApi
    object Secret : StateTag("Secret")

    /**
     * A value the user authored — pins, drafts, read markers — that sync must
     * never overwrite. `snapshot(SnapshotScope.UserAuthored)` captures
     * exactly the states that carry it, which is what a persisted overlay
     * writes. Cannot be combined with [Secret] (the overlay leaves memory) or
     * with [Remote].
     */
    @ExperimentalStoreApi
    object UserAuthored : StateTag("UserAuthored")

    /**
     * A value that comes from a remote source (a fetch, a sync) and goes
     * stale. [StoreSnapshot.encode] leaves it out unless called with
     * `includeRemote = true`, and a sterile [restore] (`sterile = true`)
     * ignores the snapshot's value for it and resets it to its initial value.
     * Cannot be combined with [UserAuthored].
     */
    @ExperimentalStoreApi
    object Remote : StateTag("Remote")
}

/**
 * Refuse the tag combinations no state may carry, naming the state
 * ([qualifiedName], `Store.property`): [StateTag.Secret] with
 * [StateTag.UserAuthored], and [StateTag.UserAuthored] with [StateTag.Remote].
 *
 * @throws IllegalArgumentException for a refused combination.
 */
@OptIn(ExperimentalStoreApi::class)
internal fun validateTags(
    qualifiedName: String,
    tags: Set<StateTag>,
) {
    require(StateTag.Secret !in tags || StateTag.UserAuthored !in tags) {
        "$qualifiedName cannot be tagged both Secret and UserAuthored: a UserAuthored state is what " +
            "snapshot(SnapshotScope.UserAuthored) captures for a persisted overlay, which leaves memory, and a " +
            "Secret value must never leave memory. Keep the secret in a Secret state of its own."
    }
    require(StateTag.UserAuthored !in tags || StateTag.Remote !in tags) {
        "$qualifiedName cannot be tagged both UserAuthored and Remote: sync may overwrite a Remote state and must " +
            "never overwrite what the user authored. Declare one state of each and combine them in a derived state."
    }
}
