package com.vynatix.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Asymmetric: stored value is X, getter returns X+1. Used to verify that rollback
 * does not drift the reading invariant under transformer mishandling.
 */
private class IncrementOnGetTransformer : Transformer<Int> {
    override fun set(value: Int): Int = value
    override fun get(value: Int): Int = value + 1
}

/**
 * Symmetric idempotent: setter uppercases, getter lowercases. uppercase(lowercase(x))
 * preserves the reading view of x even after a setter-then-getter round-trip.
 */
private class CaseFlippingTransformer : Transformer<String> {
    override fun set(value: String): String = value.uppercase()
    override fun get(value: String): String = value.lowercase()
}

/**
 * Identity transformer that counts how many times each side runs. Used to verify
 * the architecture doesn't make extra calls under mutate/commit/rollback.
 */
private class CountingTransformer : Transformer<Int> {
    var setCalls = 0
    var getCalls = 0
    override fun set(value: Int): Int {
        setCalls++
        return value
    }
    override fun get(value: Int): Int {
        getCalls++
        return value
    }
}

private class AsymmetricTransformerVault : Vault<AsymmetricTransformerVault>() {
    val n by state(IncrementOnGetTransformer()) { 0 }
    val s by state(CaseFlippingTransformer()) { "hello" }
}

private class CountingTransformerVault(val transformer: CountingTransformer) : Vault<CountingTransformerVault>() {
    val n by state(transformer) { 0 }
}

class TransformerCallCountTest {

    @Test
    fun singleMutateInvokesTransformerSetExactlyOnce() {
        val tr = CountingTransformer()
        val v = CountingTransformerVault(tr)
        tr.setCalls = 0
        tr.getCalls = 0

        v action { n mutate 5 }

        assertEquals(
            1,
            tr.setCalls,
            "expected one transformer.set call per mutate, got ${tr.setCalls}",
        )
    }

    @Test
    fun mutateDoesNotInvokeTransformerGetForBookkeeping() {
        val tr = CountingTransformer()
        val v = CountingTransformerVault(tr)
        tr.setCalls = 0
        tr.getCalls = 0

        v action { n mutate 5 }

        assertTrue(
            tr.getCalls <= 1,
            "transformer.get was called ${tr.getCalls} times for a single mutate; " +
                "expected ≤ 1 (no internal bookkeeping reads)",
        )
    }
}

class TransformerRollbackTest {

    @Test
    fun rolledBackActionPreservesReadingViewUnderAsymmetricTransformer() {
        val v = AsymmetricTransformerVault()

        val before = v.n.value
        assertEquals(1, before, "initial: stored 0, getter returns 0+1=1")

        val result = v action {
            n mutate 50
            error("rollback")
        }
        assertIs<TransactionResult.Error>(result)

        val after = v.n.value
        assertEquals(
            before,
            after,
            "rollback must preserve the getter's view; before=$before, after=$after",
        )
    }

    @Test
    fun rolledBackActionPreservesReadingViewUnderSymmetricCaseFlippingTransformer() {
        val v = AsymmetricTransformerVault()
        val before = v.s.value
        assertEquals("hello", before)

        v action {
            s mutate "world"
            error("rollback")
        }
        assertEquals(
            before,
            v.s.value,
            "case-flipping transformer + rollback must preserve reading view",
        )
    }

    @Test
    fun rolledBackActionPreservesReadingViewUnderSymmetricCountingTransformer() {
        val tr = CountingTransformer()
        val v = CountingTransformerVault(tr)
        v action { n mutate 10 }

        val before = v.n.value
        val setCallsBeforeRollback = tr.setCalls

        v action {
            n mutate 99
            error("rollback")
        }

        val after = v.n.value
        assertEquals(
            before,
            after,
            "rollback didn't preserve reading. before=$before, after=$after, " +
                "setCalls during rollback=${tr.setCalls - setCallsBeforeRollback}",
        )
    }
}

class TransformerEffectConsistencyTest {

    @Test
    fun initialEffectCallbackMatchesValueGetterUnderTransformer() {
        val v = AsymmetricTransformerVault()
        val seen = mutableListOf<Int>()
        val disposable = v { n effect { seen.add(this) } }

        val viaGetter = v.n.value
        assertEquals(
            listOf(viaGetter),
            seen,
            "initial effect callback gave $seen; value getter gave $viaGetter; must agree",
        )
        disposable.dispose()
    }

    @Test
    fun effectCallbackAfterCommitMatchesValueGetterUnderTransformer() {
        val v = AsymmetricTransformerVault()
        val seen = mutableListOf<Int>()
        val disposable = v { n effect { seen.add(this) } }
        seen.clear()

        v action { n mutate 10 }

        val viaGetter = v.n.value
        assertEquals(
            listOf(viaGetter),
            seen,
            "post-commit effect callback gave $seen; value getter gave $viaGetter; must agree",
        )
        disposable.dispose()
    }
}

