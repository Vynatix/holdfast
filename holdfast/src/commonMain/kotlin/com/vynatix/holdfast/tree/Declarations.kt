@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.internalDetach
import com.vynatix.holdfast.settling
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

// The delegate providers `Root.branch(...)` and `Root.keyed<K, S>()` return
// (issue #21 decisions U4, U7): a branch registers its stores at
// declaration — attaching each through the PR 10 slot, outside the registry
// lock, then registering under it, then fanning out `onAttached` after
// release — and runs no leaf code (it runs inside `object App`'s
// initializer). Names come from the property, or from pins.

/** The key codec a `keyed<String, S>()` declaration gets by default: the key is its own text. */
internal object StringKeyCodec : StateCodec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = string
}

/**
 * What `val settings by branch(SettingsStore(), ...)` delegates to. Pin
 * names before the delegate binds: [named] with a store pins that store's
 * leaf name (else its class name minus `Store`), [named] with a name alone
 * pins the branch's own name (else the property's).
 */
@ExperimentalStoreApi
class BranchDeclaration internal constructor(
    private val root: Root,
    private val stores: List<Store<*>>,
    private val under: Branch?,
) : PropertyDelegateProvider<Root, ReadOnlyProperty<Root, Branch>> {
    private val leafPins = HashMap<Long, String>()
    private var branchPin: String? = null

    /** Pin the leaf name of [store], one of this declaration's stores, to [name] (`NameOrigin.Pinned`). */
    fun named(
        store: Store<*>,
        name: String,
    ): BranchDeclaration {
        require(stores.any { it === store }) {
            "named(store, \"$name\"): ${store::class.simpleName ?: "the store"} is not listed in this branch(...)"
        }
        require(name.isNotEmpty()) { "a pinned leaf name must not be empty" }
        leafPins[store.lockOrderKey] = name
        return this
    }

    /** Pin this branch's own name to [name] instead of its property's (`NameOrigin.Pinned`). */
    fun named(name: String): BranchDeclaration {
        require(name.isNotEmpty()) { "a pinned branch name must not be empty" }
        branchPin = name
        return this
    }

    override fun provideDelegate(
        thisRef: Root,
        property: KProperty<*>,
    ): ReadOnlyProperty<Root, Branch> {
        val branch = declare(thisRef, property)
        return ReadOnlyProperty { _, _ -> branch }
    }

    private fun declare(
        thisRef: Root,
        property: KProperty<*>,
    ): Branch {
        require(thisRef === root) {
            "root '${thisRef.name}': the branch for '${property.name}' was created by " +
                "root '${root.name}'.branch(...); declare a root's branches through its own branch(...)"
        }
        root.checkNotDisposed()
        val parent = parentFor(under, root, property.name)
        validateListing(property.name)
        val branchName = branchPin ?: property.name
        val origin = if (branchPin != null) NameOrigin.Pinned else NameOrigin.Property
        val branch = Branch(root, parent, branchName, origin, stores)
        branch.leaves = leavesFor(branch)
        attachAll(branch)
        var registered = false
        try {
            // Indexes each leaf still holding its store and owes it its
            // announcement; a store disposed since its attach (from another
            // thread) is neither indexed nor announced, so no listener hears
            // `onDetached` for a leaf it never heard `onAttached` for.
            root.registry.registerBranch(branch, parent)
            registered = true
        } finally {
            if (!registered) for (store in stores) store.internalDetach(treeMembershipKey)
        }
        // One settle for the whole listing: a `value` following the tree
        // recomputes once after every leaf is told, not once per leaf. A
        // store that disposes while it is being announced is detached right
        // after its announcement, by this thread (`Root.onLeafAttached`).
        settling { for (leaf in branch.leaves) root.onLeafAttached(leaf) }
        return branch
    }

    private fun validateListing(propertyName: String) {
        for ((index, store) in stores.withIndex()) {
            require(stores.indexOfFirst { it === store } == index) {
                "root '${root.name}': branch '$propertyName' lists ${store::class.simpleName ?: "a store"} twice"
            }
            require(!store.isDisposed) {
                "root '${root.name}': branch '$propertyName' lists a disposed ${store::class.simpleName ?: "store"}"
            }
        }
    }

    private fun leavesFor(branch: Branch): List<LeafNode> {
        val taken = HashSet<String>()
        return stores.map { store ->
            val pin = leafPins[store.lockOrderKey]
            val name =
                pin ?: checkNotNull(defaultLeafName(store::class.simpleName)) {
                    "root '${root.name}': branch '${branch.name}' lists a store whose class has no simple name " +
                        "(anonymous or local); pin its leaf with named(store, \"...\")"
                }
            check(taken.add(name)) {
                "root '${root.name}': branch '${branch.name}' has two leaves named '$name'; " +
                    "pin one with named(store, \"...\")"
            }
            val origin = if (pin != null) NameOrigin.Pinned else NameOrigin.ClassName
            LeafNode(root, branch, name, origin, key = null).also {
                it.storeRef = store
                it.storeKey = store.lockOrderKey
            }
        }
    }

    /** Attach every store outside the registry lock; on failure detach the ones this declaration attached. */
    private fun attachAll(branch: Branch) {
        val attached = ArrayList<Store<*>>()
        var complete = false
        try {
            for (leaf in branch.leaves) {
                val store = checkNotNull(leaf.storeRef)
                val attachment =
                    store.internalAttachIfAbsent(treeMembershipKey) { TreeLeafAttachment(root, leaf, entry = null) }
                check(attachment.leaf === leaf) { doubleListingMessage(store, attachment, branch) }
                attached.add(store)
            }
            complete = true
        } finally {
            if (!complete) for (store in attached) store.internalDetach(treeMembershipKey)
        }
    }

    private fun doubleListingMessage(
        store: Store<*>,
        existing: TreeLeafAttachment,
        branch: Branch,
    ): String =
        "${store::class.simpleName ?: "A store"} is listed under branch '${branch.name}' of root '${root.name}' " +
            "but already belongs to root '${existing.root.name}' under '${existing.leaf.parent.name}'; " +
            "a store belongs to one branch of one root"
}

