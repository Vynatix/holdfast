package com.vynatix.holdfast.platform

import com.vynatix.holdfast.Store
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Walk the instance's superclass chain to the `Store<…>` parameterization and,
 * when the type argument is a concrete class, require it to equal the instance's
 * own class. A type-variable argument (generic intermediate base) is skipped.
 */
internal actual fun validateCrtpSelfType(store: Store<*>) {
    val instanceClass: Class<*> = store::class.java
    val selfArgument = resolveStoreSelfArgument(instanceClass) ?: return
    if (selfArgument is Class<*> && selfArgument != instanceClass) {
        error(
            "${instanceClass.simpleName} declares Store<${selfArgument.simpleName}> but the CRTP " +
                "Self type parameter must be the declaring class itself. Change the supertype to " +
                "Store<${instanceClass.simpleName}> (a wrong Self otherwise surfaces as a swallowed " +
                "ClassCastException deep inside the store DSL).",
        )
    }
}

/** The actual type argument passed to the nearest `Store<…>` in [start]'s chain, or null. */
private fun resolveStoreSelfArgument(start: Class<*>): Type? {
    var current: Class<*>? = start
    while (current != null) {
        val genericSuper = current.genericSuperclass
        if (genericSuper is ParameterizedType && genericSuper.rawType == Store::class.java) {
            return genericSuper.actualTypeArguments.firstOrNull()
        }
        current = current.superclass
    }
    return null
}
