package com.vynatix.holdfast.platform

/**
 * Thread-local slot backing the active atomic-frame marker (see
 * `com.vynatix.holdfast.FrameMarkers`). Typed as `Any?` so the platform actuals
 * stay dependency-free; the single reader/writer casts to the marker type.
 *
 * wasmJs note: the actual there is a plain global `var` — the platform is
 * single-threaded by assumption (`currentThreadId()` returns `0` for everyone),
 * so a process-global slot IS the thread-local slot.
 */
internal expect fun currentFrameLocal(): Any?

/** Write the thread-local frame-marker slot. See [currentFrameLocal]. */
internal expect fun setFrameLocal(value: Any?)

/**
 * Second thread-local slot, backing the commit-fanout marker (see
 * `com.vynatix.holdfast.FanoutMarkers`): the roots whose suspending commit the
 * current thread is running. Kept apart from the frame-marker slot on purpose —
 * a frame marker switches on enrollment policing, which must never apply to
 * observers, bridges or event collectors. Same wasmJs note as
 * [currentFrameLocal].
 */
internal expect fun currentFanoutLocal(): Any?

/** Write the thread-local fanout-marker slot. See [currentFanoutLocal]. */
internal expect fun setFanoutLocal(value: Any?)
