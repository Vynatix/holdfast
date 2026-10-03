package com.vynatix.holdfast.platform

/**
 * Thread-local slot holding the innermost construction log open on the
 * current thread (`com.vynatix.holdfast.ConstructionLog`): the tree's record
 * of which stores a child lambda or keyed factory built ON THIS THREAD while
 * it ran. Typed as `Any?` so the platform actuals stay dependency-free; the
 * single reader/writer casts.
 *
 * wasmJs note: the actual there is a plain global `var` — the platform is
 * single-threaded by assumption (`currentThreadId()` returns `0` for everyone),
 * so a process-global slot IS the thread-local slot. Same pattern as
 * [currentMaterializingLocal].
 */
internal expect fun currentConstructionLocal(): Any?

/** Write the thread-local construction-log slot. See [currentConstructionLocal]. */
internal expect fun setConstructionLocal(value: Any?)
