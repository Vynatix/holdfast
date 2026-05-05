package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.vaultTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

private class CountVault : Store<CountVault>() {
    val count by state { 0 }
    val name by state { "x" }
}

class StateMatcherTest {

    // ----- shouldMatch (lenient) -----

    @Test
    fun shouldMatchPassesOnExactSubset() = vaultTest {
        val ctr = track(CountVault())
        ctr.action { count mutate 5 }.shouldBeSuccess()
        ctr shouldMatch {
            CountVault::count shouldEqual 5
        }
    }

    @Test
    fun shouldMatchPassesWhenAllAssertedFieldsEqual() = vaultTest {
        val ctr = track(CountVault())
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()
        ctr shouldMatch {
            CountVault::count shouldEqual 5
            CountVault::name shouldEqual "Hilde"
        }
    }

    @Test
    fun shouldMatchIgnoresUnassertedFields() = vaultTest {
        val ctr = track(CountVault())
        // Mutate both, but only assert one. Lenient: that's fine.
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()
        ctr shouldMatch {
            CountVault::count shouldEqual 5
        }
    }

    @Test
    fun shouldMatchFailsOnMismatch() = vaultTest {
        val ctr = track(CountVault())
        val err = assertFailsWith<AssertionError> {
            ctr shouldMatch {
                CountVault::count shouldEqual 99
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "count")
        assertContains(msg, "expected=99")
        assertContains(msg, "actual=0")
    }

    @Test
    fun shouldMatchListsEveryMismatch() = vaultTest {
        val ctr = track(CountVault())
        ctr.action {
            count mutate 1
            name mutate "real"
        }.shouldBeSuccess()
        val err = assertFailsWith<AssertionError> {
            ctr shouldMatch {
                CountVault::count shouldEqual 99
                CountVault::name shouldEqual "fake"
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "count: expected=99 actual=1")
        assertContains(msg, "name: expected=fake actual=real")
    }

    // ----- shouldMatchExactly (strict) -----

    @Test
    fun shouldMatchExactlyPassesOnComplete() = vaultTest {
        val ctr = track(CountVault())
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()
        ctr shouldMatchExactly {
            CountVault::count shouldEqual 5
            CountVault::name shouldEqual "Hilde"
        }
    }

    @Test
    fun shouldMatchExactlyFailsOnMissing() = vaultTest {
        val ctr = track(CountVault())
        // Touch both states so they're registered.
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()
        val err = assertFailsWith<AssertionError> {
            ctr shouldMatchExactly {
                CountVault::count shouldEqual 5
                // name omitted on purpose
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "states not asserted")
        assertContains(msg, "name")
    }

    @Test
    fun shouldMatchExactlyFailsOnValueMismatchAfterAllAsserted() = vaultTest {
        val ctr = track(CountVault())
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()
        val err = assertFailsWith<AssertionError> {
            ctr shouldMatchExactly {
                CountVault::count shouldEqual 5
                CountVault::name shouldEqual "wrong"
            }
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "name: expected=wrong actual=Hilde")
    }

    // ----- shouldMatchSnapshotOf -----

    @Test
    fun shouldMatchSnapshotOfPassesOnIdentical() = vaultTest {
        val ctr = track(CountVault())
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()

        val expected = CountVault()
        // Untracked store — its action result is not policed by the scope-exit
        // guard, so we don't need to consume.
        expected action {
            count mutate 5
            name mutate "Hilde"
        }

        ctr shouldMatchSnapshotOf expected
    }

    @Test
    fun shouldMatchSnapshotOfFailsOnValueMismatch() = vaultTest {
        val ctr = track(CountVault())
        ctr.action {
            count mutate 5
            name mutate "Hilde"
        }.shouldBeSuccess()

        val expected = CountVault()
        expected action {
            count mutate 5
            name mutate "different"
        }

        val err = assertFailsWith<AssertionError> {
            ctr shouldMatchSnapshotOf expected
        }
        val msg = err.message.orEmpty()
        assertContains(msg, "name")
        assertContains(msg, "Hilde")
        assertContains(msg, "different")
    }

    @Test
    fun shouldMatchSnapshotOfFailsOnStateNameMismatch() = vaultTest {
        // Two vaults of different shape — touch a different subset on each so
        // their snapshot.stateNames differ.
        val ctr = track(CountVault())
        ctr.action { count mutate 1 }.shouldBeSuccess() // touches only `count`

        val expected = CountVault()
        expected action {
            count mutate 1
            name mutate "x" // touches both
        }

        val err = assertFailsWith<AssertionError> {
            ctr shouldMatchSnapshotOf expected
        }
        assertContains(err.message.orEmpty(), "state-name mismatch")
    }
}
