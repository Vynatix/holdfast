package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object ConstructionLocalHolder {
    var value: Any? = null
}

internal actual fun currentConstructionLocal(): Any? = ConstructionLocalHolder.value

internal actual fun setConstructionLocal(value: Any?) {
    ConstructionLocalHolder.value = value
}
