@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import com.vynatix.holdfast.middleware.LoggingMiddleware
import com.vynatix.holdfast.middleware.ProfilingMiddleware
import com.vynatix.holdfast.middleware.TimingMiddleware
import com.vynatix.holdfast.middleware.ValidationMiddleware
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.tags
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val VAULT_SEED = "suspend-redaction-seed".encodeToByteArray()

private const val TOKEN = "t0ken-5ECRET-21"
private const val PIN = "p1n-5ECRET-22"

private class SuspendCredentialStore : Store<SuspendCredentialStore>() {
    val user by state(codec = StringCodec) { "guest" }
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val pin by state(
        transformer = EncryptingTransformer(XorCipher(VAULT_SEED)),
        codec = StringCodec,
        tags = setOf(StateTag.Secret),
    ) { "" }
}

/** Writes out what its context exposes, and a snapshot of the store: what an audit log would keep. */
private class SuspendAudit(
    private val out: MutableList<String>,
) : Middleware<SuspendCredentialStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<SuspendCredentialStore>) {
        out += context.toString()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<SuspendCredentialStore>) {
        out += context.toString()
        out += context.metadata.toString()
        runCatching { context.transaction.modifiedStates.toString() }.onSuccess { out += it }
        val snapshot = context.store.snapshot(SnapshotScope.Raw)
        out += snapshot.render()
        out += snapshot.encode()
        out += snapshot.toString()
    }

    override fun onTransactionError(
        context: MiddlewareContext<SuspendCredentialStore>,
        error: Throwable,
    ) {
        out += context.toString()
        out += error.toString()
    }
}

private fun SuspendCredentialStore.withEveryMiddleware(out: MutableList<String>): ProfilingMiddleware<SuspendCredentialStore> {
    val profiler = ProfilingMiddleware<SuspendCredentialStore> { out += it.toString() }
    middlewares(
        LoggingMiddleware("suspend-credentials") { out += it },
        TimingMiddleware { id, status, ms -> out += "$id $status $ms" },
        ValidationMiddleware { require(user.value != "banned") { "user is banned" } },
        SuspendAudit(out),
        profiler,
    )
    return profiler
}

/**
 * A Secret state's value appears in no middleware output under
 * `suspendAction` and `suspendAtomic` (issue #20, R3, acceptance 1,
 * middleware part), and a `suspendDerived` of a Secret state is Secret.
 */
class SecretRedactionSuspendTest {
    private val secrets = listOf(TOKEN, PIN, XorCipher(VAULT_SEED).encrypt(PIN))

    private fun assertNoSecretIn(out: List<String>) {
        assertTrue(out.isNotEmpty(), "the middlewares saw the transactions")
        out.forEach { line -> secrets.forEach { secret -> assertFalse(secret in line, "a middleware wrote a secret: $line") } }
    }

    @Test fun suspendActionsNeverExposeASecret() =
        runBlocking {
            val out = mutableListOf<String>()
            val store = SuspendCredentialStore()
            val profiler = store.withEveryMiddleware(out)

            store
                .suspendAction {
                    token mutate TOKEN
                    delay(1)
                    pin mutate PIN
                }.getOrThrow()
            // The validation rejects this; suspendAction isolates middleware
            // hooks, so whether it commits is not this test's concern — only
            // what the middleware wrote.
            store.suspendAction {
                token mutate "$TOKEN-2"
                user mutate "banned"
            }
            assertIs<TransactionResult.Error>(
                store.suspendAction {
                    pin mutate "$PIN-2"
                    error("the body fails")
                },
            )
            store.suspendAction { user mutate "ada" }.getOrThrow()

            out += profiler.profile().toString()
            assertNoSecretIn(out)
        }

    @Test fun suspendAtomicFramesNeverExposeASecret() =
        runBlocking {
            val out = mutableListOf<String>()
            val a = SuspendCredentialStore()
            val b = SuspendCredentialStore()
            val profilers = listOf(a.withEveryMiddleware(out), b.withEveryMiddleware(out))

            suspendAtomic(a, b) {
                a { token mutate TOKEN }
                delay(1)
                b { pin mutate PIN }
            }.getOrThrow()
            assertIs<TransactionResult.Error>(
                suspendAtomic(a, b) {
                    a { pin mutate "$PIN-2" }
                    b { user mutate "banned" }
                },
            )
            assertIs<TransactionResult.Error>(
                suspendAtomic(a, b) {
                    b { token mutate "$TOKEN-2" }
                    error("the frame fails")
                },
            )

            profilers.forEach { out += it.profile().toString() }
            assertNoSecretIn(out)
        }

    @Test fun aSuspendDerivedOfASecretIsSecret() {
        val store = SuspendCredentialStore()
        val (tokenLength, disposable) = store.suspendDerived(store.token) { token.value.length }
        val (userLength, other) = store.suspendDerived(store.user) { user.value.length }
        try {
            assertEquals(setOf(StateTag.Secret), tokenLength.tags)
            assertEquals(emptySet(), userLength.tags)
        } finally {
            disposable.dispose()
            other.dispose()
        }
    }
}
