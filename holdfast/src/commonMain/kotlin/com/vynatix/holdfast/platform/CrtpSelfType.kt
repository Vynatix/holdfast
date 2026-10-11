package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store

/**
 * Verify, as far as this platform allows, that [store]'s class anchors itself
 * to its own type — the CRTP contract of `Store<Self : Store<Self>>`
 * (`class Foo : Store<Foo>()`, never `class Foo : Store<Bar>()`).
 *
 * Platform divergence (documented on [Store] and in GUIDE §2, not hidden):
 *  - **JVM/Android** — enforced at construction: the actual walks
 *    `javaClass.genericSuperclass` to `Store`, substituting type variables
 *    level-by-level, and throws a teaching `IllegalStateException` when the
 *    resolved `Self` argument's raw class cannot hold `this`
 *    (`rawSelf.isInstance(this)` — the exact soundness condition of
 *    `this as Self`). Fails OPEN when the signature data is missing or
 *    unresolvable (R8 strips the `Signature` attribute by default; the
 *    module's `consumer-rules.pro` keeps it for minified apps).
 *  - **iOS/wasmJs** — no construction-time reflection: the actual is a no-op.
 *    On Kotlin/Native the violation surfaces at the FIRST DISPATCH, where the
 *    receiver-bridge cast (`this as Self`) throws; [crtpSelfCastTeachingOrNull]
 *    upgrades that to a teaching rethrow from `action`/`invoke` (a documented
 *    exception to `action`'s no-throw-result contract, for this programming
 *    error only). On wasmJs bridge casts may surface as WasmGC traps that
 *    cannot be intercepted — nothing is claimed there.
 *
 * `internal` on purpose (issue 006): zero API-dump impact.
 */
internal expect fun checkCrtpSelfTypeAtConstruction(store: Store<*>)

/** The `"class "` prefix both the JVM's and Kotlin/Native's cast messages use. */
private const val CAST_MESSAGE_PREFIX = "class "

private const val CAST_MESSAGE_MIDDLE = " cannot be cast to class "

/**
 * The teaching [ClassCastException] for a first-dispatch CRTP violation, or
 * `null` when [failure] is not this store's self-cast failure.
 *
 * The gate is deliberately narrow (binding decision, issue 006): only a
 * `ClassCastException` whose message names THIS store's own concrete class as
 * the cast SOURCE — `"class Foo cannot be cast to class …"` — is upgraded, so
 * a user's `ClassCastException` thrown inside an action body is never
 * relabeled; it keeps today's handling (wrapped into `TransactionResult.Error`
 * by `action`, propagated by `invoke`). Message formats verified identical on
 * JVM and Native; a format the parser does not recognize fails open.
 */
internal fun crtpSelfCastTeachingOrNull(
    store: Store<*>,
    failure: Throwable,
): ClassCastException? {
    val message = (failure as? ClassCastException)?.message ?: return null
    return selfCastSimpleNameOf(store, message)?.let { simpleName ->
        ClassCastException(firstDispatchTeachingMessage(simpleName))
    }
}

/**
 * The store's own SIMPLE name when [message] names the store (by qualified or
 * simple name) as the cast SOURCE — the cast message may carry the
 * fully-qualified name (Kotlin/Native); the teaching message reads best with
 * the simple one. `null` when it does not.
 */
private fun selfCastSimpleNameOf(
    store: Store<*>,
    message: String,
): String? {
    val klass = store::class
    val source = castSourceClassName(message) ?: return null
    return source
        .takeIf { it == klass.qualifiedName || it == klass.simpleName }
        ?.let { klass.simpleName ?: source }
}

/**
 * The class this cast failure tried to convert FROM, out of a
 * `"class X cannot be cast to class Y …"` message; `null` when the message is
 * not in that shape.
 */
private fun castSourceClassName(message: String): String? {
    if (!message.startsWith(CAST_MESSAGE_PREFIX)) return null
    return message
        .removePrefix(CAST_MESSAGE_PREFIX)
        .substringBefore(CAST_MESSAGE_MIDDLE, "")
        .takeIf { it.isNotEmpty() }
}

/**
 * One-type message: at dispatch the declared (wrong) `Self` argument is not
 * reliably recoverable, so only the store's own class is named.
 */
private fun firstDispatchTeachingMessage(className: String): String =
    "$className is not its own self type: declare it as " +
        "`class $className : Store<$className>()` — a Store's Self type argument must be " +
        "the store class itself, and this store failed the cast to its declared Self at " +
        "first dispatch. (Kotlin/Native and wasmJs cannot verify this at construction; " +
        "JVM and Android stores are checked at construction when generic signatures are " +
        "available.)"
