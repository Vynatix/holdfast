@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

// restore(): decide everything first, then stage raw in one action (issue #20,
// R1; plan D10).
//
// The PLAN runs before the restore's action opens — at top level, holding no
// lock of the store — because it runs user code. It first checks the
// snapshot's schema version against the store's (StoreSchema.kt), which
// upcasts an older decoded snapshot through the store's `migrate`; then it
// materializes every target state a never-read one would need (its
// initializer), and decodes a decoded snapshot's texts (the states' codecs).
// It also checks each captured value against its target (the type witness
// below) and applies the policy. The
// ACTION then only stages the planned raw values, or throws the planned
// failure, so a rejected restore changes nothing and middleware still sees it
// as one failed transaction — inside an atomic(...) frame, one that aborts the
// frame.
//
// The type witness. A state's declared type is erased at runtime, so a restore
// cannot ask whether a value fits it. What it can see is the class of the value
// the state holds now. A value whose class is that same class fits. Otherwise
// it is rejected only when either class is a built-in value type (String,
// Boolean, Char or a primitive number): a state holding one of those and a
// value of another class share no declared type short of `Any`, `Comparable`,
// `Number` or the like. Two other classes — sealed siblings, subclasses, two
// List implementations — are never rejected: nothing tells a sibling from a
// stranger without the declared type. The witness skips values it can trust
// by construction: a captured snapshot taken by an instance of the target's
// class (or of a superclass of it) — the class's declarations produced its
// values — and a decoded snapshot's values, which the target state's own
// codec produced. The class is erased, so a generic store's type arguments
// are not compared: a `Box<Int>` snapshot restores unchecked into a
// `Box<String>`, and the wrong value surfaces as a ClassCastException where
// the state is read. The same holds for states whose declarations differ
// between instances of one class (function-local delegated properties).
//
// A target that removeState/clearStates drops after the plan is resolved
// again by the action: a declared state is materialized again there (its
// initializer then runs under the action's locks), a dropped derived backing
// is skipped, and a dropped internal state — which loses its declaration too
// — fails the restore.
//
// A STERILE restore (issue #20, R3) plans as if the snapshot held no entry for
// a Remote state and no derived backing, materializes every Remote state the
// store declares, and then — in the action, after staging the planned writes —
// resets the Remote states through the reset pass (Reset.kt), so a stale
// synced value never survives the restore. The pass reads the store's other
// declared states at the values the action staged: a Remote initializer sees
// the restored values, as it would in a fresh store holding them. A declared
// state or keyed entry the restore itself brings to life
// ([RestorePlanner.cameToLife]: not live when the restore began, not
// restored, never written since) was materialized from pre-restore values —
// by the plan, or by a first read (or `get`) in the pass — so the pass
// recomputes it from the restored values too, as a fresh store's first read
// would.

/**
 * One raw value a restore stages into [decl]'s state. [backing]: a derived
 * backing state (same-instance undo). [decl] may be a keyed entry's
 * (KeyedRestorePlanner.kt).
 */
internal class PlannedWrite(
    val decl: StateDeclaration<*>,
    val raw: Any,
    val backing: Boolean = false,
)

/**
 * Restore [snapshot] into this store under [policy] — [sterile]ly, resetting
 * its Remote states instead of restoring them — as one action whose value is
 * [result] of the restore's report: plan, then stage.
 *
 * @throws IllegalStateException like [Store.action]: when the store is
 *   disposed, or inside a state initializer or a schema migration.
 */
internal fun <V : Store<V>, R> V.runRestore(
    snapshot: StoreSnapshot,
    policy: RestorePolicy,
    sterile: Boolean = false,
    result: (RestoreReport) -> R,
): TransactionResult<R> {
    checkNotDisposed()
    NoWriteRegion.refuse { "restore $displayName" }
    val planner = RestorePlanner(this, snapshot.content, sterile)
    // A failing target initializer is carried into the action, which reports
    // it like one inside the restore, whatever the policy.
    val failure = runCatching { planner.plan() }.exceptionOrNull() ?: planner.rejection(policy)
    val sterileReset = if (sterile) planner::cameToLife else null
    return action(Restore(planner.writes, failure, sterileReset) { result(planner.report()) })
}

/**
 * The body of a restore's action. A class rather than a lambda so the
 * transaction's id — the body's simple name — reads `Restore`.
 */
