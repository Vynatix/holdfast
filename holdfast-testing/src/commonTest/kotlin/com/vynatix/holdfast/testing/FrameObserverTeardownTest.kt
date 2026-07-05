@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameObserver
import com.vynatix.holdfast.FrameObservers
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class ObsAccount : Store<ObsAccount>() {
    val balance by state { 0L }
}

/**
 * Regression for the inherited-F cleanup: [storeTest] teardown clears the
 * process-global [FrameObservers] registry, so an observer registered inside
 * one `storeTest` never fires during a later one in the same process.
 */
class FrameObserverTeardownTest {
    // Belt-and-suspenders: if the assertion ever fails mid-way, don't poison
    // sibling tests in this process with a leaked observer.
    @AfterTest fun cleanup() {
        FrameObservers.clear()
    }

    @Test
    fun frameObserverFromOneStoreTestDoesNotLeakIntoTheNext() {
        val fires = mutableListOf<String>()
        val observer =
            object : FrameObserver {
                override fun onFrameStarted(
                    frameId: String,
                    participants: List<Store<*>>,
                ) {
                    fires += frameId
                }
            }

        storeTest {
            val a = track(ObsAccount())
            val b = track(ObsAccount())
            FrameObservers.register(observer)
            atomic(a.store, b.store) {}
            assertEquals(1, fires.size, "observer must fire inside its own storeTest")
        }

        // storeTest #1's teardown ran FrameObservers.clear(); the observer must
        // be gone for the second, otherwise-identical storeTest.
        storeTest {
            val a = track(ObsAccount())
            val b = track(ObsAccount())
            atomic(a.store, b.store) {}
            assertEquals(
                1,
                fires.size,
                "frame observer leaked across storeTest boundary — teardown must clear FrameObservers",
            )
        }
    }
}
