@file:Suppress("MagicNumber", "TooManyFunctions", "LargeClass", "LongMethod", "TopLevelPropertyNaming")

/*
 * BankingDemo — a single end-to-end exercise of every public Vault API.
 *
 * This file is read top-to-bottom as a tutorial and runs as a test:
 *
 *   1) Domain types          — enum + data classes for the banking model
 *   2) Transformers          — symmetric (Email), asymmetric-by-shouldTransform (Name),
 *                              and EncryptingTransformer (taxId, store-encrypted)
 *   3) Vaults                — AccountVault (8 states) + BankVault (cross-vault read source)
 *   4) Middleware            — Tracing (all 3 hooks + metadata bag), StatusGuard
 *                              (cross-vault read on start), DailyLimit (post-body validation
 *                              via read-your-own-writes)
 *   5) Bridges               — BalancePersistence (save-on-commit + load-on-attach
 *                              two-way Bridge<T>) and AdminStatusChannel (inbound-only
 *                              Observable<T> consumed via observeFrom)
 *   6) Domain operations     — deposit, withdraw, transferTo (cross-vault atomic),
 *                              applyMonthEnd (savepoints)
 *   7) The end-to-end demo   — sixteen phases that touch every public symbol +
 *                              focused 1.1 feature tests (encryption, FileSystemKvStore,
 *                              snapshot/restore, derived state, cross-vault atomic)
 *
 * Coverage checklist:
 *   Vault<Self>, state, state(transformer), action, mutate, effect, bridge,
 *   middlewares, clearMiddleware, invoke, activeTransaction, properties, getState,
 *   hasState, removeState, clearStates, Transaction.commit/rollback (idempotent),
 *   Transaction.id/status/parent/endTime, TransactionResult.Success/Error,
 *   TransactionStatus, Middleware (all hooks + metadata), Bridge/Observable/Publisher,
 *   Transformer.set/get/shouldTransform, Disposable, kotlin.uuid.Uuid (used for
 *   ledger entry IDs), nested savepoint actions, cross-vault rejection,
 *   concurrent commits, EncryptingTransformer + XorCipher, FileSystemKvStore +
 *   KvBridge, Vault.snapshot()/restore(), Vault.derived(...) push-recomputed,
 *   atomic(...) cross-vault transactions.
 *   (suspendAction is exercised in vault-coroutines/.../SuspendActionTest.kt.)
 */

package com.vynatix.vault.demo

import com.vynatix.vault.Bridge
import com.vynatix.vault.Disposable
import com.vynatix.vault.Middleware
import com.vynatix.vault.MutableState
import com.vynatix.vault.Observable
import com.vynatix.vault.State
import com.vynatix.vault.Transaction
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.TransactionStatus
import com.vynatix.vault.Transformer
import com.vynatix.vault.Vault
import com.vynatix.vault.atomic
import com.vynatix.vault.bridge.InMemoryKvStore
import com.vynatix.vault.bridge.KvBridge
import com.vynatix.vault.bridge.LongCodec
import com.vynatix.vault.bridge.StringCodec
import com.vynatix.vault.crypto.EncryptingTransformer
import com.vynatix.vault.crypto.XorCipher
import com.vynatix.vault.derived
import com.vynatix.vault.middleware.LoggingMiddleware
import com.vynatix.vault.middleware.TimingMiddleware
import com.vynatix.vault.middleware.ValidationMiddleware
import com.vynatix.vault.restore
import com.vynatix.vault.snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ════════════════════════════════════════════════════════════════════════════════
// 1. DOMAIN TYPES
// ════════════════════════════════════════════════════════════════════════════════

private enum class AccountStatus { Active, Frozen, Closed }

private data class LedgerEntry(
    val id: String,
    val timestampMs: Long,
    val description: String,
    val deltaCents: Long,
    val resultingBalanceCents: Long,
)

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
private fun randomLedgerId(): String = Uuid.random().toString()

// ════════════════════════════════════════════════════════════════════════════════
// 2. TRANSFORMERS — normalize values transparently on write/read
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Symmetric transformer: a user types whatever; we store one canonical form;
 * we read it back as stored. `set` does the work; `get` is identity.
 */
private class EmailNormalizer : Transformer<String> {
    override fun set(value: String): String = value.trim().lowercase()
    override fun get(value: String): String = value
}

/**
 * Demonstrates `shouldTransform`: the empty initial value passes through unchanged
 * (it would otherwise be an empty trimmed-titled string, which is a wash but
 * conceptually "we haven't been told a name yet, don't make one up"). Real names
 * are trimmed, collapsed, and Title-Cased.
 */
private class NameNormalizer : Transformer<String> {
    override fun shouldTransform(value: String): Boolean = value.isNotEmpty()
    override fun set(value: String): String = value
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
    override fun get(value: String): String = value
}

// ════════════════════════════════════════════════════════════════════════════════
// 3. VAULTS
// ════════════════════════════════════════════════════════════════════════════════

/**
 * One account's full state. Seven states cover every shape the library handles:
 *  - primitive Long          (balanceCents, dailyWithdrawnCents, lastTransactionAtMs)
 *  - enum                    (status)
 *  - normalized strings      (holderName via NameNormalizer; email via EmailNormalizer)
 *  - append-only collection  (ledger)
 */
private val DEMO_CIPHER_SEED = "demo-bank-key-not-production".encodeToByteArray()

