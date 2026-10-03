@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

// The delegate providers `store { }`, `group { }` and `keyed<K, S> { }`
// return (TreeDeclaring.kt). A declaration registers a `ChildEntry` on the
// declaring store when the property binds — named by the property or a pin,
// checked unique among the store's children — and runs nothing: a
// `store`/`group` lambda runs on the delegate's first read or when a tree
// operation needs the subtree (TreeMaterialize.kt); a keyed branch exists
// from the declaration on, its factory running per key.

/** The key codec a `keyed<String, S> { }` declaration gets by default: the key is its own text. */
internal object StringKeyCodec : StateCodec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = string
}

/**
 * The fields of one `keyed<K, S> { }` declaration, carried from the inline
 * [keyed] call to the [KeyedBranch] it declares.
 */
@PublishedApi
internal class KeyedSpec<K : Any, S : Store<S>>
    @PublishedApi
    internal constructor(
        val keyClass: KClass<K>,
        val storeClass: KClass<S>,
        keyCodec: StateCodec<K>?,
        val named: String?,
        val onParentDispose: KeyedDisposal,
        val factory: (K) -> S,
    ) {
        /** The declared key codec, defaulted for `String` keys. */
        @Suppress("UNCHECKED_CAST")
        val keyCodec: StateCodec<K>? =
            keyCodec ?: if (keyClass == String::class) StringKeyCodec as StateCodec<K> else null
    }

/**
 * What `val settings by store { SettingsStore() }` delegates to: one child
 * of the declaring store, named by the property or by `store(named = …)`.
 * Reading the property materializes the child on first read (the lambda
 * runs once; concurrent first reads get the same instance) and answers it —
 * the same instance for the declaring store's whole life, even once that
 * child was disposed.
 */
@ExperimentalStoreApi
class StoreDeclaration<S : Any> internal constructor(
    private val declaring: Store<*>,
    private val named: String?,
    private val child: () -> S,
) : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, S>> {
    override fun provideDelegate(
        thisRef: Store<*>,
        property: KProperty<*>,
    ): ReadOnlyProperty<Store<*>, S> {
        val origin = if (named != null) NameOrigin.Pinned else NameOrigin.Property
        val entry =
            declareChild(declaring, thisRef, property) { attachment ->
                ChildEntry(attachment, named ?: property.name, origin, ChildEntry.Kind.Store, child)
            }
        return ReadOnlyProperty { _, _ ->
            thisRef.checkNotDisposed()
            @Suppress("UNCHECKED_CAST")
            (entry.produced ?: materializeChild(entry)) as S
        }
    }
}

/**
 * What `val session by group { listOf(SignInStore(), ProfileStore()) }`
 * delegates to: a group of the declaring store's children, named by the
 * property or by `group(named = …)`, each listed store at its own leaf
 * (named by its `named` pin, else its class name minus `Store`). Reading the
 * property materializes the group on first read and answers its [Branch].
 */
@ExperimentalStoreApi
class GroupDeclaration internal constructor(
    private val declaring: Store<*>,
    private val named: String?,
    private val members: GroupScope.() -> List<Store<*>>,
) : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, Branch>> {
    override fun provideDelegate(
        thisRef: Store<*>,
        property: KProperty<*>,
    ): ReadOnlyProperty<Store<*>, Branch> {
        val origin = if (named != null) NameOrigin.Pinned else NameOrigin.Property
        val entry =
            declareChild(declaring, thisRef, property) { attachment ->
                ChildEntry(attachment, named ?: property.name, origin, ChildEntry.Kind.Group, members)
            }
        return ReadOnlyProperty { _, _ ->
            thisRef.checkNotDisposed()
            (entry.produced ?: materializeChild(entry)) as Branch
        }
    }
}

/**
 * What `val threads by keyed<String, ThreadStore> { id -> ThreadStore(id) }`
 * delegates to: a [KeyedBranch] of the declaring store, named by the
 * property or by `keyed(named = …)`, created when the property binds (no
 * store code runs) and live from then on; its stores are created per key
 * through the declared factory ([KeyedBranch.create]/[KeyedBranch.getOrCreate]).
 */
@ExperimentalStoreApi
class KeyedDeclaration<K : Any, S : Store<S>> internal constructor(
    private val declaring: Store<*>,
    private val spec: KeyedSpec<K, S>,
) : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, KeyedBranch<K, S>>> {
    override fun provideDelegate(
        thisRef: Store<*>,
        property: KProperty<*>,
    ): ReadOnlyProperty<Store<*>, KeyedBranch<K, S>> {
        var branch: KeyedBranch<K, S>? = null
        val name = spec.named ?: property.name
        val origin = if (spec.named != null) NameOrigin.Pinned else NameOrigin.Property
        declareChild(declaring, thisRef, property) { attachment ->
            val keyed = KeyedBranch(attachment.node, name, origin, spec, thisRef, attachment.registry)
            branch = keyed
            ChildEntry(attachment, name, origin, ChildEntry.Kind.Keyed, spec.factory).also {
                it.node = keyed
                it.produced = keyed
                it.phase = ChildEntry.Phase.Live
            }
        }
        val declared = checkNotNull(branch)
        return ReadOnlyProperty { _, _ ->
            thisRef.checkNotDisposed()
            declared
        }
    }
}

/**
 * Register the entry [entry] builds on [thisRef], the store declaring the
 * property — which must be the store the `store`/`group`/`keyed` call was made
 * on ([declaring]).
 *
 * @throws IllegalArgumentException if [thisRef] is not [declaring].
 * @throws IllegalStateException if [thisRef] is disposed, or already has a
 *   child of that name.
 */
private fun declareChild(
    declaring: Store<*>,
    thisRef: Store<*>,
    property: KProperty<*>,
    entry: (TreeLeafAttachment) -> ChildEntry,
): ChildEntry {
    require(thisRef === declaring) {
        "'${property.name}' on ${thisRef.displayName} was declared through ${declaring.displayName}'s " +
            "store/group/keyed; declare a store's children through its own store { }/group { }/keyed { }"
    }
    thisRef.checkNotDisposed()
    val attachment = thisRef.treeAttachment()
    return entry(attachment).also {
        attachment.registry.declare(it)
        childDeclarationEpoch.incrementAndGet()
    }
}
