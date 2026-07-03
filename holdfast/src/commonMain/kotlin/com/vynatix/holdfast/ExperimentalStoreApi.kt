package com.vynatix.holdfast

/**
 * Marks Holdfast API that is shipped for early feedback but has NOT soaked the
 * two-minor stabilization window the release train requires before 1.0. The
 * shape (names, parameters, semantics) may change in any 0.x release without a
 * deprecation cycle.
 *
 * Opting in is safe for applications that can absorb source breakage on
 * upgrade; libraries building on Holdfast should avoid experimental surface or
 * pin an exact Holdfast version.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This Holdfast API is experimental and may change shape in any 0.x release. " +
            "Opt in only if you can absorb source breakage on upgrade.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class ExperimentalStoreApi
