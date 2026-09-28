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
                sameNode(a.node, b.node) &&
                a.leaf == b.leaf &&
                a.children.size == b.children.size &&
                a.children.indices.all { treeEquals(a.children[it], b.children[it]) }
        )

internal fun treeHash(tree: TreeSnapshot): Int {
    var hash = tree.node.name.hashCode() * HASH_PRIME + tree.scope.hashCode()
    hash = hash * HASH_PRIME + (tree.leaf?.hashCode() ?: 0)
    for (child in tree.children) hash = hash * HASH_PRIME + treeHash(child)
    return hash
}

internal fun treeEqualsEncodable(
    a: TreeSnapshot,
    b: TreeSnapshot,
    includeRemote: Boolean,
): Boolean {
    // By name, as `encode()` writes them: a capture lists children in
    // declaration order, a decoded text in the sorted order it was written in.
    val childrenA = a.children.filter { it.isEncodable() }.sortedWith(encodedChildOrder)
    val childrenB = b.children.filter { it.isEncodable() }.sortedWith(encodedChildOrder)
    return sameNode(a.node, b.node) &&
        sameLeafBody(a, b, includeRemote) &&
        childrenA.size == childrenB.size &&
        childrenA.indices.all { treeEqualsEncodable(childrenA[it], childrenB[it], includeRemote) }
}

private val encodedChildOrder = compareBy<TreeSnapshot>({ it.node.name }, { kindOf(it.node) })

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
    a: StoreNode,
    b: StoreNode,
): Boolean = a.name == b.name && kindOf(a) == kindOf(b)

private const val HASH_PRIME = 31
