package com.vynatix.holdfast.platform

// Kotlin/Native has no catchable stack overflow; kotlin.OutOfMemoryError is
// what its runtime and collections throw on a capacity overflow.
internal actual fun Throwable.isFatal(): Boolean = this is OutOfMemoryError
