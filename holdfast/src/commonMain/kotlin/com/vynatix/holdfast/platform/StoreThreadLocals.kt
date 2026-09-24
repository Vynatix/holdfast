package com.vynatix.holdfast.platform

/**
 * Thread-local slot naming the state initializers running on the current
 * thread (see `com.vynatix.holdfast.NoWriteRegion`): the innermost one, which
 * links to the initializer whose read started it. Typed as `Any?` so the
 * platform actuals stay dependency-free; the single reader/writer casts.
 *
 * wasmJs note: the actual there is a plain global `var` — the platform is
 * single-threaded by assumption (`currentThreadId()` returns `0` for everyone),
 * so a process-global slot IS the thread-local slot. Same pattern as
 * [currentFrameLocal].
 */
internal expect fun currentInitializerLocal(): Any?

/** Write the thread-local initializer slot. See [currentInitializerLocal]. */
internal expect fun setInitializerLocal(value: Any?)
