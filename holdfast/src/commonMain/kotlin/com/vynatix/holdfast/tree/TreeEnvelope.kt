@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotJsonWriter
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.writeStoreBody

// The tree wire format, `holdfast.tree` v1 (issue #21 decision U9):
//
//   {"format":"holdfast.tree","v":1,"scope":"All","path":[],
//    "tree":{"kind":"leaf","store":<receiver body>,
//            "children":{"settings":{"kind":"leaf","store":<body>},
//                        "session":{"kind":"branch","children":{"Profile":{"kind":"leaf","store":<body>}}},
//                        "threads":{"kind":"keyed","entries":{"42":{"kind":"leaf","store":<body>}}}}},
//    "skipped":[["notes"]]}
//
// Canonical: no whitespace, children by name and entries by encoded key in
// sorted order. Every node is uniform: a `leaf` (a store's node) may carry
// `store` (omitted when nothing was captured) and `children` (omitted when
// empty, so a childless leaf is byte-identical to the shape without
// children); a `branch` always has `children`; a `keyed` branch always has
// `entries`, whose values are leaf nodes. `path` locates the captured node
// from the receiver — the store whose `tree` took the capture, whose own
// name is never written (a keyed leaf's segment is its encoded key) — and
// `skipped` paths are relative to the receiver too. Every store body is the
// `holdfast.store` v1 body of issue #20 R1, embedded verbatim, so a store's
// text is byte-identical to that store's own `encode()`. A keyed branch
// without a key codec is never encoded: as a child it is left out, as the
// captured node itself it is written with no entries, and either way its
// path is listed under `skipped`; a capture under such a branch has no
// writable path and is refused. Names are the names AS CAPTURED
// (`TreeSnapshot.name`), so a kept capture encodes the structure it was
// taken from whatever disposed since. Decoding is `TreeDecode.kt`.

internal const val TREE_SNAPSHOT_FORMAT = "holdfast.tree"
internal const val TREE_SNAPSHOT_VERSION = 1

internal fun encodeTree(
    tree: TreeSnapshot,
    includeRemote: Boolean,
): String {
    if (tree.scope === SnapshotScope.UserAuthored) refuseClassDerivedLeaves(tree)
    val path = pathBelow(tree)
    val skipped = ArrayList<List<String>>()
    val writer = SnapshotJsonWriter()
    writer.beginObject()
    writer.name("format")
    writer.value(TREE_SNAPSHOT_FORMAT)
    writer.name("v")
    writer.value(TREE_SNAPSHOT_VERSION)
    writer.name("scope")
    writer.value(tree.scope.toString())
    writer.name("path")
    writer.writeStrings(path)
    writer.name("tree")
    writer.writeNode(tree, includeRemote, skipped, path)
    writer.name("skipped")
    writer.beginArray()
    for (skippedPath in skipped.sortedBy { it.joinToString("/") }) writer.writeStrings(skippedPath)
    writer.endArray()
    writer.endObject()
    return writer.toString()
}

/**
 * Refuse a store node of the capture — other than the receiver, whose name
 * is never written — that captured something and is named by its store's
 * class (as captured): a rename or obfuscation would orphan what was
 * persisted under it.
 */
private fun refuseClassDerivedLeaves(tree: TreeSnapshot) {
    val index = tree.index
    val classNamed = tree.hasStore && tree.node !== index.ownerNode && index.originOf(tree.node) == NameOrigin.ClassName
    if (classNamed) {
        val parentName = index.parentOf[tree.node]?.let(index::nameOf)
        error(
            "Cannot encode a UserAuthored tree capture: leaf '${tree.name}' under '$parentName' is named by its " +
                "store's class, which a rename or obfuscation would change and orphan what was persisted under " +
                "it; pin it with stores(names = mapOf(Store::class to \"...\"))",
        )
    }
    for (child in tree.children) refuseClassDerivedLeaves(child)
}

/**
 * The captured names from the receiver's child down to [tree]'s node; empty
 * for the receiver itself. A node under a keyed branch without a key codec
 * has no writable path: its segment would be the key's `toString()`, which
 * no decode can read back and which may leak an arbitrary object's text.
 *
 * @throws IllegalStateException for a node under a keyed branch without a key codec.
 */
private fun pathBelow(tree: TreeSnapshot): List<String> {
    val index = tree.index
    var current: StoreNode = tree.node
    while (current !== index.ownerNode) {
        val parent = index.parentOf[current] ?: break
        check(parent !is KeyedBranch<*, *> || parent.keyCodec != null) {
            "Cannot encode a tree capture under keyed branch '${index.nameOf(parent)}' of " +
                "'${index.ownerNode.name}': the branch has no key codec, so no path can spell the captured " +
                "node's key; declare it with stores<K, S>(keyCodec = ...)"
        }
        current = parent
    }
    return index.pathOf(tree.node)
}

private fun SnapshotJsonWriter.writeStrings(strings: List<String>) {
    beginArray()
    for (s in strings) value(s)
    endArray()
}

private fun SnapshotJsonWriter.writeNode(
    tree: TreeSnapshot,
    includeRemote: Boolean,
    skipped: MutableList<List<String>>,
    path: List<String>,
) {
    beginObject()
    name("kind")
    value(kindOf(tree.node))
    when (val node = tree.node) {
        is LeafNode -> {
            writeStore(tree, includeRemote)
            if (tree.children.isNotEmpty()) writeChildren(tree, includeRemote, skipped, path)
        }
        is KeyedBranch<*, *> -> writeEntries(tree, node, includeRemote, skipped, path)
        is Branch -> writeChildren(tree, includeRemote, skipped, path)
    }
    endObject()
}

/** A store node's body; one that captured nothing in its scope gets no `store` member. */
private fun SnapshotJsonWriter.writeStore(
    tree: TreeSnapshot,
    includeRemote: Boolean,
) {
    val capture = tree.leaf ?: return
    name("store")
    writeStoreBody(capture.content.toBody(includeRemote))
}

/** A keyed branch's entries by encoded key; without a key codec none can be written: no entries, the branch listed. */
private fun SnapshotJsonWriter.writeEntries(
    tree: TreeSnapshot,
    branch: KeyedBranch<*, *>,
    includeRemote: Boolean,
    skipped: MutableList<List<String>>,
    path: List<String>,
) {
    name("entries")
    beginObject()
    if (branch.keyCodec == null) {
        skipped += path
    } else {
        for (child in tree.children.sortedBy { it.name }) {
            name(child.name)
            writeNode(child, includeRemote, skipped, path + child.name)
        }
    }
    endObject()
}

private fun SnapshotJsonWriter.writeChildren(
    tree: TreeSnapshot,
    includeRemote: Boolean,
    skipped: MutableList<List<String>>,
    path: List<String>,
) {
    name("children")
    beginObject()
    val seen = HashSet<String>()
    for (child in tree.children.sortedBy { it.name }) {
        val childPath = path + child.name
        val keyed = child.node as? KeyedBranch<*, *>
        if (keyed != null && keyed.keyCodec == null) {
            skipped += childPath
            continue
        }
        check(seen.add(child.name)) {
            "Cannot encode the tree under '${tree.index.ownerNode.name}': '${tree.name}' has two children named " +
                "'${child.name}'; pin one of them"
        }
        name(child.name)
        writeNode(child, includeRemote, skipped, childPath)
    }
    endObject()
}
