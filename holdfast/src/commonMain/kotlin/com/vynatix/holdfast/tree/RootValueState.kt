@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.ObservableBacked
import com.vynatix.holdfast.State

/**
 * `Root.value`: a [State] over the root's tree value. A read seeds the
 * derived node on first use (the first capture, which runs never-read
 * initializers), settles a pending structural change when it can, and
 * otherwise answers the last settled tree; every observation path (`effect`,
 * flows, Compose) resolves it through [observableBacking] to the node's
 * backing, an unregistered `distinct` state of the root's host.
 */
internal class RootValueState(
    private val owner: RootValue,
) : State<TreeSnapshot>,
    ObservableBacked<TreeSnapshot> {
    override val value: TreeSnapshot
        get() = owner.read()

    override val observableBacking: MutableState<TreeSnapshot>?
        get() = owner.node().backing

    override fun toString(): String = "Root.value(${owner.root.name})"
}
