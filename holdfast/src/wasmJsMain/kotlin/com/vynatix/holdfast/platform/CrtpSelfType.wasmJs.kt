package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store

/**
 * wasmJs actual: no construction-time CRTP check (binding honesty, issue 006)
 * — and NO teaching-message claim either. Kotlin/Wasm bridge casts may
 * surface as WasmGC traps that `catch (e: ClassCastException)` cannot
 * intercept, and wasm test tasks never run in CI for this module, so nothing
 * about dispatch-time behavior is verified here. The common
 * [crtpSelfCastTeachingOrNull] gate still applies wherever a catchable,
 * message-bearing CCE does surface — as an unverified bonus, not a claim.
 */
internal actual fun checkCrtpSelfTypeAtConstruction(store: Store<*>) = Unit
