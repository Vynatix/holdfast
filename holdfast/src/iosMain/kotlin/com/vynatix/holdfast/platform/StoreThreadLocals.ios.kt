package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object InitializerLocalHolder {
    var value: Any? = null
}

internal actual fun currentInitializerLocal(): Any? = InitializerLocalHolder.value

internal actual fun setInitializerLocal(value: Any?) {
    InitializerLocalHolder.value = value
}
