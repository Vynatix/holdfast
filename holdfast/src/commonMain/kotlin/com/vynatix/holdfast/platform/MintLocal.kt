package com.vynatix.holdfast.platform

/**
 * Thread-local slot holding the keyed-store mint of the current thread (see
 * `com.vynatix.holdfast.tree.Mint`): the `KeyedBranch.create` call whose
 * factory is running here, which is the only place `KeyedBranch.at(key)` may
 * mint a membership token. Typed as `Any?` so the platform actuals stay
 * dependency-free; the single reader/writer casts.
 *
 * wasmJs note: the actual there is a plain global `var` — the platform is
 * single-threaded by assumption (`currentThreadId()` returns `0` for everyone),
 * so a process-global slot IS the thread-local slot. Same pattern as
 * [currentFrameLocal].
 */
internal expect fun currentMintLocal(): Any?

/** Write the thread-local mint slot. See [currentMintLocal]. */
internal expect fun setMintLocal(value: Any?)
