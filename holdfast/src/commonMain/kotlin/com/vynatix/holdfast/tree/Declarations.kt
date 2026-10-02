@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Stateful
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

// The delegate providers `store { }`, `stores { }` and `stores<K, S> { }`
// return (TreeDeclaring.kt). A declaration registers a `ChildEntry` on the
// declaring store when the property binds — named by the property or a pin,
// checked unique among the store's children — and runs nothing: a
// `store`/`stores` lambda runs on the delegate's first read or when a tree
// operation needs the subtree (TreeMaterialize.kt); a keyed branch exists
// from the declaration on, its factory running per key.

/** The key codec a `stores<String, S> { }` declaration gets by default: the key is its own text. */
internal object StringKeyCodec : StateCodec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = string
}

/**
 * What `val settings by store { SettingsStore }` delegates to: one child of
 * the declaring store, named by the property or by `store(named = …)`.
 * Reading the property materializes the child on first read (the lambda
 * runs once; concurrent first reads get the same instance) and answers it.
 */
@ExperimentalStoreApi
class StoreDeclaration<S : Stateful> internal constructor(
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
                ChildEntry(attachment, named ?: property.name, origin, ChildEntry.Kind.Store, child, null)
            }
        return ReadOnlyProperty { _, _ ->
            thisRef.checkNotDisposed()
            @Suppress("UNCHECKED_CAST")
            (entry.produced ?: materializeChild(entry)) as S
        }
    }
}

/**
 * What `val session by stores { listOf(SignInStore(), ProfileStore()) }`
 * delegates to: a group of the declaring store's children, named by the
 * property, each listed store at its own leaf (named by a `names` pin, else
 * its class name minus `Store`). Reading the property materializes the
 * group on first read and answers its [Branch].
 */
@ExperimentalStoreApi
class GroupDeclaration internal constructor(
    private val declaring: Store<*>,
    private val names: Map<KClass<out Store<*>>, String>,
    private val group: () -> List<Store<*>>,
) : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, Branch>> {
    override fun provideDelegate(
        thisRef: Store<*>,
        property: KProperty<*>,
    ): ReadOnlyProperty<Store<*>, Branch> {
        val entry =
            declareChild(declaring, thisRef, property) { attachment ->
                ChildEntry(attachment, property.name, NameOrigin.Property, ChildEntry.Kind.Group, group, names)
            }
        return ReadOnlyProperty { _, _ ->
            thisRef.checkNotDisposed()
            (entry.produced ?: materializeChild(entry)) as Branch
        }
    }
}

/**
 * What `val threads by stores<String, ThreadStore> { id -> ThreadStore(id) }`
 * delegates to: a [KeyedBranch] of the declaring store, named by the
 * property, created when the property binds (no store code runs) and live
 * from then on; its stores are created per key through the declared
 * factory ([KeyedBranch.create]/[KeyedBranch.getOrCreate]).
 */
@ExperimentalStoreApi
class KeyedDeclaration<K : Any, S : Store<S>> internal constructor(
    private val declaring: Store<*>,
    private val keyClass: KClass<K>,
    private val storeClass: KClass<S>,
    private val keyCodec: StateCodec<K>?,
    private val factory: (K) -> S,
) : PropertyDelegateProvider<Store<*>, ReadOnlyProperty<Store<*>, KeyedBranch<K, S>>> {
    override fun provideDelegate(
        thisRef: Store<*>,
        property: KProperty<*>,
    ): ReadOnlyProperty<Store<*>, KeyedBranch<K, S>> {
        var branch: KeyedBranch<K, S>? = null
        declareChild(declaring, thisRef, property) { attachment ->
            val keyed =
                KeyedBranch(
                    attachment.node,
                    property.name,
                    keyClass,
                    storeClass,
                    keyCodec,
                    factory,
                    thisRef,
                    attachment.registry,
                )
            branch = keyed
            ChildEntry(attachment, property.name, NameOrigin.Property, ChildEntry.Kind.Keyed, factory, null).also {
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
 * property — which must be the store the `store`/`stores` call was made
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
        "'${property.name}' on ${thisRef.displayName} was declared through ${declaring.displayName}.store/stores; " +
            "declare a store's children through its own store { }/stores { }"
    }
    thisRef.checkNotDisposed()
    val attachment = thisRef.treeAttachment()
    return entry(attachment).also {
        attachment.registry.declare(it)
        childDeclarationEpoch.incrementAndGet()
    }
}
