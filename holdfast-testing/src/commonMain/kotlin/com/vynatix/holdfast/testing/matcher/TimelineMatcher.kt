package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.BridgeObserved
import com.vynatix.holdfast.testing.BridgePublished
import com.vynatix.holdfast.testing.EmissionEvent
import com.vynatix.holdfast.testing.MiddlewareCompleted
import com.vynatix.holdfast.testing.MiddlewareErrored
import com.vynatix.holdfast.testing.MiddlewareStarted
import com.vynatix.holdfast.testing.StoreEvent
import com.vynatix.holdfast.testing.StoreHandle
import com.vynatix.holdfast.testing.TransactionCommitted
import com.vynatix.holdfast.testing.TransactionErrored
import com.vynatix.holdfast.testing.TransactionRolledBack
import com.vynatix.holdfast.testing.TransactionStarted
import kotlin.reflect.KProperty1

/**
 * DSL marker preventing accidental cross-builder pollution. Without this,
 * predicates declared inside a [MiddlewareBuilder] could implicitly resolve
 * methods on the enclosing [TimelineMatcher], which would silently register a
 * second predicate the user never typed.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class TimelineMatcherDsl

/**
 * Builder receiver used by [shouldFire] / [shouldFireInOrder] /
 * [shouldFireInExactOrder] / [shouldNotFire] to collect [EventPredicate]s.
 *
 * The builder uses a "predicate-getter-registers-on-access" pattern: simply
 * referencing `started`, `committed`, etc. in the lambda body adds the
 * corresponding predicate to [predicates]. Method calls (`started(id)`,
 * `emitted(prop)`, `middleware<M>()`) likewise register and return the
 * predicate they constructed for the rare cases where the caller wants to
 * keep a reference (none of the four combinators need this — predicates run
 * by index off the registered list).
 *
 * KProperty1-based predicates ([emitted], [bridgePublished], [bridgeObserved])
 * need a store context to resolve the property reference to a [State]
 * reference. The handle-receiver combinators ([StoreHandle.shouldFire] etc.)
 * pass [vaultRef] from `handle.store`. The list-receiver combinators (e.g.
 * `List<StoreEvent>.shouldFire`) pass `null`, in which case calling
 * [emitted] / [bridgePublished] / [bridgeObserved] throws
 * [IllegalStateException] — the synthetic-timeline form is only meant for
 * predicates that don't need a store.
 */
