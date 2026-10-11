@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

// The serializer's install window.
//
// A store's serializer ([Store.asyncSerializer]) is installed lazily, by the
// first `:holdfast-coroutines` suspending entry on it: `suspendAction`,
// `suspendAtomic` or a hydration decision. A blocking top-level holder —
// `action`, `atomic`, [Store.tryTopLevelAction] (and `reset()`/`restore()`,
// which run through `action`) — reads it before taking the store, and one that
// reads it as not installed yet takes `transactionLock` alone: a LOCK-ONLY
// holder. A suspending holder installs its transaction holding the serializer
// only: it may resume on any thread, and the lock is owned by a thread.
//
// Left alone, the two overlapped. A suspending entry that installed the
// serializer while a lock-only holder ran took the serializer at once and
// installed its transaction over the lock-only one; the lock-only body's
// writes staged into it (a suspending owner makes every thread stage into its
// transaction), and the lock-only holder's exit then put back its parent —
// no transaction — over it. The suspending body's next `mutate` found no
// transaction, opened a one-shot action, and spun forever on the serializer
// its own coroutine held.
//
// Two rules close the window, one per side:
//  1. RE-CHECK. A blocking top-level holder that read no serializer reads it
//     again once it holds `transactionLock`, before it touches the store. If
//     one was installed meanwhile, it releases the lock and takes the store
//     again, serializer first ([holdSerialized]); the never-blocking
//     [Store.tryTopLevelAction] answers busy instead, like a taken serializer.
//  2. WAIT OUT. A holder of the serializer that installs a transaction without
//     `transactionLock` first waits until the lock is free
//     ([internalTransactionLockFree]; `:holdfast-coroutines` polls it,
//     suspending between tries, after taking the serializer and before
//     installing anything).
//
// Why that is enough. Let W be the install, a volatile write, and S a
// suspending holder that took the serializer (so after W) and then found the
// lock free at instant P, after W. Volatile reads, writes and lock operations
// are totally ordered:
//  - A lock-only holder L whose re-check read no serializer read it while
//    holding the lock, before W. The lock was free at P, after W, so L had
//    released it — restored the store's transaction slot and finished — before
//    P, and S installs its transaction after P.
//  - A lock-only holder that takes the lock after P re-reads the serializer
//    after W, so it finds it and waits for it — S holds it — instead of
//    touching the store.
//  - A blocking holder that read the serializer as installed holds it before
//    it takes the lock, so never while S does.
// So S never shares the store with a lock-only holder, and neither the
// re-check nor the wait-out needs to know whether the other side has run.
//
// Why it cannot deadlock or strand work:
//  - S waits without holding a thread (it suspends between probes), so a
//    lock-only holder that needs the dispatcher S runs on still gets it. A
//    coroutine running inline inside a lock-only holder's body on the same
//    thread does not count that holder as gone
//    ([StoreLock.tryAcquireIfUnheld]), and its suspension lets the holder go on.
//  - Every wait this adds is one the store has anyway once its serializer is
//    installed, just turned around: L would have held the serializer for its
//    whole transaction and S would have waited for it; now S holds it and
//    waits for the lock L holds over the same span. A holder that backs off
//    after the re-check releases the lock and then waits for the serializer,
//    as it would have had it read it installed: serializer before lock, and
//    across a frame's participants the same sorted order, so no new cycle.
//    The one wait that never ends is one that already never ends with the
//    serializer installed: S started through `runBlocking` on the very thread
//    that holds the lock (inside that holder's body or commit) blocks the
//    holder it waits for, just as it would wait forever for the serializer
//    that holder would then hold.
//  - The post-commit hand-off (Store.tryTopLevelAction) still holds. S holds
//    the serializer while its probe holds the lock for an instant, and drains
//    after releasing the serializer, so a drain skipped because the probe held
//    the lock is S's to run. A holder that backs off after the re-check drains
//    after it has run its transaction, and a backed-off
//    [Store.tryTopLevelAction] drains unless the store is held again.
//
// Not covered: `:holdfast-testing`'s open transactions, which install a
// transaction under the lock but then hold it across their open period with
// neither lock nor serializer, by design (a peer `action` nests into them). A
// suspending entry does wait for their commit and rollback, which hold the lock.

/**
 * Run [block] as a blocking top-level holder of this store: its serializer,
 * if one is installed, then `transactionLock`, re-reading the serializer
 * under the lock. A serializer installed between the two reads may belong to
 * a suspending holder, which installs its transaction without the lock: then
 * this call releases the lock and starts over, serializer first, so it never
 * runs [block] beside that holder (see the top of this file). Runs [block]
 * once, and returns what it returns.
 *
 * For `action`'s and `atomic`'s top-level holds; a caller nested in a
 * transaction this thread already owns takes the lock alone.
 */
internal inline fun <T : Any> Store<*>.holdSerialized(block: () -> T): T {
    while (true) {
        val serializer = asyncSerializer
        serializer?.blockingAcquire()
        try {
            val result =
                transactionLock.withLock {
                    // Read as not installed, installed since: start over, serializer first.
                    if (serializer == null && asyncSerializer != null) null else block()
                }
            if (result != null) return result
        } finally {
            serializer?.blockingRelease()
        }
    }
}

/**
 * Whether no thread holds this store's `transactionLock` — this one included
 * — right now: takes it and releases it again at once when it is free, and
 * never waits. For a holder of the store's [Store.AsyncSerializer] that
 * installs a transaction without the lock, as `:holdfast-coroutines`'
 * suspending entries do: once it holds the serializer, it must wait until
 * this returns `true` before it installs anything, which waits out a
 * blocking holder that read no serializer and holds the lock alone (see the
 * top of this file). It must poll without holding a thread, and
 * drain the store's post-commit queue once it has released the serializer,
 * as every holder does: a drain another thread skipped because this probe
 * held the lock for an instant is its to run.
 *
 * Works after dispose and never throws: `dispose()` holds the lock only
 * while it empties the store, so on a disposed store this answers `true`
 * once no caller holds the lock any more (an action of this store that
 * calls `dispose()` still does), and a suspending entry that polls it while
 * racing `dispose()` stops waiting there.
 */
@StoreInternalApi
fun Store<*>.internalTransactionLockFree(): Boolean {
    if (!transactionLock.tryAcquireIfUnheld()) return false
    transactionLock.release()
    return true
}
