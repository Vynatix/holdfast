package com.vynatix.holdfast.platform

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object MintLocalHolder {
    var value: Any? = null
}

internal actual fun currentMintLocal(): Any? = MintLocalHolder.value

internal actual fun setMintLocal(value: Any?) {
    MintLocalHolder.value = value
}
