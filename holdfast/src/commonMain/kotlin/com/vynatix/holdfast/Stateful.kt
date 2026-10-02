package com.vynatix.holdfast

/**
 * The marker every store satisfies: something whose states a [Store] owns.
 * Named after [Eventful], which exposes `events`; this exposes the store.
 *
 * Lets a consumer interface over an inline child store reach the store that
 * owns its states, so a caller holding only the interface can still open an
 * action on it:
 *
 * ```
 * interface Draft : Stateful {
 *     val text: State<String>
 * }
 *
 * val draft: Draft = object : NodeStore(), Draft {
 *     override val text by state { "" }
 * }
 *
 * draft.owningStore action { draft.text mutate "x" }
 * ```
 *
 * Every [Store] is a `Stateful` whose [owningStore] is itself ([NodeStore]
 * is the base for anonymous children like the one above). It is the same
 * notion as [MutableState.owningStore] — the store that owns a state —
 * applied to the whole object rather than to a single state.
 *
 * The member is deliberately NOT named `store`: [Store] has
 * `operator fun invoke(block: Self.() -> R)`, so a member property named
 * `store` would shadow the top-level `store { }` child-declaration function
 * of the store tree (`com.vynatix.holdfast.tree.store`) — a member property plus
 * `invoke` on an implicit receiver beats a top-level function in Kotlin
 * overload resolution. (This is unrelated to the existing `store { }`
 * plain-invoke idiom on a store instance.)
 */
@ExperimentalStoreApi
interface Stateful {
    /** The store that owns this object's states; for a [Store], itself. */
    val owningStore: Store<*>
}
