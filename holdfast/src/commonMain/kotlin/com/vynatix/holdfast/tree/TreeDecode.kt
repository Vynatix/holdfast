@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.DecodedContent
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.JsonKind
import com.vynatix.holdfast.SnapshotJsonReader
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StoreBody
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.describeClass
import com.vynatix.holdfast.readStoreBody
import com.vynatix.holdfast.skipValue
import com.vynatix.holdfast.snapshotFormatError

// Decoding the `holdfast.tree` v1 wire text (`tree.decode`): the envelope
// is read once, the nodes resolved against the receiver's declarations as
// they are now — a path the receiver does not declare is listed, never
// fatal; a keyed segment of `path` and a keyed entry go through the branch's
// key codec, and a key with no live store becomes a pending key when the
// text holds a body for it (a keyed leaf written without one, an empty leaf
// capture, decodes as an empty leaf: nothing to restore, nothing pending) —
// and every store body is retained verbatim, decoded lazily by the reading
// store's codecs. A key codec's failure, and two entries that decode to one
// key, are format failures naming the branch, never the key text. The
// receiver's membership is recorded as of the decode, as a capture records
// it when taken (`TreeIndex.memberKeys`). A store's tree state is reached
// only through `LeafNode.attachment` — never its attachment slot — so a
// store mid-dispose under a live receiver is an unresolved name, never a
// throw.

/**
 * The parsed shape of one node, before it is resolved: its [kind] (`leaf`,
 * `branch` or `keyed`), a leaf's [store] body (`null` when written with
 * nothing captured), a leaf's or branch's [children] and a keyed branch's
 * [entries] (leaf nodes by encoded key). Members a kind does not carry are
 * read and ignored.
 */
private class DecodedNode(
    val kind: String,
    val store: StoreBody?,
    val children: Map<String, DecodedNode>,
    val entries: Map<String, DecodedNode>,
)

private class Envelope(
    val scope: SnapshotScope,
    val path: List<String>,
    val tree: DecodedNode,
)

internal fun decodeTree(
    ownerNode: LeafNode,
    text: String,
): TreeSnapshot {
    checkNotNull(ownerNode.store) { "${ownerNode.name}'s store disposed" }.checkNotDisposed()
    val envelope = readEnvelope(SnapshotJsonReader(text))
    val members = listingOf(ownerNode, ownerNode).memberKeys
    val index = TreeIndex(members, ownerNode)
    val unresolved = ArrayList<List<String>>()
    // A keyed segment of `path` is pending only as its last segment, and only
    // when the text holds a body at the top.
    val top = resolvePath(ownerNode, envelope.path, index, topHoldsBody = envelope.tree.store != null)
    val topName = envelope.path.lastOrNull() ?: ownerNode.name
    val resolver = Resolver(envelope.scope, index, unresolved)
    val built = top?.let { resolver.build(it, envelope.tree, envelope.path, topName) }
    if (top == null) unresolved += envelope.path
    return if (built == null) {
        val empty = TreeIndex(members, ownerNode)
        TreeSnapshot(ownerNode, ownerNode.name, emptyList(), envelope.scope, null, 0L, empty, unresolved)
    } else {
        TreeSnapshot(built.node, built.name, built.children, built.scope, built.leaf, built.storeKey, index, unresolved)
    }
}

private fun readEnvelope(reader: SnapshotJsonReader): Envelope {
    val start = reader.position
    var format: String? = null
    var version: Int? = null
    var scope: String? = null
    var path: List<String>? = null
    var tree: DecodedNode? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "format" -> format = reader.nextStringOrNull("format")
            "v" -> version = reader.nextInt("v")
            "scope" -> scope = reader.nextStringOrNull("scope")
            "path" -> path = reader.readStrings()
            "tree" -> tree = reader.readNode()
            else -> reader.skipValue()
        }
    }
    reader.endContainer()
    reader.endDocument()
    if (format != TREE_SNAPSHOT_FORMAT) snapshotFormatError("not a $TREE_SNAPSHOT_FORMAT document", start)
    if (version != TREE_SNAPSHOT_VERSION) snapshotFormatError("unsupported $TREE_SNAPSHOT_FORMAT version", start)
    val resolvedScope = scopeNamed(scope) ?: snapshotFormatError("unknown snapshot scope", start)
    return Envelope(resolvedScope, path ?: emptyList(), tree ?: snapshotFormatError("missing \"tree\"", start))
}

private fun scopeNamed(name: String?): SnapshotScope? =
    when (name) {
        SnapshotScope.All.toString() -> SnapshotScope.All
        SnapshotScope.UserAuthored.toString() -> SnapshotScope.UserAuthored
        SnapshotScope.Raw.toString() -> SnapshotScope.Raw
        else -> null
    }

