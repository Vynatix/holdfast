package com.vynatix.holdfast.platform

internal actual fun Throwable.isFatal(): Boolean = this is VirtualMachineError
