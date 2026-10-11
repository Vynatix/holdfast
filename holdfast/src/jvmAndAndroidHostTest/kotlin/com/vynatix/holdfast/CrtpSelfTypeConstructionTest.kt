package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The wrong-declared `Self`: a perfectly legal store, just not THIS one. */
private class AnchorStore : Store<AnchorStore>() {
    val n by state { 0 }
}

/** The pinned shape from issue 006: `class Foo : Store<Bar>()`. */
private class MistypedStore : Store<AnchorStore>()

/** The library's own generic intermediate, subclassed the documented way. */
private sealed class HostedEvent {
    data object Saved : HostedEvent()
}

private class EventfulLeaf : EventfulStore<EventfulLeaf, HostedEvent>() {
    val n by state { 0 }
}

/** A user generic intermediate: `UserBase<S : UserBase<S>> : Store<S>`. */
private open class UserBase<S : UserBase<S>> : Store<S>() {
    val tag by state { "base" }
}

private class UserLeaf : UserBase<UserLeaf>()

/** A concrete store, subclassed (and anonymously faked) below. */
private open class ConcreteBase : Store<ConcreteBase>() {
    val n by state { 0 }
}

private class ConcreteDerived : ConcreteBase()

/** A generic store: `G<T> : Store<G<T>>`. */
private open class GenericStore<T : Any>(
    private val initial: T,
) : Store<GenericStore<T>>() {
    val value by state { initial }
}

private class IntGenericStore : GenericStore<Int>(3)

/** Self stays one of the class's own type variables — nothing to enforce on. */
private class OpenMiddle<M : Store<M>> : Store<M>()

/** Sibling forward-reference: `class Other : MyBase()` where `MyBase : Store<Foo>`. */
private class ForwardTarget : Store<ForwardTarget>()

private open class SiblingBase : Store<ForwardTarget>()

private class SiblingSub : SiblingBase()

/**
 * JVM/Android construction-time rows (issue 006). These run on the shared
 * jvmAndAndroidHostTest source set: the reflection walk exists only there.
 * `CrtpSelfTypeTest` (common) covers the platform-neutral rows.
 */
class CrtpSelfTypeConstructionTest {
    @Test
    fun wrongSelfTypeFailsAtConstructionWithPinnedTwoTypeMessage() {
        val failure =
            assertFailsWith<IllegalStateException> {
                MistypedStore()
            }
        assertEquals(
            "class MistypedStore extends Store<AnchorStore>; the type argument must be MistypedStore itself — " +
                "declare it as `class MistypedStore : Store<MistypedStore>()`. Every Store member-extension " +
                "(action { } bodies, mutate, effect, bridge) resolves against Self, so a store anchored " +
                "to another type fails the first time it is used.",
            failure.message,
        )
    }

    @Test
    fun wrongSelfTypeFailsAtConstructionOnEveryConstruction() {
        repeat(3) {
            assertFailsWith<IllegalStateException> { MistypedStore() }
        }
    }

    @Test
    fun eventfulStoreIntermediateBaseConstructsAndDispatches() {
        val store = EventfulLeaf()
        val result: TransactionResult<Unit> = store action { n mutate 1 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(1, store.n.value)
    }

    @Test
    fun userGenericIntermediateConstructsAndDispatches() {
        val store = UserLeaf()
        val result: TransactionResult<Unit> = store action { tag mutate "leaf" }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("leaf", store.tag.value)
    }

    @Test
    fun concreteStoreSubclassConstructsAndDispatches() {
        val store = ConcreteDerived()
        val result: TransactionResult<Unit> = store action { n mutate 5 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(5, store.n.value)
    }

    @Test
    fun anonymousFakeOfConcreteStoreConstructsAndDispatches() {
        val store = object : ConcreteBase() {}
        val result: TransactionResult<Unit> = store action { n mutate 7 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(7, store.n.value)
    }

    @Test
    fun genericStoreConstructsAndDispatches() {
        val store = IntGenericStore()
        val result: TransactionResult<Int> = store action { value.value }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(3, store.value.value)
    }

    @Test
    fun unresolvableSelfTypeArgumentFailsOpen() {
        // Self resolves to OpenMiddle's own type variable M: the check must
        // skip (no throw) — the fail-open rule (issue 006, binding).
        OpenMiddle<AnchorStore>()
    }

    @Test
    fun siblingForwardReferenceIsRejected() {
        val failure =
            assertFailsWith<IllegalStateException> {
                SiblingSub()
            }
        assertEquals(
            "class SiblingSub extends Store<ForwardTarget>; the type argument must be SiblingSub itself — " +
                "declare it as `class SiblingSub : Store<SiblingSub>()`. Every Store member-extension " +
                "(action { } bodies, mutate, effect, bridge) resolves against Self, so a store anchored " +
                "to another type fails the first time it is used.",
            failure.message,
        )
    }

    @Test
    fun siblingForwardReferenceBaseItselfIsRejected() {
        assertFailsWith<IllegalStateException> { SiblingBase() }
    }
}
