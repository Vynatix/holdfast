package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM/Android actual (shared source set; both compile against
 * `java.lang.reflect`, no kotlin-reflect): enforce `Store<Self>`'s CRTP
 * contract at construction.
 *
 * The walk (issue 006, binding spec — an immediate-superclass-only reading,
 * or an equality-vs-`javaClass` one, is WRONG: both reject the library's own
 * `EventfulStore<Self, E>` intermediate, user generic intermediates,
 * subclasses of concrete stores, anonymous fakes and generic stores):
 *
 *  1. Start at `store.javaClass`, walk `getGenericSuperclass` up to `Store`,
 *     substituting type variables level-by-level (the `TypeVariable` instances
 *     a signature resolves to are the same objects `getTypeParameters()`
 *     returns, so identity-keyed substitution is sound).
 *  2. Take the resolved `Self` argument's RAW class (a `ParameterizedType`
 *     like `G<Int>` raw-resolves to `G`).
 *  3. Enforce `rawSelf.isInstance(store)` — expressed here as
 *     `rawSelf.isAssignableFrom(javaClass)`, the same condition without
 *     needing the instance — which is exactly the soundness condition of
 *     `this as Self` on the receiver bridge.
 *
 * Fails OPEN everywhere: an unsubstituted `TypeVariable` (a generic base
 * constructed directly), a raw (non-parameterized) step into `Store` (R8
 * strips the `Signature` attribute by default — `consumer-rules.pro` keeps
 * it), a class-file signature the JVM cannot resolve, or any reflection
 * failure skips the check. Never a false positive on a legal shape.
 *
 * The verdict is cached per `Class` — the test suites construct stores by the
 * hundreds.
 */
internal actual fun checkCrtpSelfTypeAtConstruction(store: Store<*>) {
    when (val verdict = verdictFor(store.javaClass)) {
        is CrtpVerdict.Violation ->
            throw IllegalStateException(
                constructionTeachingMessage(store.javaClass, verdict.declaredSelf),
            )
        CrtpVerdict.Ok, CrtpVerdict.Open -> Unit
    }
}

/** The per-class outcome of the walk; `Open` is "no verdict — check skipped". */
private sealed class CrtpVerdict {
    data object Ok : CrtpVerdict()

    data class Violation(
        val declaredSelf: Class<*>,
    ) : CrtpVerdict()

    data object Open : CrtpVerdict()
}

private val verdicts = ConcurrentHashMap<Class<*>, CrtpVerdict>()

private fun verdictFor(javaClass: Class<*>): CrtpVerdict = verdicts.getOrPut(javaClass) { resolveVerdict(javaClass) }

/** The resolution itself never throws: every failure mode fails open. */
private fun resolveVerdict(javaClass: Class<*>): CrtpVerdict =
    runCatching { resolveVerdictOrThrow(javaClass) }
        .getOrElse { CrtpVerdict.Open }

private fun resolveVerdictOrThrow(javaClass: Class<*>): CrtpVerdict {
    val selfArgument =
        when (val resolved = resolveSelfArgument(javaClass)) {
            // Missing signature data (raw `Store` superclass, e.g. an R8 build
            // without the consumer rules) or a walk that never resolved.
            null -> return CrtpVerdict.Open
            // A generic base constructed directly: Self stays one of ITS OWN
            // type variables — nothing to enforce against, fail open.
            is TypeVariable<*> -> return CrtpVerdict.Open
            else -> resolved
        }
    val rawSelf =
        rawClassOf(selfArgument) ?: return CrtpVerdict.Open
    return if (rawSelf.isAssignableFrom(javaClass)) {
        CrtpVerdict.Ok
    } else {
        CrtpVerdict.Violation(rawSelf)
    }
}

/**
 * Walk `javaClass`'s superclass chain to `Store` and return its resolved
 * `Self` argument; `null` when the signature data needed to resolve it is
 * missing or malformed (fail open).
 */
private fun resolveSelfArgument(javaClass: Class<*>): Type? {
    var substitutions: Map<TypeVariable<*>, Type> = emptyMap()
    var current: Class<*> = javaClass
    while (true) {
        if (current == STORE_CLASS) return null
        when (val supertype = current.genericSuperclass) {
            null -> return null
            is ParameterizedType -> {
                val raw = supertype.rawType as? Class<*> ?: return null
                val arguments = supertype.actualTypeArguments.map { substitute(it, substitutions) }
                if (raw == STORE_CLASS) return arguments.singleOrNull()
                val parameters = raw.typeParameters
                if (parameters.size != arguments.size) return null
                substitutions = parameters.zip(arguments).toMap()
                current = raw
            }
            // A raw (non-generic) step — either a non-generic intermediate
            // (`class Derived : Base()`, `Base : Store<Base>()`) or a stripped
            // signature. Type variables from below can no longer flow through
            // it; if `Store` is next and its argument is one of this level's
            // own variables, the TypeVariable branch downstream fails open.
            is Class<*> -> {
                substitutions = emptyMap()
                current = supertype
            }
            else -> return null
        }
    }
}

/** Resolve [type] through [substitutions] until it stops being a mapped variable. */
private fun substitute(
    type: Type,
    substitutions: Map<TypeVariable<*>, Type>,
): Type {
    var current = type
    while (current is TypeVariable<*>) {
        val mapped = substitutions[current] ?: return current
        // Pathological self-map guard; real hierarchies cannot build one.
        if (mapped === current) return current
        current = mapped
    }
    return current
}

/** The raw class of a resolvable [Type]; `null` for shapes that cannot hold `this`. */
private fun rawClassOf(type: Type): Class<*>? =
    when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        else -> null
    }

private val STORE_CLASS: Class<*> = Store::class.java

/**
 * The two-type teaching message (wording pinned by
 * `CrtpSelfTypeConstructionTest`): names both the concrete class and the
 * wrong declared `Self`.
 */
private fun constructionTeachingMessage(
    javaClass: Class<*>,
    declaredSelf: Class<*>,
): String {
    val name = javaClass.simpleName.ifEmpty { javaClass.name }
    val declared = declaredSelf.simpleName.ifEmpty { declaredSelf.name }
    return "class $name extends Store<$declared>; the type argument must be $name itself — " +
        "declare it as `class $name : Store<$name>()`. Every Store member-extension " +
        "(action { } bodies, mutate, effect, bridge) resolves against Self, so a store " +
        "anchored to another type fails the first time it is used."
}
