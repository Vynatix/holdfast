@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.lifecycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CountedStore : Store<CountedStore>() {
    val count by state { 0 }
    var disposeCalls = 0
        private set

    override fun onDispose() {
        disposeCalls++
    }
}

/** Named on purpose: ViewModelProvider refuses local and anonymous classes. */
private class CountedViewModel : StoreViewModel<CountedStore>() {
    var created = false
        private set

    override fun createStore(): CountedStore {
        created = true
        return CountedStore()
    }

    fun clearOnceForTest() = onCleared()
}

class StoreViewModelTest {
    @Test
    fun storeIsCreatedOnceOnFirstRead() {
        val vm = CountedViewModel()
        assertFalse(vm.created)

        val first = vm.store
        assertTrue(vm.created)

        assertSame(first, vm.store)
        assertFalse(first.isDisposed)
        assertEquals(0, first.disposeCalls)
    }

    @Test
    fun viewModelStoreClearDisposesTheStoreExactlyOnce() {
        val vm = CountedViewModel()
        val store = vm.store

        vm.clearThroughViewModelStore()

        assertTrue(store.isDisposed)
        assertEquals(1, store.disposeCalls)
    }

    @Test
    fun repeatedOnClearedIsANoOp() {
        val vm = CountedViewModel()
        val store = vm.store

        vm.clearOnceForTest()
        vm.clearOnceForTest()

        assertTrue(store.isDisposed)
        assertEquals(1, store.disposeCalls)
    }

    @Test
    fun storeAfterClearThrows() {
        val vm = CountedViewModel()
        vm.store
        vm.clearOnceForTest()

        val error = assertFailsWith<IllegalStateException> { vm.store }
        assertTrue(
            "was read after onCleared" in (error.message ?: ""),
            "teaching error, got: ${error.message}",
        )
    }

    @Test
    fun clearWithoutAReadNeverCreatesTheStore() {
        val vm = CountedViewModel()
        vm.clearOnceForTest()
        assertFalse(vm.created)
    }

    @Test
    fun storeViewModelFactoryDisposesThroughOnCleared() {
        val vm = storeViewModel { CountedStore() }
        val store = vm.store

        vm.clearThroughViewModelStore()

        assertTrue(store.isDisposed)
        assertEquals(1, store.disposeCalls)
    }

    @Test
    fun aCloseableAddedBeforeClearSeesTheLiveStoreBeforeDispose() {
        val vm = CountedViewModel()
        val store = vm.store

        var seen: CountedStore? = null
        var seenLive = false
        vm.addCloseable(
            AutoCloseable {
                vm.store.let {
                    seen = it
                    seenLive = !it.isDisposed
                }
            },
        )

        vm.clearThroughViewModelStore()

        // The platform closes closeables before onCleared: the closeable read
        // the live store, and only then did the wiring dispose it — the
        // persist-on-clear recipe's ordering guarantee.
        assertSame(store, seen)
        assertTrue(seenLive)
        assertTrue(store.isDisposed)
    }

    @Test
    fun viewModelProviderWiringDisposesOnStoreClear() {
        val viewModelStore = ViewModelStore()
        val provider =
            ViewModelProvider.create(
                viewModelStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(
                        modelClass: KClass<T>,
                        extras: CreationExtras,
                    ): T = CountedViewModel() as T
                },
            )
        val vm = provider.get("counted", CountedViewModel::class)
        val store = vm.store

        viewModelStore.clear()

        assertTrue(store.isDisposed)
        assertEquals(1, store.disposeCalls)
    }
}
