package com.vynatix.holdfast

/**
 * DSL marker preventing accidental access to outer-scope receivers in nested
 * store DSLs. Without this, code inside `inner.action { … }` could implicitly
 * resolve methods on an enclosing `outer.action { … }`'s receiver, which is
 * almost never what you want.
 *
 * With the marker, inner-scope code MUST qualify outer-scope store calls
 * explicitly (e.g. `outer.something()`), making cross-store references
 * intentional and reviewable.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class StoreActionDsl
