package com.vynatix.holdfast

// reset(): put every declared state back to what its retained initializer
// computes, as one transaction (issue #20, R4).
//
// The initializers are re-run, not replayed: each runs again now, in the order
// a fresh store's `snapshot()` would first run them, and its output is staged
// RAW — exactly as materialization stores an initial value, without
// `Transformer.set` — and only where it differs from what the transaction
// currently holds for the state. A state whose initializer reads another
// declared state of the store sees that state's RESET value, as a fresh store's
// initializer sees the other state's initial value. See [ResetPass].

/**
 * Put every declared state of this store back to its initial value, as one
 * transaction: each state's initializer (the `{ … }` of `state { … }`) runs
 * again and its result is staged back into the state. On success every
 * declared state holds the raw value a freshly constructed store's state holds
 * once read (the value `snapshot()` captures for it; [StoreSnapshot] itself
 * has no value equality) — except a state whose initializer reads a `derived`
 * state computed from states this reset changes (see "Fresh-store order") —
 * and on failure no state's value changes (states it materialized first stay
 * materialized; see below).
 *
 * - **Raw, like an initial value.** The initializer's result is staged the way
 *   a state's initial value is stored: raw, without `Transformer.set`. An
 *   asymmetric transformer (an `EncryptingTransformer`) is therefore not
 *   applied twice, and the raw value after a reset equals a fresh store's.
 * - **Only what changed.** A state is staged only when its initializer's
 *   result differs (`==`) from the value the transaction currently holds for
 *   it, so observers and bridges fire once for each state the reset changes
 *   and never for one it leaves as it was — even when `distinct` is `false`.
 *   Bridges receive the reset values through their `publish`, as with
 *   [restore]; detach them first if that should not echo.
 * - **Fresh-store order.** The initializers run in declaration order, and an
 *   initializer that reads another declared state of this store — declared
 *   before or after it — reads that state's reset value, running that state's
 *   initializer first if it has not run yet in this reset: the order and the
 *   values a fresh store's first reads would produce. Everything else an
 *   initializer reads — another store's states, this store's `derived` states
 *   — it reads at its committed value, never the pending writes of an
 *   enclosing action or frame (see [Store.state]). A `derived` state
 *   recomputes only once the reset commits, so an initializer reading one
 *   computed from states this reset changes reads its pre-reset value, and
 *   its state can differ from a fresh store's: read the derived's sources, or
 *   a `computed` state, which sees the reset values. An initializer that
 *   needs its own state, directly or through others, is a cycle and fails
 *   the reset.
 * - **Every declared state.** A state nobody has read yet, or one dropped with
 *   [Store.removeState] or [Store.clearStates], is materialized first — its
 *   initializer runs as its first read would run it, before the reset's
 *   transaction opens — and then reset like the others, so its initializer
 *   runs twice. Once the reset has decided a state's value — staged it, or
 *   left it because it already held its reset value — [Store.removeState]
 *   and [Store.clearStates] refuse that state until the reset's transaction
 *   (or the action or frame it joined) commits or rolls back. `derived`
 *   states are not reset: they recompute from their sources once the reset
 *   has committed.
 * - **One transaction.** Middleware sees one transaction (id `Reset`). Called
 *   inside another action on this store, the reset is a savepoint: it overrides
 *   that action's pending writes to this store's declared states, and commits
 *   or rolls back with it. Inside an [atomic] frame that enrolls this store it
 *   joins the frame the same way.
 *
 * A throwing initializer — while materializing a never-read state, or re-run
 * by the reset — or an initializer cycle rolls the whole reset back and makes
 * it return [TransactionResult.Error] carrying that exception. No state's value
 * changes, but a state the reset materialized before its transaction opened (a
 * never-read one, or one dropped with [Store.removeState]/[Store.clearStates])
 * stays materialized, at the value its initializer computed then, as after
 * [snapshot]: after a failed reset, a state dropped with `removeState` is
 * live again.
 *
 * The re-run initializers run inside the reset's action, under this store's
 * action locks, so other actions on this store wait for the reset.
 *
 * Experimental (issue #20): the semantics above may still change.
 *
 * @throws IllegalStateException like [Store.action]: if the store is disposed,
 *   or when called from inside a state initializer. `reset()` is a blocking
 *   action: inside a `:holdfast-coroutines` `suspendAtomic` body that enrolls
 *   this store it throws `FrameInteropException`, and inside an [atomic] body
 *   that does not enroll it, `UnenrolledStoreException` (unless the frame's
 *   policy allows unenrolled writes).
 */
