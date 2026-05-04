package com.vynatix.holdfast.coroutines.platform

import kotlinx.coroutines.runBlocking

internal actual fun <T> runBlockingForInitialSeed(body: suspend () -> T): T = runBlocking { body() }
