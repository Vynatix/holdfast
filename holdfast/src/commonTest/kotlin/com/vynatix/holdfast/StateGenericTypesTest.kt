package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class Profile(val name: String, val age: Int)

private sealed interface Status {
    data object Active : Status
    data object Inactive : Status
    data class Suspended(val reason: String) : Status
}

private data class Wrapped<T>(val value: T?)

private class GenericTypesVault : Holdfast<GenericTypesVault>() {
    val text by state { "hello" }
    val profile by state { Profile("Alice", 30) }
    val list by state { listOf(1, 2, 3) }
    val map by state<Map<String, Int>> { mapOf("a" to 1) }
    val status by state<Status> { Status.Active }
    val wrappedNullable by state { Wrapped<String>(null) }
    val nestedList by state { listOf(listOf(1, 2), listOf(3, 4)) }
    val largeList by state { (1..10_000).toList() }
}

class StateGenericTypesTest {

    @Test
    fun stateOfStringPreservesValueIdentityAndContent() {
        val v = GenericTypesVault()
        assertEquals("hello", v.text.value)
        v action { text mutate "world" }
        assertEquals("world", v.text.value)
    }

    @Test
    fun stateOfDataClassPreservesEquality() {
        val v = GenericTypesVault()
        assertEquals(Profile("Alice", 30), v.profile.value)
        val newProfile = Profile("Bob", 25)
        v action { profile mutate newProfile }
        assertEquals(newProfile, v.profile.value)
        assertSame(newProfile, v.profile.value, "value getter returns the same reference")
    }

    @Test
    fun stateOfListReplacesWholeReferenceOnMutate() {
        val v = GenericTypesVault()
        val original = v.list.value
        val newList = listOf(4, 5, 6)
        v action { list mutate newList }
        assertEquals(newList, v.list.value)
        assertNotSame(original, v.list.value, "the new list reference is the one stored")
        assertSame(newList, v.list.value)
    }

    @Test
    fun stateOfMapReplacesWholeReferenceOnMutate() {
        val v = GenericTypesVault()
        val newMap = mapOf("b" to 2, "c" to 3)
        v action { map mutate newMap }
        assertEquals(newMap, v.map.value)
    }

    @Test
    fun stateOfSealedHierarchyHandlesAllSubtypes() {
        val v = GenericTypesVault()
        assertSame(Status.Active, v.status.value)
        v action { status mutate Status.Inactive }
        assertSame(Status.Inactive, v.status.value)
        val suspended = Status.Suspended("violation")
        v action { status mutate suspended }
        assertEquals(suspended, v.status.value)
    }

    @Test
    fun stateOfWrappedNullableHandlesPresenceAndAbsence() {
        val v = GenericTypesVault()
        assertNull(v.wrappedNullable.value.value, "Wrapped null is observable")
        v action { wrappedNullable mutate Wrapped("present") }
        assertEquals("present", v.wrappedNullable.value.value)
        v action { wrappedNullable mutate Wrapped(null) }
        assertNull(v.wrappedNullable.value.value, "round-trip back to null preserved")
    }

    @Test
    fun stateOfDeeplyNestedListPreservesStructure() {
        val v = GenericTypesVault()
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), v.nestedList.value)
        val newNested = listOf(listOf(10, 20, 30), listOf(40))
        v action { nestedList mutate newNested }
        assertEquals(newNested, v.nestedList.value)
    }

    @Test
    fun stateOfLargeCollectionRoundTripsCorrectly() {
        val v = GenericTypesVault()
        val original = v.largeList.value
        assertEquals(10_000, original.size)
        assertEquals(1, original.first())
        assertEquals(10_000, original.last())

        val replacement = (1..5_000).map { it * 2 }
        v action { largeList mutate replacement }
        val after = v.largeList.value
        assertEquals(5_000, after.size)
        assertEquals(2, after.first())
        assertEquals(10_000, after.last())
        assertTrue(after.all { it % 2 == 0 })
    }
}
