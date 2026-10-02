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
 * operation needs this store's subtree), once; the store it produces —
 * [child]'s `owningStore`, so an inline `object : NodeStore(), Draft { … }`
 * works when the type argument is given (`store<Draft> { … }`) — attaches
 * under this store, named [named] (`NameOrigin.Pinned`) or by the property
 * (`NameOrigin.Property`).
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
 * this store's subtree), once; each listed store attaches at its own leaf,
 * named by [names] (by exact class: a subclass of a pinned class is not
 * pinned; a class literal never initializes an `object`) or by its class
 * name minus `Store`.
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
