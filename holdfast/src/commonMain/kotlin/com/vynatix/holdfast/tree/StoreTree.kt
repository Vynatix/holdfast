@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult

/**
 * A store's view of its subtree (`store.tree`): the store itself — the
 * receiver — and every store declared under it with `store { }`,
 * `group { }` and `keyed<K, S> { }`, at any depth.
 *
 * It is itself the subtree's value: a [TreeSnapshot] over every live store
 * of the subtree as ONE consistent capture, kept current — recomputed once
 * per outermost entry that changes the subtree (an `atomic` frame over
 * several stores, an action with nested actions, a `restore` or `reset`, a
 * store that joins or leaves), after every lock that entry took is
 * released, and published only when it differs (full value equality).
 * Observe it like any state (`effect`, `asFlow`, `collectAsState`). The
 * first read or observation builds it — materializing every declared child
 * first — and a read after the receiver's `dispose()` answers the last tree
 * (a first read then throws). [snapshot] is a fresh cut instead.
 *
 * Every structural member materializes the receiver's declared children
 * first (their lambdas run if they have not; materializing runs no state
 * initializer by itself, but a tree value in use at or above a child that
 * attached settles afterwards and captures it, which materializes its
 * never-read states as `snapshot()` does) and refuses a [StoreNode] outside
 * the receiver's subtree. After the
 * receiver is disposed every member throws ("store disposed") except
 * [node], [parent] and [removeMiddleware] (which answers `false`), and
 * [value], which answers the last tree when it was read before.
 *
 * Sealed: `store.tree` is the only implementation (the library's seams cast
 * to it), so a fake cannot stand in for a handle.
 */
@ExperimentalStoreApi
// The tree DSL is intentionally broad; each member is one primitive; bodies live in helper files.
@Suppress("TooManyFunctions")
sealed interface StoreTree : State<TreeSnapshot> {
    /** The receiver's node: the store's place, for its whole life. */
    val node: LeafNode

    /**
     * The node the receiver hangs under — its parent's node, a group or a
     * keyed branch — or `null` for a subtree root.
     */
    val parent: StoreNode?

    /**
     * The receiver's declared children, in declaration order, after
     * materializing them (and, recursively, their own): each `store { }`
     * child's [LeafNode], each group's [Branch], each keyed branch's
     * [KeyedBranch] (listed from its declaration on, with or without
     * entries). A child whose lambda throws makes this throw.
     *
     * WARNING — this is not a cheap read: it RUNS THE CHILD
     * LAMBDAS of every declared child not materialized yet, at any depth, on
     * the calling thread (user code, which may open actions and build
     * stores), and it may PARK the calling thread for a moment while another
     * thread finishes attaching a child it materialized first. Do not read
     * it from code that must not run user code or block (an observer, a
     * listener, a hot loop); hold the list it answered instead.
     */
    fun children(): List<StoreNode>

    /**
     * Every live store of the subtree at [node], in tree order (the receiver
     * first when [node] is the receiver's node).
     *
     * @throws IllegalArgumentException if [node] is not in the receiver's subtree.
     */
    fun stores(node: StoreNode = this.node): List<Store<*>>

    /** [store]'s node when [store] is the receiver or lives under it; `null` otherwise. */
    fun nodeOf(store: Store<*>): LeafNode?

    /**
     * Capture the subtree at [node] as ONE consistent cut across its live
     * stores, in [scope] (see [TreeSnapshot]). Never-read declared states the
     * scope captures are materialized first; no store's transaction lock is
     * taken, no writer is blocked. A store disposed while the capture runs
     * is left out; a keyed store still inside its factory is never included.
     * Under `SnapshotScope.UserAuthored`, stores and groups with nothing
     * captured are pruned (never [node] itself).
     *
     * @throws IllegalArgumentException if [node] is not in the receiver's subtree.
     */
    fun snapshot(
        node: StoreNode = this.node,
        scope: SnapshotScope = SnapshotScope.All,
    ): TreeSnapshot

    /**
     * Restore [tree] into the stores at its nodes now, as ONE frame: every
     * store is planned first, outside every lock (schema version, `migrate`,
     * codecs, [policy]), then each plan is staged into one outermost
     * `atomic`, so observers see the restored subtree whole. A keyed store's
     * capture goes to whichever store lives under its key today; a capture
     * with no live store is skipped (under [RestorePolicy.Strict] the
     * restore fails before anything is touched). With [sterile], every store
     * is restored sterile.
     *
     * @throws IllegalArgumentException if [tree] was not captured from this
     *   receiver's subtree.
     * @throws IllegalStateException from inside an action, frame, suspending
     *   entry, commit fanout or recompute.
     */
    fun restore(
        tree: TreeSnapshot,
        policy: RestorePolicy = RestorePolicy.IgnoreUnknown,
        sterile: Boolean = false,
    ): TransactionResult<TreeRestoreReport>

    /**
     * Reset every live store of the subtree at [node] — the receiver's own
     * states included when [node] is the receiver's node — as ONE frame.
     *
     * @throws IllegalArgumentException if [node] is not in the receiver's subtree.
     * @throws IllegalStateException from inside an action, frame, suspending
     *   entry, commit fanout or recompute.
     */
    fun reset(node: StoreNode = this.node): TransactionResult<TreeResetReport>

    /**
     * Read a `holdfast.tree` v1 text ([TreeSnapshot.encode]) back into a
     * capture addressed by this subtree's nodes; a path the subtree does not
     * declare is listed in [TreeSnapshot.unresolvedPaths]. The text must
     * have been captured under this receiver: its `receiver` must equal this
     * receiver's identity — its [TreeIdentified.treeId], else its node name
     * (a child's property name or pin; a top-level store's class name minus
     * `Store`, which a class rename changes).
     *
     * @throws com.vynatix.holdfast.SnapshotFormatException if the text is not
     *   a well-formed `holdfast.tree` v1 envelope, or was captured under
     *   another receiver (the message names both, never a value).
     */
    fun decode(text: String): TreeSnapshot

    /**
     * The persisted-name self-check over the subtree at [node]: every
     * persisted store at a group leaf named by its class rather than pinned;
     * every `store { }` child, group or keyed branch named by its Kotlin
     * property with a persisted store at or under it (pin it with
     * `named =`); every keyed branch declared without a key codec; and the
     * receiver, when it is a top-level store identified by its class name
     * (not [TreeIdentified]) and the subtree persists. Each is a name a
     * rename or obfuscation would change under persisted data.
     *
     * @throws IllegalArgumentException if [node] is not in the receiver's subtree.
     */
    fun verifyPersistedNames(node: StoreNode = this.node): List<NamingIssue>

    /**
     * Install [middleware] on the receiver and every store of its subtree,
     * attached now or later, outermost of each store's own; a parent's tree
     * middleware wraps a child's, and a middleware installed on both fires
     * once, at the parent's place.
     *
     * @throws IllegalStateException from inside an `atomic` frame, a
     *   transaction of any store of the subtree, or a suspending body of one.
     */
    fun middlewares(vararg middleware: TreeMiddleware)

    /**
     * Stop [middleware] installed through this receiver. `false` when it
     * was not installed here — including on a disposed receiver, whose
     * dispose removed everything (a documented no-check exception).
     *
     * @throws IllegalStateException as [middlewares].
     */
    fun removeMiddleware(middleware: TreeMiddleware): Boolean
}
