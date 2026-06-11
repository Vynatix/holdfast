package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class InspectionVault : Store<InspectionVault>() {
    val a by state { 0 }
    val b by state { "init" }
}

class RuntimeStateInspectionTest {
    @Test
    fun getStateByNameReturnsRegisteredStateInstance() {
        val v = InspectionVault()
        val direct = v.a
        val byName = v.getState("a")
        assertSame(direct, byName, "getState must return the same instance as the property delegate")
    }

    @Test
    fun getStateForUnknownNameReturnsNull() {
        val v = InspectionVault()
        v.a // ensure 'a' is registered so the map is non-empty
        assertNull(v.getState("nonexistent"))
    }

    @Test
    fun hasStateReturnsTrueAfterStateDelegateAccess() {
        val v = InspectionVault()
        assertFalse(v.hasState("a"), "before first access, no state is registered")
        v.a
        assertTrue(v.hasState("a"))
    }

    @Test
    fun hasStateReturnsFalseBeforeAnyDelegateAccess() {
        val v = InspectionVault()
        assertFalse(v.hasState("a"))
        assertFalse(v.hasState("b"))
        assertFalse(v.hasState("anything"))
    }

    @Test
    fun propertiesSnapshotReturnsAllRegisteredStates() {
        val v = InspectionVault()
        v.a
        v.b
        val props = v.properties
        assertEquals(2, props.size)
        assertTrue("a" in props.keys)
        assertTrue("b" in props.keys)
    }

    @Test
    fun propertiesSnapshotIsImmutableCopyAndDoesNotReflectLaterAdditions() {
        val v = InspectionVault()
        v.a
        val snapshot = v.properties
        val sizeBefore = snapshot.size
        v.b // adds 'b' to internal map
        assertEquals(sizeBefore, snapshot.size, "earlier snapshot does not reflect 'b'")
        assertEquals(2, v.properties.size, "fresh snapshot does")
    }

    @Test
    fun removeStateDropsTheRegistrationAndReAccessRecreatesWithInitial() {
        val v = InspectionVault()
        v action { a mutate 99 }
        assertEquals(99, v.a.value)

        v.removeState("a")
        assertFalse(v.hasState("a"))

        // Re-access via delegate creates a fresh state with the initial value
        val newA = v.a
        assertEquals(0, newA.value, "re-access after removeState recreates with initial value")
        assertTrue(v.hasState("a"))
    }

    @Test
    fun clearStatesEmptiesAllRegistrationsAndReAccessRecreatesAll() {
        val v = InspectionVault()
        v action {
            a mutate 5
            b mutate "x"
        }
        assertEquals(5, v.a.value)
        assertEquals("x", v.b.value)

        v.clearStates()
        assertFalse(v.hasState("a"))
        assertFalse(v.hasState("b"))

        // Re-access recreates each state with its initial value
        assertEquals(0, v.a.value)
        assertEquals("init", v.b.value)
    }

    @Test
    fun removeStateDuringActiveTransactionLeavesPendingWritesOrphaned() {
        val v = InspectionVault()
        v action {
            a mutate 99
            v.removeState("a")
        }
        // Fresh access creates new state with initial; the orphan's _value=99 is unobservable
        assertEquals(
            0,
            v.a.value,
            "fresh state recreated post-removeState has initial value, not the orphan's value",
        )
    }
}
