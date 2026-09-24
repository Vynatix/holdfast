package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object SettleLocalHolder {
    var value: Any? = null
}

internal actual fun currentSettleLocal(): Any? = SettleLocalHolder.value

internal actual fun setSettleLocal(value: Any?) {
    SettleLocalHolder.value = value
}

@ThreadLocal
private object ComputeReadsLocalHolder {
    var value: Any? = null
}

internal actual fun currentComputeReadsLocal(): Any? = ComputeReadsLocalHolder.value

internal actual fun setComputeReadsLocal(value: Any?) {
    ComputeReadsLocalHolder.value = value
}