private class ConditionalTransformer : Transformer<Int> {
    var setCalls = 0
    var getCalls = 0
    var shouldTransformCalls = 0
    override fun set(value: Int): Int {
        setCalls++
        return value * -1
    }
    override fun get(value: Int): Int {
        getCalls++
        return value * -1
    }
    override fun shouldTransform(value: Int): Boolean {
        shouldTransformCalls++
        return false
    }
}

private class ConditionalTransformerVault(val transformer: ConditionalTransformer) : Vault<ConditionalTransformerVault>() {
    val n by state(transformer) { 7 }
}

private class ThrowingSetTransformer : Transformer<Int> {
    override fun set(value: Int): Int = throw RuntimeException("set refused")
    override fun get(value: Int): Int = value
}

private class ThrowingSetVault : Vault<ThrowingSetVault>() {
    val n by state(ThrowingSetTransformer()) { 0 }
}

private class ThrowingGetTransformer : Transformer<Int> {
    override fun set(value: Int): Int = value
    override fun get(value: Int): Int = throw RuntimeException("get refused")
}

private class ThrowingGetVault : Vault<ThrowingGetVault>() {
    val n by state(ThrowingGetTransformer()) { 0 }
}

private class NullTransformerVault : Vault<NullTransformerVault>() {
    val n by state(transformer = null) { 0 }
}

private class TimingTransformer : Transformer<Int> {
    val calls = mutableListOf<String>()
    override fun set(value: Int): Int {
        calls.add("set:$value")
        return value
    }
    override fun get(value: Int): Int {
        calls.add("get:$value")
        return value
    }
}

private class TimingTransformerVault(val transformer: TimingTransformer) : Vault<TimingTransformerVault>() {
    val n by state(transformer) { 0 }
}

class TransformerEdgeCaseTest {

    @Test
    fun transformerWhoseShouldTransformReturnsFalseBypassesSetAndGet() {
        val tr = ConditionalTransformer()
        val v = ConditionalTransformerVault(tr)

        // Read: shouldTransform() consulted; returns false → no get() call.
        val firstRead = v.n.value
        assertEquals(7, firstRead, "value passes through when shouldTransform returns false")
        assertEquals(0, tr.getCalls, "get must not run when shouldTransform returned false")

        // Mutate: shouldTransform() consulted; returns false → no set() call.
        v action { n mutate 21 }
        assertEquals(21, v.n.value, "raw value stored when shouldTransform returns false")
        assertEquals(0, tr.setCalls, "set must not run when shouldTransform returned false")
        assertTrue(tr.shouldTransformCalls > 0, "shouldTransform must be consulted")
    }

    @Test
    fun transformerThrowingInSetCausesMutateToFailAndActionToRollBack() {
        val v = ThrowingSetVault()
        val initial = v.n.value
        assertEquals(0, initial)

        val result = v action { n mutate 5 }

        assertIs<TransactionResult.Error>(result)
        assertEquals("set refused", result.exception.message)
        assertEquals(initial, v.n.value, "state must remain unchanged when transformer.set throws")
    }

    @Test
    fun transformerThrowingInGetCausesValueReadToFail() {
        val v = ThrowingGetVault()
        val ex = assertFailsWith<RuntimeException> { v.n.value }
        assertEquals("get refused", ex.message)
    }

    @Test
    fun nullTransformerOnStateActsAsIdentity() {
        val v = NullTransformerVault()
        assertEquals(0, v.n.value)
        v action { n mutate 100 }
        assertEquals(100, v.n.value, "null transformer is identity in both directions")
    }

    @Test
    fun transformerSetIsAppliedAtMutateNotAtCommit() {
        val tr = TimingTransformer()
        val v = TimingTransformerVault(tr)
        tr.calls.clear()

        v action {
            n mutate 5
            // After mutate, set must have already run; nothing else fires set during commit.
            assertTrue(tr.calls.contains("set:5"), "set must run at mutate time; calls=${tr.calls}")
        }

        val setCallsAfterAction = tr.calls.count { it.startsWith("set:") }
        assertEquals(1, setCallsAfterAction, "exactly one set call across the action")
    }

    @Test
    fun transformerGetIsAppliedAtReadNotAtCommit() {
        val tr = TimingTransformer()
        val v = TimingTransformerVault(tr)
        v action { n mutate 5 }
        val getCallsAfterCommit = tr.calls.count { it.startsWith("get:") }

        // No reads done in the action; get should only run when state.value is invoked.
        v.n.value
        v.n.value
        v.n.value

        val getCallsAfterReads = tr.calls.count { it.startsWith("get:") }
        assertEquals(
            getCallsAfterCommit + 3,
            getCallsAfterReads,
            "get must run once per value read, not at commit time",
        )
    }
}