@TimelineMatcherDsl
class TimelineMatcher<V : Store<V>> internal constructor(
    internal val vaultRef: V?,
) {
    /**
     * Predicates collected in declaration order. The four combinators iterate
     * this list, so order matters for [shouldFireInOrder] /
     * [shouldFireInExactOrder]; for [shouldFire] / [shouldNotFire] only the
     * set of predicates matters.
     */
    internal val predicates: MutableList<EventPredicate> = mutableListOf()

    private fun <P : EventPredicate> register(predicate: P): P {
        predicates.add(predicate)
        return predicate
    }

    /**
     * Match any [TransactionStarted] event. Accessing this property registers
     * the predicate; declared as a `get()` so each access creates a fresh
     * predicate instance (so `started; started` declares two predicates).
     */
    val started: TransactionStartedPredicate
        get() = register(TransactionStartedPredicate(id = null))

    /** Match any [TransactionCommitted] event. */
    val committed: TransactionCommittedPredicate
        get() = register(TransactionCommittedPredicate(id = null))

    /** Match any [TransactionRolledBack] event. */
    val rolledBack: TransactionRolledBackPredicate
        get() = register(TransactionRolledBackPredicate(id = null))

    /** Match any [TransactionErrored] event. */
    val errored: TransactionErroredPredicate
        get() = register(TransactionErroredPredicate(id = null))

    /** Match a [TransactionStarted] event whose transaction has the given [id]. */
    fun started(id: String): TransactionStartedPredicate = register(TransactionStartedPredicate(id))

    /** Match a [TransactionCommitted] event whose transaction has the given [id]. */
    fun committed(id: String): TransactionCommittedPredicate = register(TransactionCommittedPredicate(id))

    /** Match a [TransactionRolledBack] event whose transaction has the given [id]. */
    fun rolledBack(id: String): TransactionRolledBackPredicate = register(TransactionRolledBackPredicate(id))

    /** Match a [TransactionErrored] event whose transaction has the given [id]. */
    fun errored(id: String): TransactionErroredPredicate = register(TransactionErroredPredicate(id))

    /**
     * Open a [MiddlewareBuilder] scoped to middleware instances of type [M].
     * Member predicates ([MiddlewareBuilder.started], `.completed`, `.errored`)
     * register against this matcher when accessed.
     *
     * **v1 caveat**: see [StoreHandle.middlewareEventsOf] — only the recorder's
     * own self-events are captured. User middlewares pass through with no
     * lifecycle events, so a `middleware<UserClass>()` block will never match
     * anything in v1. This is documented and tested via the recorder.
     */
    inline fun <reified M : Middleware<*>> middleware(): MiddlewareBuilder<V> =
        MiddlewareBuilder(
            owner = this,
            classMatch = { it is M },
            instanceMatch = null,
            label = M::class.simpleName ?: "Middleware",
        )

    /**
     * Open a [MiddlewareBuilder] scoped to a specific middleware [instance].
     * Matches by referential equality (`===`).
     */
    fun <M : Middleware<*>> middleware(instance: M): MiddlewareBuilder<V> =
        MiddlewareBuilder(
            owner = this,
            classMatch = null,
            instanceMatch = instance,
            label = instance::class.simpleName ?: "Middleware",
        )

    /**
     * Match any [EmissionEvent] for the [State] referenced by [prop]. Resolves
     * the property reference to a State reference at predicate-construction
     * time using [vaultRef], so subsequent matching is `===` against the
     * EmissionEvent's `state` field.
     *
     * Throws [IllegalStateException] if [vaultRef] is null (i.e. when invoked
     * via the [List]-receiver combinator without a store context).
     */
    fun emitted(prop: KProperty1<V, State<*>>): EmissionPredicate =
        register(
            EmissionPredicate(
                target = resolveState(prop, "emitted"),
                propName = prop.name,
                checkNewValue = false,
                expectedNewValue = null,
            ),
        )

    /**
     * Match any [EmissionEvent] for [prop] whose `newValue` equals [value]
     * (`==`). [value] may be `null` to match emissions whose `newValue` is null.
     */
    fun emitted(
        prop: KProperty1<V, State<*>>,
        value: Any?,
    ): EmissionPredicate =
        register(
            EmissionPredicate(
                target = resolveState(prop, "emitted"),
                propName = prop.name,
                checkNewValue = true,
                expectedNewValue = value,
            ),
        )

    /** Match any [BridgePublished] event for the State referenced by [prop]. */
    fun bridgePublished(prop: KProperty1<V, State<*>>): BridgePublishedPredicate =
        register(
            BridgePublishedPredicate(target = resolveState(prop, "bridgePublished"), propName = prop.name),
        )

    /** Match any [BridgeObserved] event for the State referenced by [prop]. */
    fun bridgeObserved(prop: KProperty1<V, State<*>>): BridgeObservedPredicate =
        register(
            BridgeObservedPredicate(target = resolveState(prop, "bridgeObserved"), propName = prop.name),
        )

    private fun resolveState(
        prop: KProperty1<V, State<*>>,
        surface: String,
    ): State<*> {
        val v =
            vaultRef
                ?: error(
                    "$surface(${prop.name}) requires a store context. Use " +
                        "StoreHandle.$surface { … } instead of List<StoreEvent>.$surface { … }, " +
                        "or build a synthetic-timeline test that doesn't reference state properties.",
                )
        return prop.get(v)
    }

    /**
     * Internal handle for a [MiddlewareBuilder] to register a predicate
     * constructed in its own scope.
     */
    internal fun <P : EventPredicate> registerFromBuilder(predicate: P): P = register(predicate)
}

