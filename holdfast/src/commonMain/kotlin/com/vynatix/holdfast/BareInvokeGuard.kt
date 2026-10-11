package com.vynatix.holdfast

import com.vynatix.holdfast.platform.crtpSelfCastTeachingOrNull
import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The bare-invoke mutation guard (issue 005).
 *
 * `Store.invoke { }` looks like a transaction scope but is a plain
 * pass-through: each bare `mutate`/`update` inside it synthesizes its OWN
 * one-shot action, so a throw mid-block leaves the earlier writes committed —
 * the exact non-atomicity the library exists to prevent, and (for `mutate`)
 * into a discarded `TransactionResult` at that. [Store.invoke] therefore arms
 * this per-thread non-mutating flag around its body; the one-shot-synthesis
 * branches of `mutate`/`update` refuse while it is armed, and BOTH `emit`
 * implementations refuse when it is armed AND no transaction this thread
 * stages into is active (emit has no synthesis branch to hang the check on;
 * see [Store.stagesInto] for the precedence rule), throwing
 * [BARE_INVOKE_MUTATION_MESSAGE] to the caller.
 *
 * The precedence rule is structural (binding, spec A-matrix): the flag is
 * checked ONLY where the one-shot action would be synthesized. An entry that
 * opens or holds a transaction this thread/coroutine owns — `action { }`
 * started inside the invoke, a `suspendAction`/`suspendAtomic` body, an
 * `atomic(...)` frame — stages into it and never reaches the check, so the
 * documented receiver-DSL shapes (`store { x mutate v }` inside a suspending
 * entry, `atomic(a, b) { a { x mutate v } }`) keep working. Conversely the
 * flag is scope-free: armed by ANY store's invoke on this thread, it refuses a
 * bare write to ANY store (`storeA { storeB.bump() }` throws) — a write into
 * another store from inside a wiring block is exactly the partial-commit trap.
 *
 * Storage is a lock-guarded map keyed by [currentThreadId] (core has no
 * ThreadLocal primitive); entries are counted for depth safety and removed in
 * the `finally`, so nested invokes do not disarm each other and a throwing
 * body cannot leak the flag. The lock is a leaf: no user code ever runs under
 * it. wasmJs's constant thread id `0` degenerates to a single slot, correct
 * on a single-threaded host.
 *
 * Thread-hop escape is a stated best-effort boundary, not a guarantee:
 * `store { scope.launch { x mutate 1 } }` is not caught (invoke is not
 * suspend; the body cannot carry the flag across a dispatch). Documented on
 * [Store.invoke].
 */
internal object BareInvokeGuard {
    private val lock = SynchronizedObject()
    private val depths = HashMap<Long, Int>()

    /** Arm the guard for this thread around [block]; disarm in a `finally`. */
    fun <R> armed(block: () -> R): R {
        val thread = currentThreadId()
        synchronized(lock) { depths[thread] = (depths[thread] ?: 0) + 1 }
        try {
            return block()
        } finally {
            synchronized(lock) {
                val next = (depths[thread] ?: 0) - 1
                if (next > 0) depths[thread] = next else depths.remove(thread)
            }
        }
    }

    /**
     * Throw [BARE_INVOKE_MUTATION_MESSAGE] when the guard is armed on this
     * thread. Called only from the one-shot-synthesis branches (see the
     * object KDoc) and from `emit`, which has no synthesis path of its own.
     */
    fun refuseMutation() {
        val armed = synchronized(lock) { (depths[currentThreadId()] ?: 0) > 0 }
        if (armed) error(BARE_INVOKE_MUTATION_MESSAGE)
    }
}

/**
 * The body of `Store.invoke`: the disposed gate, the bare-invoke guard
 * bracket, and the CRTP first-dispatch teaching wrap (issue 006). Lives in
 * this file, not `Store.kt`, only to keep `Store` at detekt's `LargeClass`
 * limit — the behavior is documented on the operator itself. The receiver
 * bridge `this as S` stays inside the `try` so a self-type violation is
 * upgraded to the teaching rethrow exactly as it was (issue 006).
 */
internal fun <S : Store<S>, R> Store<S>.runBareInvoke(block: S.() -> R): R {
    checkNotDisposed()
    return BareInvokeGuard.armed {
        try {
            @Suppress("UNCHECKED_CAST")
            block(this as S)
        } catch (e: ClassCastException) {
            throw crtpSelfCastTeachingOrNull(this, e) ?: e
        }
    }
}

/**
 * The teaching refusal for a bare `mutate`/`update`/`emit` inside a bare
 * `store { }` invoke block. Deliberately does not name a store: the guard is
 * per-thread and scope-free, so the arning store is not necessarily the one
 * being written (issue 005).
 */
internal const val BARE_INVOKE_MUTATION_MESSAGE =
    "Mutating a store inside a bare `store { }` block is refused: each `mutate`/`update`/`emit` there would " +
        "run as its own one-shot action, so a mid-block throw commits the earlier writes — the partial-commit " +
        "trap `action` exists to prevent. Use `store action { }` (or `suspendAction`/`suspendAtomic`/`atomic(...)`) " +
        "for mutations; bare `store { }` is the read-only/wiring scope, where reads, `effect { }`, `bridge` and " +
        "`observeFrom` stay legal. This guard is per-thread and best-effort: a mutation that hops threads " +
        "(e.g. `scope.launch { … }`) is not caught."
