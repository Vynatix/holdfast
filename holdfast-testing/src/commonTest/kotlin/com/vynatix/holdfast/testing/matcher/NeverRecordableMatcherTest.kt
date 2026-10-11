@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.MiddlewareStarted
import com.vynatix.holdfast.testing.StoreEvent
import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

private class NeverRecordableVault : Store<NeverRecordableVault>() {
    val theme by state { "light" }
    val other by state { 0 }
}

/** A middleware a user could install — the recorder never wraps it. */
private class UserNoopMiddleware : Middleware<NeverRecordableVault>()

/**
 * Never-recordable matcher categories throw a teaching error instead of
 * passing vacuously (or failing with a generic "did not match"):
 *
 *  - `middleware { }` predicates scoped to anything but the recorder;
 *  - `bridgePublished` / `bridgeObserved` on a state whose bridge the
 *    recorder never wrapped.
 *
 * Both are refused by the handle-receiver combinators only — synthetic
 * `List<StoreEvent>` timelines keep every predicate.
 */
class NeverRecordableMatcherTest {
    @Test
    fun userMiddlewareClassPredicateThrowsOnHandleReceiver() =
        storeTest {
            val v = track(NeverRecordableVault())
            v.read { theme.value } // materialize + auto-track
            val err =
                assertFailsWith<IllegalStateException> {
                    v shouldFire {
                        middleware<UserNoopMiddleware>().started
                    }
                }
            assertContains(err.message.orEmpty(), "can never match")
            assertContains(err.message.orEmpty(), "middleware<UserNoopMiddleware>")
        }

    @Test
    fun userMiddlewareInstancePredicateThrowsOnHandleReceiver() =
        storeTest {
            val v = track(NeverRecordableVault())
            v.read { theme.value }
            val err =
                assertFailsWith<IllegalStateException> {
                    v shouldFireInOrder {
                        middleware(UserNoopMiddleware()).started
                    }
                }
            assertContains(err.message.orEmpty(), "middleware<UserNoopMiddleware>")
        }

    @Test
    fun userMiddlewarePredicateThrowsForShouldNotFireToo() =
        storeTest {
            // The vacuous-pass killer: without the refusal this passes
            // trivially because the predicate's events can never be recorded.
            val v = track(NeverRecordableVault())
            v.read { theme.value }
            val err =
                assertFailsWith<IllegalStateException> {
                    v shouldNotFire {
                        middleware<UserNoopMiddleware>().errored
                    }
                }
            assertContains(err.message.orEmpty(), "can never match")
        }

    @Test
    fun captureNoneSkipsTheRefusal() =
        storeTest {
            // Capture.None opts out of recording entirely (documented as
            // "records nothing"), so the refusal stays silent there.
            val v = track(NeverRecordableVault(), Capture.None)
            v shouldNotFire {
                middleware<UserNoopMiddleware>().started
            }
        }

    @Test
    fun syntheticTimelineKeepsUserMiddlewarePredicates() {
        // A hand-built timeline may hold events a recorder could not produce;
        // the list-receiver combinators stay permissive.
        val mw = UserNoopMiddleware()
        val txn = Transaction.createForExternal("t", ownerThreadId = 0L)
        val timeline =
            listOf<StoreEvent>(
                MiddlewareStarted(middleware = mw, transaction = txn, timestamp = 0L),
            )
        timeline shouldFire {
            middleware(mw).started
        }
    }

    @Test
    fun bridgePredicateThrowsWhenBridgeAttachedAfterTrack() =
        storeTest {
            val store = NeverRecordableVault()
            val v = track(store)
            // Attach AFTER track: never wrapped, never recorded.
            val bridge = RecordingBridge<String>(initial = "light")
            store { theme bridge bridge }
            v.read { theme.value } // materialize so the state resolves
            val err =
                assertFailsWith<IllegalStateException> {
                    v shouldFire {
                        bridgePublished(NeverRecordableVault::theme)
                    }
                }
            assertContains(err.message.orEmpty(), "bridgePublished(theme) can never match")
            assertContains(err.message.orEmpty(), "track(v)")
        }

    @Test
    fun bridgePredicateThrowsWhenNoBridgeAttached() =
        storeTest {
            val v = track(NeverRecordableVault())
            v.read { other.value }
            val err =
                assertFailsWith<IllegalStateException> {
                    v shouldNotFire {
                        bridgeObserved(NeverRecordableVault::other)
                    }
                }
            assertContains(err.message.orEmpty(), "bridgeObserved(other) can never match")
        }

    @Test
    fun wrappedBridgePredicateStillMatches() =
        storeTest {
            // The happy path: a bridge attached (and the state materialized)
            // BEFORE track is wrapped at install time and its publishes reach
            // the timeline, so the predicate is not refused.
            val bridge = RecordingBridge<String>(initial = "light")
            val store =
                NeverRecordableVault().also { s ->
                    s { theme bridge bridge }
                    s.theme.value // materialize before track so the wrap sees it
                }
            val v = track(store)
            v.action { theme mutate "dark" }.shouldBeSuccess()
            v shouldFire {
                bridgePublished(NeverRecordableVault::theme)
            }
        }
}
