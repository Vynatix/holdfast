package com.vynatix.holdfast

/**
 * Outcome of [Store.tryTopLevelAction], which never blocks: it either ran the
 * body as a top-level action, or reports why the store could not take one
 * right now.
 */
internal sealed interface TopLevelAttempt {
    /** The body ran as a top-level action; [result] is what `action` would have returned. */
    class Ran(
        val result: TransactionResult<Unit>,
    ) : TopLevelAttempt

    /**
     * The store has an active [transaction] — this thread's own (a top-level
     * action cannot nest inside it) or another holder's. Whoever holds it
     * drains the post-commit queue once it ends.
     */
    class Busy(
        val transaction: Transaction,
    ) : TopLevelAttempt

    /**
     * The store's serializer or transaction lock is held but no transaction
     * is installed yet (or any longer), e.g. a coroutine that was just handed
     * the serializer and has not resumed. The holder still drains after it
     * releases — including another [Store.tryTopLevelAction] that took the
     * serializer or lock and then backed out busy, which drains unless the
     * store has a holder again by then.
     */
    data object BusyNoTxn : TopLevelAttempt

    /** The store is disposed; nothing ran and nothing will. */
    data object Disposed : TopLevelAttempt
}
