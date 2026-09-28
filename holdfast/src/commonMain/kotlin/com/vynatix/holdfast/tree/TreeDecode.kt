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

// Decoding the `holdfast.tree` v1 wire text (`Root.decode`): the envelope is
// read once, the nodes resolved against the root's declarations as they are
// now — a path the root does not declare is listed, never fatal; a keyed
// entry with no live store becomes a pending key — and every leaf body is
// retained verbatim, decoded lazily by the reading store's codecs.

/** The parsed shape of one node, before it is resolved against the root. */
private sealed class DecodedNode {
    class Container(
        val kind: String,
        val children: Map<String, DecodedNode>,
    ) : DecodedNode()

    class Keyed(
        val entries: Map<String, StoreBody>,
    ) : DecodedNode()

    class Leaf(
        val body: StoreBody,
    ) : DecodedNode()
}

private class Envelope(
    val scope: SnapshotScope,
    val path: List<String>,
    val tree: DecodedNode,
)

internal fun decodeTree(
    root: Root,
    text: String,
): TreeSnapshot {
    root.checkNotDisposed()
    val envelope = readEnvelope(SnapshotJsonReader(text))
    val index = TreeIndex()
    val unresolved = ArrayList<List<String>>()
    val top = resolvePath(root, envelope.path)
    if (top == null) {
        unresolved += envelope.path
        return TreeSnapshot(root, emptyList(), envelope.scope, null, 0L, index, unresolved)
    }
    val built = Resolver(root, envelope.scope, index, unresolved).build(top, envelope.tree, envelope.path)
    return if (built == null) {
        TreeSnapshot(root, emptyList(), envelope.scope, null, 0L, index, unresolved)
    } else {
        TreeSnapshot(built.node, built.children, built.scope, built.leaf, built.storeKey, index, unresolved)
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
    var entries: Map<String, StoreBody>? = null
    var store: StoreBody? = null
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "kind" -> kind = nextStringOrNull("kind")
            "children" -> children = readChildren()
            "entries" -> entries = readEntries()
            "store" -> store = readStoreBody()
            else -> skipValue()
        }
    }
    endContainer()
    return when (kind) {
        "leaf" -> DecodedNode.Leaf(store ?: snapshotFormatError("a leaf without a store body", start))
        "keyed" -> DecodedNode.Keyed(entries ?: emptyMap())
        "root", "branch" -> DecodedNode.Container(kind, children ?: emptyMap())
        else -> snapshotFormatError("unknown node kind", start)
    }
}

private fun SnapshotJsonReader.readChildren(): Map<String, DecodedNode> {
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

private fun SnapshotJsonReader.readEntries(): Map<String, StoreBody> {
    val out = LinkedHashMap<String, StoreBody>()
    beginObject()
    while (hasNext()) {
        val key = nextName()
        out[key] = readStoreBody()
    }
    endContainer()
    return out
}

/** Walk [path] down from [root] through declared children; `null` when a name is not declared there. */
private fun resolvePath(
    root: Root,
    path: List<String>,
): StoreNode? {
    var current: StoreNode = root
    for (name in path) {
        current = root.registry.childNodes(current).firstOrNull { it.name == name }
            ?: (current as? Branch)?.leaves?.firstOrNull { it.name == name }
            ?: return null
    }
    return current
}

/** Resolves parsed nodes against the root's declarations, building the decoded [TreeSnapshot] bottom-up. */
private class Resolver(
    private val root: Root,
    private val scope: SnapshotScope,
    private val index: TreeIndex,
    private val unresolved: MutableList<List<String>>,
) {
    fun build(
        node: StoreNode,
        decoded: DecodedNode,
        path: List<String>,
    ): TreeSnapshot? =
        when {
            decoded is DecodedNode.Leaf && node is LeafNode -> leaf(node, decoded.body)
            decoded is DecodedNode.Keyed && node is KeyedBranch<*, *> -> keyed(node, decoded, path)
            decoded is DecodedNode.Container && (node is Root || node is Branch) -> container(node, decoded, path)
            else -> {
                unresolved += path
                null
            }
        }

    private fun leaf(
        node: LeafNode,
        body: StoreBody,
    ): TreeSnapshot = leafCapture(node, body, node.store?.lockOrderKey ?: 0L)

    private fun leafCapture(
        node: LeafNode,
        body: StoreBody,
        storeKey: Long,
    ): TreeSnapshot = TreeSnapshot(node, emptyList(), scope, StoreSnapshot(DecodedContent(body)), storeKey, index)

    private fun container(
        node: StoreNode,
        decoded: DecodedNode.Container,
        path: List<String>,
    ): TreeSnapshot {
        val declared = root.registry.childNodes(node)
        val leaves = (node as? Branch)?.leaves.orEmpty()
        val children = ArrayList<TreeSnapshot>()
        for ((name, child) in decoded.children) {
            val childPath = path + name
            val target =
                when (child) {
                    is DecodedNode.Leaf -> leaves.firstOrNull { it.name == name }
                    is DecodedNode.Keyed -> declared.firstOrNull { it.name == name && it is KeyedBranch<*, *> }
                    is DecodedNode.Container -> declared.firstOrNull { it.name == name && it is Branch }
                }
            if (target == null) {
                unresolved += childPath
                continue
            }
            build(target, child, childPath)?.let(children::add)
        }
        return TreeSnapshot(node, children, scope, null, 0L, index)
    }

    private fun keyed(
        branch: KeyedBranch<*, *>,
        decoded: DecodedNode.Keyed,
        path: List<String>,
    ): TreeSnapshot? {
        val keyCodec = branch.keyCodec
        if (keyCodec == null) {
            unresolved += path
            return null
        }
        val children = ArrayList<TreeSnapshot>()
        val pending = LinkedHashSet<Any>()
        for ((encodedKey, body) in decoded.entries) {
            val key = decodeKey(branch, keyCodec, encodedKey)
            val live = root.registry.liveStore(branch, key)
            val existing = live?.let { root.registry.leafOf(it) }
            val leafNode = existing ?: LeafNode(root, branch, encodedKey, NameOrigin.Key, key)
            if (live == null) pending += key
            children += leafCapture(leafNode, body, live?.lockOrderKey ?: 0L)
        }
        if (pending.isNotEmpty()) index.pendingKeys[branch] = pending
        return TreeSnapshot(branch, children, scope, null, 0L, index)
    }

    private fun decodeKey(
        branch: KeyedBranch<*, *>,
        keyCodec: StateCodec<*>,
        encodedKey: String,
    ): Any =
        try {
            keyCodec.decode(encodedKey)
        } catch (failure: IllegalArgumentException) {
            keyFormatError(branch, failure)
        } catch (failure: IllegalStateException) {
            keyFormatError(branch, failure)
        } catch (failure: NumberFormatException) {
            keyFormatError(branch, failure)
        }

    private fun keyFormatError(
        branch: KeyedBranch<*, *>,
        failure: Throwable,
    ): Nothing =
        snapshotFormatError(
            "a key of keyed branch '${branch.name}' could not be decoded: " +
                "its key codec threw ${failure.describeClass()}",
            0,
        )
}
