@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.platform.threadYield
import com.vynatix.holdfast.testing.TreeEvent
import com.vynatix.holdfast.testing.TreeHandle
import com.vynatix.holdfast.tree.StoreTree
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How long [TreeFixtures.tearDown] re-probes a tree's live stores for one
 * still held by an entry it cannot wait out before it skips the reset and
 * fails the test. Real time, between probes the thread yields: a transient
 * holder — an in-flight `suspendDerived` recompute on `Store.scope`, which
 * no test can join, a thread finishing an action — is gone long before
 * this; a `suspendAction` body parked in un-joined work never is.
 */
internal val TEARDOWN_HELD_LEAF_BUDGET: Duration = 1.seconds

/**
 * What [TreeFixtures.tearDown] found, one line per tree: the trees whose
 * reset ran and failed (or whose subtree could not be listed), and the
 * trees whose reset it skipped because a store was still held when [TEARDOWN_HELD_LEAF_BUDGET] ran out — each a
 * failure of the test unless the body already failed.
 */
internal class TreeTeardownReport(
    val resetFailures: List<String>,
    val skippedResets: List<String>,
)

/**
 * The trees one `StoreTestScope` tracks (`track(tree)`), by receiver
 * identity, and their teardown: after the store handles' recorders are
 * gone, each tree stops hearing of new members, is reset as one frame over
 * the receiver and its subtree (unless opted out, the receiver is disposed,
 * or a member is still held — see [tearDown]) with its recorder still
 * installed so a veto is recorded with its store, and then loses its
 * recorder. Never disposes a store.
 */
internal class TreeFixtures {
    private val lock = SynchronizedObject()
    private val trees = mutableListOf<TreeHandle>()

    /**
     * One [TreeHandle] per receiver [owner]: the existing one, else
     * [create]'s — a losing racer's handle is unwound. A throwing [create]
     * registers nothing; the handle's own construction unwinds whatever it
     * had installed.
     */
    fun register(
        owner: Store<*>,
        create: () -> TreeHandle,
    ): TreeHandle {
        synchronized(lock) { trees.firstOrNull { it.root === owner } }?.let { return it }
        val fresh = create()
        val winner = synchronized(lock) { trees.firstOrNull { it.root === owner } ?: fresh.also { trees.add(it) } }
        if (winner !== fresh) {
            fresh.listener.dispose()
            // The last step: a fatal error (issue #61) loses nothing by propagating now.
            runCatching { fresh.tree.removeMiddleware(fresh.recorder) }.onFailure(PrivilegedHooks::rethrowIfFatal)
        }
        return winner
    }

    /**
     * Unwind every tracked tree, in track order, and report the trees whose
     * reset failed or was skipped.
     *
     * The reset is one `atomic` frame over the receiver and every live store
     * of its subtree, taken through each store's serializer and transaction
     * lock, and it would wait for a store an entry still holds — spinning on
     * the test thread when that entry is a `suspendAction`/`suspendAtomic`
     * body parked in un-joined work, which only the test thread could
     * resume. So before resetting, every live member is probed
     * ([PrivilegedHooks.isHeldByAnEntry]), and while one is held the probe
     * repeats, yielding between rounds, for up to
     * [TEARDOWN_HELD_LEAF_BUDGET]: a transient holder (an in-flight
     * `suspendDerived` recompute on `Store.scope`, a thread finishing an
     * action) is waited out and the reset runs. A store still held when the
     * budget ends skips the whole tree's reset — and that skip is reported,
     * naming the tree and the held stores, because it leaves the body's
     * values for the next test to find; `resetAtTeardown = false` is the
     * opt-out for a test that parks work deliberately. The probe is a
     * decision, not a lock: an entry taking a store right after it answered
     * free is waited for by the reset like any `atomic` would. Listing the
     * subtree materializes declared children not read yet; a child lambda
     * that throws there is reported as a reset failure, never a silent
     * skip.
     *
     * A fatal error (issue #61) a step throws — a child lambda's
     * `StackOverflowError` while listing, a reset's — is never reported: it
     * is held in [fatal] while this tree's remaining steps and every later
     * tree still run, for the scope's teardown to throw after its last step.
     */
    fun tearDown(fatal: HeldFatal): TreeTeardownReport {
        val all = synchronized(lock) { trees.toList().also { trees.clear() } }
        val resetFailures = mutableListOf<String>()
        val skippedResets = mutableListOf<String>()
        for (handle in all) {
            fatal.isolated { handle.listener.dispose() }
            if (handle.resetAtTeardown && !handle.root.isDisposed) {
                tearDownReset(handle, resetFailures, skippedResets, fatal)
            }
            fatal.isolated { handle.tree.removeMiddleware(handle.recorder) }
            handle.recorder.dispose()
        }
        return TreeTeardownReport(resetFailures, skippedResets)
    }

