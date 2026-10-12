@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.lifecycle

import androidx.lifecycle.ViewModelStore
import com.vynatix.holdfast.ExperimentalStoreApi

/**
 * Clear a [StoreViewModel] through the platform's own path — a
 * [ViewModelStore] that owns it — the wiring real hosts run.
 */
internal fun StoreViewModel<*>.clearThroughViewModelStore() {
    val owner = ViewModelStore()
    owner.put("under-test", this)
    owner.clear()
}
