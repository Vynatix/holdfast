package com.vynatix.holdfast

/**
 * Marks symbols that are technically `public` for cross-module visibility (so
 * companion modules like `:holdfast-coroutines` and `:holdfast-compose` can reach
 * them) but are NOT part of the library's stable public API. They may change
 * shape between minor releases without going through the binary-compat
 * deprecation cycle.
 *
 * Application code should never opt in. If you find yourself needing to opt
 * in, file an issue — we likely need a real public API instead.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This symbol is internal to the Holdfast library. Companion modules must @OptIn; " +
        "application code should not.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class StoreInternalApi