private class Restore<V : Store<V>, R>(
    private val writes: List<PlannedWrite>,
    private val failure: Throwable?,
    /** For a sterile restore, which non-Remote declared states it brought to life (see [RestorePlanner.cameToLife]). */
    private val sterileReset: ((StateDeclaration<*>) -> Boolean)?,
    private val result: () -> R,
) : (V) -> R {
    override fun invoke(store: V): R {
        failure?.let { throw it }
        val txn = checkNotNull(store.activeTransaction) { "restore() stages into the action it runs in" }
        for (write in writes) {
            // Resolved again, for a state removeState/clearStates dropped since
            // the plan: a declared one is materialized again (its initializer
            // then runs here, under the action's locks); a dropped derived
            // backing is skipped; a dropped internal state lost its
            // declaration with it, so it fails the restore. A keyed entry
            // evicted since is created again the same way.
            val state =
                liveEntryState(write.decl) ?: write.decl.materialized ?: when {
                    write.backing -> continue
                    write.decl.kind == StateKind.Declared -> materialize(write.decl)
                    else -> error("${store.displayName} state '${write.decl.name}' was removed while restore() ran")
                }
            txn.stagePendingRaw(state, write.raw)
        }
        // After the planned writes, which never touch a Remote state here: the
        // reset compares each Remote state with the value the transaction
        // holds for it, and its initializers read the restored values.
        sterileReset?.let { store.stageResetOfRemoteStates(txn, it) }
        return result()
    }
}

/**
 * Plans one restore of [content] into [store]: what to stage, and what to
 * report. A [sterile] plan drops the entries of Remote states and derived
 * backings, and materializes the Remote states the action will reset.
 */