@ExperimentalStoreApi
fun <V : Store<V>> V.reset(): TransactionResult<Unit> {
    checkNotDisposed()
    NoWriteRegion.refuse { "reset $displayName" }
    // Materialize every declared state BEFORE the transaction opens, the way
    // its first read would: taking no lock of this store, so a never-read
    // state's initializer does not run under the reset's own locks. A failure
    // is carried into the action, which reports it like one inside the reset.
    val materializeFailure = runCatching { materializeDeclaredStates() }.exceptionOrNull()
    return action(Reset(materializeFailure))
}

/**
 * The body of [reset]'s action. A class rather than a lambda so the
 * transaction's id — the body's simple name — reads `Reset`.
 */
private class Reset<V : Store<V>>(
    private val materializeFailure: Throwable?,
) : (V) -> Unit {
    override fun invoke(store: V) {
        materializeFailure?.let { throw it }
        val txn = checkNotNull(store.activeTransaction) { "reset() stages into the action it runs in" }
        store.stageResetOfDeclaredStates(txn)
    }
}

/**
 * Stage the reset of every declared state of this store into [txn] (see
 * [reset] for what a reset stages), then run the reset's extension points.
 * [txn] must be this store's active transaction — a transaction [reset]'s
 * action opened, or an `atomic` frame's root for this store, so that one frame
 * can reset several stores with one transaction per store (the shape of
 * issue #21's `reset(node)`).
 *
 * The caller materializes the declared states first, outside [txn]'s locks
 * where it can ([materializeDeclaredStates]). A state a concurrent
 * `removeState`/`clearStates` drops before its reset is resolved is
 * materialized again when its reset is staged; once resolved — staged, or
 * left as it was — it is held until [txn]'s root applies or ends, and
 * `removeState`/`clearStates` refuse it (see [Transaction.resetHeld]).
 *
 * Throws what a re-run initializer throws, and [IllegalStateException] for an
 * initializer cycle or when [txn] is closed to writes; what was staged by then
 * stays in [txn], and the caller rolls it back.
 */
internal fun Store<*>.stageResetOfDeclaredStates(txn: Transaction) {
    check(activeTransaction === txn) { "a reset stages into its store's active transaction" }
    check(!txn.closedToWrites) { appliedTransactionMessage("reset $displayName", displayName, txn) }
    val pass = ResetPass(this, txn)
    txn.pendingReset = pass
    try {
        pass.stageAll()
    } finally {
        txn.pendingReset = null
    }
    pass.notifyAttachments()
}

/**
 * One reset of [store], staging into [txn]: the declared states whose reset
 * is not staged yet ([pending]), the ones whose initializer is [running] right
 * now, and the reset value of every state [resolved] so far.
 *
 * A state is resolved by running its initializer in the [NoWriteRegion] —
 * marked as this pass's ([NoWriteRegion.runForReset]) — then staging the
 * result raw if it differs from what [txn] holds for the state. While that
 * initializer runs, [MutableState.value] asks [valueFor] first: a read of a
 * state this pass resets returns its reset value, resolving it on the spot if
 * it is still pending. That is how an initializer sees the reset value of a
 * state declared after it (a forward reference), and how the whole pass runs
 * in the order a fresh store's first reads would.
 *
 * Confined to the thread running the reset: [valueFor] touches the sets only
 * once it has checked that its caller is an initializer this very pass is
 * running, which no other thread can be.
 */
