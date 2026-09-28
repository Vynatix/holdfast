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
// frame. The two halves are one PlannedRestore: restoreInOneFrame
// (OneFrameRestore.kt) plans several stores this way before its frame opens,
// then stages each plan into that store's root of the frame.
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
//
// A restore limited to a TAG (internalRestoreTagged; `:holdfast-coroutines`'
// persisted overlay, issue #20 R8/R3) plans only the entries of the states and
// keyed state families the store declares with that tag, matched by name
// after the schema check has migrated the snapshot, so a renamed state is
// matched under its new name. An entry of a state or family declared without
// the tag is dropped silently, as a sterile restore drops a Remote one, and so
// are the derived backings; an entry the store does not declare at all is an
// unknown state, as in any restore. Unlike any other restore it REPLACES each
// tagged family the snapshot lists (a planned family that raised no issue):
// its action, before staging the planned writes, evicts every entry live in
// its transaction's view whose key the snapshot does not hold — entries an
// enclosing transaction created included — so the family holds the
// snapshot's keys and no others. A family the snapshot does not list (one it
// skipped for want of a codec, say) keeps its entries. It may also be limited
// to TARGETS: a planned value whose state the filter refuses is not staged,
// and the state keeps the value the transaction holds (a keyed entry the
// snapshot lists is still kept).

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
 * its Remote states instead of restoring them; [only] the entries of states
 * and families declared with that tag, when it is set (see the top of this
 * file) — as one action whose value is [result] of the restore's report:
 * plan, then stage. The plan and the action are one entry: what the plan
 * changes that a derived state follows as a whole — a keyed entry it
 * creates (StoreEdges.kt) — settles with the action's own commit, once,
 * after it (SettleScope.kt).
 *
 * @throws IllegalStateException like [Store.action]: when the store is
 *   disposed, or inside a state initializer or a schema migration.
 */
internal fun <V : Store<V>, R> V.runRestore(
    snapshot: StoreSnapshot,
    policy: RestorePolicy,
    sterile: Boolean = false,
    only: StateTag? = null,
    targets: ((State<*>) -> Boolean)? = null,
    result: (RestoreReport) -> R,
): TransactionResult<R> {
    checkNotDisposed()
    NoWriteRegion.refuse { "restore $displayName" }
    return settling {
        val plan = PlannedRestore(this, snapshot.content, policy, sterile, only, targets)
        action(Restore(plan, result))
    }
}

/**
 * [restore] under [policy], limited to [tag]: only the entries of [snapshot]
 * whose state — or keyed state family — this store declares with [tag] are
 * restored. An entry of a state or family declared without [tag] is dropped
 * silently (neither restored nor an issue), as are the derived backing
 * states; an entry of a name this store does not declare at all is a
 * [RestoreIssue.UnknownState], as in any restore. Names are matched after the
 * schema check has migrated the snapshot ([SchemaVersioned.migrate]), so a
 * renamed state is matched under its new name.
 *
 * Unlike any other restore, it REPLACES each [tag]ged keyed state family the
 * snapshot lists: before staging its values, it evicts every entry of the
 * family live in the transaction's view whose key the snapshot does not hold
 * — one an enclosing transaction (or another thread, meanwhile) created, too
 * — so the family ends up with the snapshot's keys and no others. A family the snapshot does not list
 * (a decoded one it skipped, having no codec or keyCodec to encode it)
 * keeps its entries, and so does one whose entries raised an issue the
 * [policy] tolerated.
 *
 * When [targets] is set, a value is staged only into a state it accepts
 * (the declared state, or the keyed entry, the value would be restored
 * into): any other keeps the value its transaction holds, and a keyed entry
 * the snapshot lists is kept either way — not evicted.
 *
 * Everything else is the experimental `restore(snapshot, policy)`: the plan
 * before the action, the one action (id `Restore`; a savepoint inside an
 * action of this store), the failures it returns as
 * [TransactionResult.Error] — a [SnapshotMigrationException], a
 * [RestoreRejectedException], a target's initializer failure — with nothing
 * changed (no entry evicted either).
 *
 * For `:holdfast-coroutines`' persisted overlay, which applies only the
 * [StateTag.UserAuthored] entries of the blob it reads, whatever else the blob
 * holds.
 *
 * @throws IllegalStateException like [Store.action]: if the store is
 *   disposed, or when called from inside a state initializer or a
 *   [SchemaVersioned.migrate].
 */
@ExperimentalStoreApi
@StoreInternalApi
fun <V : Store<V>> V.internalRestoreTagged(
    snapshot: StoreSnapshot,
    tag: StateTag,
    policy: RestorePolicy,
    targets: ((State<*>) -> Boolean)? = null,
): TransactionResult<Unit> = runRestore(snapshot, policy, only = tag, targets = targets) { }

/**
 * The body of a restore's action. A class rather than a lambda so the
 * transaction's id — the body's simple name — reads `Restore`.
 */
private class Restore<V : Store<V>, R>(
    private val plan: PlannedRestore,
    private val result: (RestoreReport) -> R,
) : (V) -> R {
    override fun invoke(store: V): R {
        plan.stage(checkNotNull(store.activeTransaction) { "restore() stages into the action it runs in" })
        return result(plan.report())
    }
}

