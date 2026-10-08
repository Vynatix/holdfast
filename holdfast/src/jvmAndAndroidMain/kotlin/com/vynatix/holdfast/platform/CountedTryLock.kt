package com.vynatix.holdfast.platform

import kotlinx.atomicfu.locks.SynchronousMutex

internal actual fun SynchronousMutex.tryLockCounted(): Boolean = tryLock()
