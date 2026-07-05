package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A correctly-parameterized store.
private class CrtpGood : Store<CrtpGood>() {
    val n by state { 0 }
}

// Another correct store, used only as the wrong Self argument below.
private class CrtpOther : Store<CrtpOther>() {
    val n by state { 0 }
}

// Mis-parameterized: declares Store<CrtpOther> instead of Store<CrtpWrong>.
private class CrtpWrong : Store<CrtpOther>()

private sealed class CrtpEvent

// EventfulStore subclass — its Self is concrete and correct.
private class CrtpEventful : EventfulStore<CrtpEventful, CrtpEvent>() {
    val n by state { 0 }
}

// Generic intermediate base whose Store<S> argument is a type variable.
private abstract class CrtpBase<S : CrtpBase<S>> : Store<S>()

private class CrtpDerivedFromBase : CrtpBase<CrtpDerivedFromBase>()

/**
 * P1-crtp: the CRTP Self type is validated at construction (JVM/Android only).
 */
class CrtpValidationTest {
    @Test fun correctSelfConstructs() {
        assertTrue(!CrtpGood().isDisposed)
    }

    @Test fun wrongSelfThrowsAtConstructionNamingBothTypes() {
        val ex = assertFailsWith<IllegalStateException> { CrtpWrong() }
        val msg = ex.message ?: ""
        assertTrue("CrtpWrong" in msg, "names the declaring class: $msg")
        assertTrue("CrtpOther" in msg, "names the wrong Self type: $msg")
    }

    @Test fun eventfulStoreSubclassPasses() {
        assertTrue(!CrtpEventful().isDisposed)
    }

    @Test fun genericIntermediateBasePasses() {
        assertTrue(!CrtpDerivedFromBase().isDisposed)
    }
}
