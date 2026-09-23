package com.vynatix.holdfast.debug

import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult

/**
 * Apply every `(state, value)` pair as ONE transaction on [store] — through the
 * real `mutate` path, so `Transformer.set`, every middleware (including
 * validation) and every observer/bridge run exactly as for an app write, and a
 * rejected write rolls the whole batch back.
 *
 * The cast is sound only because [LiteralParser] produced `value` from the
 * state's current value's runtime class; never call this with a value of any
 * other type.
 */
internal fun applyWrites(
    store: Store<*>,
    writes: List<Pair<State<*>, Any>>,
): TransactionResult<Unit> = store.action(ConsoleWrite(writes))

/**
 * The action body, as a named class rather than a lambda: core names a
 * transaction after its body's class, so journal entries and `set` output read
 * `txn ConsoleWrite` instead of a synthetic lambda name.
 */
private class ConsoleWrite(
    private val writes: List<Pair<State<*>, Any>>,
) : (Store<*>) -> Unit {
    override fun invoke(store: Store<*>) {
        with(store) {
            for ((state, value) in writes) {
                @Suppress("UNCHECKED_CAST")
                (state as State<Any>) mutate value
            }
        }
    }
}
