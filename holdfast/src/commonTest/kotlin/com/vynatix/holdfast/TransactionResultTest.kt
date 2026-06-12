package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

private class ResultTestStore : Store<ResultTestStore>() {
    val count by state { 0 }
}

class TransactionResultTest {
    private fun success(value: String = "ok"): TransactionResult<String> =
        ResultTestStore() action {
            count mutate 1
            value
        }

    private fun failure(exception: Throwable): TransactionResult<String> =
        ResultTestStore() action {
            count mutate 99
            throw exception
        }

    @Test
    fun getOrThrowOnSuccessReturnsTheBodyValue() {
        assertEquals("ok", success().getOrThrow())
    }

    @Test
    fun getOrThrowOnErrorRethrowsTheOriginalExceptionInstance() {
        val original = IllegalStateException("boom")
        val result = failure(original)
        val thrown = assertFailsWith<IllegalStateException> { result.getOrThrow() }
        assertSame(original, thrown, "getOrThrow must rethrow the exact exception instance, not a wrapper")
    }

    @Test
    fun valueOrNullOnSuccessReturnsTheBodyValue() {
        assertEquals("ok", success().valueOrNull)
    }

    @Test
    fun valueOrNullOnErrorReturnsNull() {
        assertNull(failure(IllegalStateException("boom")).valueOrNull)
    }

    @Test
    fun onErrorRunsBlockWithTheErrorAndReturnsThisOnFailure() {
        val original = IllegalStateException("boom")
        val result = failure(original)
        var seen: TransactionResult.Error? = null

        val returned = result.onError { seen = it }

        assertSame(result, returned, "onError must return the receiver for chaining")
        val error = seen
        assertIs<TransactionResult.Error>(error)
        assertSame(original, error.exception)
    }

    @Test
    fun onErrorSkipsBlockAndReturnsThisOnSuccess() {
        val result = success()
        var called = false

        val returned = result.onError { called = true }

        assertSame(result, returned)
        assertEquals(false, called, "onError block must not run for Success")
    }

    @Test
    fun onSuccessRunsBlockWithTheValueAndReturnsThisOnSuccess() {
        val result = success("payload")
        var seen: String? = null

        val returned = result.onSuccess { seen = it }

        assertSame(result, returned, "onSuccess must return the receiver for chaining")
        assertEquals("payload", seen)
    }

    @Test
    fun onSuccessSkipsBlockAndReturnsThisOnError() {
        val result = failure(IllegalStateException("boom"))
        var called = false

        val returned = result.onSuccess { called = true }

        assertSame(result, returned)
        assertEquals(false, called, "onSuccess block must not run for Error")
    }

    @Test
    fun onSuccessAndOnErrorChainInCallOrderOnSuccess() {
        val calls = mutableListOf<String>()

        val returned =
            success()
                .onError { calls += "error" }
                .onSuccess { calls += "success-1" }
                .onSuccess { calls += "success-2" }

        assertIs<TransactionResult.Success<*>>(returned)
        assertEquals(listOf("success-1", "success-2"), calls)
    }

    @Test
    fun onSuccessAndOnErrorChainInCallOrderOnError() {
        val calls = mutableListOf<String>()

        val returned =
            failure(IllegalStateException("boom"))
                .onSuccess { calls += "success" }
                .onError { calls += "error-1" }
                .onError { calls += "error-2" }

        assertIs<TransactionResult.Error>(returned)
        assertEquals(listOf("error-1", "error-2"), calls)
    }
}