/**
 * Sub-builder returned by [TimelineMatcher.middleware]. Property accesses on
 * this builder ([started], [completed], [errored]) register predicates against
 * the parent [TimelineMatcher] in declaration order — so a `middleware<X>()`
 * block contributes one predicate per accessed property.
 *
 * The builder carries the matching strategy (class-based or instance-based) so
 * the predicates can be constructed eagerly without re-running reified type
 * checks at match time.
 */
@TimelineMatcherDsl
class MiddlewareBuilder<V : Store<V>>
    @PublishedApi
    internal constructor(
        private val owner: TimelineMatcher<V>,
        private val classMatch: ((Middleware<*>) -> Boolean)?,
        private val instanceMatch: Middleware<*>?,
        private val label: String,
    ) {
        /** Match a [MiddlewareStarted] event for this builder's middleware scope. */
        val started: MiddlewareStartedPredicate
            get() =
                owner.registerFromBuilder(
                    MiddlewareStartedPredicate(classMatch = classMatch, instanceMatch = instanceMatch, label = label),
                )

        /** Match a [MiddlewareCompleted] event for this builder's middleware scope. */
        val completed: MiddlewareCompletedPredicate
            get() =
                owner.registerFromBuilder(
                    MiddlewareCompletedPredicate(classMatch = classMatch, instanceMatch = instanceMatch, label = label),
                )

        /** Match a [MiddlewareErrored] event for this builder's middleware scope. */
        val errored: MiddlewareErroredPredicate
            get() =
                owner.registerFromBuilder(
                    MiddlewareErroredPredicate(classMatch = classMatch, instanceMatch = instanceMatch, label = label),
                )
    }

/**
 * Sealed root of timeline predicates. Each predicate carries:
 *  - a [description] used in failure messages so tests pinpoint the unmatched
 *    expectation by user-facing name (e.g. `"transaction 'foo' committed"`);
 *  - a [matches] check that returns `true` iff the given event satisfies the
 *    predicate.
 *
 * `sealed` so the matcher hierarchy is closed and exhaustive — exhaustive when
 * marshalling predicates into failure messages (we never need to print a
 * generic "unknown predicate" branch).
 */
sealed interface EventPredicate {
    /** Human-readable label included in failure messages. */
    val description: String

    /** Return `true` iff [event] satisfies this predicate. */
    fun matches(event: StoreEvent): Boolean
}

// ----- Transaction lifecycle predicates -----

/** Match [TransactionStarted]; if [id] is non-null, additionally require the transaction id. */
class TransactionStartedPredicate internal constructor(
    internal val id: String?,
) : EventPredicate {
    override val description: String = if (id == null) "any transaction started" else "transaction '$id' started"

    override fun matches(event: StoreEvent): Boolean = event is TransactionStarted && (id == null || event.transaction.id == id)
}

/** Match [TransactionCommitted]; if [id] is non-null, additionally require the transaction id. */
class TransactionCommittedPredicate internal constructor(
    internal val id: String?,
) : EventPredicate {
    override val description: String = if (id == null) "any transaction committed" else "transaction '$id' committed"

    override fun matches(event: StoreEvent): Boolean = event is TransactionCommitted && (id == null || event.transaction.id == id)
}

/** Match [TransactionRolledBack]; if [id] is non-null, additionally require the transaction id. */
class TransactionRolledBackPredicate internal constructor(
    internal val id: String?,
) : EventPredicate {
    override val description: String = if (id == null) "any transaction rolledBack" else "transaction '$id' rolledBack"

    override fun matches(event: StoreEvent): Boolean = event is TransactionRolledBack && (id == null || event.transaction.id == id)
}

/** Match [TransactionErrored]; if [id] is non-null, additionally require the transaction id. */
class TransactionErroredPredicate internal constructor(
    internal val id: String?,
) : EventPredicate {
    override val description: String = if (id == null) "any transaction errored" else "transaction '$id' errored"

    override fun matches(event: StoreEvent): Boolean = event is TransactionErrored && (id == null || event.transaction.id == id)
}

// ----- Middleware lifecycle predicates -----

