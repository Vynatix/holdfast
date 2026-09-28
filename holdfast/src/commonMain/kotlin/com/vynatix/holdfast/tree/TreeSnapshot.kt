@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.observableBacking

/**
 * One consistent capture of a subtree (`Root.snapshot(node, scope)`): the
 * tree of nodes as of the capture, with a [StoreSnapshot] at every live
 * leaf, all read from ONE lock-free cut across the leaves — a commit or an
 * `atomic`/`suspendAtomic` frame applying while it was taken is seen whole
 * or not at all, never a mix (`captureConsistent`, the cut issue #20 R9
 * built for this; a participant a frame joined as a savepoint applies with
 * its enclosing transaction, so user-composed mixed nesting is the pinned
 * exception). A capture never takes a leaf's transaction lock, never
 * blocks a writer, and reads committed values even on the committing
 * thread.
 *
 * Reads are by identity: [get] with a node gives the subtree's capture,
 * [get]/[entry] with a state give that state's value as the leaf's
 * [StoreSnapshot] would (`null`/`Redacted` for a `Secret` state outside
 * `SnapshotScope.Raw`), for a store still in the tree, or one since
 * disposed, whose capture this tree holds. A state of a store outside the
 * captured subtree reads `null` (`Absent`); one of a store that never
 * belonged to this root, or that no store declared, throws.
 *
 * Equality is full value equality — names, structure, scope and every
 * leaf's [StoreSnapshot] value equality, `Secret`, `Remote` and codec-less
 * states included; [equalsEncodable] is the round-trip contract with
 * `decode(encode())`, over the encodable projection only. [render] and
 * [toString] never show a `Secret` value.
 */
@ExperimentalStoreApi
class TreeSnapshot internal constructor(
    /** The node this capture is of: the subtree's top. */
    val node: StoreNode,
    /** The captures of the live children, in tree order (a branch's leaves, then what is declared under it). */
    val children: List<TreeSnapshot>,
    /** The scope the capture was taken in. */
    val scope: SnapshotScope,
    internal val leaf: StoreSnapshot?,
    internal val storeKey: Long,
    internal val index: TreeIndex,
    /**
     * Tree paths a decoded text named that this root does not declare — a
     * diagnostic naming nodes, the one place the tree hands out strings.
     * Empty for a capture.
     */
    val unresolvedPaths: List<List<String>> = emptyList(),
) {
    init {
        index.register(this)
    }

    /** Whether [node] is a [LeafNode], so this capture holds one store's [StoreSnapshot]. */
    val isLeaf: Boolean get() = node is LeafNode

    /** The capture of the subtree at [node], or `null` when [node] is not inside this capture. */
    operator fun get(node: StoreNode): TreeSnapshot? = index.byNode[node]

    /**
     * [state]'s captured value: `Present` as the leaf's [StoreSnapshot.entry]
     * reads it, `Redacted` for a `Secret` state outside `SnapshotScope.Raw`,
     * `Absent` for a state this capture holds no value for — including one
     * of a store outside the captured subtree.
     *
     * @throws IllegalArgumentException for a state no store declared (a
     *   `computed { }`, a `derivedState`), or one of a store that never
     *   belonged to this root.
     */
    fun <T : Any> entry(state: State<T>): SnapshotEntry<T> {
        val declaration = state.observableBacking()?.declaration
        requireNotNull(declaration) {
            "TreeSnapshot reads declared states only: this State was not declared by a store " +
                "(a computed { } or a derivedState/merged; read its sources instead)"
        }
        val store: Store<*> = declaration.store
        val leafCapture = index.byStoreKey[store.lockOrderKey]
        if (leafCapture != null) return checkNotNull(leafCapture.leaf).entry(state)
        val registry = node.root.registry
        require(registry.disposed || registry.leafOf(store) != null) {
            "${declaration.qualifiedName} belongs to a store that is not a member of root '${node.root.name}'"
        }
        return SnapshotEntry.Absent
    }

    /** [entry]'s value, or `null` when it is `Absent` or `Redacted`. */
    operator fun <T : Any> get(state: State<T>): T? = (entry(state) as? SnapshotEntry.Present<T>)?.value

    /**
     * The keys a decoded text holds bodies for under [branch] with no live
     * store at decode time: create those stores, then restore. Empty for a
     * capture.
     */
    fun <K : Any, S : Store<S>> pendingKeys(branch: KeyedBranch<K, S>): Set<K> {
        @Suppress("UNCHECKED_CAST")
        return (index.pendingKeys[branch] ?: emptySet()) as Set<K>
    }

    /** A multi-line rendering for logs: names, kinds, origins and each leaf's values; a `Secret` is `<redacted>`. */
    fun render(): String = renderTree(this)

    /**
     * The `holdfast.tree` v1 wire text of this capture: the subtree's path
     * from its root, the node structure by name, and at every leaf its
     * store's `holdfast.store` v1 body verbatim (`StoreSnapshot.encode()`:
     * a `Secret` state is written `null`, a codec-less state and a `Remote`
     * one unless [includeRemote] are left out). A keyed branch without a
     * key codec is left out and listed under `skipped`. Under
     * `SnapshotScope.UserAuthored` a leaf named by its class refuses (T6:
     * that name would change with the class; pin it). Runs the leaves'
     * codecs, no other user code.
     *
     * @throws IllegalStateException for a class-named leaf under
     *   `SnapshotScope.UserAuthored`, or two children of one node sharing a name.
     */
    fun encode(includeRemote: Boolean = false): String = encodeTree(this, includeRemote)

    /**
     * Whether [other] holds the same encodable projection as this capture
     * — the round-trip contract `decode(encode()).equalsEncodable(this)`:
     * `Secret` values, `Remote` states (unless [includeRemote]), codec-less
     * states and keyed branches without a key codec are left out, as
     * `encode()` leaves them out. Runs the leaves' codecs.
     */
    fun equalsEncodable(
        other: TreeSnapshot,
        includeRemote: Boolean = false,
    ): Boolean = treeEqualsEncodable(this, other, includeRemote)

    override fun equals(other: Any?): Boolean = other is TreeSnapshot && treeEquals(this, other)

    override fun hashCode(): Int = treeHash(this)

    /** Names only, never a value. */
    override fun toString(): String = "TreeSnapshot(${node.name}: ${children.joinToString { it.node.name }})"
}
