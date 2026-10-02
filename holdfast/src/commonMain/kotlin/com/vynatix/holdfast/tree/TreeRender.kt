@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

/**
 * `TreeSnapshot.render()`: one line per node — name, kind, name origin —
 * with each leaf's values under it as its `StoreSnapshot.render()` prints
 * them (so a `Secret` value is `<redacted>`). Layout may change; for logs.
 */
internal fun renderTree(tree: TreeSnapshot): String {
    val out = StringBuilder("TreeSnapshot (scope ").append(tree.scope).append(')')
    renderNode(tree, 0, out)
    return out.toString()
}

private fun renderNode(
    tree: TreeSnapshot,
    depth: Int,
    out: StringBuilder,
) {
    val indent = "  ".repeat(depth)
    out.append('\n').append(indent).append(tree.node.name)
    out
        .append(" (")
        .append(kindOf(tree.node))
        .append(", ")
        .append(originText(tree.node.nameOrigin))
        .append(')')
    tree.leaf
        ?.render()
        ?.lineSequence()
        ?.drop(1)
        ?.forEach { line -> out.append('\n').append(indent).append(line) }
    for (child in tree.children) renderNode(child, depth + 1, out)
}

internal fun kindOf(node: StoreNode): String =
    when (node) {
        is Root -> "root"
        is Branch -> "branch"
        is KeyedBranch<*, *> -> "keyed"
        is LeafNode -> "leaf"
    }

private fun originText(origin: NameOrigin): String =
    when (origin) {
        NameOrigin.Property -> "property name"
        NameOrigin.ClassName -> "class name"
        NameOrigin.Key -> "key"
        NameOrigin.Pinned -> "pinned"
    }
