package com.vynatix.holdfast.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.posix.pthread_self
import platform.posix.sched_yield

@OptIn(ExperimentalForeignApi::class)
actual fun currentThreadId(): Long = pthread_self().toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun threadYield() {
    sched_yield()
}
