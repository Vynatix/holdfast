package com.vynatix.holdfast.platform

/**
 * Default sink for a store's post-commit and dispose-notification failures
 * while it has no `uncaughtObserverHandler`: print [message], then [error]'s
 * stack trace, where a developer will see them — standard error on JVM and
 * Android (logcat's `System.err` tag), standard output on iOS (the Xcode
 * console) and wasmJs (the browser console).
 *
 * Never throws: it runs inside commit fanout, where a throw would skip the
 * remaining observers, bridges and events of a commit that already applied,
 * and inside `dispose()`, which never throws.
 */
internal expect fun logUncaughtFailure(
    message: String,
    error: Throwable,
)
