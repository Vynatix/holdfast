@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store

/** One leaf a `treeFrame` worked on: its node, its store, and what `prepare` produced for it. */
internal class TreeFrameTarget<P>(
    val leaf: LeafNode,
    val store: Store<*>,
    val prepared: P,
)
