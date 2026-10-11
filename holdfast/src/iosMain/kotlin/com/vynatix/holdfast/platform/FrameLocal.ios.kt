package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object FrameLocalHolder {
    var value: Any? = null
}

internal actual fun currentFrameLocal(): Any? = FrameLocalHolder.value

internal actual fun setFrameLocal(value: Any?) {
    FrameLocalHolder.value = value
}

@ThreadLocal
private object FanoutLocalHolder {
    var value: Any? = null
}

internal actual fun currentFanoutLocal(): Any? = FanoutLocalHolder.value

internal actual fun setFanoutLocal(value: Any?) {
    FanoutLocalHolder.value = value
}
