package com.vynatix.holdfast.platform

/**
 * Thread-local slot holding the stack of latches the current thread holds
 * while materializing (see `com.vynatix.holdfast.InitializerGraph.hold`):
 * every state declaration whose initializer, and every tree child
 * declaration whose lambda, runs on this thread right now, innermost on
 * top. The cycle messages render from it, so a cycle through state
 * initializers and child declarations prints in order. Typed as `Any?` so
 * the platform actuals stay dependency-free; the single reader/writer casts.
 *
 * wasmJs note: the actual there is a plain global `var` — the platform is
 * single-threaded by assumption (`currentThreadId()` returns `0` for everyone),
 * so a process-global slot IS the thread-local slot. Same pattern as
 * [currentFrameLocal].
 */
internal expect fun currentMaterializingLocal(): Any?

/** Write the thread-local materializing slot. See [currentMaterializingLocal]. */
internal expect fun setMaterializingLocal(value: Any?)
