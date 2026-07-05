package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store

/**
 * Validate that a [Store] subclass parameterizes the CRTP `Self` type with its
 * own class — i.e. `class Foo : Store<Foo>()`, not `class Foo : Store<Bar>()`.
 * A wrong `Self` otherwise degrades to a swallowed `ClassCastException` deep
 * inside the DSL; enforcing it at construction turns that into a clear,
 * two-type teaching message.
 *
 * Enforcement is JVM/Android-only (it needs generic-superclass reflection). On
 * iOS/wasmJs this is a no-op — the dev loop runs on JVM, and native reflection
 * can't recover the erased type argument. A generic intermediate base whose
 * `Self` is a type variable (e.g. `EventfulStore<Self, E> : Store<Self>()`) is
 * skipped: only a concrete, mismatched type argument fails.
 */
internal expect fun validateCrtpSelfType(store: Store<*>)
