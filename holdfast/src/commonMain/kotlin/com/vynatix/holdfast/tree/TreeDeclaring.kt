@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

// Declaring a store's children, next to its states (the dynamic store tree,
// issue #21):
//
// ```
// class AppStore : Store<AppStore>() {
//     val settings by store { SettingsStore() }                              // one child, node "settings"
//     val prefs by store(named = "prefs-v2") { PrefsStore() }                // a pinned wire name
//     val session by group { listOf(SignInStore() named "sign-in", ProfileStore()) } // a group of leaves
//     val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }   // a keyed branch
//     val draft by store<Draft> { object : NodeStore(), Draft { override val text by state { "" } } }
// }
// ```
//
// A store has one parent: a second declaration of a store that already has
// one fails when it materializes, naming both parents. Disposing a store
// disposes the children its declarations BUILT — the store a `store { }`
// lambda constructed, the stores a `group { }` lambda constructed, every
// store a keyed factory built — and releases the rest (a store that existed
// before the lambda ran) as subtree roots; any declaration can opt out with
// `onParentDispose = KeyedDisposal.Release`.

/**
 * Declare one child of this store: `val settings by store { SettingsStore() }`.
 * The child lambda runs on the property's first read (or when a tree
 * operation needs this store's subtree) — once, unless racing first reads
 * each run it (see below); the store it produces attaches under this store,
 * named [named] (`NameOrigin.Pinned`) or by the property
 * (`NameOrigin.Property`; `verifyPersistedNames` flags it when the child's
 * subtree persists: pin it with [named]).
 *
 * [child] must produce a [Store]: `S` is free so an inline child can be
 * typed by its consumer interface — `store<Draft> { object : NodeStore(),
 * Draft { … } }` (the type argument is required); anything that is not a
 * store fails the first read.
 *
 * The property is a stable `val`: once materialized it answers the same
 * store for the parent's whole life — even after that store was disposed
 * (the lambda never runs again), so a disposed child stays reachable, and
 * uncollectable, for as long as the parent is. Released children of a
 * disposed parent, and keyed stores, hold no such reference.
 *
 * When this store disposes, [onParentDispose] decides what happens to the
 * child: [KeyedDisposal.Dispose] (the default) disposes it when the lambda
 * BUILT it — constructed it on the reading thread while it ran, e.g.
 * `store { SettingsStore() }` or an inline `object : NodeStore()` — and
 * releases it as a subtree root that keeps working when it existed before
 * the lambda ran (`store { sharedSettings }`); [KeyedDisposal.Release]
 * always releases it. The dispose happens where an owned keyed store's
 * does: when the entry this store's dispose ran in settles, never under a
 * lock of the tree. A lambda that builds the child on another thread hands
 * over a store it did not build here: it is released, never disposed.
 *
 * The lambda is ordinary code run on the READING thread, holding no lock or
 * latch of the tree's — only what that thread already holds. So:
 * - read inside an action, it runs inside that action and sees its
 *   UNCOMMITTED writes; the attach it leads to is structural, not
 *   transactional, and a rollback of that action does NOT undo it;
 * - read inside a state initializer, a `derivedState` compute or a
 *   `SchemaVersioned.migrate`, it inherits that no-write region, so a
 *   constructor that opens an action or writes a state fails there (the
 *   declaration stays retryable) — first-read such children outside that
 *   code;
 * - racing first reads on several threads may each run the lambda: the
 *   first result to attach wins and every reader gets it; each other run's
 *   stores that were built during that run and that nothing else holds are
 *   disposed (a store that existed before the run never is), so keep side
 *   effects other than building the child out of it;
 * - the lambda reading its own declaration again on the same thread —
 *   directly, through another child or through a state initializer — is a
 *   cycle and throws, naming the chain.
 *
 * A throwing lambda attaches nothing and runs again on the next read; an
 * attach that fails after it returned (a store that already has a parent,
 * one that would be its own ancestor, this store disposed meanwhile)
 * disposes the stores that run built.
 *
 * @throws IllegalArgumentException if [named] is empty.
 */
@ExperimentalStoreApi
fun <S : Any> Store<*>.store(
    named: String? = null,
    onParentDispose: KeyedDisposal = KeyedDisposal.Dispose,
    child: () -> S,
): StoreDeclaration<S> {
    require(named == null || named.isNotEmpty()) { "a pinned child name must not be empty" }
    return StoreDeclaration(this, named, onParentDispose, child)
}

