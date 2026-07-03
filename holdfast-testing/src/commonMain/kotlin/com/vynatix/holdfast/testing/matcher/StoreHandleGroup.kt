package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.testing.StoreHandle
import com.vynatix.holdfast.testing.TransactionCommitted

// ============================================================================
// Cross-store frame matchers — correlate one `atomic` / `suspendAtomic` frame
// across several tracked stores' timelines via `Transaction.frameId`.
// ============================================================================

/**
 * A group of tracked handles for cross-store assertions. Build with the
 * infix [and] combinator:
 *
 * ```
 * storeTest {
 *     val a = track(AccountA); val b = track(AccountB)
 *     atomic(AccountA, AccountB) { /* transfer */ }
 *     (a and b) shouldCommitTogether ()
 * }
 * ```
 */
class StoreHandleGroup internal constructor(
    internal val handles: List<StoreHandle<*>>,
) {
    /** Extend the group with another tracked handle. */
    infix fun and(other: StoreHandle<*>): StoreHandleGroup = StoreHandleGroup(handles + other)
}

/** Start a [StoreHandleGroup] from two tracked handles. */
infix fun StoreHandle<*>.and(other: StoreHandle<*>): StoreHandleGroup = StoreHandleGroup(listOf(this, other))

/**
 * Frame ids (see `Transaction.frameId`) of every committed transaction on this
 * handle's timeline, in commit order. Ordinary single-store transactions have
 * no frame id and are excluded.
 */
fun StoreHandle<*>.committedFrameIds(): List<String> =
    timeline
        .filterIsInstance<TransactionCommitted>()
        .mapNotNull { it.transaction.frameId }

/**
 * Assert that every handle in the group committed a transaction belonging to
 * the SAME cross-store frame — i.e. at least one `atomic`/`suspendAtomic`
 * frame enrolled all of these stores and committed. Returns the shared frame
 * id (the most recent one, when several frames spanned the whole group) for
 * follow-up assertions.
 *
 * Failure: [AssertionError] listing each store's committed frame ids so the
 * torn frame is visible at a glance.
 */
fun StoreHandleGroup.shouldCommitTogether(): String {
    check(handles.size >= 2) { "shouldCommitTogether needs at least two tracked handles" }
    val perHandle = handles.map { it to it.committedFrameIds() }
    val shared =
        perHandle
            .map { (_, ids) -> ids.toSet() }
            .reduce { acc, ids -> acc intersect ids }
    if (shared.isEmpty()) {
        val detail =
            perHandle.joinToString("\n") { (handle, ids) ->
                val name = handle.store::class.simpleName ?: "Store"
                "  $name: ${if (ids.isEmpty()) "(no committed frames)" else ids.joinToString()}"
            }
        throw AssertionError(
            "Expected all ${handles.size} stores to commit inside one atomic frame, " +
                "but no frame id is shared by every store's timeline:\n$detail",
        )
    }
    // Most recent shared frame: last occurrence in the first handle's timeline.
    return perHandle.first().second.last { it in shared }
}

/**
 * Negation of [shouldCommitTogether]: assert that NO single frame committed
 * across every store in the group — e.g. after a frame was rolled back, or to
 * prove two flows commit independently.
 */
fun StoreHandleGroup.shouldNotCommitTogether() {
    check(handles.size >= 2) { "shouldNotCommitTogether needs at least two tracked handles" }
    val shared =
        handles
            .map { it.committedFrameIds().toSet() }
            .reduce { acc, ids -> acc intersect ids }
    if (shared.isNotEmpty()) {
        throw AssertionError(
            "Expected no shared committed frame across the group, but found: ${shared.joinToString()}",
        )
    }
}
