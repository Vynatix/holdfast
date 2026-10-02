@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotJsonWriter
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.writeStoreBody

// The tree wire format, `holdfast.tree` v1 (issue #21 decision U9):
//
//   {"format":"holdfast.tree","v":1,"scope":"All","path":["settings"],
//    "tree":{"kind":"branch","children":{"Settings":{"kind":"leaf","store":<store body>},
//                                        "threads":{"kind":"keyed","entries":{"t1":<store body>}}}},
//    "skipped":[["settings","notes"]]}
//
// Canonical: no whitespace, children by name and entries by encoded key in
// sorted order. `path` locates the captured node from the root (a keyed
// leaf's last segment is its encoded key). Every store body is the
// `holdfast.store` v1 body of issue #20 R1, embedded verbatim, so a leaf's
// text is byte-identical to that store's own `encode()`; a leaf that
// captured nothing in its scope (only ever the captured node itself) is
// written without a `store` member. A keyed branch without a key codec is
// never encoded: as a child it is left out, as the captured node itself it
// is written with no entries, and either way its root-relative path is
// listed under `skipped`; a capture under such a branch has no writable
// path and is refused. Decoding resolves tree paths only: leaf bodies stay
// name-keyed decoded StoreSnapshots (so `migrate` and the RestorePolicy
// run per leaf at restore time), a keyed entry with no live store becomes a
// pending key, and a path this root does not declare lands in
// `unresolvedPaths`.

internal const val TREE_SNAPSHOT_FORMAT = "holdfast.tree"
internal const val TREE_SNAPSHOT_VERSION = 1

internal fun encodeTree(
    tree: TreeSnapshot,
    includeRemote: Boolean,
): String {
    if (tree.scope === SnapshotScope.UserAuthored) refuseClassDerivedLeaves(tree)
    val path = pathFromRoot(tree.node)
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

private fun refuseClassDerivedLeaves(tree: TreeSnapshot) {
    if (tree.isLeaf && tree.node.nameOrigin == NameOrigin.ClassName) {
        error(
            "Cannot encode a UserAuthored tree capture: leaf '${tree.node.name}' under '${tree.node.parent?.name}' " +
                "is named by its store's class, which a rename or obfuscation would change and orphan what was " +
                "persisted under it; pin it with branch(...).named(store, \"...\")",
        )
    }
    for (child in tree.children) refuseClassDerivedLeaves(child)
}

/**
 * The names from the root's first child down to [node]; empty for the root
 * itself. A node under a keyed branch without a key codec has no writable
 * path: its segment would be the key's `toString()`, which no decode can
 * read back and which may leak an arbitrary object's text.
 *
 * @throws IllegalStateException for a node under a keyed branch without a key codec.
 */
private fun pathFromRoot(node: StoreNode): List<String> {
    val names = ArrayList<String>()
    var current: StoreNode? = node
    while (current != null && current !is Root) {
        val parent = current.parent
        check(parent !is KeyedBranch<*, *> || parent.keyCodec != null) {
            "Cannot encode a tree capture under keyed branch '${parent?.name}' of root '${node.root.name}': " +
                "the branch has no key codec, so no path can spell the captured node's key; " +
                "declare it with keyed(keyCodec = ...)"
        }
        names.add(current.name)
        current = parent
    }
    return names.asReversed()
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
        is LeafNode -> writeLeaf(tree, includeRemote)
        is KeyedBranch<*, *> -> writeEntries(tree, node, includeRemote, skipped, path)
        is Root, is Branch -> writeChildren(tree, includeRemote, skipped, path)
    }
    endObject()
}

/** A leaf's store body; a leaf that captured nothing (only ever the captured node itself) gets no `store` member. */
private fun SnapshotJsonWriter.writeLeaf(
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
        for (child in tree.children.sortedBy { it.node.name }) {
            name(child.node.name)
            writeStoreBody(checkNotNull(child.leaf).content.toBody(includeRemote))
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
    for (child in tree.children.sortedBy { it.node.name }) {
        val childPath = path + child.node.name
        val keyed = child.node as? KeyedBranch<*, *>
        if (keyed != null && keyed.keyCodec == null) {
            skipped += childPath
            continue
        }
        check(seen.add(child.node.name)) {
            "Cannot encode the tree of root '${tree.node.root.name}': '${tree.node.name}' has two children named " +
                "'${child.node.name}' (a leaf and a branch); pin one of them"
        }
        name(child.node.name)
        writeNode(child, includeRemote, skipped, childPath)
    }
    endObject()
}
