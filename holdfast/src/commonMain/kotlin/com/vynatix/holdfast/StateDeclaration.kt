@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlinx.atomicfu.locks.SynchronousMutex
import kotlin.reflect.KProperty

// The declaration registry (issue #20, R5; plan decisions D1-D3).
//
// A state is DECLARED when its property is delegated (`val x by state { … }`
// calls `provideDelegate` while the store is being constructed) and
// MATERIALIZED when its `MutableState` is created from the initializer: on the
// first read of the property, or when `snapshot()`/`restore()`/`reset()` needs it.
// Declaring runs no user code; materializing runs the initializer once, on one
// thread, without taking any store lock itself (see Materialization.kt).

/** What a [StateDeclaration] stands for. */
internal enum class StateKind {
    /** A property delegated to [Store.state]. Materialized lazily from its retained initializer. */
    Declared,

    /**
     * The backing state of a `derived`/`suspendDerived`, created eagerly by
     * [Store.registerDerivedBackingState]. Captured by `snapshot()` but hidden
     * from [StoreSnapshot.stateNames], and restored only into the store that
     * captured it.
     */
    DerivedBacking,

    /** A state created eagerly by [Store.registerInternalState] under a synthesized name. */
    Internal,

    /**
     * The backing state of a `DerivedState` (`derivedState`/`merged`). Never
     * registered: no registry, snapshot, restore or reset sees it. Read-only —
     * only its recompute writes it; every store write entrypoint refuses it.
     */
    ReadOnlyDerived,
}

/**
 * One named state of [store]: how to create it, and — once created — the
 * [materialized] instance. Kept in the store's registry in declaration order
 * for the store's lifetime ([Store.removeState] and [Store.clearStates] drop
 * only the materialized instance of a [StateKind.Declared] state, so a later
 * read creates it again from [initializer]); [Store.dispose] clears the
 * registry. The exception is a [StateKind.ReadOnlyDerived] declaration, which
 * only names and tags a `DerivedState`'s backing state: `createDerivedState`
 * sets it on a MutableState it builds by hand, it is never in the registry,
 * and its [materialized] stays `null`.
 *
 * [latch] and [latchOwner] belong to the store's [InitializerGraph]: the
 * latch is held by the one thread running [initializer], and [latchOwner]
 * names that thread (guarded by the graph's lock).
 */
internal class StateDeclaration<T : Any>(
    val store: Store<*>,
    val name: String,
    val kind: StateKind,
    val initializer: () -> T,
    val transformer: Transformer<T>?,
    val distinct: Boolean,
    /**
     * How a snapshot encodes this state's raw value, or `null` when it cannot
     * leave memory: `snapshot().encode()` lists the state as unencodable
     * instead. Always `null` for an eagerly registered state.
     */
    val codec: StateCodec<T>?,
    /**
     * The delegated property this declaration came from, or `null` for an
     * eagerly registered state. A second declaration of [name] from the same
     * property binds to this declaration instead of failing
     * ([StateRegistry.declare]).
     */
    val property: KProperty<*>?,
    /** Whether [property] was declared without a receiver: a local (or top-level) delegated property. */
    val local: Boolean,
    /** The sources of a [StateKind.DerivedBacking] or [StateKind.ReadOnlyDerived] state; empty otherwise. */
    val sources: List<State<*>> = emptyList(),
    /**
     * The state's [StateTag]s ([State.tags]): as declared, already validated
     * ([validateTags]); for a [StateKind.DerivedBacking] or
     * [StateKind.ReadOnlyDerived] state, the taint of its [sources]
     * ([derivedTags]); empty for an internal state.
     */
    val tags: Set<StateTag> = emptySet(),
) {
    /** The live state, or `null` until materialized (and again after `removeState`/`clearStates`/`dispose`). */
    @kotlin.concurrent.Volatile
    var materialized: MutableState<T>? = null

    /** Held by the thread running [initializer]; waiters block on it. */
    val latch = SynchronousMutex()

    /** The thread holding [latch], or [NO_LATCH_OWNER]. Guarded by the store's [InitializerGraph]. */
    var latchOwner: Long = NO_LATCH_OWNER

    /**
     * How deep in a chain of derived states this state sits: 0 for a state
     * with no [sources], one more than its deepest source for the backing of
     * a `derived` or `derivedState`. A settle scope recomputes lower ranks
     * first, so a derived state recomputes after every derived state it reads
     * ([SettleTask.settleRank]).
     */
    val settleRank: Int = settleRankOver(sources)

    /** `Store.name`, for failure messages. */
    val qualifiedName: String
        get() = "${store.displayName}.$name"
}

