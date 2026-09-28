@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import com.vynatix.holdfast.middleware.LoggingMiddleware
import com.vynatix.holdfast.middleware.ProfilingMiddleware
import com.vynatix.holdfast.middleware.TimingMiddleware
import com.vynatix.holdfast.middleware.ValidationMiddleware
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val CREDENTIAL_SEED = "secret-redaction-seed".encodeToByteArray()

private const val API_TOKEN = "api-T0KEN-4417"
private const val PASSCODE = "pass-C0DE-9923"
private const val SESSION = "sess-1D-3108"

private class CredentialStore : Store<CredentialStore>() {
    val user by state(codec = StringCodec) { "guest" }
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val passcode by state(
        transformer = EncryptingTransformer(XorCipher(CREDENTIAL_SEED)),
        codec = StringCodec,
        tags = setOf(StateTag.Secret),
    ) { "" }
    val session by state(codec = StringCodec, tags = setOf(StateTag.Secret, StateTag.Remote)) { "" }
}

/**
 * What a middleware can reach through its context, written out: the context
 * itself, its metadata, the transaction's modified states, and a snapshot of
 * the store rendered, encoded and printed — what an audit log would keep.
 */
private class AuditMiddleware(
    private val out: MutableList<String>,
) : Middleware<CredentialStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<CredentialStore>) {
        out += context.toString()
        out += context.metadata.toString()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<CredentialStore>) {
        out += context.toString()
        out += context.metadata.toString()
        out += context.transaction.modifiedStates.toString()
        val snapshot = context.store.snapshot()
        out += snapshot.render()
        out += snapshot.encode(includeRemote = true)
        out += snapshot.toString()
        out += context.store.snapshot(SnapshotScope.Raw).render()
    }

    override fun onTransactionError(
        context: MiddlewareContext<CredentialStore>,
        error: Throwable,
    ) {
        out += context.toString()
        out += error.toString()
    }
}

/** Every built-in middleware, plus an audit of the context, on one store; all output lands in [out]. */
private fun CredentialStore.withEveryMiddleware(out: MutableList<String>): ProfilingMiddleware<CredentialStore> {
    val profiler = ProfilingMiddleware<CredentialStore> { out += it.toString() }
    middlewares(
        LoggingMiddleware("credentials") { out += it },
        TimingMiddleware { id, status, ms -> out += "$id $status $ms" },
        ValidationMiddleware { require(user.value != "banned") { "user is banned" } },
        AuditMiddleware(out),
        profiler,
    )
    return profiler
}

/**
 * A Secret state's value appears in no middleware output and nothing a
 * middleware context exposes (issue #20, R3, acceptance 1, middleware part):
 * every built-in middleware, through blocking actions — committed, rolled
 * back, restore, sterile restore, reset, a bare `mutate` — and `atomic`
 * frames.
 */
class SecretRedactionMiddlewareTest {
    private val secrets = listOf(API_TOKEN, PASSCODE, SESSION, XorCipher(CREDENTIAL_SEED).encrypt(PASSCODE))

    private fun assertNoSecretIn(out: List<String>) {
        assertTrue(out.isNotEmpty(), "the middlewares saw the transactions")
        for (line in out) {
            secrets.forEach { secret -> assertFalse(secret in line, "a middleware wrote a secret: $line") }
        }
    }

    private fun CredentialStore.writeSecrets() =
        action {
            user mutate "ada"
            token mutate API_TOKEN
            passcode mutate PASSCODE
            session mutate SESSION
        }

    @Test fun blockingActionsNeverExposeASecret() {
        val out = mutableListOf<String>()
        val store = CredentialStore()
        val profiler = store.withEveryMiddleware(out)

        store.writeSecrets().getOrThrow()
        val undo = store.snapshot()
        store { token mutate "rotated-$API_TOKEN" }
        assertIs<TransactionResult.Error>(
            store action {
                token mutate API_TOKEN
                user mutate "banned"
            },
        )
        assertIs<TransactionResult.Error>(
            store action {
                passcode mutate PASSCODE
                error("the body fails")
            },
        )
        store.restore(undo).getOrThrow()
        store.restore(undo, RestorePolicy.Strict, sterile = true).getOrThrow()
        // Rejected (the passcode's value is of the wrong class): the failure names states, never values.
        val rejected = StoreSnapshot(mapOf("token" to API_TOKEN, "passcode" to 7))
        assertIs<TransactionResult.Error>(store.restore(rejected, RestorePolicy.Strict))
        store.reset().getOrThrow()
        store.writeSecrets().getOrThrow()

        out += profiler.profile().toString()
        assertNoSecretIn(out)
        assertTrue(out.any { "Restore" in it } && out.any { "Reset" in it }, "restore and reset were observed")
    }

    @Test fun atomicFramesNeverExposeASecret() {
        val out = mutableListOf<String>()
        val a = CredentialStore()
        val b = CredentialStore()
        val profilers = listOf(a.withEveryMiddleware(out), b.withEveryMiddleware(out))

        atomic(a, b) {
            a.writeSecrets().getOrThrow()
            b { token mutate API_TOKEN }
            b { passcode mutate PASSCODE }
        }.getOrThrow()
        assertIs<TransactionResult.Error>(
            atomic(a, b) {
                a { session mutate "$SESSION-2" }
                b { user mutate "banned" }
            },
        )
        assertIs<TransactionResult.Error>(
            atomic(a, b) {
                b { passcode mutate "$PASSCODE-2" }
                error("the frame fails")
            },
        )
        val undo = a.snapshot()
        atomic(a, b) {
            a.restore(undo, RestorePolicy.Strict, sterile = true).getOrThrow()
            b.reset().getOrThrow()
        }.getOrThrow()

        profilers.forEach { out += it.profile().toString() }
        assertNoSecretIn(out)
    }
}
