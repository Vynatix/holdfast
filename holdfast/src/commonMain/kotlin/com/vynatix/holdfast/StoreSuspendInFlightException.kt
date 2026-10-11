package com.vynatix.holdfast

/**
 * Thrown by a blocking entry — `action { }`, `atomic(…)`, `reset()`,
 * `restore()` — when it has been waiting for a suspending operation
 * (`suspendAction`/`suspendAtomic`, `:holdfast-coroutines`) that holds the
 * store through its serializer mutex and has stopped making observable
 * progress for the whole wait budget (2 s by default).
 *
 * The wait is progress-aware: every observable step the in-flight suspending
 * operation takes — entering, staging a write, starting its commit — restarts
 * the budget clock, so a busy operation never trips the guard no matter how
 * long it runs. Only a holder that goes quiet for the full budget (parked on
 * something that never completes, or stuck behind a thread this very wait is
 * starving) fails its waiters, and it fails them with this exception instead
 * of freezing the calling thread forever — which is what used to happen.
 *
 * It escapes the blocking entrypoint **raw** — `action { }` rethrows it rather
 * than folding it into a `TransactionResult.Error` — because it is thrown by
 * the serializer acquire, before any transaction exists. A wait behind
 * another *blocking* holder is never budgeted (blocking-vs-blocking waits
 * stay unbounded, as for serializer-free stores), and on single-threaded
 * platforms (wasmJs) any wait beside a parked suspending holder is refused
 * outright (issue #27), as is a blocking entry from inside the suspending
 * body itself (issue #31) — different exceptions, thrown before this one's
 * budget could even start.
 *
 * The message names the store, the suspending operation kind holding it, the
 * elapsed wait and the configured budget, and the way out: from a coroutine,
 * write through `suspendAction { … }` (or `suspendAtomic(…)` across stores),
 * which waits by suspending; from a blocking context, retry once the
 * suspending operation has finished.
 *
 * Tests beware of kotlinx virtual time: the budget runs on the real monotonic
 * clock, so under `runTest` an operation parked on virtual time (waiting for
 * `advanceTimeBy`/`runCurrent`) burns real budget while making no observable
 * progress — a cross-thread blocking waiter in the same test can throw even
 * though the parked operation would have resumed on the next virtual tick.
 *
 * @constructor Only serializer implementations construct this (opt-in
 *   `@StoreInternalApi`); user code catches it.
 */
@StoreInternalApi
class StoreSuspendInFlightException
    @StoreInternalApi
    constructor(
        message: String,
    ) : IllegalStateException(message)