/**
 * Match [MiddlewareStarted] events. Either [classMatch] or [instanceMatch] is
 * non-null (mutually exclusive — set by the builder that constructed the
 * predicate). [label] is the user-facing simpleName used in failure messages.
 */
class MiddlewareStartedPredicate internal constructor(
    internal val classMatch: ((Middleware<*>) -> Boolean)?,
    internal val instanceMatch: Middleware<*>?,
    internal val label: String,
) : EventPredicate {
    override val description: String = "middleware<$label> started"

    override fun matches(event: StoreEvent): Boolean =
        event is MiddlewareStarted && middlewareMatches(event.middleware, classMatch, instanceMatch)
}

/** Match [MiddlewareCompleted] events. See [MiddlewareStartedPredicate] for the matching strategy. */
class MiddlewareCompletedPredicate internal constructor(
    internal val classMatch: ((Middleware<*>) -> Boolean)?,
    internal val instanceMatch: Middleware<*>?,
    internal val label: String,
) : EventPredicate {
    override val description: String = "middleware<$label> completed"

    override fun matches(event: StoreEvent): Boolean =
        event is MiddlewareCompleted && middlewareMatches(event.middleware, classMatch, instanceMatch)
}

/** Match [MiddlewareErrored] events. See [MiddlewareStartedPredicate] for the matching strategy. */
class MiddlewareErroredPredicate internal constructor(
    internal val classMatch: ((Middleware<*>) -> Boolean)?,
    internal val instanceMatch: Middleware<*>?,
    internal val label: String,
) : EventPredicate {
    override val description: String = "middleware<$label> errored"

    override fun matches(event: StoreEvent): Boolean =
        event is MiddlewareErrored && middlewareMatches(event.middleware, classMatch, instanceMatch)
}

// ----- Emission predicate -----

/**
 * Match [EmissionEvent] events for a specific State. [target] is pre-resolved
 * via `prop.get(store)` at builder-construction time; matching uses `===` so
 * structurally-equal but distinct State instances do not collide.
 *
 * If [checkNewValue] is true, the predicate additionally requires
 * `event.newValue == expectedNewValue` (`==`, allowing nullable comparison).
 */
class EmissionPredicate internal constructor(
    internal val target: State<*>,
    internal val propName: String,
    internal val checkNewValue: Boolean,
    internal val expectedNewValue: Any?,
) : EventPredicate {
    override val description: String =
        if (!checkNewValue) {
            "emitted($propName)"
        } else {
            "emitted($propName) with newValue=$expectedNewValue"
        }

    override fun matches(event: StoreEvent): Boolean =
        event is EmissionEvent &&
            event.state === target &&
            (!checkNewValue || event.newValue == expectedNewValue)
}

// ----- Bridge predicates -----

/** Match [BridgePublished] events for a specific State. See [EmissionPredicate] for the resolution strategy. */
class BridgePublishedPredicate internal constructor(
    internal val target: State<*>,
    internal val propName: String,
) : EventPredicate {
    override val description: String = "bridgePublished($propName)"

    override fun matches(event: StoreEvent): Boolean = event is BridgePublished && event.state === target
}

/** Match [BridgeObserved] events for a specific State. See [EmissionPredicate] for the resolution strategy. */
class BridgeObservedPredicate internal constructor(
    internal val target: State<*>,
    internal val propName: String,
) : EventPredicate {
    override val description: String = "bridgeObserved($propName)"

    override fun matches(event: StoreEvent): Boolean = event is BridgeObserved && event.state === target
}

/**
 * Helper for [MiddlewareStartedPredicate] / [MiddlewareCompletedPredicate] /
 * [MiddlewareErroredPredicate] to apply whichever match strategy the builder
 * was configured with — exactly one of [classMatch] / [instanceMatch] is
 * non-null.
 */
internal fun middlewareMatches(
    actual: Middleware<*>,
    classMatch: ((Middleware<*>) -> Boolean)?,
    instanceMatch: Middleware<*>?,
): Boolean =
    when {
        classMatch != null -> classMatch(actual)
        instanceMatch != null -> actual === instanceMatch
        else -> false
    }
