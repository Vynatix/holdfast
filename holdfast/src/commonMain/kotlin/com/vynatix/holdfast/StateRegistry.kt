@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

/**
 * One store's states: every [StateDeclaration] in declaration order, and the
 * materialized [MutableState]s by name. Both maps are guarded by [lock] (the
 * store's `propertiesLock`), which is only ever held briefly and never across
 * user code — initializers run outside it (see Materialization.kt).
 */
internal class StateRegistry(
    private val store: Store<*>,
) {
    val lock = StoreLock()

    /** Materialized states by name: what `Store.properties` copies. Guarded by [lock]. */
    val states = mutableMapOf<String, MutableState<*>>()

    /** Every declaration by name, in declaration order. Guarded by [lock]. */
    private val declarations = LinkedHashMap<String, StateDeclaration<*>>()

    /**
     * Record [candidate], or return the declaration it repeats.
     *
     * A name is declared once per store. A second declaration of it binds to
     * the first when it comes from the same declaration site running again —
     * the same member property of another object delegating to this store
     * (a helper class instantiated twice), or a local delegated property
     * evaluated again (a function called twice) — so both share one state, as
     * they always did; the first declaration's initializer and transformer
     * win. Any other second declaration fails fast: a subclass redeclaring a
     * state of its base class, two different properties with one name, or a
     * local property named like a member would otherwise silently share one
     * state and one initializer.
     *
     * Local delegated properties are matched by name alone: Kotlin/Native
     * does not promise one property-reference instance per local declaration
     * site, so two different local properties with one name share one state
     * on every platform.
     */
    fun <T : Any> declare(candidate: StateDeclaration<T>): StateDeclaration<T> =
        lock.withLock {
            val existing = declarations[candidate.name]
            when {
                existing == null -> candidate.also { declarations[it.name] = it }
                repeats(existing, candidate) -> {
                    @Suppress("UNCHECKED_CAST")
                    existing as StateDeclaration<T>
                }
                else -> throw IllegalStateException(shadowingMessage(existing, candidate))
            }
        }

    private fun repeats(
        existing: StateDeclaration<*>,
        candidate: StateDeclaration<*>,
    ): Boolean =
        existing.kind == StateKind.Declared &&
            candidate.kind == StateKind.Declared &&
            existing.local == candidate.local &&
            (existing.local || existing.property == candidate.property)

    /**
     * Make [state] the live state of [decl]. Refused on a disposed store, so
     * an initializer that outlives `dispose()` cannot re-register anything,
     * and for a declaration this store no longer holds: an eagerly registered
     * state that `removeState`/`clearStates` dropped (which also drops its
     * declaration) is not created again by a caller that looked it up before
     * the drop — a restore that planned it, say — as a state no declaration
     * lists.
     */
    fun <T : Any> publish(
        decl: StateDeclaration<T>,
        state: MutableState<T>,
    ) {
        lock.withLock {
            check(!store.isDisposed) { "store disposed" }
            check(declarations[decl.name] === decl) {
                "${store.displayName} no longer declares a state named '${decl.name}' (it was removed); " +
                    "cannot create it again"
            }
            states[decl.name] = state
            decl.materialized = state
        }
    }

    /**
     * Create and register a state eagerly under [name], with [initial] as its
     * value: a [StateKind.DerivedBacking] or [StateKind.Internal] state. An
     * [StateKind.Internal] name already registered as one returns that state
     * (create-or-fetch, the `registerInternalState` contract); any other
     * existing declaration of [name] fails fast. A derived backing state
     * carries the Secret taint of its [sources] ([derivedTags]); an internal
     * state carries no tags.
     */
    @Suppress("LongParameterList") // One declaration's fields, forwarded as-is.
    fun <T : Any> registerEager(
        name: String,
        initial: T,
        transformer: Transformer<T>?,
        distinct: Boolean,
        kind: StateKind,
        sources: List<State<*>>,
    ): MutableState<T> =
        lock.withLock {
            val existing = declarations[name]
            if (existing != null) {
                check(kind == StateKind.Internal && existing.kind == StateKind.Internal) {
                    shadowingMessage(existing, kind)
                }
                @Suppress("UNCHECKED_CAST")
                (existing.materialized as MutableState<T>?)?.let { return@withLock it }
            }
            val decl =
                StateDeclaration(
                    store = store,
                    name = name,
                    kind = kind,
                    initializer = { initial },
                    transformer = transformer,
                    distinct = distinct,
                    codec = null,
                    property = null,
                    local = false,
                    sources = sources,
                    tags = if (kind == StateKind.DerivedBacking) derivedTags(sources) else emptySet(),
                )
            val state = MutableState(initial, transformer, store, distinct)
            state.declaration = decl
            declarations[name] = decl
            states[name] = state
            decl.materialized = state
            state
        }

    /** The declaration of [name], if any. */
    fun declaration(name: String): StateDeclaration<*>? = lock.withLock { declarations[name] }

    /** Every declaration, in declaration order. */
    fun declarationsInOrder(): List<StateDeclaration<*>> = lock.withLock { declarations.values.toList() }

    /** Every materialized state with its declaration, in declaration order. */
    fun materializedInOrder(): List<Pair<StateDeclaration<*>, MutableState<*>>> =
        lock.withLock { declarations.values.mapNotNull { decl -> decl.materialized?.let { decl to it } } }

    /**
     * Forget the live state of [name] (the caller holds [lock], and shuts the
     * state down after releasing it). A [StateKind.Declared] state keeps its declaration, so the next
     * read creates it again from its initializer; an eagerly registered one
     * has nothing to create it from and is dropped with its declaration.
     */
    fun dematerialize(name: String) {
        lock.withLock {
            states.remove(name)
            val decl = declarations[name] ?: return@withLock
            decl.materialized = null
            if (decl.kind != StateKind.Declared) declarations.remove(name)
        }
    }

    /**
     * Drop every state and every declaration — the store is being disposed —
     * and return the states that were live, for the caller to shut down
     * outside [lock].
     */
    fun releaseAll(): List<MutableState<*>> =
        lock.withLock {
            val live = states.values.toList()
            states.clear()
            declarations.values.forEach { it.materialized = null }
            declarations.clear()
            live
        }

    private fun shadowingMessage(
        existing: StateDeclaration<*>,
        candidate: StateDeclaration<*>,
    ): String =
        "${store.displayName} already declares a state named '${existing.name}' (${existing.describeSite()}), " +
            "and ${candidate.describeSite()} declares it again. Two declarations of one name would silently " +
            "share one state and one initializer, so the second one fails. Give one of them another name — " +
            "a subclass cannot redeclare a state its base class declares, so give the subclass's state a " +
            "name of its own."

    private fun shadowingMessage(
        existing: StateDeclaration<*>,
        kind: StateKind,
    ): String =
        "${store.displayName} already declares a state named '${existing.name}' (${existing.describeSite()}); " +
            "cannot register a ${kind.describe()} under the same name."
}