private fun SnapshotJsonReader.readStrings(): List<String> {
    val out = ArrayList<String>()
    beginArray()
    while (hasNext()) out += nextStringOrNull("a name") ?: snapshotFormatError("a null name", position)
    endContainer()
    return out
}

private fun SnapshotJsonReader.readNode(): DecodedNode {
    val start = position
    var kind: String? = null
    var children: Map<String, DecodedNode>? = null
    var entries: Map<String, DecodedNode>? = null
    var store: StoreBody? = null
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "kind" -> kind = nextStringOrNull("kind")
            "children" -> children = readNodes()
            "entries" -> entries = readNodes()
            "store" -> store = readStoreBody()
            else -> skipValue()
        }
    }
    endContainer()
    return when (kind) {
        "leaf", "branch", "keyed" -> DecodedNode(kind, store, children ?: emptyMap(), entries ?: emptyMap())
        else -> snapshotFormatError("unknown node kind", start)
    }
}

private fun SnapshotJsonReader.readNodes(): Map<String, DecodedNode> {
    val out = LinkedHashMap<String, DecodedNode>()
    beginObject()
    while (hasNext()) {
        val name = nextName()
        if (peek() != JsonKind.Object) snapshotFormatError("a child that is not an object", position)
        out[name] = readNode()
    }
    endContainer()
    return out
}

/** A store node's children, or `null` when its store is gone or disposing (its names are unresolved). */
private fun registryOf(node: LeafNode): ChildRegistry? = node.attachment?.registry

/** The declared, live child named [name] of [node]'s store. */
private fun declaredChild(
    node: LeafNode,
    name: String,
): StoreNode? = registryOf(node)?.declaredEntry(name)?.liveNode

/**
 * Walk [path] down from [ownerNode]: through a store's declared children by
 * property or pinned name, a group's leaves by name, and a keyed branch by
 * decoding the segment with its key codec — the live store's leaf, else a
 * leaf minted for the key, recorded in [index] as pending when it is the
 * last segment and the text holds a body there ([topHoldsBody]). Every node
 * walked is recorded in [index] with its segment as its name. `null` when a
 * name is not declared there, its store is gone, or the keyed branch has
 * no key codec to decode the segment with.
 */
private fun resolvePath(
    ownerNode: LeafNode,
    path: List<String>,
    index: TreeIndex,
    topHoldsBody: Boolean,
): StoreNode? {
    val keyed = KeyedLeaves(index)
    var current: StoreNode = ownerNode
    for ((i, name) in path.withIndex()) {
        val holdsBody = i == path.lastIndex && topHoldsBody
        val from = current
        val next: StoreNode? =
            when (val at = from) {
                is KeyedBranch<*, *> ->
                    at.keyCodec?.let { codec -> keyed.leafFor(at, keyed.decode(at, codec, name), name, holdsBody) }
                is LeafNode -> declaredChild(at, name)
                is Branch -> at.leaves.firstOrNull { it.name == name }
            }
        current = next ?: return null
        index.parentOf[current] = from
        index.chainNames[current] = name
    }
    return current
}

/**
 * Keyed segments and entries: a key decoded through its branch's codec, and
 * the leaf that sits under it now. A codec's failure and a duplicate decoded
 * key are format failures naming the branch, never the key text.
 */
private class KeyedLeaves(
    private val index: TreeIndex,
) {
    fun decode(
        branch: KeyedBranch<*, *>,
        keyCodec: StateCodec<*>,
        encodedKey: String,
    ): Any =
        try {
            keyCodec.decode(encodedKey)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception, // Whatever a codec throws is a format failure.
        ) {
            formatError(branch, failure)
        }

    /**
     * The leaf under [branch] for [key]: the live entry's, else one minted
     * for the key with no store — told apart by [LeafNode.storeKey], which no
     * store ever has as `0` — recorded as pending in [index] only when the
     * text holds a body for it ([holdsBody]): a leaf text written without
     * one (an empty leaf capture) holds nothing to restore, so it decodes as
     * an empty leaf that `pendingKeys` never lists.
     */
    fun leafFor(
        branch: KeyedBranch<*, *>,
        key: Any,
        encodedKey: String,
        holdsBody: Boolean,
    ): LeafNode =
        branch.liveLeaf(key) ?: LeafNode(null, branch, encodedKey, NameOrigin.Key, key).also {
            if (holdsBody) index.addPendingKey(branch, key)
        }

    /** Names the branch and the exception's class only: the exception is not chained, its message may quote the key. */
    private fun formatError(
        branch: KeyedBranch<*, *>,
        failure: Throwable,
    ): Nothing =
        snapshotFormatError(
            "a key of keyed branch '${branch.name}' could not be decoded: " +
                "its key codec threw ${failure.describeClass()}",
            0,
        )

    fun duplicateError(branch: KeyedBranch<*, *>): Nothing =
        snapshotFormatError(
            "keyed branch '${branch.name}' holds two entries that decode to one key " +
                "(its key codec is not canonical, so the sorted entries do not name distinct keys)",
            0,
        )
}