private class AccountVault(
    val accountId: String,
    initialHolderName: String = "",
    initialEmail: String = "",
    initialBalanceCents: Long = 0,
) : Vault<AccountVault>() {
    val balanceCents by state { initialBalanceCents }
    val status by state { AccountStatus.Active }
    val holderName by state(NameNormalizer()) { initialHolderName }
    val email by state(EmailNormalizer()) { initialEmail }
    val ledger by state { emptyList<LedgerEntry>() }
    val dailyWithdrawnCents by state { 0L }
    val lastTransactionAtMs by state { 0L }

    /**
     * Encrypted-at-rest sensitive identifier. The stored `currentValue` is
     * ciphertext; reads via `taxId.value` return plaintext through
     * `EncryptingTransformer.get`. Audit middleware that snapshots
     * `pendingWrites` sees ciphertext too. (See Phase J + the
     * `encryptingTransformerProtectsTaxIdAtRest` test below.)
     */
    val taxId by state(EncryptingTransformer(XorCipher(DEMO_CIPHER_SEED))) { "" }
}

/**
 * System-wide state. AccountStatusGuard middleware reads `emergencyLockdown` from
 * here on every account action — a clean demonstration that one vault can drive
 * cross-cutting policy in another.
 */
private class BankVault : Vault<BankVault>() {
    val emergencyLockdown by state { false }
    val accountsCreated by state { 0 }
}

// ════════════════════════════════════════════════════════════════════════════════
// 4. MIDDLEWARE — cross-cutting concerns
// ════════════════════════════════════════════════════════════════════════════════

private sealed class TraceEvent {
    data class Started(val txnId: String, val parentId: String?) : TraceEvent()
    data class Completed(val txnId: String, val status: TransactionStatus, val elapsedMs: Long) : TraceEvent()
    data class Errored(val txnId: String, val message: String, val elapsedMs: Long) : TraceEvent()
}

/**
 * Records every transaction's lifecycle. Demonstrates:
 *  - all three hooks (started, completed, error)
 *  - the per-transaction `metadata` bag (start time stashed in `started`, read in
 *    `completed` / `error` to compute elapsed)
 *  - reading `Transaction.id`, `status`, `parent` to capture the savepoint chain
 */
private class TracingMiddleware<V : Vault<V>>(private val sink: MutableList<TraceEvent>) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        context.metadata[KEY_START_MS] = nowMs()
        sink.add(TraceEvent.Started(context.transaction.id, context.transaction.parent?.id))
    }
    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        sink.add(
            TraceEvent.Completed(
                context.transaction.id,
                context.transaction.status,
                elapsedMs(context),
            ),
        )
    }
    override fun onTransactionError(context: MiddlewareContext<V>, error: Throwable) {
        sink.add(
            TraceEvent.Errored(
                context.transaction.id,
                error.message ?: error::class.simpleName ?: "?",
                elapsedMs(context),
            ),
        )
    }
    private fun elapsedMs(context: MiddlewareContext<V>): Long {
        val start = context.metadata[KEY_START_MS] as? Long ?: return -1L
        return nowMs() - start
    }
    private companion object {
        const val KEY_START_MS = "tracing.startMs"
    }
}

/**
 * Refuses *any* mutation when:
 *  - the system-wide emergency lockdown flag is set (cross-vault read), or
 *  - the account has been Closed (terminal status).
 *
 * Note that Frozen is intentionally NOT blocked here — administrative status
 * changes (freeze/unfreeze) are per-operation actions that need to flip status,
 * and a guard that blocks all mutations on Frozen would also block unfreeze.
 * Functional checks (`require(status.value == Active)`) belong in the operations
 * that need them; the middleware is for system-level kill switches.
 */
private class AccountStatusGuard(private val bank: BankVault) : Middleware<AccountVault>() {
    override fun onTransactionStarted(context: MiddlewareContext<AccountVault>) {
        check(!bank.emergencyLockdown.value) { "system lockdown — no mutations allowed" }
        check(context.vault.status.value != AccountStatus.Closed) { "account is closed" }
    }
}

/**
 * Caps the account's running daily debits. Demonstrates *post-body* validation:
 * the body has finished but commit hasn't happened yet, so reads of the vault
 * (on the owner thread) see the pending writes via the read-your-own-writes
 * path. If the cap is exceeded, throwing here aborts the action — pending
 * writes are dropped, observers see nothing.
 */
