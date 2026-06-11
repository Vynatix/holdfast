package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class EffectTestVault : Store<EffectTestVault>() {
    val count by state { 0 }
    val label by state { "init" }
}

class EffectSubscribeTest {
    @Test
    fun effectReceivesCurrentValueOnSubscribe() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val disposable = v { count effect { seen.add(this) } }
        assertEquals(listOf(0), seen)
        disposable.dispose()
    }

    @Test
    fun disposedEffectReceivesNoFurtherNotifications() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val disposable = v { count effect { seen.add(this) } }
        v action { count mutate 1 }
        disposable.dispose()
        v action { count mutate 2 }
        assertEquals(listOf(0, 1), seen)
    }

    @Test
    fun subscribingTwiceWithSameLambdaReferenceRegistersOnceDueToHashSet() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val callback: Int.() -> Unit = { seen.add(this) }
        val d1 = v { count effect callback }
        val d2 = v { count effect callback }
        seen.clear()

        v action { count mutate 1 }
        // The same lambda reference, registered twice in a Set, deduplicates.
        // Therefore we expect a single notification per commit.
        assertEquals(listOf(1), seen, "same observer reference deduped by HashSet; seen=$seen")
        d1.dispose()
        d2.dispose()
    }

    @Test
    fun subscribingDuringActiveActionReturnsInitialCommittedValue() {
        val v = EffectTestVault()
        v action { count mutate 5 }

        val seen = mutableListOf<Int>()
        var disposable: Disposable? = null
        v action {
            // Subscribing inside an action: initial sees committed _value, NOT pending.
            // count's _value is still 5 here (pending = 99 hasn't been applied).
            count mutate 99
            disposable = v { count effect { seen.add(this) } }
            // Initial callback already happened; seen should have 5.
        }
        // Action commits; observer gets notified with committed value 99.

        assertEquals(
            listOf(5, 99),
            seen,
            "initial subscribe sees committed 5; commit notifies with 99; seen=$seen",
        )
        disposable?.dispose()
    }

    @Test
    fun subscribingAfterRollbackSeesPreActionCommittedValue() {
        val v = EffectTestVault()
        v action { count mutate 10 }

        v action {
            count mutate 99
            error("rollback")
        }

        val seen = mutableListOf<Int>()
        val d = v { count effect { seen.add(this) } }
        assertEquals(
            listOf(10),
            seen,
            "after a rolled-back action, subscribers see the pre-action committed value",
        )
        d.dispose()
    }
}

