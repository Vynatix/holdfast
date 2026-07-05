package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store

// Native reflection can't recover the erased Store<Self> type argument, so CRTP
// self-type enforcement is a no-op here (the JVM/Android dev loop enforces it).
internal actual fun validateCrtpSelfType(store: Store<*>) {
    // no-op on iOS
}
