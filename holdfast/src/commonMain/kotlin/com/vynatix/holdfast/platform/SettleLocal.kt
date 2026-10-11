package com.vynatix.holdfast.platform

/**
 * Thread-local slot holding the settle scope of the current thread (see
 * `com.vynatix.holdfast.SettleScopes`): the scope the outermost action, frame,
 * `suspendAction` or `suspendAtomic` running here opened, whose derived-state
 * recomputes run once that entry has exited. Typed as `Any?` so the platform
 * actuals stay dependency-free; the single reader/writer casts.
 *
 * wasmJs note: the actual there is a plain global `var` — the platform is
 * single-threaded by assumption (`currentThreadId()` returns `0` for everyone),
 * so a process-global slot IS the thread-local slot. `:holdfast-coroutines`
 * keeps it scoped to one coroutine's resumptions, as it does the frame-marker
 * slot ([currentFrameLocal]).
 */
internal expect fun currentSettleLocal(): Any?

/** Write the thread-local settle-scope slot. See [currentSettleLocal]. */
internal expect fun setSettleLocal(value: Any?)

/**
 * Second thread-local slot: the committed cut the compute of a derived state
 * running on this thread reads its sources from (see
 * `com.vynatix.holdfast.ComputeReads`). Same wasmJs note as
 * [currentSettleLocal]; a compute never suspends, so no coroutine machinery
 * is needed for it.
 */
internal expect fun currentComputeReadsLocal(): Any?

/** Write the thread-local compute-reads slot. See [currentComputeReadsLocal]. */
internal expect fun setComputeReadsLocal(value: Any?)
