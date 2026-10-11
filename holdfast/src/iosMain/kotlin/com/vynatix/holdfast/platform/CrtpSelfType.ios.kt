package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store

/**
 * Kotlin/Native actual: no construction-time CRTP check — Native offers no
 * reliable generic-signature reflection while a constructor runs. The
 * violation surfaces at the FIRST DISPATCH (the `this as Self` receiver-bridge
 * cast throws), where `action`/`invoke` upgrade it to a teaching rethrow via
 * [crtpSelfCastTeachingOrNull] — see `platform/CrtpSelfType.kt` (common).
 *
 * Consequence for issue 010's init-order note: on Native a mistyped store's
 * eager initializers run to completion before the first dispatch failure.
 */
internal actual fun checkCrtpSelfTypeAtConstruction(store: Store<*>) = Unit
