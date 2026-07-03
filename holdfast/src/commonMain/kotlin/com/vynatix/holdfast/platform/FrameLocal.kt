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
