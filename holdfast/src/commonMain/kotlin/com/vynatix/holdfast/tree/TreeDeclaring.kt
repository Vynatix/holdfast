@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Stateful
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.reflect.KClass

// Declaring a store's children, next to its states (the dynamic store tree,
// issue #21):
//
// ```
// object AppStore : Store<AppStore>() {
//     val settings by store { SettingsStore }                              // one child, node "settings"
//     val prefs by store(named = "prefs-v2") { PrefsStore }                // a pinned wire name
//     val session by stores { listOf(SignInStore(), ProfileStore()) }      // a group of class-named leaves
//     val threads by stores<String, ThreadStore> { id -> ThreadStore(id) } // a keyed branch
//     val draft by store<Draft> { object : NodeStore(), Draft { override val text by state { "" } } }
// }
// ```
//
// A store has one parent: a second declaration of a store that already has
// one fails when it materializes, naming both parents. Disposing a store
// releases its children as subtree roots; it disposes none of them.

/**
 * Declare one child of this store: `val settings by store { SettingsStore }`.
 * The child lambda runs on the property's first read (or when a tree
 * operation needs this store's subtree) — once, unless racing first reads
 * each run it (see below); the store it produces —
 * [child]'s `owningStore`, so an inline `object : NodeStore(), Draft { … }`
 * works when the type argument is given (`store<Draft> { … }`) — attaches
 * under this store, named [named] (`NameOrigin.Pinned`) or by the property
 * (`NameOrigin.Property`).
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
fun <S : Stateful> Store<*>.store(
    named: String? = null,
    child: () -> S,
): StoreDeclaration<S> {
    require(named == null || named.isNotEmpty()) { "a pinned child name must not be empty" }
    return StoreDeclaration(this, named, child)
}

/**
 * Declare a group of this store's children:
 * `val session by stores { listOf(SignInStore(), ProfileStore()) }`. The
 * lambda runs on the property's first read (or when a tree operation needs
 * this store's subtree) — once, unless racing first reads each run it (see
 * below); each listed store attaches at its own leaf,
 * named by [names] (by exact class: a subclass of a pinned class is not
 * pinned; a class literal never initializes an `object`) or by its class
 * name minus `Store`. Two stores of one class cannot be pinned apart (a pin
 * names a class): declare them as separate `store { }` children.
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
 */
@ExperimentalStoreApi
fun Store<*>.stores(
    names: Map<KClass<out Store<*>>, String> = emptyMap(),
    group: () -> List<Store<*>>,
): GroupDeclaration = GroupDeclaration(this, names, group)

/**
 * Declare a keyed branch of this store's children:
 * `val threads by stores<String, ThreadStore> { id -> ThreadStore(id) }`.
 * Stores are created per key through [factory] by [KeyedBranch.create] /
 * [KeyedBranch.getOrCreate], and named by their key through [keyCodec] —
 * defaulted for `String` keys; without one the branch is never encoded.
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
 */
@ExperimentalStoreApi
inline fun <reified K : Any, reified S : Store<S>> Store<*>.stores(
    keyCodec: StateCodec<K>? = null,
    noinline factory: (K) -> S,
): KeyedDeclaration<K, S> = keyedDeclaration(K::class, S::class, keyCodec, factory)

/** [stores]`<K, S>` without reification. */
@PublishedApi
internal fun <K : Any, S : Store<S>> Store<*>.keyedDeclaration(
    keyClass: KClass<K>,
    storeClass: KClass<S>,
    keyCodec: StateCodec<K>?,
    factory: (K) -> S,
): KeyedDeclaration<K, S> {
    @Suppress("UNCHECKED_CAST")
    val codec = keyCodec ?: if (keyClass == String::class) StringKeyCodec as StateCodec<K> else null
    return KeyedDeclaration(this, keyClass, storeClass, codec, factory)
}