class EffectNotificationTest {
    @Test
    fun effectReceivesEachCommittedValueAfterMutation() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val disposable = v { count effect { seen.add(this) } }
        v action { count mutate 1 }
        v action { count mutate 2 }
        assertEquals(listOf(0, 1, 2), seen)
        disposable.dispose()
    }

    /**
     * Documented fan-out behaviour: each commit fires the effect, even when the value
     * equals the previously committed value.
     */
    @Test
    fun effectReceivesNotificationOnEachCommitEvenForSameValue() {
        val v = EffectTestVault()
        v action { count mutate 5 }
        val seen = mutableListOf<Int>()
        val disposable = v { count effect { seen.add(this) } }
        seen.clear()

        v action { count mutate 5 }
        v action { count mutate 5 }

        assertEquals(
            listOf(5, 5),
            seen,
            "effect must receive one notification per commit (fan-out): $seen",
        )
        disposable.dispose()
    }

    @Test
    fun effectThatThrowsExceptionDoesNotPreventOtherSubscribersFromBeingNotified() {
        val v = EffectTestVault()
        val good1 = mutableListOf<Int>()
        val good2 = mutableListOf<Int>()
        val throwerCalls = atomic(0)

        // The middle subscriber doesn't throw on the initial-subscribe callback (which
        // isn't wrapped in observe()), but it throws on every later notification.
        // notifyObservers() catches Exception around each observer's call.
        val d1 = v { count effect { good1.add(this) } }
        val d2 =
            v {
                count effect {
                    if (throwerCalls.incrementAndGet() > 1) error("subscriber-2 throws")
                }
            }
        val d3 = v { count effect { good2.add(this) } }
        good1.clear()
        good2.clear()

        v action { count mutate 7 }

        assertEquals(listOf(7), good1, "good1 still received; good1=$good1")
        assertEquals(listOf(7), good2, "good2 still received despite middle subscriber throwing; good2=$good2")
        d1.dispose()
        d2.dispose()
        d3.dispose()
    }

    @Test
    fun multipleSubscribersAllReceiveEachCommittedValue() {
        val v = EffectTestVault()
        val all = List(5) { mutableListOf<Int>() }
        val disposables = all.map { seen -> v { count effect { seen.add(this) } } }
        all.forEach { it.clear() }

        v action { count mutate 1 }
        v action { count mutate 2 }
        v action { count mutate 3 }

        all.forEachIndexed { i, seen ->
            assertEquals(listOf(1, 2, 3), seen, "subscriber #$i did not receive all values; seen=$seen")
        }
        disposables.forEach { it.dispose() }
    }

    @Test
    fun subscriberThatDisposesItselfDuringCallbackStopsReceivingFurtherNotifications() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        var disposable: Disposable? = null
        disposable =
            v {
                count effect {
                    seen.add(this)
                    if (this == 1) disposable?.dispose()
                }
            }
        // Initial 0 received. Disposable now non-null.
        v action { count mutate 1 } // Subscriber adds 1 then disposes self.
        v action { count mutate 2 } // Subscriber should NOT see 2.
        assertEquals(listOf(0, 1), seen)
    }

    @Test
    fun subscriberThatRegistersAnotherSubscriberDuringCallbackDoesNotInvokeNewOneForCurrentNotify() {
        val v = EffectTestVault()
        val outer = mutableListOf<Int>()
        val inner = mutableListOf<Int>()
        val registered = atomic(false)

        val outerD =
            v {
                count effect {
                    outer.add(this)
                    if (this == 1 && !registered.getAndSet(true)) {
                        // Register a new observer during current notification.
                        v { count effect { inner.add(this) } }
                    }
                }
            }

        v action { count mutate 1 } // outer fires with 1, registers inner. inner gets initial=1.
        v action { count mutate 2 } // both outer and inner fire with 2.

        assertEquals(listOf(0, 1, 2), outer)
        assertEquals(
            listOf(1, 2),
            inner,
            "inner registered at value=1 sees 1 as initial, then 2; not retroactively notified for 1's commit",
        )
        outerD.dispose()
    }

    @Test
    fun subscriberMutatingStateInDifferentVaultRunsAsImplicitTransactionOnThatVault() {
        val v1 = EffectTestVault()
        val v2 = EffectTestVault()
        val v2Seen = mutableListOf<Int>()
        val d2 = v2 { count effect { v2Seen.add(this) } }
        v2Seen.clear()

        val d1 =
            v1 {
                count effect {
                    if (this == 5) {
                        // Different store → different transactionLock; no nesting hazard.
                        v2 action { count mutate 100 }
                    }
                }
            }

        v1 action { count mutate 5 }

        assertEquals(100, v2.count.value, "subscriber's nested mutate on v2 succeeded")
        assertTrue(100 in v2Seen, "v2's observer fired with 100; v2Seen=$v2Seen")

        d1.dispose()
        d2.dispose()
    }
}

class EffectTopLevelExtensionTest {
    @Test
    fun topLevelEffectFiresOnCommitOutsideVaultBlock() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        // Direct top-level call: no `store { … }` wrapping.
        val d = v.count effect { seen.add(this) }
        v action { count mutate 7 }
        assertEquals(listOf(0, 7), seen)
        d.dispose()
    }

    @Test
    fun topLevelEffectDisposableStopsFurtherFires() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val d = v.count effect { seen.add(this) }
        v action { count mutate 1 }
        d.dispose()
        v action { count mutate 2 }
        assertEquals(listOf(0, 1), seen)
    }

    @Test
    fun memberCallSiteInsideVaultBlockStillCompiles() {
        // Acceptance: existing `store { state effect { … } }` keeps working.
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val d = v { count effect { seen.add(this) } }
        v action { count mutate 3 }
        assertEquals(listOf(0, 3), seen)
        d.dispose()
    }
}