    /** The reset step of [tearDown] for one tracked tree; a fatal error is held in [fatal], never reported. */
    private fun tearDownReset(
        handle: TreeHandle,
        resetFailures: MutableList<String>,
        skippedResets: MutableList<String>,
        fatal: HeldFatal,
    ) {
        // Any throw while listing (a child lambda's own exception, a group's
        // `require`) is recorded, so one tree never skips the unwinding of
        // the trees after it.
        val listed = runCatching { heldLeaves(handle.tree) }
        val listing = listed.exceptionOrNull()
        if (listing != null) {
            if (!fatal.holdIfFatal(listing)) {
                resetFailures +=
                    "tree '${handle.tree.node.name}': reset skipped — listing the subtree threw " +
                    "${listing::class.simpleName}: ${listing.message}"
            }
            return
        }
        val held = listed.getOrNull()
        when {
            held == null -> Unit // the receiver was disposed meanwhile: nothing left to reset
            held.isNotEmpty() -> skippedResets += describeSkippedReset(handle, held)
            else -> resetFailureOf(handle, fatal)?.let { resetFailures += it }
        }
    }

    /**
     * The names of the live members of [tree] still held by an entry
     * teardown cannot wait out once [TEARDOWN_HELD_LEAF_BUDGET] has passed;
     * empty as soon as one probe round finds none held; `null` when the
     * receiver was disposed meanwhile (nothing left to reset). Yields
     * between rounds and never blocks on a store. A listing failure on a
     * live receiver (a child lambda throwing) propagates.
     */
    private fun heldLeaves(tree: StoreTree): List<String>? {
        val deadline = TimeSource.Monotonic.markNow() + TEARDOWN_HELD_LEAF_BUDGET
        var held = probeHeld(tree)
        while (!held.isNullOrEmpty() && !deadline.hasPassedNow()) {
            threadYield()
            held = probeHeld(tree)
        }
        return held?.map { leafName(tree, it) }
    }

    /**
     * The live members of [tree] an entry holds right now
     * ([PrivilegedHooks.isHeldByAnEntry]); `null` ONLY when the receiver was
     * disposed meanwhile — any other listing failure propagates.
     */
    private fun probeHeld(tree: StoreTree): List<Store<*>>? =
        try {
            tree.stores().filter { PrivilegedHooks.isHeldByAnEntry(it) }
        } catch (listing: IllegalStateException) {
            if (tree.node.store?.isDisposed != false) null else throw listing
        }

    /** How the report names [store]: its node's name, or its class when the tree no longer lists it. */
    private fun leafName(
        tree: StoreTree,
        store: Store<*>,
    ): String {
        // A fatal error (issue #61) is no missing name: it propagates, held by tearDownReset.
        val name = runCatching { tree.nodeOf(store)?.name }.onFailure(PrivilegedHooks::rethrowIfFatal).getOrNull()
        return name ?: (store::class.simpleName ?: "a leaf")
    }

    /**
     * Reset [handle]'s tree and describe the failure, or `null` when it
     * committed — or failed with a fatal error (issue #61), now held in
     * [fatal] instead of described.
     */
    private fun resetFailureOf(
        handle: TreeHandle,
        fatal: HeldFatal,
    ): String? {
        val outcome = runCatching { handle.tree.reset() }
        val result = outcome.getOrNull()
        val failure = outcome.exceptionOrNull() ?: (result as? TransactionResult.Error)?.exception
        return when {
            failure == null || fatal.holdIfFatal(failure) -> null
            result is TransactionResult.Error -> describeResetFailure(handle, result)
            else -> "tree '${handle.tree.node.name}': reset threw ${failure::class.simpleName}: ${failure.message}"
        }
    }

    private fun describeSkippedReset(
        handle: TreeHandle,
        held: List<String>,
    ): String =
        "tree '${handle.tree.node.name}': reset skipped — ${held.joinToString(", ") { "leaf '$it'" }} still held " +
            "after $TEARDOWN_HELD_LEAF_BUDGET by an entry teardown cannot wait out (a suspendAction/suspendAtomic " +
            "body parked in un-joined work, a thread inside an action, or a holder of the store's serializer), so " +
            "the next test would find this body's values. Join or finish that work before the body ends, or opt " +
            "out with track(tree, resetAtTeardown = false)"

    private fun describeResetFailure(
        handle: TreeHandle,
        result: TransactionResult.Error,
    ): String {
        val frame = result.transaction.frameId
        val vetoes =
            handle.timeline
                .filter { it.phase == TreeEvent.Phase.Errored && it.cause != null }
                .filter { frame == null || it.transaction.frameId == frame }
                .map { "leaf '${it.node.name}' (${it.cause!!::class.simpleName}: ${it.cause.message})" }
                .distinct()
        val exception = result.exception
        val where =
            if (vetoes.isEmpty()) "${exception::class.simpleName}: ${exception.message}" else vetoes.joinToString("; ")
        return "tree '${handle.tree.node.name}': reset failed — $where"
    }
}
