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
// segment of `path` and a keyed entry go through the branch's key codec,
// and a key with no live store becomes a pending key when the text holds a
// body for it (a keyed leaf written without one, an empty leaf capture,
// decodes as an empty leaf: nothing to restore, nothing pending) — and
// every leaf body is retained verbatim, decoded lazily by the reading
// store's codecs. A key codec's failure, and two entries that decode to one
// key, are format failures naming the branch, never the key text. The
// root's membership is recorded as of the decode, as a capture records it
// when taken (`TreeIndex.memberKeys`).

/** The parsed shape of one node, before it is resolved against the root. */
private sealed class DecodedNode {
    class Container(
        val kind: String,
        val children: Map<String, DecodedNode>,
    ) : DecodedNode()

    class Keyed(
        val entries: Map<String, StoreBody>,
    ) : DecodedNode()

    /** [body] is `null` for a leaf written with nothing captured in its scope (an empty leaf capture). */
    class Leaf(
        val body: StoreBody?,
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
    val members = root.registry.memberStoreKeys()
    val index = TreeIndex(members)
    val unresolved = ArrayList<List<String>>()
    // A keyed segment can only end the path (a leaf has no children): the
    // key is pending only when the text holds a body at that leaf.
    val topHoldsBody = (envelope.tree as? DecodedNode.Leaf)?.body != null
    val top = resolvePath(root, envelope.path, index, topHoldsBody)
    if (top == null) {
        unresolved += envelope.path
        return TreeSnapshot(root, emptyList(), envelope.scope, null, 0L, TreeIndex(members), unresolved)
    }
    val built = Resolver(root, envelope.scope, index, unresolved).build(top, envelope.tree, envelope.path)
    return if (built == null) {
        TreeSnapshot(root, emptyList(), envelope.scope, null, 0L, TreeIndex(members), unresolved)
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
        "leaf" -> DecodedNode.Leaf(store)
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

/**
 * Walk [path] down from [root]: through declared children and a branch's
 * leaves by name, and through a keyed branch by decoding the segment with
 * its key codec — the live store's leaf, else a leaf minted for the key,
 * recorded in [index] as pending when the text holds a body for it
 * ([holdsBody]). `null` when a name is not declared there, or the keyed
 * branch has no key codec to decode the segment with.
 */
private fun resolvePath(
    root: Root,
    path: List<String>,
    index: TreeIndex,
    holdsBody: Boolean,
): StoreNode? {
    val keyed = KeyedLeaves(root, index)
    var current: StoreNode = root
    for (name in path) {
        val next: StoreNode? =
            when (val at = current) {
                is KeyedBranch<*, *> ->
                    at.keyCodec?.let { codec -> keyed.leafFor(at, keyed.decode(at, codec, name), name, holdsBody) }
                is LeafNode -> null
                is Root, is Branch ->
                    root.registry.childNodes(at).firstOrNull { it.name == name }
                        ?: (at as? Branch)?.leaves?.firstOrNull { it.name == name }
            }
        current = next ?: return null
    }
    return current
}

/**
 * Keyed segments and entries: a key decoded through its branch's codec, and
 * the leaf that sits under it now. A codec's failure and a duplicate decoded
 * key are format failures naming the branch, never the key text.
 */
private class KeyedLeaves(
    private val root: Root,
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
     * The leaf under [branch] for [key]: the live entry's, else one minted for
     * the key with no store — told apart by [LeafNode.storeKey], which no
     * store ever has as `0` — recorded as pending in [index] only when the
     * text holds a body for it ([holdsBody]): a keyed entry always does; a
     * leaf text written without one (an empty leaf capture) holds nothing to
     * restore, so it decodes as an empty leaf that `pendingKeys` never lists.
     */
    fun leafFor(
        branch: KeyedBranch<*, *>,
        key: Any,
        encodedKey: String,
        holdsBody: Boolean,
    ): LeafNode {
        val live = root.registry.liveStore(branch, key)
        val existing = live?.let { root.registry.leafOf(it) }
        if (existing != null) return existing
        if (holdsBody) index.addPendingKey(branch, key)
        return LeafNode(root, branch, encodedKey, NameOrigin.Key, key)
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

/** Resolves parsed nodes against the root's declarations, building the decoded [TreeSnapshot] bottom-up. */
private class Resolver(
    private val root: Root,
    private val scope: SnapshotScope,
    private val index: TreeIndex,
    private val unresolved: MutableList<List<String>>,
) {
    private val keyed = KeyedLeaves(root, index)

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
        body: StoreBody?,
    ): TreeSnapshot = leafCapture(node, body?.let { StoreSnapshot(DecodedContent(it)) }, node.storeKey)

    private fun leafCapture(
        node: LeafNode,
        capture: StoreSnapshot?,
        storeKey: Long,
    ): TreeSnapshot = TreeSnapshot(node, emptyList(), scope, capture, storeKey, index)

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
        return when {
            keyCodec != null -> TreeSnapshot(branch, entries(branch, keyCodec, decoded), scope, null, 0L, index)
            // This root's `encode()` writes such a branch empty and lists it as
            // skipped; entries under it are text this root cannot decode.
            decoded.entries.isEmpty() -> TreeSnapshot(branch, emptyList(), scope, null, 0L, index)
            else -> {
                unresolved += path
                null
            }
        }
    }

    private fun entries(
        branch: KeyedBranch<*, *>,
        keyCodec: StateCodec<*>,
        decoded: DecodedNode.Keyed,
    ): List<TreeSnapshot> {
        val children = ArrayList<TreeSnapshot>()
        val seen = HashSet<Any>()
        for ((encodedKey, body) in decoded.entries) {
            val key = keyed.decode(branch, keyCodec, encodedKey)
            if (!seen.add(key)) keyed.duplicateError(branch)
            val leafNode = keyed.leafFor(branch, key, encodedKey, holdsBody = true)
            children += leafCapture(leafNode, StoreSnapshot(DecodedContent(body)), leafNode.storeKey)
        }
        return children
    }
}
