package com.vynatix.holdfast.platform

import kotlinx.atomicfu.locks.SynchronousMutex

// wasmJs is single-threaded by assumption, so lock() never blocks; unlike
// atomicfu's tryLock() there, it records the hold the paired unlock() releases.
internal actual fun SynchronousMutex.tryLockCounted(): Boolean {
    lock()
    return true
}