/** Resolves parsed nodes against the receiver's declarations, building the decoded [TreeSnapshot] bottom-up. */
private class Resolver(
    private val scope: SnapshotScope,
    private val index: TreeIndex,
    private val unresolved: MutableList<List<String>>,
) {
    private val keyed = KeyedLeaves(index)

    fun build(
        node: StoreNode,
        decoded: DecodedNode,
        path: List<String>,
        name: String,
    ): TreeSnapshot? {
        val built =
            when {
                decoded.kind == KIND_LEAF && node is LeafNode -> leaf(node, decoded, path, name)
                decoded.kind == KIND_BRANCH && node is Branch -> branch(node, decoded, path, name)
                decoded.kind == KIND_KEYED && node is KeyedBranch<*, *> -> keyed(node, decoded, path, name)
                else -> null
            }
        if (built == null) unresolved += path else index.origins[node] = node.nameOrigin
        return built
    }

    /**
     * A store node: its body, then its declared children by name and kind.
     * Every child of a store that is gone (disposing, or a pending key with
     * no store) is unresolved.
     */
    private fun leaf(
        node: LeafNode,
        decoded: DecodedNode,
        path: List<String>,
        name: String,
    ): TreeSnapshot {
        val children = ArrayList<TreeSnapshot>()
        for ((childName, child) in decoded.children) {
            val childPath = path + childName
            val target = declaredChild(node, childName)?.takeIf { kindOf(it) == child.kind }
            if (target == null) {
                unresolved += unresolvedPathsOf(childPath, child)
                continue
            }
            build(target, child, childPath, childName)?.let(children::add)
        }
        val capture = decoded.store?.let { StoreSnapshot(DecodedContent(it)) }
        return TreeSnapshot(node, name, children, scope, capture, node.storeKey, index)
    }

    /**
     * The paths an unresolvable [decoded] child at [path] reports: one per
     * encoded entry of a keyed child (so a caller can tell which nested keys
     * a restore drops — `threads/42/replies/7` under a pending
     * `threads/42`), else the child's own path.
     */
    private fun unresolvedPathsOf(
        path: List<String>,
        decoded: DecodedNode,
    ): List<List<String>> =
        if (decoded.kind == KIND_KEYED && decoded.entries.isNotEmpty()) {
            decoded.entries.keys.map { path + it }
        } else {
            listOf(path)
        }

    private fun branch(
        node: Branch,
        decoded: DecodedNode,
        path: List<String>,
        name: String,
    ): TreeSnapshot {
        val children = ArrayList<TreeSnapshot>()
        for ((childName, child) in decoded.children) {
            val childPath = path + childName
            val target = node.leaves.firstOrNull { it.name == childName }
            if (target == null || child.kind != KIND_LEAF) {
                unresolved += childPath
                continue
            }
            build(target, child, childPath, childName)?.let(children::add)
        }
        return TreeSnapshot(node, name, children, scope, null, 0L, index)
    }

    private fun keyed(
        branch: KeyedBranch<*, *>,
        decoded: DecodedNode,
        path: List<String>,
        name: String,
    ): TreeSnapshot? {
        val keyCodec = branch.keyCodec
        return when {
            keyCodec != null -> {
                val children = entries(branch, keyCodec, decoded, path)
                TreeSnapshot(branch, name, children, scope, null, 0L, index)
            }
            // `encode()` writes such a branch empty and lists it as skipped;
            // entries under it are text this receiver cannot decode.
            decoded.entries.isEmpty() -> TreeSnapshot(branch, name, emptyList(), scope, null, 0L, index)
            else -> null
        }
    }

    private fun entries(
        branch: KeyedBranch<*, *>,
        keyCodec: StateCodec<*>,
        decoded: DecodedNode,
        path: List<String>,
    ): List<TreeSnapshot> {
        val children = ArrayList<TreeSnapshot>()
        val seen = HashSet<Any>()
        for ((encodedKey, child) in decoded.entries) {
            val key = keyed.decode(branch, keyCodec, encodedKey)
            if (!seen.add(key)) keyed.duplicateError(branch)
            val leafNode = keyed.leafFor(branch, key, encodedKey, holdsBody = child.store != null)
            build(leafNode, child, path + encodedKey, encodedKey)?.let(children::add)
        }
        return children
    }
}

private const val KIND_LEAF = "leaf"
private const val KIND_BRANCH = "branch"
private const val KIND_KEYED = "keyed"
