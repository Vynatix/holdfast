package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentInitializerLocal
import com.vynatix.holdfast.platform.setInitializerLocal

// The no-write region state initializers, schema migrations and derived
// states' recomputes run in, and the teaching messages built from this
// thread's stack of running initializers (issue #20, D3, D12 and R6).

/**
 * One no-write region open on this thread, linked to the region it opened
 * inside ([parent]), if any: an initializer, a store's `migrate`, or a
 * derived state's recompute.
 */
internal sealed class NoWriteFrame(
    val parent: NoWriteFrame?,
) {
    /** The exception refusing [attempt] ("write X.y") while this region is the innermost. */
    abstract fun refusal(attempt: String): IllegalStateException
}

/**
 * One state initializer running on this thread. [reset] is the [ResetPass]
 * re-running it, or `null` when it runs to materialize its state.
 */
internal class InitializingFrame(
    val declaration: StateDeclaration<*>,
    parent: NoWriteFrame?,
    val reset: ResetPass? = null,
) : NoWriteFrame(parent) {
    override fun refusal(attempt: String): IllegalStateException {
        val message = initializerWriteMessage(attempt, declaration)
        return IllegalStateException(message)
    }
}

/**
 * [SchemaVersioned.migrate] of [store] running on this thread, upcasting a
 * snapshot from schema version [from] to [to]. Keeps the refusal it last
 * threw ([refused]): its message names only stores and states, so the
 * restore that runs the migration may attach it to its failure.
 */
internal class MigratingFrame(
    val store: Store<*>,
    val from: Int,
    val to: Int,
    parent: NoWriteFrame?,
) : NoWriteFrame(parent) {
    var refused: IllegalStateException? = null
        private set

    override fun refusal(attempt: String): IllegalStateException =
        IllegalStateException(migrateWriteMessage(attempt, this)).also { refused = it }
}

/**
 * The compute of the derived state [derived] (`derivedState`/`merged`, by its
 * qualified name), running on this thread for a recompute. The recompute
 * commits what the compute returns at once, so the compute reads committed
 * values only — never the pending writes of a transaction this thread holds
 * elsewhere, which may yet roll back, including those of a `suspendAction`
 * parked on this thread — and may not write.
 */
internal class ComputingFrame(
    val derived: String,
    parent: NoWriteFrame?,
) : NoWriteFrame(parent) {
    override fun refusal(attempt: String): IllegalStateException {
        val message = computeWriteMessage(attempt, derived)
        return IllegalStateException(message)
    }
}

/**
 * The thread-local region in which state initializers, schema migrations
 * ([SchemaVersioned.migrate]) and the compute of a derived state's recompute
 * ([ComputingFrame]) run. Code inside it may read states — which
 * materializes them in turn — and sees committed values only:
 * [MutableState.value] skips the pending writes of an action on this thread
 * while a region is open. The one exception is an initializer that `reset()`
 * re-runs ([runForReset]): it reads its store's declared states at their
 * reset values (see [ResetPass]); re-run by a sterile `restore()`, it reads
 * the states that restore re-runs at their reset values and its store's
 * other declared states as the restore's transaction holds them (restored,
 * else an enclosing action's pending writes). A store write from inside it
 * is refused: `mutate`/`update`, `action`, `atomic`, `emit`, `reset()`,
 * `restore`, and `:holdfast-coroutines`' `suspendAction`/`suspendAtomic` all
 * throw inside it, naming the state being initialized, the store migrating or
 * the derived state recomputing.
 */
internal object NoWriteRegion {
    /** The innermost region open on this thread, or `null`. */
    fun current(): NoWriteFrame? = currentInitializerLocal() as NoWriteFrame?

    /** Run [block] as [decl]'s initializer, materializing its state. */
    fun <R> run(
        decl: StateDeclaration<*>,
        block: () -> R,
    ): R = enter(InitializingFrame(decl, current()), block)

    /** Run [block] as [decl]'s initializer, re-run by [pass] to reset its state. */
    fun <R> runForReset(
        decl: StateDeclaration<*>,
        pass: ResetPass,
        block: () -> R,
    ): R = enter(InitializingFrame(decl, current(), pass), block)

    /** Run [block] as the migration [frame] describes. */
    fun <R> runMigration(
        frame: MigratingFrame,
        block: () -> R,
    ): R = enter(frame, block)

    /** Run [block] as the compute of the derived state [derived]'s recompute. */
    fun <R> runCompute(
        derived: String,
        block: () -> R,
    ): R = enter(ComputingFrame(derived, current()), block)