/** [StateDeclaration.latchOwner] while no thread holds the latch. No platform hands out this thread id. */
internal const val NO_LATCH_OWNER = Long.MIN_VALUE

/**
 * The delegate [Store.state] returns. Unbound ([declaration] `null`) as
 * returned; [provideDelegate] declares the state on [store] and returns a
 * delegate bound to that declaration, which then serves every read.
 *
 * The owning store is always [store] — the receiver of `state(…)` — never
 * `thisRef`: a delegate may be declared from outside its store, or as a local
 * delegated property, where `thisRef` is `null`.
 *
 * [getValue] on an unbound delegate declares on first read instead. That is
 * the path of code compiled against a release without [provideDelegate], and
 * of a caller invoking `getValue` by hand (a wrapper delegate that does not
 * forward [provideDelegate]); such a state is declared only once it is read.
 */
@Suppress("LongParameterList") // One declaration's fields, carried until provideDelegate names the state.
internal class DeclaringStateDelegate<T : Any>(
    private val store: Store<*>,
    private val transformer: Transformer<T>?,
    private val distinct: Boolean,
    private val codec: StateCodec<T>?,
    private val tags: Set<StateTag>,
    private val initializer: Initializer<T>,
    private val declaration: StateDeclaration<T>? = null,
) : StateDelegate<T> {
    /** The declaration an unbound delegate made on its first [getValue]. */
    @kotlin.concurrent.Volatile
    private var declaredOnRead: StateDeclaration<T>? = null

    override fun provideDelegate(
        thisRef: Any?,
        property: KProperty<*>,
    ): StateDelegate<T> {
        store.checkNotDisposed()
        val bound = declare(thisRef, property)
        return DeclaringStateDelegate(store, transformer, distinct, codec, tags, initializer, bound)
    }

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): State<T> {
        store.checkNotDisposed()
        val decl =
            declaration
                ?: declaredOnRead?.takeIf { it.name == property.name }
                ?: declareOnRead(thisRef, property)
        return decl.materialized ?: materialize(decl)
    }

    private fun declareOnRead(
        thisRef: Any?,
        property: KProperty<*>,
    ): StateDeclaration<T> = declare(thisRef, property).also { declaredOnRead = it }

    /**
     * Declare the state on [store] under [property]'s name. Its tags are
     * checked first, so a refused combination fails where the state is
     * declared, naming it.
     *
     * @throws IllegalArgumentException for a refused tag combination.
     */
    private fun declare(
        thisRef: Any?,
        property: KProperty<*>,
    ): StateDeclaration<T> {
        validateTags("${store.displayName}.${property.name}", tags)
        return store.registry.declare(
            StateDeclaration(
                store = store,
                name = property.name,
                kind = StateKind.Declared,
                initializer = initializer,
                transformer = transformer,
                distinct = distinct,
                codec = codec,
                property = property,
                local = thisRef == null,
                tags = tags,
            ),
        )
    }
}

/**
 * The delegate of `Store.state`'s experimental overload: checks that the store
 * is not disposed, and copies [tags].
 */
internal fun <T : Any> Store<*>.checkedDeclaringDelegate(
    transformer: Transformer<T>?,
    distinct: Boolean,
    codec: StateCodec<T>?,
    tags: Set<StateTag>,
    initialize: Initializer<T>,
): StateDelegate<T> {
    checkNotDisposed()
    return DeclaringStateDelegate(this, transformer, distinct, codec, tags.toSet(), initialize)
}

/** Where a declaration came from, for failure messages. */
internal fun StateDeclaration<*>.describeSite(): String =
    when {
        kind != StateKind.Declared -> "as a ${kind.describe()}"
        local -> "a local delegated property"
        else -> "a delegated property"
    }

/** A [StateKind] in words, for failure messages. */
internal fun StateKind.describe(): String =
    when (this) {
        StateKind.Declared -> "declared state"
        StateKind.DerivedBacking -> "derived backing state"
        StateKind.Internal -> "internal state"
        StateKind.ReadOnlyDerived -> "derived state (derivedState or merged)"
    }