/**
 * What `val threads by keyed<String, ThreadStore>()` delegates to: registers
 * a [KeyedBranch] named by the property, under [under] or the root.
 */
@ExperimentalStoreApi
class KeyedDeclaration<K : Any, S : Store<S>> internal constructor(
    private val root: Root,
    private val keyClass: KClass<K>,
    private val storeClass: KClass<S>,
    private val under: Branch?,
    private val keyCodec: StateCodec<K>?,
) : PropertyDelegateProvider<Root, ReadOnlyProperty<Root, KeyedBranch<K, S>>> {
    override fun provideDelegate(
        thisRef: Root,
        property: KProperty<*>,
    ): ReadOnlyProperty<Root, KeyedBranch<K, S>> {
        require(thisRef === root) {
            "root '${thisRef.name}': the keyed branch for '${property.name}' was created by " +
                "root '${root.name}'.keyed(); declare a root's branches through its own keyed()"
        }
        root.checkNotDisposed()
        val parent = parentFor(under, root, property.name)
        val branch = KeyedBranch(root, parent, property.name, keyClass, storeClass, keyCodec)
        root.registry.registerKeyed(branch, parent)
        return ReadOnlyProperty { _, _ -> branch }
    }
}

private fun parentFor(
    under: Branch?,
    root: Root,
    propertyName: String,
): StoreNode {
    if (under == null) return root
    require(under.root === root) {
        "root '${root.name}': '$propertyName' is declared under branch '${under.name}' of root '${under.root.name}'; " +
            "a branch nests only under a branch of its own root"
    }
    return under
}
