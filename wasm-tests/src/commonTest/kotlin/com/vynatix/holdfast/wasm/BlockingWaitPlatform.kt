package com.vynatix.holdfast.wasm

/**
 * Whether a blocking call beside a parked suspending holder can wait it out
 * here: `true` on the JVM, where the holder resumes on another thread;
 * `false` on wasmJs, where it refuses instead (issue #27).
 */
internal expect val blockingWaitsCanEnd: Boolean
