package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object BareInvokeDepthHolder {
    var value: Int = 0
}

internal actual fun bareInvokeDepth(): Int = BareInvokeDepthHolder.value

internal actual fun setBareInvokeDepth(value: Int) {
    BareInvokeDepthHolder.value = value
}