class EffectTransactionIsolationTest {
    @Test
    fun effectsDoNotReceiveValuesFromInProgressTransaction() {
        val v = EffectTestVault()
        val seen = mutableListOf<Int>()
        val disposable = v { count effect { seen.add(this) } }
        seen.clear()

        v action {
            count mutate 42
            error("rollback")
        }

        assertTrue(
            42 !in seen,
            "effect must not receive uncommitted values from in-progress transactions; seen=$seen",
        )
        disposable.dispose()
    }

    @Test
    fun effectsDoNotReceiveValuesFromRolledBackTransaction() {
        val v = EffectTestVault()
        v action { count mutate 1 }

        val seen = mutableListOf<Int>()
        val disposable = v { count effect { seen.add(this) } }
        assertEquals(listOf(1), seen, "initial subscribe sees committed value")

        v action {
            count mutate 99
            error("force rollback")
        }

        assertEquals(1, v.count.value, "rollback restores committed value")
        assertEquals(
            listOf(1),
            seen,
            "effect must not receive any value from a rolled-back transaction; seen=$seen",
        )
        disposable.dispose()
    }
}

class EffectInteractionTest {
    @Test
    fun subscriberCanReadStateValueDuringCallbackAndSeeCommittedValue() {
        val v = EffectTestVault()
        val readBacks = mutableListOf<Int>()
        val d =
            v {
                count effect {
                    // Inside the post-commit notification, state.value should reflect the
                    // just-committed value (which equals `this`).
                    readBacks.add(v.count.value)
                }
            }
        v action { count mutate 7 }

        assertEquals(listOf(0, 7), readBacks, "state.value during callback matches notification value")
        d.dispose()
    }

    @Test
    fun subscriberCanReadDifferentStateValueDuringCallback() {
        val v = EffectTestVault()
        val labelReads = mutableListOf<String>()
        val d =
            v {
                count effect {
                    labelReads.add(v.label.value)
                }
            }
        v action {
            count mutate 1
            label mutate "updated"
        }

        // Initial subscribe: label is "init".
        // Post-commit: label has been updated to "updated".
        assertEquals(listOf("init", "updated"), labelReads)
        d.dispose()
    }

    @Test
    fun subscribingThenDisposingThenSubscribingAgainReceivesFreshInitialValue() {
        val v = EffectTestVault()
        v action { count mutate 5 }

        val first = mutableListOf<Int>()
        val d1 = v { count effect { first.add(this) } }
        d1.dispose()

        v action { count mutate 10 }

        val second = mutableListOf<Int>()
        val d2 = v { count effect { second.add(this) } }

        assertEquals(listOf(5), first, "first subscriber saw initial 5")
        assertEquals(listOf(10), second, "second subscriber sees fresh initial 10")
        d2.dispose()
    }

    @Test
    fun effectFiresForEachStateMutationWhenMultiStateActionCommits() {
        val v = EffectTestVault()
        val countSeen = mutableListOf<Int>()
        val labelSeen = mutableListOf<String>()
        val d1 = v { count effect { countSeen.add(this) } }
        val d2 = v { label effect { labelSeen.add(this) } }
        countSeen.clear()
        labelSeen.clear()

        v action {
            count mutate 1
            label mutate "first"
        }

        assertEquals(listOf(1), countSeen)
        assertEquals(listOf("first"), labelSeen)
        d1.dispose()
        d2.dispose()
    }

    @Test
    fun observerOnUnchangedStateDoesNotFireWhenOtherStateMutates() {
        val v = EffectTestVault()
        val countSeen = mutableListOf<Int>()
        val labelSeen = mutableListOf<String>()
        val d1 = v { count effect { countSeen.add(this) } }
        val d2 = v { label effect { labelSeen.add(this) } }
        countSeen.clear()
        labelSeen.clear()

        v action { count mutate 99 } // only count is mutated

        assertEquals(listOf(99), countSeen)
        assertEquals(emptyList(), labelSeen, "label observer must not fire when only count mutates")
        d1.dispose()
        d2.dispose()
    }
}
