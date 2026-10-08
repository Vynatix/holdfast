package com.vynatix.holdfast.platform

import kotlinx.atomicfu.locks.SynchronousMutex

/**
 * [SynchronousMutex.tryLock] that counts a successful take as a hold on every
 * target, so the [SynchronousMutex.unlock] paired with it always balances.
 * Every holdfast `tryLock` on a [SynchronousMutex] goes through here.
 *
 * atomicfu's js/wasm actual (0.32.1 and 0.33.0) returns `true` from `tryLock()`
 * without counting it, so the paired `unlock()` fails its "Mutex already
 * unlocked" check (issue #26). The JVM and native actuals do count it. The
 * timed `tryLock(timeout)` has the same js/wasm bug and no counted variant
 * yet: add one here before using it.
 *
 * Only for a caller that has ruled out holding [this] itself (callers check
 * their owner bookkeeping first): the wasmJs actual takes the mutex
 * unconditionally, which is sound there only because that target is
 * single-threaded, so nothing else can hold it.
 */
internal expect fun SynchronousMutex.tryLockCounted(): Boolean