private class DailyWithdrawalLimit(private val limitCents: Long) : Middleware<AccountVault>() {
    override fun onTransactionCompleted(context: MiddlewareContext<AccountVault>) {
        val withdrawn = context.vault.dailyWithdrawnCents.value
        check(withdrawn <= limitCents) {
            "daily withdrawal limit exceeded: $withdrawn > $limitCents"
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// 5. BRIDGES — external sync
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Persistence in this demo is the stdlib `KvBridge` over `InMemoryKvStore`
 * (and `FileSystemKvStore` in the dedicated 1.1 test below). The earlier
 * versions of this demo shipped a hand-rolled `BalancePersistenceBridge`;
 * since 1.1 the same wiring is one line: `state bridge KvBridge(kv, key, codec)`.
 *
 * Inbound-only push channel: an "admin console" can flip an account's status
 * without going through `vault action { ... }`. Unlike a [Bridge] (two-way),
 * this only implements [Observable] — the vault binds to it via `state observeFrom obs`
 * and the channel fires its registered observers when admin code calls [adminPushes].
 */
private class AdminStatusChannel : Observable<AccountStatus> {
    private val callbacks = mutableListOf<(AccountStatus) -> Unit>()
    override fun observe(observer: (AccountStatus) -> Unit): Disposable {
        callbacks.add(observer)
        return Disposable { callbacks.remove(observer) }
    }
    fun adminPushes(status: AccountStatus) {
        callbacks.toList().forEach { it(status) }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// 6. DOMAIN OPERATIONS — application-level functions over AccountVault
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Action returning a [LedgerEntry] for read-after-action use. Demonstrates the
 * generic `action<R>` form: `TransactionResult.Success.value` carries the entry.
 */
private fun AccountVault.deposit(cents: Long, description: String): TransactionResult<LedgerEntry> = action {
    require(cents > 0) { "amount must be positive" }
    require(status.value == AccountStatus.Active) { "account not Active" }
    balanceCents update { it + cents }
    val entry = LedgerEntry(
        id = randomLedgerId(),
        timestampMs = nowMs(),
        description = description,
        deltaCents = cents,
        resultingBalanceCents = balanceCents.value,
    )
    ledger update { it + entry }
    lastTransactionAtMs mutate nowMs()
    entry // body's return value → TransactionResult.Success.value
}

private fun AccountVault.withdraw(cents: Long, description: String): TransactionResult<LedgerEntry> = action {
    require(cents > 0) { "amount must be positive" }
    require(status.value == AccountStatus.Active) { "account not Active" }
    require(balanceCents.value >= cents) { "insufficient funds" }
    balanceCents update { it - cents }
    dailyWithdrawnCents update { it + cents }
    val entry = LedgerEntry(
        id = randomLedgerId(),
        timestampMs = nowMs(),
        description = description,
        deltaCents = -cents,
        resultingBalanceCents = balanceCents.value,
    )
    ledger update { it + entry }
    lastTransactionAtMs mutate nowMs()
    entry
}

@Suppress("unused") // demo-domain operations exposed for completeness; exercised in
// `accountStatusTransitionsViaFreezeUnfreezeAndClose` below.
private fun AccountVault.freeze(): TransactionResult<Unit> = action { status mutate AccountStatus.Frozen }

@Suppress("unused")
private fun AccountVault.unfreeze(): TransactionResult<Unit> = action { status mutate AccountStatus.Active }

@Suppress("unused")
private fun AccountVault.close(): TransactionResult<Unit> = action { status mutate AccountStatus.Closed }

/**
 * Cross-vault transfer using `atomic(...)`: a single bracketed action across
 * both vaults that commits-or-rollbacks together. If the credit side throws
 * (e.g., the peer is Frozen, or a middleware rejects), `atomic` rolls back
 * the debit too — no hand-rolled compensation needed.
 *
 * Phase O of the 1.1 plan; the previous version of this function had to
 * reverse the debit manually when the credit failed, because each vault held
 * its own independent lock. `atomic` sorts the vaults by `lockOrderKey`,
 * acquires both locks deadlock-safely, and treats inner `vault.action {}` as
 * a savepoint of the cross-vault root.
 */
private fun AccountVault.transferTo(other: AccountVault, cents: Long, memo: String): TransactionResult<LedgerEntry> = atomic(this, other) {
    val debit = withdraw(cents, "transfer to ${other.accountId}: $memo")
    if (debit is TransactionResult.Error) throw debit.exception
    val credit = other.deposit(cents, "transfer from $accountId: $memo")
    if (credit is TransactionResult.Error) throw credit.exception
    // Both succeeded; the debit-side ledger entry is canonical for the caller.
    (debit as TransactionResult.Success<LedgerEntry>).value
}

/**
 * Month-end accounting. The OUTER action's body invokes two NESTED actions
 * (interest accrual, fee deduction) and a final reconciliation. Each nested
 * action is a savepoint — its pending writes merge into the outer's on commit.
 * If the reconciliation throws, the outer rolls back and BOTH the interest
 * and the fee writes are discarded together.
 */
private fun AccountVault.applyMonthEnd(interestBps: Int, monthlyFeeCents: Long): TransactionResult<Unit> = action {
    // Step 1 — interest accrual (savepoint 1)
    action {
        val interest = balanceCents.value * interestBps / 10_000
        balanceCents update { it + interest }
        ledger update {
            it + LedgerEntry(
                id = randomLedgerId(),
                timestampMs = nowMs(),
                description = "monthly interest @ $interestBps bps",
                deltaCents = interest,
                resultingBalanceCents = balanceCents.value,
            )
        }
    }

    // Step 2 — monthly fee (savepoint 2)
    action {
        balanceCents update { it - monthlyFeeCents }
        ledger update {
            it + LedgerEntry(
                id = randomLedgerId(),
                timestampMs = nowMs(),
                description = "monthly fee",
                deltaCents = -monthlyFeeCents,
                resultingBalanceCents = balanceCents.value,
            )
        }
    }

    // Step 3 — reconciliation; outer-level reject. Pending writes from both
    // savepoints have merged into this outer transaction; throwing here drops
    // everything atomically.
    check(balanceCents.value >= 0) { "month-end would overdraft" }
}

// ════════════════════════════════════════════════════════════════════════════════
// 7. THE DEMO — an end-to-end scenario verifying every API path
// ════════════════════════════════════════════════════════════════════════════════

class BankingDemo {

    @Test
    fun fullEndToEndScenario() = runBlocking {
        // ────────────────────────────────────────────────────────────────────
        // Phase 1 — Bootstrap: build vaults, install middleware
        // ────────────────────────────────────────────────────────────────────
        val bank = BankVault()
        val trace = mutableListOf<TraceEvent>()

        val alice = AccountVault(accountId = "ACC-001", initialHolderName = "", initialEmail = "")
        val bob = AccountVault(accountId = "ACC-002", initialHolderName = "Bob Builder", initialEmail = "bob@example.com")

        // Install three middlewares on alice. Order matters: started runs left-to-right
        // (Tracing first), completed/error run right-to-left as the chain unwinds.
        alice.middlewares(
            TracingMiddleware(trace),
            AccountStatusGuard(bank),
            DailyWithdrawalLimit(limitCents = 100_000), // $1,000/day
        )
        bob.middlewares(TracingMiddleware(trace))
        bank.middlewares(TracingMiddleware(trace))

        bank action { accountsCreated update { it + 2 } }
        assertEquals(2, bank.accountsCreated.value)
        assertNull(alice.activeTransaction, "no in-flight txn after action returns")

        // Initial state assertions before anything mutates the accounts.
        assertEquals(0L, alice.balanceCents.value)
        assertEquals(AccountStatus.Active, alice.status.value)
        assertEquals(emptyList(), alice.ledger.value)
        // Name transformer's shouldTransform skipped the empty initial value.
        assertEquals("", alice.holderName.value)
        // Bob's initial values were transformed: NameNormalizer titled, EmailNormalizer lowered.
        assertEquals("Bob Builder", bob.holderName.value)
        assertEquals("bob@example.com", bob.email.value)

        // ────────────────────────────────────────────────────────────────────
        // Phase 2 — Subscribe with effects (initial-fire on subscribe)
        // ────────────────────────────────────────────────────────────────────
        val balanceUpdates = mutableListOf<Long>()
        val statusUpdates = mutableListOf<AccountStatus>()
        val balanceSub: Disposable = alice { balanceCents effect { balanceUpdates.add(this) } }
        val statusSub: Disposable = alice { status effect { statusUpdates.add(this) } }
        // Each subscribe fires once with the current value.
        assertEquals(listOf(0L), balanceUpdates)
        assertEquals(listOf(AccountStatus.Active), statusUpdates)

        // ────────────────────────────────────────────────────────────────────
        // Phase 3 — Atomic deposit: multi-state action commits as a unit
        // ────────────────────────────────────────────────────────────────────
        val openingDeposit = alice.deposit(200_000, "opening deposit") // $2,000
        assertIs<TransactionResult.Success<*>>(openingDeposit)
        assertEquals(TransactionStatus.Committed, openingDeposit.transaction.status)
        assertNotNull(openingDeposit.transaction.endTime)
        assertEquals(200_000L, alice.balanceCents.value)
        assertEquals(1, alice.ledger.value.size)
        // Balance observer fires once for this commit; status didn't change so its observer is silent.
        assertEquals(listOf(0L, 200_000L), balanceUpdates)
        assertEquals(listOf(AccountStatus.Active), statusUpdates)

        // ────────────────────────────────────────────────────────────────────
        // Phase 4 — Successful withdrawal under the daily cap
        // ────────────────────────────────────────────────────────────────────
        val withdrawSmall = alice.withdraw(50_000, "rent")
        assertIs<TransactionResult.Success<*>>(withdrawSmall)
        assertEquals(150_000L, alice.balanceCents.value)
        assertEquals(50_000L, alice.dailyWithdrawnCents.value)
        assertEquals(2, alice.ledger.value.size)
        assertEquals(listOf(0L, 200_000L, 150_000L), balanceUpdates)

        // ────────────────────────────────────────────────────────────────────
        // Phase 5 — Withdrawal over the daily cap rolls back atomically
        // ────────────────────────────────────────────────────────────────────
        val balanceBefore = alice.balanceCents.value
        val ledgerSizeBefore = alice.ledger.value.size
        val balanceUpdatesSize = balanceUpdates.size

        // 50k already withdrawn + 60k more = 110k > 100k cap → DailyWithdrawalLimit throws
        // in onTransactionCompleted, after the body has staged its writes.
        val overLimit = alice.withdraw(60_000, "luxury")
        assertIs<TransactionResult.Error>(overLimit)
        assertEquals(TransactionStatus.RolledBack, overLimit.transaction.status)
        assertEquals(true, overLimit.exception.message?.contains("daily withdrawal limit"))

        // Every staged write was discarded.
        assertEquals(balanceBefore, alice.balanceCents.value)
        assertEquals(50_000L, alice.dailyWithdrawnCents.value)
        assertEquals(ledgerSizeBefore, alice.ledger.value.size)
        // Observers never saw the rolled-back values.
        assertEquals(balanceUpdatesSize, balanceUpdates.size)

        // ────────────────────────────────────────────────────────────────────
        // Phase 6 — Cross-vault transfer (two top-level actions, hand-rolled
        //           compensation if the credit side fails)
        // ────────────────────────────────────────────────────────────────────
        val transferOk = alice.transferTo(bob, 30_000, "pizza fund")
        assertIs<TransactionResult.Success<*>>(transferOk)
        assertEquals(120_000L, alice.balanceCents.value) // 150k - 30k
        assertEquals(80_000L, alice.dailyWithdrawnCents.value) // 50k + 30k, still ≤ 100k cap
        assertEquals(30_000L, bob.balanceCents.value)
        // Alice's ledger has the debit; Bob's has the credit. Both vaults stayed atomic.
        assertEquals(3, alice.ledger.value.size)
        assertEquals(1, bob.ledger.value.size)

        // ────────────────────────────────────────────────────────────────────
        // Phase 7 — Savepoint / nested action: applyMonthEnd
        // ────────────────────────────────────────────────────────────────────
        // 7a — successful month-end: interest + fee both commit through the outer.
        val monthEndOk = bob.applyMonthEnd(interestBps = 50, monthlyFeeCents = 500) // +0.5%, $5 fee
        assertIs<TransactionResult.Success<*>>(monthEndOk)
        // 30,000 + (30,000 * 50 / 10_000) = 30,150 - 500 = 29,650
        assertEquals(29_650L, bob.balanceCents.value)
        assertEquals(3, bob.ledger.value.size, "deposit + interest + fee, all visible after outer commit")

        // 7b — failing month-end: the reconciliation rejects, both savepoints discarded together.
        val poorAccount = AccountVault(accountId = "ACC-POOR", initialBalanceCents = 200) // $2.00
        poorAccount.middlewares(TracingMiddleware(trace))
        val balanceWas = poorAccount.balanceCents.value
        val ledgerWas = poorAccount.ledger.value
        val monthEndBad = poorAccount.applyMonthEnd(interestBps = 50, monthlyFeeCents = 500) // fee wipes balance
        assertIs<TransactionResult.Error>(monthEndBad)
        assertEquals(balanceWas, poorAccount.balanceCents.value, "outer rollback discarded all savepoint writes")
        assertEquals(ledgerWas, poorAccount.ledger.value, "ledger entries from savepoints did not survive outer rollback")

        // The trace records nested savepoints' Started events with parentId = outer's id.
        val poorTrace = trace.filter { it is TraceEvent.Started && it.parentId != null }
        assertTrue(poorTrace.isNotEmpty(), "nested savepoint actions surface in the trace via Transaction.parent")

        // ────────────────────────────────────────────────────────────────────
        // Phase 8 — System-level kill switch via cross-vault middleware read
        // ────────────────────────────────────────────────────────────────────
        bank action { emergencyLockdown mutate true }
        val refusedDuringLockdown = alice.deposit(1, "during lockdown")
        assertIs<TransactionResult.Error>(refusedDuringLockdown)
        assertEquals(refusedDuringLockdown.exception.message?.contains("lockdown"), true)
        bank action { emergencyLockdown mutate false }

        // Lockdown didn't touch alice's state.
        assertEquals(120_000L, alice.balanceCents.value)

        // ────────────────────────────────────────────────────────────────────
        // Phase 9 — Transformers normalize input on write
        // ────────────────────────────────────────────────────────────────────
        alice action {
            holderName mutate "  alice   wonderland  " // trim + collapse + Title Case
            email mutate "  Alice@EXAMPLE.com  " // trim + lowercase
        }
        assertEquals("Alice Wonderland", alice.holderName.value)
        assertEquals("alice@example.com", alice.email.value)
        // shouldTransform stays consistent: another empty mutation passes through.
        alice action { holderName mutate "" }
        assertEquals("", alice.holderName.value)

        // ────────────────────────────────────────────────────────────────────
        // Phase 10 — Bare mutate-outside-action (auto-wrapped in implicit action)
        // ────────────────────────────────────────────────────────────────────
        // Same end-to-end semantics as a one-statement action: middleware fires,
        // observer fires once with the committed value.
        val timestampUpdates = mutableListOf<Long>()
        val tsSub = alice { lastTransactionAtMs effect { timestampUpdates.add(this) } }
        timestampUpdates.clear()
        alice { lastTransactionAtMs mutate 42L }
        assertEquals(42L, alice.lastTransactionAtMs.value)
        assertEquals(listOf(42L), timestampUpdates)
        tsSub.dispose()

        // ────────────────────────────────────────────────────────────────────
        // Phase 11 — Persistence bridge: save-on-commit + load-on-attach
        // ────────────────────────────────────────────────────────────────────
        val kv = InMemoryKvStore()
        val balanceKey = "balance:${alice.accountId}"
        val balanceBridge = KvBridge(kv, balanceKey, LongCodec)
        alice { balanceCents bridge balanceBridge }
        // Trigger one commit to populate the KV.
        alice.deposit(5_000, "post-attach deposit")
        assertEquals(125_000L, alice.balanceCents.value)
        assertEquals("125000", kv.get("balance:${alice.accountId}"))

        // Simulate a process restart: a fresh AccountVault for the same account ID,
        // attaching the SAME bridge → state hydrates from KV via bridge.observe.
        val aliceReborn = AccountVault(accountId = alice.accountId)
        aliceReborn.middlewares(TracingMiddleware(trace))
        aliceReborn { balanceCents bridge balanceBridge }
        assertEquals(125_000L, aliceReborn.balanceCents.value, "balance hydrated from persistence on attach")

        // ────────────────────────────────────────────────────────────────────
        // Phase 12 — Inbound-only Observable<T> via observeFrom (no stub publish)
        // ────────────────────────────────────────────────────────────────────
        val adminChannel = AdminStatusChannel()
        val adminSub: Disposable = alice { status observeFrom adminChannel }
        val statusUpdatesBefore = statusUpdates.size

        adminChannel.adminPushes(AccountStatus.Frozen)
        assertEquals(AccountStatus.Frozen, alice.status.value)
        assertTrue(statusUpdates.size > statusUpdatesBefore, "status observer fired for inbound observable update")
        assertEquals(AccountStatus.Frozen, statusUpdates.last())

        // Operations refuse on Frozen via their own require() (the middleware lets
        // status changes through; the operation enforces business rules).
        val refusedFrozen = alice.deposit(1, "should be refused")
        assertIs<TransactionResult.Error>(refusedFrozen)

        // Admin reactivates and the operation resumes.
        adminChannel.adminPushes(AccountStatus.Active)
        assertEquals(AccountStatus.Active, alice.status.value)
        val resumed = alice.deposit(100, "resumed")
        assertIs<TransactionResult.Success<LedgerEntry>>(resumed)
        // resumed.value is the LedgerEntry created by the deposit — demonstrates action<R>.
        assertEquals(100L, resumed.value.deltaCents)
        adminSub.dispose()

        // ────────────────────────────────────────────────────────────────────
        // Phase 13 — Bridge swap and detach (no further publishes to the old bridge)
        // ────────────────────────────────────────────────────────────────────
        val secondKv = InMemoryKvStore()
        val secondBridge = KvBridge(secondKv, balanceKey, LongCodec)
        alice { balanceCents bridge secondBridge }
        val firstSavedBefore = kv.get("balance:${alice.accountId}")
        alice.deposit(1, "after swap")
        // Old KV did NOT receive the latest publish; new KV did.
        assertEquals(firstSavedBefore, kv.get("balance:${alice.accountId}"))
        assertEquals(alice.balanceCents.value.toString(), secondKv.get("balance:${alice.accountId}"))

        // Detach entirely. The State<T> public type doesn't expose `bridge`; we cast.
        @Suppress("UNCHECKED_CAST")
        alice { (balanceCents as MutableState<Long>).bridge = null }
        val savedBeforeDetach = secondKv.get("balance:${alice.accountId}")
        alice.deposit(1, "after detach")
        assertEquals(savedBeforeDetach, secondKv.get("balance:${alice.accountId}"), "no bridge → no persistence")

        // ────────────────────────────────────────────────────────────────────
        // Phase 14 — Inspection / introspection
        // ────────────────────────────────────────────────────────────────────
        assertTrue(alice.hasState("balanceCents"))
        assertTrue(alice.hasState("status"))
        val balanceState: State<*>? = alice.getState("balanceCents")
        assertNotNull(balanceState)
        assertEquals(alice.balanceCents.value, balanceState.value)
        assertEquals(7, alice.properties.size)
        assertNull(alice.activeTransaction, "no in-flight txn between actions")

        // Capture activeTransaction from inside an action to prove it's non-null only there.
        var seenInside: Transaction? = null
        alice action {
            seenInside = activeTransaction
            // Within an action body, the active transaction's status is Active.
            assertEquals(TransactionStatus.Active, activeTransaction?.status)
        }
        val capturedTxn = assertNotNull(seenInside)
        assertEquals(TransactionStatus.Committed, capturedTxn.status, "after the action returns the txn is Committed")

        // ────────────────────────────────────────────────────────────────────
        // Phase 15 — Cross-vault rejection: a foreign State cannot be mutated
        // ────────────────────────────────────────────────────────────────────
        val foreign: State<Long> = bob.balanceCents
        val bobBalanceBefore = bob.balanceCents.value
        val foreignAttempt = alice action {
            foreign mutate 99_999_999L
        }
        assertIs<TransactionResult.Error>(foreignAttempt)
        assertNotEquals(99_999_999L, bob.balanceCents.value)
        assertEquals(bobBalanceBefore, bob.balanceCents.value)

        // ────────────────────────────────────────────────────────────────────
        // Phase 16 — Idempotent commit/rollback on a finalized transaction
        // ────────────────────────────────────────────────────────────────────
        val noOp = alice.deposit(1, "idempotency probe")
        assertIs<TransactionResult.Success<*>>(noOp)
        assertEquals(TransactionStatus.Committed, noOp.transaction.status)
        // Calling rollback on a Committed txn is a no-op (no state change, no observer fire).
        val balanceSnapshot = alice.balanceCents.value
        val balanceUpdatesSnapshot = balanceUpdates.size
        noOp.transaction.rollback()
        assertEquals(TransactionStatus.Committed, noOp.transaction.status)
        assertEquals(balanceSnapshot, alice.balanceCents.value)
        assertEquals(balanceUpdatesSnapshot, balanceUpdates.size)
        // Calling commit on a Committed txn is also a no-op.
        noOp.transaction.commit()
        assertEquals(TransactionStatus.Committed, noOp.transaction.status)

        // ────────────────────────────────────────────────────────────────────
        // Phase 17 — Concurrency: 4 workers × 100 deposits, single account
        // ────────────────────────────────────────────────────────────────────
        val concurrent = AccountVault(accountId = "ACC-CONC")
        concurrent.middlewares(TracingMiddleware(trace))
        val workers = 4
        val perWorker = 100
        val jobs = List(workers) {
            async(Dispatchers.Default) {
                repeat(perWorker) { concurrent.deposit(1, "stress") }
            }
        }
        jobs.awaitAll()
        assertEquals((workers * perWorker).toLong(), concurrent.balanceCents.value)
        assertEquals(workers * perWorker, concurrent.ledger.value.size)

        // ────────────────────────────────────────────────────────────────────
        // Phase 18 — Cleanup: dispose subscribers, clear middleware, drop states
        // ────────────────────────────────────────────────────────────────────
        balanceSub.dispose()
        statusSub.dispose()
        // Double-dispose is idempotent.
        balanceSub.dispose()

        alice.clearMiddleware()
        // After clearing middleware, the system-lockdown guard is gone — even with
        // bank.emergencyLockdown=true, alice's actions now go through.
        bank action { emergencyLockdown mutate true }
        val postClearMw = alice.deposit(1, "post-clear-middleware")
        assertIs<TransactionResult.Success<*>>(postClearMw)
        bank action { emergencyLockdown mutate false }

        // removeState then re-access recreates with the initial value (delegate cache cleared).
        val sizeBeforeRemove = alice.properties.size
        alice.removeState("dailyWithdrawnCents")
        assertEquals(sizeBeforeRemove - 1, alice.properties.size)
        assertEquals(0L, alice.dailyWithdrawnCents.value, "fresh delegate registers with initial value")

        alice.clearStates()
        assertEquals(0, alice.properties.size)
        // Even after clearStates, the delegate-backed properties are usable — the
        // next read recreates each MutableState from its initializer.
        assertEquals(0L, alice.balanceCents.value)
        assertEquals(AccountStatus.Active, alice.status.value)

        // ────────────────────────────────────────────────────────────────────
        // Phase 19 — Trace verification: every Started has a paired Completed/Errored
        // ────────────────────────────────────────────────────────────────────
        val started = trace.count { it is TraceEvent.Started }
        val completed = trace.count { it is TraceEvent.Completed }
        val errored = trace.count { it is TraceEvent.Errored }
        assertTrue(started > 0, "tracing middleware ran at least once")
        assertEquals(started, completed + errored, "every Started has exactly one Completed or Errored counterpart")

        // The trace also captures status transitions of recorded transactions.
        val rolledBackCount = trace.count {
            it is TraceEvent.Completed &&
                it.status == TransactionStatus.RolledBack ||
                it is TraceEvent.Errored
        }
        assertTrue(rolledBackCount > 0, "trace recorded at least one rolled-back / errored transaction")
    }

    /**
     * A focused micro-demo isolating just the savepoint chain — useful when reading
     * the file to understand merge semantics in isolation from the rest of the demo.
     */
    @Test
    fun savepointMergeAndRollbackIsolated() {
        val v = AccountVault(accountId = "ACC-SP")
        v action { balanceCents mutate 1000L }
        val baseline = v.balanceCents.value

        // Two nested actions both succeed → both merge into the outer → outer commits.
        val ok = v action {
            action { balanceCents update { it + 50L } }
            action { balanceCents update { it - 10L } }
        }
        assertIs<TransactionResult.Success<*>>(ok)
        assertEquals(baseline + 40L, v.balanceCents.value)

        // Two nested actions succeed, but the outer rejects in its tail → all writes are discarded.
        val rejected = v action {
            action { balanceCents update { it + 100L } }
            action { balanceCents update { it + 100L } }
            error("outer rejects")
        }
        assertIs<TransactionResult.Error>(rejected)
        assertEquals(baseline + 40L, v.balanceCents.value, "outer rollback drops both savepoints' merged writes")
    }

    /**
     * Trims-by-stdlib version: same banking flow, but every cross-cutting concern
     * uses the shipped helpers in [com.vynatix.vault.middleware] and
     * [com.vynatix.vault.bridge] instead of the file-local TracingMiddleware /
     * BalancePersistenceBridge. Demonstrates that a "real" consumer can wire up
     * logging, timing, validation, and KV-backed persistence without writing any
     * Bridge or Middleware subclass themselves.
     */
    @Test
    fun stdlibShowcaseUsesOnlyShippedMiddlewareAndBridges() {
        val v = AccountVault(accountId = "ACC-STD")
        val log = mutableListOf<String>()
        val timings = mutableListOf<Long>()
        // Outermost (LAST) is Logging so its onError sees inner failures.
        v.middlewares(
            ValidationMiddleware {
                require(balanceCents.value >= 0) { "balance must stay non-negative" }
            },
            TimingMiddleware { _, _, ms -> timings.add(ms) },
            LoggingMiddleware("ACC-STD", log::add),
        )

        val kv = InMemoryKvStore()
        val key = "balance:${v.accountId}"
        v { balanceCents bridge KvBridge(kv, key, LongCodec) }

        // Successful deposit: persisted, logged, timed.
        val r1 = v.deposit(500, "opening")
        assertIs<TransactionResult.Success<LedgerEntry>>(r1)
        assertEquals("500", kv.get("balance:${v.accountId}"))
        assertTrue(log.any { it.contains("✓") })
        assertEquals(1, timings.size)

        // Validation rejection: balance can't go negative. Rolled back atomically;
        // KV not updated; Logging sees the error path.
        val savedBefore = kv.get("balance:${v.accountId}")
        val r2 = v.withdraw(99_999, "overdraft")
        assertIs<TransactionResult.Error>(r2) // ValidationMiddleware fails because pending balance < 0
        assertEquals(savedBefore, kv.get("balance:${v.accountId}"), "rolled-back transaction does not persist")
        assertTrue(log.any { it.contains("✗") }, "Logging.onError fired for the validation rejection")

        // Re-hydration: a fresh vault attached to the same KV loads the persisted balance.
        val reborn = AccountVault(accountId = v.accountId)
        reborn { balanceCents bridge KvBridge(kv, key, LongCodec) }
        assertEquals(500L, reborn.balanceCents.value, "stdlib KvBridge restored persisted balance to fresh vault")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1.1 FEATURE TESTS — focused exercises for each feature added in 1.1.
    // The big end-to-end scenario above continues to cover the 1.0 surface;
    // these test methods isolate one 1.1 capability each so failures point
    // directly at the affected primitive.
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Phase J — `EncryptingTransformer` + `XorCipher`.
     *
     * The `taxId` field is declared with `state(EncryptingTransformer(XorCipher(seed)))`.
     * Reads return plaintext; the persisted ciphertext (visible via `KvBridge` to a
     * `KvStore`) is NOT plaintext.
     */
    @Test
    fun encryptingTransformerProtectsTaxIdAtRest() {
        val v = AccountVault(accountId = "ACC-CRYPT")
        val plaintext = "TAX-987-654-321"
        v action { taxId mutate plaintext }
        // Read view is plaintext.
        assertEquals(plaintext, v.taxId.value)

        // Persist via KvBridge: the bytes hitting the KV store are CIPHERTEXT,
        // because `applyCommitted` publishes the raw post-`set` value.
        val kv = InMemoryKvStore()
        v { taxId bridge KvBridge(kv, "tax:${v.accountId}", StringCodec) }
        v action { taxId mutate "TAX-NEW-VALUE-9999" }
        val persisted = kv.get("tax:${v.accountId}") ?: error("expected persisted ciphertext")
        assertNotEquals("TAX-NEW-VALUE-9999", persisted, "persisted value is ciphertext, not plaintext")
        // Sanity: ciphertext is non-empty + base64-ish.
        assertTrue(persisted.isNotEmpty())
        // Reads still return plaintext.
        assertEquals("TAX-NEW-VALUE-9999", v.taxId.value)
    }

    /**
     * Phase K — `FileSystemKvStore`.
     *
     * Same `KvBridge` shape as the in-memory store, but persistence survives
     * `KvStore` lifetime — useful when you want filesystem-backed storage
     * without writing platform IO yourself.
     */
    @Test
    fun fileSystemKvStorePersistsBalanceAcrossSimulatedRestart() {
        val rootSuffix = "vault-banking-demo-fs-${com.vynatix.vault.bridge.randomDirSuffix()}"
        val rootPath = com.vynatix.vault.bridge.tempRoot(rootSuffix)
        val kv = com.vynatix.vault.bridge.FileSystemKvStore(rootPath)

        // Session 1: write.
        run {
            val v = AccountVault(accountId = "ACC-FS")
            v { balanceCents bridge KvBridge(kv, "balance:${v.accountId}", LongCodec) }
            v action { balanceCents mutate 75_000 }
            assertEquals(75_000L, v.balanceCents.value)
        }
        // Session 2: fresh vault, same KV → load via attach.
        val reborn = AccountVault(accountId = "ACC-FS")
        reborn { balanceCents bridge KvBridge(kv, "balance:${reborn.accountId}", LongCodec) }
        assertEquals(75_000L, reborn.balanceCents.value, "FileSystemKvStore round-trips through attach")
    }

    /**
     * Phase L — `Vault.snapshot()` / `Vault.restore(snapshot)`.
     *
     * Snapshots capture raw stored values (post-`transformer.set` form).
     * Restore writes them back without re-running `set`, so asymmetric
     * transformers like the encryption on `taxId` round-trip cleanly.
     */
    @Test
    fun snapshotAndRestoreRoundTripsAccountStateIncludingEncryptedFields() {
        val v = AccountVault(accountId = "ACC-SNAP")
        v action {
            balanceCents mutate 10_000
            holderName mutate "Cap Snapshot"
            email mutate "snap@example.com"
            taxId mutate "ORIGINAL-TAX-ID"
            ledger mutate listOf(
                LedgerEntry(randomLedgerId(), nowMs(), "opening", 10_000, 10_000),
            )
        }
        val snap = v.snapshot()
        assertTrue("balanceCents" in snap.stateNames)
        assertTrue("taxId" in snap.stateNames)

        // Mutate aggressively, then restore.
        v action {
            balanceCents mutate 999
            holderName mutate "Should Disappear"
            email mutate "throwaway@x"
            taxId mutate "DIFFERENT-TAX-ID"
            ledger mutate emptyList()
        }
        val r = v.restore(snap)
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(10_000L, v.balanceCents.value)
        assertEquals("Cap Snapshot", v.holderName.value)
        assertEquals("snap@example.com", v.email.value)
        assertEquals("ORIGINAL-TAX-ID", v.taxId.value, "encrypted state survives snapshot/restore round-trip")
        assertEquals(1, v.ledger.value.size)
    }

    /**
     * Phase M — `Vault.derived(...)` push-recomputed state.
     *
     * Subscribes to the `ledger` source state; whenever the ledger commits a
     * change, the derived `netDebits` recomputes inside a fresh action and
     * fires its own observers.
     */
    @Test
    fun derivedNetDebitsRecomputesOnLedgerCommits() {
        val v = AccountVault(accountId = "ACC-DERIVED", initialBalanceCents = 1_000_000)
        val (netDebits, dispose) = v.derived(v.ledger) {
            ledger.value.filter { it.deltaCents < 0 }.sumOf { -it.deltaCents }
        }
        try {
            assertEquals(0L, netDebits.value)

            v.deposit(50_000, "salary") // delta +50k → debits unchanged
            assertEquals(0L, netDebits.value)

            v.withdraw(20_000, "rent") // delta -20k
            assertEquals(20_000L, netDebits.value)

            v.withdraw(5_000, "groceries") // delta -5k
            assertEquals(25_000L, netDebits.value)

            // Observer fanout works on the derived state too.
            val seen = mutableListOf<Long>()
            val sub = v { netDebits effect { seen.add(this) } }
            seen.clear()
            v.withdraw(1_000, "coffee")
            assertEquals(listOf(26_000L), seen, "derived observer fires after ledger commit")
            sub.dispose()
        } finally {
            dispose.dispose()
        }
    }

    /**
     * Phase O — Cross-vault `atomic(...)` rollback semantics.
     *
     * The refactored `transferTo` uses `atomic(this, other)`. On the failing
     * path (peer is Frozen, deposit refused), the entire transfer rolls back
     * — both the debit and any partial state writes are dropped, with no
     * hand-rolled compensation.
     */
    @Test
    fun crossVaultAtomicTransferRollsBackBothVaultsOnFailure() {
        val a = AccountVault(accountId = "ACC-ATOM-A", initialBalanceCents = 10_000)
        val b = AccountVault(accountId = "ACC-ATOM-B")
        // Freeze b so its deposit will throw via the deposit operation's require.
        b action { status mutate AccountStatus.Frozen }

        val r = a.transferTo(b, 3_000, "should-fail")
        assertIs<TransactionResult.Error>(r)
        assertEquals(10_000L, a.balanceCents.value, "atomic rolled back the debit on a")
        assertEquals(0L, b.balanceCents.value, "atomic prevented any write to b")
        assertEquals(0L, a.dailyWithdrawnCents.value, "atomic rolled back the daily counter too")
        assertTrue(a.ledger.value.isEmpty(), "no ledger entry persisted from the rolled-back transfer")
    }

    @Test
    fun crossVaultAtomicTransferSucceedsAtomically() {
        val a = AccountVault(accountId = "ACC-ATOM-OK-A", initialBalanceCents = 10_000)
        val b = AccountVault(accountId = "ACC-ATOM-OK-B")
        val r = a.transferTo(b, 4_000, "atomic-ok")
        assertIs<TransactionResult.Success<LedgerEntry>>(r)
        assertEquals(6_000L, a.balanceCents.value)
        assertEquals(4_000L, b.balanceCents.value)
        assertEquals(1, a.ledger.value.size)
        assertEquals(1, b.ledger.value.size)
    }
}
