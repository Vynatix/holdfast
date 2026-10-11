package com.vynatix.holdfast.platform

expect fun currentThreadId(): Long

expect fun threadYield()

/**
 * Whether `kotlinx.atomicfu.locks.SynchronousMutex.tryLock` counts on this
 * platform. It does on the JVM, Android and iOS. It does NOT on wasmJs, where
 * atomicfu's actual is a counting no-op: `tryLock` always succeeds WITHOUT
 * incrementing the state, `lock` always increments it, and `unlock` always
 * decrements it, throwing `IllegalStateException: Mutex already unlocked`
 * when it reads zero. A tryLock-claim paired with a later `unlock` therefore
 * underflows the counter there (the wasm break holdfast PR #40 first found in
 * `InitializerGraph`).
 *
 * Where this reads `false`, a lock the owner-state already proved free must be
 * claimed with `lock()` — the one acquire balanced with `unlock()` on every
 * target — which cannot park on that single-threaded platform. See
 * `StoreLock.tryAcquire`.
 */
internal expect val tryLockCounts: Boolean
