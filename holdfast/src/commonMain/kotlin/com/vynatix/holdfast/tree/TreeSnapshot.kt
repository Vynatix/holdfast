@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.observableBacking

/**
 * One consistent capture of a subtree (`store.tree.snapshot(node, scope)`):
 * the tree of nodes as of the capture, with a [StoreSnapshot] at every live
 * store, all read from ONE lock-free cut across the stores — a commit or an
 * `atomic`/`suspendAtomic` frame applying while it was taken is seen whole
 * or not at all, never a mix (`captureConsistent`, the cut issue #20 R9
 * built for this; a participant a frame joined as a savepoint applies with
 * its enclosing transaction, so user-composed mixed nesting is the pinned
 * exception). A capture never takes a leaf's transaction lock, never
 * blocks a writer, and reads committed values even on the committing
 * thread.
 *
 * Reads are by identity: [get] with a node gives the subtree's capture,
 * [get]/[entry] with a state give that state's value as the store's
 * [StoreSnapshot] would (`null`/`Redacted` for a `Secret` state outside
 * `SnapshotScope.Raw`), for a store still in the tree, or one since
 * disposed, whose capture this tree holds. Membership is decided when the
 * capture is taken: a state of a store outside the captured subtree reads
 * `null` (`Absent`) — one anywhere under the receiver (the store whose
 * `tree` took it) then, disposed since or not, or one that joined since;
 * one of the receiver's ancestor, of an unrelated store, or that no store
 * declared, throws. A subtree taken with [get] is scoped the same way: it
 * reads only what lies under its own node, even though the whole capture
 * it came from holds more.
 *
 * A capture keeps the structure it was taken from: [name], the nodes'
 * parents and [get] read the structure AS CAPTURED, so a kept snapshot
 * reads, encodes and reports the same paths whatever disposed since (a
 * store whose parent disposed becomes a class-named subtree root on its
 * live node, never in a capture).
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
    /**
     * The node's name when this capture was taken, or the decoded segment;
     * `node.name` can differ once an ancestor disposed and the node became a
     * subtree root.
     */
    val name: String,
    /**
     * The captures of the live children, in tree order: a store's declared
     * children in declaration order, a group's members, a keyed branch's
     * entries in creation order.
     */
    val children: List<TreeSnapshot>,
    /** The scope the capture was taken in. */
    val scope: SnapshotScope,
    internal val leaf: StoreSnapshot?,
    internal val storeKey: Long,
    internal val index: TreeIndex,
    /**
     * Tree paths a decoded text named that the receiver does not declare — a
     * diagnostic naming nodes, the one place the tree hands out strings.
     * Empty for a capture.
     */
    val unresolvedPaths: List<List<String>> = emptyList(),
) {
    init {
        index.register(this)
        for (child in children) index.parentOf[child.node] = node
    }

    /** Whether this capture holds one store's [StoreSnapshot] (a store node that captured something). */
    val hasStore: Boolean get() = leaf != null

    /** Whether this capture has no children. */
    val isLeaf: Boolean get() = children.isEmpty()

    /**
     * The capture of the subtree at [node], or `null` when [node] does not
     * lie under this capture's own node in the captured structure.
     */
    operator fun get(node: StoreNode): TreeSnapshot? {
        val capture = index.byNode[node] ?: return null
        return capture.takeIf { index.isUnderCaptured(node, this.node) }
    }

    /**
     * [state]'s captured value: `Present` as the leaf's [StoreSnapshot.entry]
     * reads it, `Redacted` for a `Secret` state outside `SnapshotScope.Raw`,
     * `Absent` for a state this capture holds no value for — including one
     * of a store outside the captured subtree: one under the receiver when
     * the capture was taken (disposed since or not), or one under it now.
     *
     * @throws IllegalArgumentException for a state no store declared (a
     *   `computed { }`, a `derivedState`), or one of a store that was not
     *   under the receiver when the capture was taken and is not now —
     *   while the receiver is live. Once the receiver is disposed (its
     *   children released, so "under it now" no longer means anything),
     *   such a read answers `Absent` instead, as does one of a store that
     *   joined the receiver's subtree since the capture.
     */
    fun <T : Any> entry(state: State<T>): SnapshotEntry<T> {
        val declaration = state.observableBacking()?.declaration
        requireNotNull(declaration) {
            "TreeSnapshot reads declared states only: this State was not declared by a store " +
                "(a computed { } or a derivedState/merged; read its sources instead)"
        }
        val store: Store<*> = declaration.store
        val storeKey = store.lockOrderKey
        val leafCapture = index.byStoreKey[storeKey]
        return when {
            // The store sits at a leaf of this capture: it reads inside this
            // subtree (disposed since or not), and is Absent outside it.
            leafCapture != null -> {
                val capture = leafCapture.leaf?.takeIf { index.isUnderCaptured(leafCapture.node, node) }
                capture?.entry(state) ?: SnapshotEntry.Absent
            }
            // Under the receiver when the capture was taken, outside the
            // captured subtree: Absent, whether or not it has disposed since.
            storeKey in index.memberKeys -> SnapshotEntry.Absent
            // A store the capture never listed: under the receiver now (joined
            // since), or the receiver disposed meanwhile, answers Absent; a
            // slot read, which never throws.
            else -> {
                // Live links first, then the receiver's state: the receiver's
                // dispose flips isDisposed before it releases any child, so a
                // child read as "not under" here is seen with the owner gone.
                val underNow = store.isUnderNow(index.ownerNode)
                require(underNow || index.ownerNode.store?.isDisposed != false) {
                    "${declaration.qualifiedName} belongs to a store that is not under '${index.ownerNode.name}'"
                }
                SnapshotEntry.Absent
            }
        }
    }

    /** [entry]'s value, or `null` when it is `Absent` or `Redacted`. */
    operator fun <T : Any> get(state: State<T>): T? = (entry(state) as? SnapshotEntry.Present<T>)?.value

    /**
     * The keys a decoded text holds bodies for under [branch] with no live
     * store at decode time: create those stores, then restore. A keyed leaf
     * written without a body (an empty leaf capture, `{"kind":"leaf"}`)
     * holds nothing to restore: it decodes as an empty leaf and is never
     * pending. Empty for a capture, and for a branch outside this subtree
     * (a decoded keyed leaf answers its own branch's).
     */
    fun <K : Any, S : Store<S>> pendingKeys(branch: KeyedBranch<K, S>): Set<K> {
        if (!index.isUnderCaptured(branch, node) && !index.isUnderCaptured(node, branch)) return emptySet()
        @Suppress("UNCHECKED_CAST")
        return (index.pendingKeys[branch] ?: emptySet()) as Set<K>
    }

    /** A multi-line rendering for logs: names, kinds, origins and each leaf's values; a `Secret` is `<redacted>`. */
    fun render(): String = renderTree(this)

    /**
     * The `holdfast.tree` v1 wire text of this capture: the subtree's path
     * from the receiver (whose own name is never written), the node
     * structure by name, and at every store node its
     * store's `holdfast.store` v1 body verbatim (`StoreSnapshot.encode()`:
     * a `Secret` state is written `null`, a codec-less state and a `Remote`
     * one unless [includeRemote] are left out). A keyed branch without a
     * key codec is left out and listed under `skipped`. Under
     * `SnapshotScope.UserAuthored` a group leaf named by its class refuses (T6:
     * that name would change with the class; pin it), and a capture under a
     * keyed branch without a key codec refuses (its path would have to
     * spell the key). The text nests at most 64 container levels — two per
     * branch level plus a leaf's body, about 28 branch levels — the cap the
     * snapshot writer and reader share with `StoreTree.decode`. Runs the leaves'
     * codecs, no other user code.
     *
     * @throws IllegalStateException for a class-named leaf under
     *   `SnapshotScope.UserAuthored`, a capture under a keyed branch without
     *   a key codec, or two children of one node sharing a name.
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
    override fun toString(): String = "TreeSnapshot($name: ${children.joinToString { it.name }})"
}

/** Whether this store sits at [ownerNode] or under it now, over the live links (a slot read: never throws). */
private fun Store<*>.isUnderNow(ownerNode: LeafNode): Boolean {
    val node = internalAttachment(treeMembershipKey)?.node ?: return false
    return node.isUnder(ownerNode)
}
