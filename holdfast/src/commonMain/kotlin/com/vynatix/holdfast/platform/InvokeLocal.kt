package com.vynatix.holdfast.platform

/**
 * Thread-local depth of nested bare `Store.invoke` (`store { … }`) blocks on the
 * current thread. Non-zero means the current call stack is inside a bare-invoke
 * context — a mutation that would synthesize its own one-shot transaction there
 * is a bare-invoke mutation and must fail loudly (a bare `store { }` opens no
 * transaction, so piecemeal commits would fire observers between writes).
 *
 * A depth (not a boolean) so nested `store { store2 { … } }` invokes restore
 * correctly on exit. Actions entered inside an invoke are unaffected: a mutate
 * inside an `action { }` has an owned transaction and never reaches the
 * synthesis branch that consults this depth.
 *
 * wasmJs note: single-threaded by assumption (`currentThreadId()` == 0), so a
 * process-global counter IS the thread-local counter.
 */
internal expect fun bareInvokeDepth(): Int

/** Write the thread-local bare-invoke depth. See [bareInvokeDepth]. */
internal expect fun setBareInvokeDepth(value: Int)