internal class RestorePlanner(
    private val store: Store<*>,
    private val content: SnapshotContent,
    private val sterile: Boolean,
) {
    val writes = ArrayList<PlannedWrite>()
    private val written = HashSet<StateDeclaration<*>>()

    /**
     * The keyed entries this plan writes, by family and key: an entry evicted
     * after the plan is created again for its key by the action, which
     * stages the planned value into it — a new declaration, still written.
     */
    private val writtenEntries = HashSet<Pair<KeyedFamily<*, *>, Any>>()
    private val restored = LinkedHashSet<String>()
    private val issues = ArrayList<RestoreIssue>()

    /**
     * For a [sterile] plan: the declarations whose state was live when the
     * restore began — declared states and keyed entries (see [cameToLife]).
     */
    private val liveBefore: Set<StateDeclaration<*>> =
        if (sterile) {
            val states = store.declarations().filterTo(HashSet()) { it.materialized != null }
            store.registry.keyed.familiesInOrder().flatMapTo(states) { family ->
                family.committedEntries().map { (_, entry) -> checkNotNull(entry.declaration) }
            }
        } else {
            emptySet()
        }

    /**
     * Whether a sterile restore's reset recomputes [decl], a declared state
     * or keyed entry it neither restores nor resets as Remote: one this
     * restore brought to life. Its state was not live when the restore began
     * and is live now (not evicted) — materialized by the plan (a target, an
     * entry the snapshot holds, or a state a Remote initializer read), or by a
     * first read (or `get`) inside the reset — and no write has committed to
     * it since, so it holds a first-read value computed from pre-restore
     * values, which the reset replaces with the value a first read computes
     * after the restore. A state live before the restore keeps its value, as
     * a plain restore leaves it, and so does one a concurrent action has
     * written since it came to life. Asked inside the restore's action.
     */
    fun cameToLife(decl: StateDeclaration<*>): Boolean {
        val state = decl.materialized.takeIf { decl.kind.isWritable && decl !in liveBefore } ?: return false
        return !state.retired && !plansWriteTo(decl) && state.writesBegun.value == 0L
    }

    /** Whether this plan writes [decl]'s state — for a keyed entry, its family's entry of that key. */
    private fun plansWriteTo(decl: StateDeclaration<*>): Boolean {
        val entry = decl.keyed ?: return decl in written
        return entry.family to entry.key in writtenEntries
    }

    /**
     * A captured snapshot an instance of the target's class (or a superclass)
     * took: the class's declarations produced its values. The class is erased,
     * so a generic store's type arguments are not compared.
     */
    private val trusted = (content as? CapturedContent)?.originClass?.isInstance(store) == true

    /** The keyed state families' part of the plan (KeyedRestorePlanner.kt), adding to this plan's writes and report. */
    private val families = KeyedRestorePlanner(store, sterile, trusted, writes, writtenEntries, restored, issues)

    /**
     * Check the schema version, then materialize every target and decide every
     * entry (and, when [sterile], materialize the Remote states). Throws a
     * [SnapshotMigrationException] for a snapshot the store's schema refuses
     * (or its `migrate` fails on), before any other user code runs, and what
     * a target's initializer throws.
     */
    fun plan() {
        val schema = StoreSchema(store)
        when (content) {
            is CapturedContent -> {
                schema.checkCaptured(content.schema)
                content.rawValues.forEach { (name, raw) -> planCaptured(name, raw) }
                content.families.forEach { (name, family) -> families.planCaptured(name, family) }
                if (!sterile && content.originKey == store.lockOrderKey) planBackings(content)
            }
            is DecodedContent -> {
                val body = schema.upcast(content.body)
                body.states.forEach { (name, text) -> planDecoded(name, text) }
                body.families.forEach { (name, entries) -> families.planDecoded(name, entries) }
            }
        }
        // The Remote states the action resets, never-read ones included: their
        // initializers run now, outside the action's locks, as reset() runs them.
        if (sterile) store.materializeDeclaredStates { it.isRemote }
    }

    /** The failure [policy] makes of the issues found, or `null` if it tolerates them all. */
    fun rejection(policy: RestorePolicy): RestoreRejectedException? =
        issues
            .filterNot { policy.tolerates(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { RestoreRejectedException(store.displayName, policy, it) }

    /** What the restore did, once it has staged [writes] (and, [sterile], reset the Remote states). */
    fun report(): RestoreReport {
        val skipped = issues.mapTo(HashSet()) { it.stateName }
        val declared = store.reportedNames()
        val sterilized = if (sterile) declared.filter { it.second }.mapTo(LinkedHashSet()) { it.first } else emptySet()
        val kept =
            declared
                .map { it.first }
                .filterTo(LinkedHashSet()) { it !in restored && it !in skipped && it !in sterilized }
        return RestoreReport(restored.toSet(), kept, issues.toList(), sterilized)
    }

    private fun planCaptured(
        name: String,
        raw: Any,
    ) {
        val decl = target(name) ?: return
        val mismatch = if (trusted) null else typeMismatch(name, raw, materialize(decl).rawCurrentValue)
        if (mismatch != null) issues += mismatch else write(decl, raw)
    }

    /** A withheld value (`null` text) has nothing to restore: its state keeps its value. */
    private fun planDecoded(
        name: String,
        text: String?,
    ) {
        val decl = target(name) ?: return
        val codec = decl.codec
        when {
            text == null -> Unit
            codec == null -> issues += RestoreIssue.NoCodec(name)
            else ->
                runCatching { codec.decode(text) }
                    .onSuccess { write(decl, it) }
                    .onFailure { issues += RestoreIssue.Undecodable(name, "its codec threw ${it.describeClass()}") }
        }
    }

    /** Derived backing states, restored only into the store that captured them (undo), if they still exist. */
    private fun planBackings(content: CapturedContent) {
        content.derivedBackingValues.forEach { (name, raw) ->
            val decl = store.registry.declaration(name)?.takeIf { it.kind == StateKind.DerivedBacking }
            if (decl?.materialized != null) writes += PlannedWrite(decl, raw, backing = true)
        }
    }

    /**
     * The declared state [name] names, materialized; or `null`: reported as
     * unknown when this store declares no such state (a derived backing's
     * name included: those are private to their store), and dropped silently
     * when a [sterile] restore resets the state instead.
     */
    private fun target(name: String): StateDeclaration<*>? {
        val decl = store.registry.declaration(name)?.takeIf { it.kind != StateKind.DerivedBacking }
        when {
            decl == null -> issues += store.undeclaredStateIssue(name)
            sterile && decl.isRemote -> return null
            else -> materialize(decl)
        }
        return decl
    }

    private fun write(
        decl: StateDeclaration<*>,
        raw: Any,
    ) {
        writes += PlannedWrite(decl, raw)
        written += decl
        restored += decl.name
    }
}

/** A value class without a simple name (an anonymous object), as [RestoreIssue.TypeMismatch] names it. */
private const val ANONYMOUS = "<anonymous>"

/** The type witness (see the top of this file): a [RestoreIssue.TypeMismatch], or `null` when [raw] may fit. */
internal fun typeMismatch(
    name: String,
    raw: Any,
    current: Any,
): RestoreIssue.TypeMismatch? {
    val fits = raw::class == current::class || !raw.isBuiltinValue() && !current.isBuiltinValue()
    if (fits) return null
    return RestoreIssue.TypeMismatch(name, raw.describeClass(ANONYMOUS), current.describeClass(ANONYMOUS))
}

private fun Any.isBuiltinValue(): Boolean =
    when (this) {
        is String, is Boolean, is Char, is Byte, is Short, is Int, is Long, is Float, is Double -> true
        else -> false
    }
