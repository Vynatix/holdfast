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
//    "skipped":[["sync","notes"]]}
//
// Canonical: no whitespace, children by name and entries by encoded key in
// sorted order. `path` locates the captured node from the root. Every store
// body is the `holdfast.store` v1 body of issue #20 R1, embedded verbatim, so
// a leaf's text is byte-identical to that store's own `encode()`. A keyed
// branch without a key codec is skipped and listed. Decoding resolves tree
// paths only: leaf bodies stay name-keyed decoded StoreSnapshots (so
// `migrate` and the RestorePolicy run per leaf at restore time), a keyed
// entry with no live store becomes a pending key, and a path this root does
// not declare lands in `unresolvedPaths`.

internal const val TREE_SNAPSHOT_FORMAT = "holdfast.tree"
internal const val TREE_SNAPSHOT_VERSION = 1

internal fun encodeTree(
    tree: TreeSnapshot,
    includeRemote: Boolean,
): String {
    if (tree.scope === SnapshotScope.UserAuthored) refuseClassDerivedLeaves(tree)
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
    writer.writeStrings(pathFromRoot(tree.node))
    writer.name("tree")
    writer.writeNode(tree, includeRemote, skipped, emptyList())
    writer.name("skipped")
    writer.beginArray()
    for (path in skipped.sortedBy { it.joinToString("/") }) writer.writeStrings(path)
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

/** The names from the root's first child down to [node]; empty for the root itself. */
private fun pathFromRoot(node: StoreNode): List<String> {
    val names = ArrayList<String>()
    var current: StoreNode? = node
    while (current != null && current !is Root) {
        names.add(current.name)
        current = current.parent
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
        is LeafNode -> {
            name("store")
            writeStoreBody(checkNotNull(tree.leaf).content.toBody(includeRemote))
        }
        is KeyedBranch<*, *> -> writeEntries(tree, includeRemote)
        is Root, is Branch -> writeChildren(tree, includeRemote, skipped, path)
    }
    endObject()
}

private fun SnapshotJsonWriter.writeEntries(
    tree: TreeSnapshot,
    includeRemote: Boolean,
) {
    name("entries")
    beginObject()
    for (child in tree.children.sortedBy { it.node.name }) {
        name(child.node.name)
        writeStoreBody(checkNotNull(child.leaf).content.toBody(includeRemote))
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
