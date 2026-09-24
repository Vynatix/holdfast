package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentInitializerLocal
import com.vynatix.holdfast.platform.setInitializerLocal

// The no-write region state initializers run in, and the teaching messages
// built from this thread's stack of running initializers (issue #20, D3).

/**
 * One state initializer running on this thread, linked to the initializer
 * whose read started it ([parent]), if any.
 */
internal class InitializingFrame(
    val declaration: StateDeclaration<*>,
    val parent: InitializingFrame?,
)

/**
 * The thread-local region in which state initializers run. Initializers may
 * read states — which materializes them in turn — and see committed values
 * only: [MutableState.value] skips the pending writes of an action on this
 * thread while a region is open. A store write from one is refused:
 * `mutate`/`update`, `action`, `atomic`, `emit`, and `:holdfast-coroutines`'
 * `suspendAction`/`suspendAtomic` all throw inside it, naming the state being
 * initialized.
 */
internal object NoWriteRegion {
    /** The innermost initializer running on this thread, or `null`. */
    fun current(): InitializingFrame? = currentInitializerLocal() as InitializingFrame?

    /** Run [block] as [decl]'s initializer. */
    fun <R> run(
        decl: StateDeclaration<*>,
        block: () -> R,
    ): R {
        val prior = current()
        setInitializerLocal(InitializingFrame(decl, prior))
        try {
            return block()
        } finally {
            setInitializerLocal(prior)
        }
    }

    /** Throw if an initializer is running on this thread; [attempt] names the refused call ("write X.y"). */
    inline fun refuse(attempt: () -> String) {
        val frame = current() ?: return
        throw IllegalStateException(initializerWriteMessage(attempt(), frame.declaration))
    }

    /** The initializers running on this thread, outermost first. */
    fun stack(): List<StateDeclaration<*>> {
        val innermostFirst = generateSequence(current()) { it.parent }.map { it.declaration }.toList()
        return innermostFirst.asReversed()
    }
}

/**
 * Refuse a store write from inside a state initializer: throws when a state
 * initializer is running on this thread, naming [attempt] and the state being
 * initialized. For `:holdfast-coroutines`' suspending entrypoints, which
 * police what the blocking ones police. It only reads a thread-local, so it
 * works on a disposed store too; the caller does its own disposed check.
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
        "initializer runs lazily — at the state's first read, or when snapshot() or restore() needs the state — " +
        "so a write from it would land at an unpredictable moment, inside whatever action, commit or snapshot " +
        "first needed the state. Initializers may read other states, but not write them, open an action or an " +
        "atomic(...) frame, or emit events. Fix: compute the initial value from what the initializer can read, " +
        "and make the write in an action once the store exists."

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