    private fun <R> enter(
        frame: NoWriteFrame,
        block: () -> R,
    ): R {
        setInitializerLocal(frame)
        try {
            return block()
        } finally {
            setInitializerLocal(frame.parent)
        }
    }

    /** Throw if a region is open on this thread; [attempt] names the refused call ("write X.y"). */
    inline fun refuse(attempt: () -> String) {
        val frame = current() ?: return
        throw frame.refusal(attempt())
    }

    /** The initializers running on this thread, outermost first. */
    fun stack(): List<StateDeclaration<*>> {
        val innermostFirst =
            generateSequence(current()) { it.parent }
                .filterIsInstance<InitializingFrame>()
                .map { it.declaration }
                .toList()
        return innermostFirst.asReversed()
    }
}

/**
 * Refuse a store write from inside a state initializer (or a schema
 * migration, or a derived state's recompute): throws when one is running on
 * this thread, naming [attempt] and the state being initialized (or the store
 * migrating, or the derived state recomputing). For
 * `:holdfast-coroutines`' suspending entrypoints, which police what the
 * blocking ones police. It only reads a thread-local, so it works on a
 * disposed store too; the caller does its own disposed check.
 */
@StoreInternalApi
fun Store<*>.internalRefuseInitializerWrite(attempt: String) {
    NoWriteRegion.refuse { "$attempt on $displayName" }
}

internal fun initializerWriteMessage(
    attempt: String,
    initializing: StateDeclaration<*>,
): String =
    "Cannot $attempt: the initializer of ${initializing.qualifiedName} is running on this thread. A state " +
        "initializer runs lazily — at the state's first read, when snapshot() or restore() needs the state, or " +
        "when reset() or a sterile restore() re-runs it — so a write from it would land at an unpredictable " +
        "moment, inside whatever action, commit or snapshot first needed the state. Initializers may read other " +
        "states, but not write them, open an action or an atomic(...) frame, reset a store, or emit events. " +
        "Fix: compute the initial value from what the initializer can read, and make the write in an action " +
        "once the store exists."

internal fun migrateWriteMessage(
    attempt: String,
    migrating: MigratingFrame,
): String =
    "Cannot $attempt: ${migrating.store.displayName}.migrate(from = ${migrating.from}) is running on this " +
        "thread, upcasting a snapshot from schema version ${migrating.from} to ${migrating.to}. migrate runs " +
        "while restore() plans, before the restore's action opens, so a write from it would land outside that " +
        "restore's transaction, and stay when the restore fails. migrate may read states, but not write them, " +
        "open an action or an atomic(...) frame, restore or reset a store, or emit events. Fix: make every " +
        "change to the snapshot through the EncodedSnapshotView migrate is given, and make any other write in " +
        "an action once the restore has returned."

internal fun computeWriteMessage(
    attempt: String,
    derived: String,
): String =
    "Cannot $attempt: the compute of $derived is running on this thread, recomputing it after a commit that " +
        "changed one of its sources. A derived state's compute may read states, but not write them, open an " +
        "action or an atomic(...) frame, restore or reset a store, or emit events: its value is what its " +
        "sources explain, and it commits in a transaction of its own right after the compute returns. Fix: " +
        "make the write in the action that changes the sources, or compute that value as a derived state too."

internal fun sameThreadCycleMessage(decl: StateDeclaration<*>): String {
    val stack = NoWriteRegion.stack()
    val from = stack.indexOf(decl)
    val chain = (if (from >= 0) stack.subList(from, stack.size) else listOf(decl)) + decl
    return "State initializer cycle: ${chain.render()}. Each initializer reads the next state before its own " +
        "value exists, so no order of evaluation can finish. Break the cycle: give one of these states an " +
        "initial value that does not read the others, and compute the rest from it (computed { } or derived(...))."
}

internal fun crossThreadCycleMessage(cycle: List<StateDeclaration<*>>): String {
    val mine = cycle.last()
    val stack = NoWriteRegion.stack()
    val from = stack.indexOf(mine)
    val here = if (from >= 0) stack.subList(from, stack.size) else listOf(mine)
    val chain = here + cycle
    return "State initializer cycle across threads: ${chain.render()}. This thread is initializing " +
        "${here.last().qualifiedName} and needs ${cycle.first().qualifiedName}, which another thread is " +
        "initializing and which needs ${mine.qualifiedName} — waiting would deadlock both threads. Break the " +
        "cycle: give one of these states an initial value that does not read the others, and compute the rest " +
        "from it (computed { } or derived(...))."
}

private fun List<StateDeclaration<*>>.render(): String = joinToString(" → ") { it.qualifiedName }
