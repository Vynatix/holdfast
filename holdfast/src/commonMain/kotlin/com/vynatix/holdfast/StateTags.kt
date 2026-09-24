@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

// Reading tags back (issue #20, R3; plan D13). `State.tags` and
// `Store.taggedStates` are THE lookup every tag-driven feature uses — merged
// states, hydration's adopt policy, the persisted overlay, and issue #21's
// self-check — so a tag means the same thing everywhere. `displayValue` is
// what `:holdfast-testing` records a state's value as (Redacted for a Secret
// state).

/**
 * The tags this state carries: the ones its declaration gave it
 * (`state(tags = …) { … }`), or, for an entry of a keyed state family, the
 * family's (`keyedState(tags = …) { … }`). A `derived` (or `:holdfast-coroutines`
 * `suspendDerived`) state, and a [derivedState] or [merged] one, carries
 * [StateTag.Secret] when any of its sources does — only its listed sources
 * count, not what its `compute` reads — and never [StateTag.UserAuthored] or
 * [StateTag.Remote]. Empty for a state
 * declared without tags, for a `computed { }` state (which has no
 * declaration, and cannot be a `derived` source: a derived that reads one in
 * `compute` inherits nothing from it), and for a `MutableState` constructed
 * by hand.
 *
 * A state's tags never change, and this keeps answering after its store is
 * disposed.
 *
 * Experimental (issue #20, R3).
 */
@ExperimentalStoreApi
val State<*>.tags: Set<StateTag>
    get() = observableBacking()?.declaration?.tags ?: emptySet()

/**
 * Every state of this store that carries [tag] (see [State.tags]), in
 * declaration order: the declared states — a never-read one is materialized
 * first, its initializer running as its first read would — and, for
 * [StateTag.Secret], the `derived` states with a Secret source; then every
 * live entry of each keyed state family ([keyedState]) carrying [tag], family
 * by family in declaration order, each family's entries in the order they
 * were created (no entry is created for this).
 * Internal states registered by companion modules carry no tags. A
 * [DerivedState] ([derivedState], [merged]) is never listed: the store does
 * not register it.
 *
 * Experimental (issue #20, R3).
 *
 * @throws IllegalStateException if the store is disposed, or for an
 *   initializer cycle (see [Store.state]); what a never-read state's
 *   initializer throws propagates as is.
 */
@ExperimentalStoreApi
fun Store<*>.taggedStates(tag: StateTag): List<State<*>> {
    checkNotDisposed()
    val declared =
        declarations().filter { tag in it.tags }.mapNotNull { decl ->
            decl.materialized ?: if (decl.kind == StateKind.Declared) materialize(decl) else null
        }
    val families = registry.keyed.familiesInOrder()
    return declared + families.filter { tag in it.tags }.flatMap { it.entries.values }
}

/**
 * What a log line, a failure message or a recorded event may show of
 * [value], a value of this state: [Redacted] for a [StateTag.Secret] state,
 * [value] itself otherwise. `:holdfast-testing` records timeline events and
 * bridge publish histories through it (its matchers check the Secret tag
 * themselves before printing a value), so a Secret value never reaches a
 * timeline.
 */
@StoreInternalApi
fun State<*>.displayValue(value: Any?): Any? = displayValue(StateTag.Secret in tags, value)

/** Whether this declaration's state is tagged [StateTag.Remote]. */
internal val StateDeclaration<*>.isRemote: Boolean
    get() = StateTag.Remote in tags

/** [value], or [Redacted] when it belongs to a Secret state. */
internal fun displayValue(
    secret: Boolean,
    value: Any?,
): Any? = if (secret) Redacted else value

/** How [StoreSnapshot.render] shows a captured value: [REDACTED_TEXT] for a Secret state's. */
internal fun renderedValue(
    secret: Boolean,
    raw: Any,
): Any = if (secret) REDACTED_TEXT else raw

/** The text [StoreSnapshot.render] shows in place of a withheld value. */
internal const val REDACTED_TEXT = "<redacted>"

/**
 * The tags a `derived` backing state carries: [StateTag.Secret] when any of
 * its [sources] is Secret (a value computed from a secret is one), nothing
 * else — a derived is neither authored by the user nor fetched.
 */
internal fun derivedTags(sources: List<State<*>>): Set<StateTag> =
    if (sources.any { StateTag.Secret in it.tags }) setOf(StateTag.Secret) else emptySet()