/**
 * One restore of [content] into [store], planned — at construction, before
 * any transaction opens and taking no lock of [store] (see the top of this
 * file) — and ready to [stage] into a transaction of [store]: the action
 * [runRestore] opens, or the root an `atomic` frame opened for [store]
 * ([restoreInOneFrame]). What the plan throws (a target's initializer, the
 * schema check), else the failure [policy] makes of the issues it found, is
 * kept for [stage] to throw, so a rejected restore fails inside its
 * transaction, having changed nothing.
 */
internal class PlannedRestore(
    val store: Store<*>,
    content: SnapshotContent,
    policy: RestorePolicy,
    sterile: Boolean = false,
    only: StateTag? = null,
    targets: ((State<*>) -> Boolean)? = null,
) {
    private val planner = RestorePlanner(store, content, sterile, only, targets)

    // A failing target initializer is carried into the transaction, which
    // reports it like one inside the restore, whatever the policy.
    private val failure = runCatching { planner.plan() }.exceptionOrNull() ?: planner.rejection(policy)

    /** For a sterile restore, which non-Remote declared states it brought to life (see [RestorePlanner.cameToLife]). */
    private val sterileReset: ((StateDeclaration<*>) -> Boolean)? = if (sterile) planner::cameToLife else null

    /**
     * Stage the planned restore into [txn], [store]'s active transaction, or
     * throw the planned failure. What was staged when a later step throws
     * stays in [txn], and the caller rolls it back.
     */
    fun stage(txn: Transaction) {
        failure?.let { throw it }
        // Before the planned writes, none of which is to an entry evicted
        // here: the family then holds the snapshot's keys and no others.
        for ((family, keys) in planner.replaced) {
            family.stageEvictions("replace the entries of ${family.qualifiedName}") {
                family.entries.mapNotNull { (key, entry) -> (entry as MutableState<*>).takeIf { key !in keys } }
            }
        }
        for (write in planner.writes) {
            // Resolved again, for a state removeState/clearStates dropped since
            // the plan: a declared one is materialized again (its initializer
            // then runs here, under the transaction's locks); a dropped derived
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
    }

    /** What the restore did, once [stage] has run. */
    fun report(): RestoreReport = planner.report()
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
    /** When set, plan only the entries of states and families declared with this tag ([admitted]). */
    private val only: StateTag? = null,
    /** When set, plan a value only into a state (or keyed entry) it accepts. */
    private val targets: ((State<*>) -> Boolean)? = null,
) {
    val writes = ArrayList<PlannedWrite>()
    private val written = HashSet<StateDeclaration<*>>()

    /**
     * For a restore limited to a tag ([only]), the families it replaces: each
     * planned family that raised no issue, with the keys the snapshot holds
     * for it (see the top of this file). Empty for any other restore.
     */
    val replaced = LinkedHashMap<KeyedFamily<*, *>, Set<Any>>()

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
    private val families =
        KeyedRestorePlanner(
            store,
            sterile,
            trusted,
            writes,
            writtenEntries,
            restored,
            issues,
            accepts = ::accepts,
            replaced = replaced.takeIf { only != null },
        )

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
                content.rawValues.admitted(store, only).forEach { (name, raw) -> planCaptured(name, raw) }
                content.families.admitted(store, only).forEach { (name, family) -> families.planCaptured(name, family) }
                if (!sterile && only == null && content.originKey == store.lockOrderKey) planBackings(content)
            }
            is DecodedContent -> {
                val body = schema.upcast(content.body)
                body.states.admitted(store, only).forEach { (name, text) -> planDecoded(name, text) }
                body.families.admitted(store, only).forEach { (name, entries) -> families.planDecoded(name, entries) }
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
        val decl = target(name)?.takeIf(::accepts) ?: return
        val mismatch = if (trusted) null else typeMismatch(name, raw, materialize(decl).rawCurrentValue)
        if (mismatch != null) issues += mismatch else write(decl, raw)
    }

    /** A withheld value (`null` text) has nothing to restore: its state keeps its value. */
    private fun planDecoded(
        name: String,
        text: String?,
    ) {
        val decl = target(name)?.takeIf(::accepts) ?: return
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

    /** Whether a value may be planned into [decl]'s state, which exists: always, unless [targets] refuses it. */
    private fun accepts(decl: StateDeclaration<*>): Boolean =
        targets?.invoke(checkNotNull(decl.materialized) { "a planned target exists" }) != false
}

/**
 * The entries of this map, by snapshot name, that a restore of [store]
 * limited to [only] plans: all of them when [only] is `null`; else the
 * entries of the states and keyed state families [store] declares with
 * [only], and of the names it does not declare at all (which the plan reports
 * as unknown). An entry of a state or family declared without [only] — a
 * derived backing's included — is dropped.
 */
private fun <T> Map<String, T>.admitted(
    store: Store<*>,
    only: StateTag?,
): Map<String, T> =
    if (only == null) {
        this
    } else {
        filterKeys { name ->
            val declared = store.registry.declaration(name)?.tags
            val tags =
                declared ?: store.registry.keyed
                    .family(name)
                    ?.tags
            tags == null || only in tags
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
