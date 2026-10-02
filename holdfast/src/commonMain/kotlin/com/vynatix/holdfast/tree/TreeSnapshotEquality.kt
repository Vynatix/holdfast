@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

// `TreeSnapshot` equality (issue #21 decision U8): `equals`/`hashCode` are
// full value equality over names, structure, scope and every leaf's
// `StoreSnapshot` (Secret, Remote and codec-less states included, so a
// `distinct` backing never dedups a Secret-only change); `equalsEncodable`
// compares the encodable projections only, the way `encode()` writes them.

internal fun treeEquals(
    a: TreeSnapshot,
    b: TreeSnapshot,
): Boolean =
    a === b ||
        (
            a.scope === b.scope &&
                sameNode(a, b) &&
                a.leaf == b.leaf &&
                a.children.size == b.children.size &&
                a.children.indices.all { treeEquals(a.children[it], b.children[it]) }
        )

internal fun treeHash(tree: TreeSnapshot): Int {
    var hash = tree.name.hashCode() * HASH_PRIME + tree.scope.hashCode()
    hash = hash * HASH_PRIME + (tree.leaf?.hashCode() ?: 0)
    for (child in tree.children) hash = hash * HASH_PRIME + treeHash(child)
    return hash
}

/**
 * The encodable projections of [a] and [b] are equal. At the top only the
 * kinds must match: the captured node's own name is never written (`path`
 * locates it), so a decoded top may carry another name than the capture's.
 */
internal fun treeEqualsEncodable(
    a: TreeSnapshot,
    b: TreeSnapshot,
    includeRemote: Boolean,
): Boolean = kindOf(a.node) == kindOf(b.node) && sameEncodableBody(a, b, includeRemote)

private fun sameEncodableBody(
    a: TreeSnapshot,
    b: TreeSnapshot,
    includeRemote: Boolean,
): Boolean {
    val childrenA = a.encodableChildren()
    val childrenB = b.encodableChildren()
    return sameLeafBody(a, b, includeRemote) &&
        childrenA.size == childrenB.size &&
        childrenA.indices.all {
            sameNode(childrenA[it], childrenB[it]) && sameEncodableBody(childrenA[it], childrenB[it], includeRemote)
        }
}

private val encodedChildOrder = compareBy<TreeSnapshot>({ it.name }, { kindOf(it.node) })

/**
 * The children `encode()` writes, by name as it writes them (a capture lists
 * children in declaration order, a decoded text in the sorted order it was
 * written in): none under a keyed branch without a key codec, which is
 * written empty when it is the captured node, and never as a child.
 */
private fun TreeSnapshot.encodableChildren(): List<TreeSnapshot> {
    if (!isEncodable()) return emptyList()
    return children.filter { it.isEncodable() }.sortedWith(encodedChildOrder)
}

/** Both leaves absent, or both present with equal encodable projections (runs the codecs). */
private fun sameLeafBody(
    a: TreeSnapshot,
    b: TreeSnapshot,
    includeRemote: Boolean,
): Boolean {
    val leafA = a.leaf
    val leafB = b.leaf
    if (leafA == null || leafB == null) return leafA == null && leafB == null
    return leafA.content.toBody(includeRemote) == leafB.content.toBody(includeRemote)
}

/** A keyed branch without a key codec is never encoded, so it is left out of the encodable projection. */
private fun TreeSnapshot.isEncodable(): Boolean {
    val keyed = node as? KeyedBranch<*, *> ?: return true
    return keyed.keyCodec != null
}

private fun sameNode(
    a: TreeSnapshot,
    b: TreeSnapshot,
): Boolean = a.name == b.name && kindOf(a.node) == kindOf(b.node)

private const val HASH_PRIME = 31
