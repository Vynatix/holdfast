package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object MaterializingLocalHolder {
    var value: Any? = null
}

internal actual fun currentMaterializingLocal(): Any? = MaterializingLocalHolder.value

internal actual fun setMaterializingLocal(value: Any?) {
    MaterializingLocalHolder.value = value
}
