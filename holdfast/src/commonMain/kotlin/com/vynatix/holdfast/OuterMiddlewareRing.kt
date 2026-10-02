package com.vynatix.holdfast

// Attachment-owned outer middleware ring (issue #21 plan, PR 21-1, decision
// U12): a store's consumer-registered middleware (`Store.middlewares`/
// `clearMiddleware`) is untouched; issue #21's tree attachment installs a
// SEPARATE ring here, always outermost, that `clearMiddleware()` cannot
// reach and `dispose()` tears down alongside the consumer list.

/**
 * One store's outer middleware ring: library-installed [Middleware] that
 * wraps every consumer-registered one (issue #21's `Root.middlewares`). Its
 * own [StoreLock] keeps [set]/[remove]/[clear] internally consistent without
 * taking the store's `middlewareLock`. [Store.snapshotMiddleware] and the
 * private `Store.runMiddlewareChain` read [snapshot] and append it after the
 * consumer list, so the ring is always outermost — last is outermost, per
 * [Middleware]'s ordering KDoc.
 */
internal class OuterMiddlewareRing<Self : Store<Self>> {
    private val lock = StoreLock()

    /** Replaced as a whole under [lock]; read without it — always a complete list, last writer wins. */
    @kotlin.concurrent.Volatile
    private var list: List<Middleware<Self>> = emptyList()

    /** The ring's current members, in installer order (last is outermost). */
    fun snapshot(): List<Middleware<Self>> = list

    /**
     * Whole-set replace. Converges under interleaving installs — the last
     * [set] to run wins — rather than requiring identity-tracked add/remove.
     */
    fun set(middleware: List<Middleware<Self>>) {
        lock.withLock { list = middleware.toList() }
    }

    /** Identity removal. Returns `true` iff [middleware] was in the ring. */
    fun remove(middleware: Middleware<Self>): Boolean =
        lock.withLock {
            val next = list.filterNot { it === middleware }
            val removed = next.size != list.size
            if (removed) list = next
            removed
        }

    /** Drop every ring member. Called once, by [Store.dispose]. */
    fun clear() {
        lock.withLock { list = emptyList() }
    }
}

/**
 * Replace this store's outer middleware ring with [middleware] (issue #21's
 * `Root.middlewares`; always outermost of the consumer-registered chain —
 * see [Middleware]'s ordering KDoc and [Store.middlewares]). A whole-set
 * replace: install the full desired set each time to converge under
 * interleaving installs, rather than adding members one at a time.
 *
 * @throws IllegalStateException if the store is disposed.
 */
@StoreInternalApi
fun <Self : Store<Self>> Self.internalSetOuterMiddleware(middleware: List<Middleware<Self>>) {
    checkNotDisposed()
    outerMiddleware.set(middleware)
}

/**
 * Remove [middleware] from this store by identity — from the consumer list
 * (`middlewares(...)`) or the outer ring, wherever it sits — leaving every
 * other middleware in place: the single-entry uninstall `clearMiddleware()`
 * is not (`:holdfast-testing`'s recorder leaves a store's own middleware
 * installed at teardown through it). Returns `false` on a disposed store,
 * without throwing — `dispose()` already cleared both lists, so there is
 * nothing to remove, and a caller unwinding a store that disposed
 * mid-teardown (issue #21's `Root.removeMiddleware`, the harness) must be
 * able to tell "already gone" apart from "removed" without catching an
 * exception. Not gated by `checkNotDisposed()`, unlike most entrypoints —
 * see the CLAUDE.md exception list next to [Store.snapshotMiddleware].
 */
@StoreInternalApi
fun <Self : Store<Self>> Self.internalRemoveMiddleware(middleware: Middleware<Self>): Boolean {
    if (isDisposed) return false
    val fromList = middlewareLock.withLock { middlewareList.removeAll { it === middleware } }
    val fromRing = outerMiddleware.remove(middleware)
    return fromList || fromRing
}

/**
 * [internalSetOuterMiddleware] for a star-projected store — issue #21's
 * tree ring holds its leaves as `Store<*>`. The ring is typed by the store's
 * own `Self`; the members passed are adapters written against no store type
 * at all (`Middleware<Nothing>`), which every chain accepts.
 *
 * @throws IllegalStateException if the store is disposed.
 */
internal fun Store<*>.setOuterMiddlewareUnchecked(middleware: List<Middleware<Nothing>>) {
    checkNotDisposed()
    @Suppress("UNCHECKED_CAST")
    (outerMiddleware as OuterMiddlewareRing<Nothing>).set(middleware)
}

/**
 * [internalRemoveMiddleware]'s outer-ring half for a star-projected store:
 * issue #21's tree ring unwinds its own adapters by identity when its root
 * disposes, so a member another root's ring put there meanwhile is left in
 * place. `false` on a disposed store (its ring is already cleared), without
 * throwing, as [internalRemoveMiddleware].
 */
internal fun Store<*>.removeOuterMiddlewareUnchecked(middleware: Middleware<Nothing>): Boolean {
    if (isDisposed) return false
    @Suppress("UNCHECKED_CAST")
    return (outerMiddleware as OuterMiddlewareRing<Nothing>).remove(middleware)
}
