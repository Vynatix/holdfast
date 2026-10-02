@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.ObservableBacked
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.internalAttachment
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The one [StoreTree] of [owner], kept on its tree state ([attachment]).
 * Its value machinery — the host store and the [TreeValue], which registers
 * the membership listener on [owner]'s registry — is created lazily, on the
 * first [value] read or observation ([observableBacking]) or by the
 * `@StoreInternalApi` seams that need it (`internalHost()`,
 * `internalSettleNow()`): a handle used only for structure (`snapshot`,
 * `stores`, `middlewares`, the harness's `track`, `hydrateAll`) builds no
 * host and registers no listener. Every observation path resolves the value
 * through [observableBacking].
 */
@Suppress("TooManyFunctions") // The tree DSL is intentionally broad; each member is one primitive.
internal class StoreTreeImpl(
    private val owner: Store<*>,
    attachment: TreeLeafAttachment,
) : StoreTree,
    ObservableBacked<TreeSnapshot> {
    override val node: LeafNode = attachment.node

    private val registry = attachment.registry

    /** Guards the creation of [valueRef] against [onOwnerDisposed]; taken before the registry lock, never under it. */
    private val valueLock = SynchronizedObject()

    private val valueRef = atomic<TreeValue?>(null)

    /** Set by [onOwnerDisposed] under [valueLock]: no value machinery is created from then on. */
    private var ownerDisposed = false

    /** The value machinery, if anything created it yet; never creates it. */
    val treeValueOrNull: TreeValue? get() = valueRef.value

    /**
     * The value machinery, created on first need: the host store, then the
     * [TreeValue] (registering its listener on [owner]'s registry).
     *
     * @throws IllegalStateException if [owner] is disposed and it was never created.
     */
    fun treeValue(): TreeValue =
        valueRef.value ?: synchronized(valueLock) {
            valueRef.value ?: run {
                check(!ownerDisposed) { "store disposed" }
                owner.checkNotDisposed()
                val host = TreeValueHost(owner)
                var created = false
                try {
                    TreeValue(owner, node, registry, host).also {
                        created = true
                        valueRef.value = it
                    }
                } finally {
                    if (!created) host.dispose()
                }
            }
        }

    override val parent: StoreNode? get() = node.parent

    override val value: TreeSnapshot get() = treeValue().read()

    override val observableBacking: MutableState<TreeSnapshot>
        get() = treeValue().node().backing

    override val children: List<StoreNode>
        get() {
            owner.checkNotDisposed()
            materializeSubtree(this.node)
            return registry.liveChildNodes()
        }

    override fun stores(node: StoreNode): List<Store<*>> = leaves(node).map { it.second }

    /** [stores] with each store's node, from one materialization and one listing. */
    fun leaves(node: StoreNode): List<Pair<LeafNode, Store<*>>> {
        owner.checkNotDisposed()
        requireInSubtree(node)
        materializeSubtree(this.node)
        return liveLeavesUnder(this.node, node)
    }

    override fun nodeOf(store: Store<*>): LeafNode? {
        owner.checkNotDisposed()
        materializeSubtree(this.node)
        return store.internalAttachment(treeMembershipKey)?.node?.takeIf { it === node || it.isUnder(node) }
    }

    override fun snapshot(
        node: StoreNode,
        scope: SnapshotScope,
    ): TreeSnapshot {
        owner.checkNotDisposed()
        requireInSubtree(node)
        materializeSubtree(this.node)
        return captureTree(this.node, node, scope)
    }

    override fun restore(
        tree: TreeSnapshot,
        policy: RestorePolicy,
        sterile: Boolean,
    ): TransactionResult<TreeRestoreReport> {
        owner.checkNotDisposed()
        require(tree.node === node || tree.node.isUnder(node)) {
            "the tree was captured under '${tree.index.ownerNode.name}' and its node '${tree.name}' is not under " +
                "this store ('${node.name}'); restore it through the store it was captured from"
        }
        materializeSubtree(node)
        return restoreTree(node, tree, policy, sterile)
    }

    override fun reset(node: StoreNode): TransactionResult<TreeResetReport> {
        owner.checkNotDisposed()
        requireInSubtree(node)
        materializeSubtree(this.node)
        return resetTree(this.node, node)
    }

    override fun decode(text: String): TreeSnapshot {
        owner.checkNotDisposed()
        materializeSubtree(this.node)
        return decodeTree(this.node, text)
    }

    override fun verifyPersistedNames(node: StoreNode): List<NamingIssue> {
        owner.checkNotDisposed()
        requireInSubtree(node)
        materializeSubtree(this.node)
        return verifyNames(this.node, node)
    }

    override fun middlewares(vararg middleware: TreeMiddleware) {
        owner.checkNotDisposed()
        materializeSubtree(this.node)
        installTreeMiddleware(owner, middleware.toList())
    }

    override fun removeMiddleware(middleware: TreeMiddleware): Boolean = removeTreeMiddleware(owner, middleware)

    /**
     * Step 6 of [owner]'s tree dispose (nothing to do when no value machinery
     * was ever created): stop the value, then dispose the
     * host where no lock of [owner]'s is held — when the open settle scope
     * settles, else after the holder of [owner]'s transaction lock on this
     * thread releases it (every holder drains the store's post-commit queue
     * after releasing), else inline.
     */
    fun onOwnerDisposed(disposing: Store<*>) {
        val treeValue =
            synchronized(valueLock) {
                ownerDisposed = true
                valueRef.value
            } ?: return
        val host = treeValue.host
        treeValue.disposeSync()
        when {
            SettleScopes.current()?.enqueue(HostDisposeTask(host)) == true -> Unit
            disposing.transactionLock.isHeldByCurrentThread() -> disposing.handOffPostCommit { host.dispose() }
            else -> host.dispose()
        }
    }

    private fun requireInSubtree(node: StoreNode) {
        require(node === this.node || node.isUnder(this.node)) {
            "node '${node.name}' is not under '${this.node.name}'"
        }
    }

    override fun toString(): String = "StoreTree(${owner.displayName})"
}