internal class ResetPass(
    private val store: Store<*>,
    private val txn: Transaction,
) {
    private val pending = LinkedHashSet<StateDeclaration<*>>()
    private val running = HashSet<StateDeclaration<*>>()
    private val resolved = HashMap<StateDeclaration<*>, Any>()

    /** Resolve every [StateKind.Declared] state of [store], in declaration order. */
    fun stageAll() {
        // Derived backings and internal states have no initializer of their
        // own to reset from (D6): a derived recomputes once its sources' reset
        // commits.
        store.declarations().filterTo(pending) { it.kind == StateKind.Declared }
        addLiveKeyedEntries()
        while (pending.isNotEmpty()) resolve(pending.first())
    }

    /**
     * Extension point for keyed state families (issue #20, R7; plan PR 12):
     * add the declaration of every live entry to [pending], so each entry is
     * reset by this same pass — from its family's retained initializer, reading
     * reset values like any declared state — and none is evicted.
     */
    private fun addLiveKeyedEntries() {
        // No keyed state families exist yet.
    }

    /**
     * Extension point for store attachments (plan PR 10): tell each attachment
     * that its store was reset, inside the reset's transaction and after every
     * state's reset is staged, so what it stages commits or rolls back with
     * the reset.
     */
    fun notifyAttachments() {
        // No store attachments exist yet.
    }

    /**
     * [state]'s reset value when this read comes from an initializer this pass
     * is running and [state] is one of the states it resets; `null` otherwise,
     * and the read goes on as usual (inside an initializer: committed values).
     *
     * @throws IllegalStateException when [state]'s own initializer is running
     *   in this pass: an initializer cycle.
     */
    fun <T : Any> valueFor(state: MutableState<T>): T? {
        val decl = state.declaration?.takeIf { NoWriteRegion.current()?.reset === this } ?: return null

        @Suppress("UNCHECKED_CAST")
        val known = resolved[decl] as T?
        return when {
            known != null -> known
            decl in running -> throw IllegalStateException(sameThreadCycleMessage(decl))
            decl in pending -> resolve(decl)
            else -> null
        }
    }

    /**
     * Run [decl]'s initializer for this reset and stage its result. [decl]
     * leaves [pending] only once its reset is staged: if the initializer (or a
     * cycle through it) throws, [decl] stays pending in its declaration-order
     * place, so an enclosing initializer that catches the exception cannot
     * drop it from the pass — its next read, or [stageAll], runs it again, as
     * a fresh store runs a failed initializer again on its next read.
     */
    private fun <T : Any> resolve(decl: StateDeclaration<T>): T {
        running += decl
        val initial =
            try {
                NoWriteRegion.runForReset(decl, this, decl.initializer)
            } finally {
                running -= decl
            }
        stageIfChanged(decl, initial)
        pending -= decl
        resolved[decl] = initial
        return initial
    }

    /**
     * Stage [initial] raw as [decl]'s state's pending write, unless the value
     * [txn] holds for it — an enclosing transaction's pending write, else the
     * committed one — is already equal. Either way the state is [hold]: its
     * reset is decided.
     */
    private fun <T : Any> stageIfChanged(
        decl: StateDeclaration<T>,
        initial: T,
    ) {
        val state = hold(decl)
        val current = txn.findPendingValue(state) ?: state.rawCurrentValue
        if (current == initial) return
        check(txn.stagePendingWrite(state, initial)) {
            appliedTransactionMessage("reset ${decl.qualifiedName}", store.displayName, txn)
        }
    }

    /**
     * [decl]'s live state — materialized again if a `removeState` dropped it —
     * pinned in the [Transaction.resetHeld] of [txn]'s root. The pin is taken
     * under the registry lock that `removeState`/`clearStates` hold while they
     * check it, so a removal either comes first, and this materializes the
     * state again, or refuses the state until the reset's transaction chain
     * applies or ends. Without the pin a state left unstaged because it already
     * held its reset value could be dropped and re-created from pre-reset
     * values before the reset commits.
     */
    private fun <T : Any> hold(decl: StateDeclaration<T>): MutableState<T> {
        val root = txn.root
        while (true) {
            val state = decl.materialized ?: materialize(decl)
            val held =
                store.registry.lock.withLock {
                    (decl.materialized === state).also { if (it) root.resetHeld += state }
                }
            if (held) return state
        }
    }
}