/**
 * Declare a group of this store's children:
 * `val session by group { listOf(SignInStore() named "sign-in", ProfileStore()) }`.
 * The lambda runs on the property's first read (or when a tree operation
 * needs this store's subtree) — once, unless racing first reads each run it
 * (see below); each listed store attaches at its own leaf, named by its
 * [GroupScope.named] pin, else by its class name minus `Store`. Two stores
 * of one class in one group must be told apart: pin at least one of them.
 * The group itself is named [named] (`NameOrigin.Pinned`) or by the
 * property (`NameOrigin.Property`; `verifyPersistedNames` flags it when a
 * store under it persists).
 *
 * When this store disposes, [onParentDispose] decides: [KeyedDisposal.Dispose]
 * (the default) disposes each listed store the lambda BUILT (constructed on
 * the reading thread while it ran) and releases each one that existed before
 * as a subtree root that keeps working; [KeyedDisposal.Release] releases
 * them all. See [store].
 *
 * The lambda is ordinary code run on the READING thread, holding no lock or
 * latch of the tree's — only what that thread already holds. So:
 * - read inside an action, it runs inside that action and sees its
 *   UNCOMMITTED writes; the attach it leads to is structural, not
 *   transactional, and a rollback of that action does NOT undo it;
 * - read inside a state initializer, a `derivedState` compute or a
 *   `SchemaVersioned.migrate`, it inherits that no-write region, so a
 *   constructor that opens an action or writes a state fails there (the
 *   declaration stays retryable) — first-read such children outside that
 *   code;
 * - racing first reads on several threads may each run the lambda: the
 *   first result to attach wins and every reader gets it; each other run's
 *   stores that were built during that run and that nothing else holds are
 *   disposed (a store that existed before the run never is), so keep side
 *   effects other than building the child out of it;
 * - the lambda reading its own declaration again on the same thread —
 *   directly, through another child or through a state initializer — is a
 *   cycle and throws, naming the chain.
 *
 * A throwing lambda attaches nothing and runs again on the next read; an
 * attach that fails after it returned (a store that already has a parent,
 * one that would be its own ancestor, this store disposed meanwhile)
 * disposes the stores that run built.
 *
 * @throws IllegalArgumentException if [named] is empty.
 */
@ExperimentalStoreApi
fun Store<*>.group(
    named: String? = null,
    onParentDispose: KeyedDisposal = KeyedDisposal.Dispose,
    members: GroupScope.() -> List<Store<*>>,
): GroupDeclaration {
    require(named == null || named.isNotEmpty()) { "a pinned group name must not be empty" }
    return GroupDeclaration(this, named, onParentDispose, members)
}

/**
 * Declare a keyed branch of this store's children:
 * `val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }`.
 * Stores are created per key through [factory] by [KeyedBranch.create] /
 * [KeyedBranch.getOrCreate], and named by their key through [keyCodec] —
 * defaulted for `String` keys; without one the branch is never encoded. The
 * branch is named [named] (`NameOrigin.Pinned`) or by the property
 * (`NameOrigin.Property`; `verifyPersistedNames` flags it when a store
 * under it persists).
 *
 * Every store of the branch was built by [factory], so the branch owns it:
 * when this store disposes, [onParentDispose] decides — [KeyedDisposal.Dispose]
 * (the default) disposes every live keyed store, [KeyedDisposal.Release]
 * releases them as subtree roots that keep working.
 *
 * The factory is ordinary code run on the CALLING thread of `create` /
 * `getOrCreate`, holding no lock of the tree's — only what that thread
 * already holds: inside an action it sees that action's uncommitted writes,
 * and the attach is structural, not transactional (a rollback does not undo
 * it); inside a state initializer, a `derivedState` compute or a `migrate`
 * it inherits that no-write region, so a factory that opens an action fails
 * there (retryably). Racing creators of one key may each run it; one store
 * wins and the losers' freshly built stores are disposed — keep other side
 * effects out of it. The factory needing its own key again on the same
 * thread is a cycle and throws. See [KeyedBranch].
 *
 * @throws IllegalArgumentException if [named] is empty.
 */
@ExperimentalStoreApi
inline fun <reified K : Any, reified S : Store<S>> Store<*>.keyed(
    keyCodec: StateCodec<K>? = null,
    named: String? = null,
    onParentDispose: KeyedDisposal = KeyedDisposal.Dispose,
    noinline factory: (K) -> S,
): KeyedDeclaration<K, S> = keyedDeclaration(KeyedSpec(K::class, S::class, keyCodec, named, onParentDispose, factory))

/** [keyed]`<K, S>` without reification. */
@PublishedApi
internal fun <K : Any, S : Store<S>> Store<*>.keyedDeclaration(spec: KeyedSpec<K, S>): KeyedDeclaration<K, S> {
    require(spec.named == null || spec.named.isNotEmpty()) { "a pinned keyed branch name must not be empty" }
    return KeyedDeclaration(this, spec)
}

/**
 * What a declaration's child stores become when the store declaring them
 * disposes (the `onParentDispose` of [keyed], [store] and [group]).
 */
@ExperimentalStoreApi
enum class KeyedDisposal {
    /**
     * Dispose the stores the declaration built: every live store of a keyed
     * branch (its factory built each one); a `store { }` child or a
     * `group { }` member its lambda constructed. A store that existed before
     * the lambda ran is released instead. The default.
     */
    Dispose,

    /** Release every live store of the declaration as a subtree root that keeps working. */
    Release,
}

/**
 * The receiver of a [group] lambda: pins a listed store's leaf name with
 * `store named "..."`.
 */
@ExperimentalStoreApi
class GroupScope internal constructor() {
    internal val pins = ArrayList<Pair<Store<*>, String>>()

    /**
     * Pin this store's leaf name in the group being built to [name]
     * (`NameOrigin.Pinned`) and answer the store itself, so pinned and plain
     * stores mix in one list: `listOf(a named "a", b)`. Only meaningful
     * inside the [group] lambda that lists the store.
     *
     * @throws IllegalArgumentException if [name] is empty, or this store is already pinned in this group.
     */
    infix fun <S : Store<*>> S.named(name: String): S {
        require(name.isNotEmpty()) { "a pinned leaf name must not be empty" }
        val store: Store<*> = this
        require(pins.none { it.first === store }) {
            "${store::class.simpleName ?: "Store"} is pinned twice in one group"
        }
        pins += store to name
        return this
    }
}
