@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.testing.TreeEvent
import com.vynatix.holdfast.testing.TreeHandle
import com.vynatix.holdfast.tree.Root
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The trees one `StoreTestScope` tracks (`trackTree`), by root identity, and
 * their teardown: after the leaf handles' recorders are gone, each tree
 * stops hearing of new members, is reset as one frame (unless opted out, the
 * root is disposed, or a leaf is still held — see [tearDown]) with its
 * recorder still installed so a veto is recorded with its leaf, and then
 * loses its recorder. Never disposes a root.
 */
internal class TreeFixtures {
    private val lock = SynchronizedObject()
    private val trees = mutableListOf<TreeHandle>()

    /**
     * One [TreeHandle] per root: the existing one, else [create]'s — a losing
     * racer's handle is unwound. A throwing [create] registers nothing; the
     * handle's own construction unwinds whatever it had installed.
     */
    fun register(
        root: Root,
        create: () -> TreeHandle,
    ): TreeHandle {
        synchronized(lock) { trees.firstOrNull { it.root === root } }?.let { return it }
        val fresh = create()
        val winner = synchronized(lock) { trees.firstOrNull { it.root === root } ?: fresh.also { trees.add(it) } }
        if (winner !== fresh) {
            fresh.listener.dispose()
            runCatching { root.removeMiddleware(fresh.recorder) }
        }
        return winner
    }

    /**
     * Unwind every tracked tree, in track order; one line per tree whose
     * reset failed.
     *
     * The reset is one `atomic` frame over every live leaf, taken through
     * each leaf's serializer and transaction lock. A leaf still held when
     * teardown runs — a `suspendAction`/`suspendAtomic` body parked in
     * un-joined work, a thread inside an action — skips the whole tree's
     * reset instead of waiting: the wait would spin on the test thread, the
     * only one that could resume a parked body, and un-joined work is not
     * waited for by contract. The skip is not a failure; the next test finds
     * the values the body left.
     */
    fun tearDown(): List<String> {
        val all = synchronized(lock) { trees.toList().also { trees.clear() } }
        val failures = mutableListOf<String>()
        for (tree in all) {
            runCatching { tree.listener.dispose() }
            if (tree.resetAtTeardown && !tree.root.isDisposed && !aLeafIsHeld(tree.root)) {
                val outcome = runCatching { tree.root.reset() }
                outcome.onFailure {
                    failures += "tree '${tree.root.name}': reset threw ${it::class.simpleName}: ${it.message}"
                }
                outcome.onSuccess { result ->
                    if (result is TransactionResult.Error) failures += describeResetFailure(tree, result)
                }
            }
            runCatching { tree.root.removeMiddleware(tree.recorder) }
            tree.recorder.dispose()
        }
        return failures
    }

    /**
     * Whether a live leaf of [root] is held by an entry teardown cannot wait
     * out ([PrivilegedHooks.isHeldByAnEntry]). A root disposed meanwhile is
     * treated as held: there is nothing left to reset.
     */
    private fun aLeafIsHeld(root: Root): Boolean =
        runCatching { root.children(root).any { PrivilegedHooks.isHeldByAnEntry(it) } }.getOrDefault(true)

    private fun describeResetFailure(
        tree: TreeHandle,
        result: TransactionResult.Error,
    ): String {
        val frame = result.transaction.frameId
        val vetoes =
            tree.timeline
                .filter { it.phase == TreeEvent.Phase.Errored && it.cause != null }
                .filter { frame == null || it.transaction.frameId == frame }
                .map { "leaf '${it.node.name}' (${it.cause!!::class.simpleName}: ${it.cause.message})" }
                .distinct()
        val exception = result.exception
        val where =
            if (vetoes.isEmpty()) "${exception::class.simpleName}: ${exception.message}" else vetoes.joinToString("; ")
        return "tree '${tree.root.name}': reset failed — $where"
    }
}
