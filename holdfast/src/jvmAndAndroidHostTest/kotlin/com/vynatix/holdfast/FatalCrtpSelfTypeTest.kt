package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61: the construction-time CRTP check (`platform/CrtpSelfTypeCheck.kt`)
// fails OPEN on any reflection failure — except a fatal error, which
// propagates out of the store's constructor and caches no verdict, so the
// next construction of the class walks again (and still catches the
// violation). To raise one inside the walk, the mistyped store is defined by
// a class loader that throws while resolving the store's declared `Self`,
// which only the class's `Store<…>` signature names.

/** The probe's declared `Self`: a legal store, just not the probe. */
internal class FatalCrtpProbeAnchor : Store<FatalCrtpProbeAnchor>()

/** `class Foo : Store<Bar>()`, defined again by [FatalCrtpProbeLoader] so its signature resolves through it. */
internal class FatalCrtpProbeMistyped : Store<FatalCrtpProbeAnchor>()

/** Constructs a [FatalCrtpProbeMistyped] of the loader that defined this factory. */
internal class FatalCrtpProbeFactory : () -> Store<*> {
    override fun invoke(): Store<*> = FatalCrtpProbeMistyped()
}

/**
 * Defines its own copies of the probe and its factory from the parent's
 * class files and delegates everything else — but refuses the anchor with
 * [refuse] while that is set, the way a VirtualMachineError would surface
 * while the reflection walk resolves the probe's `Store<…>` signature.
 */
private class FatalCrtpProbeLoader(
    parent: ClassLoader,
) : ClassLoader(parent) {
    @Volatile var refuse: Throwable? = null

    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        // `this`, not `getClassLoadingLock(name)`: Android's ClassLoader has no
        // such method, and for a loader that is not parallel-capable it is `this`.
        synchronized(this) {
            when (name) {
                ANCHOR -> {
                    refuse?.let { throw it }
                    super.loadClass(name, resolve)
                }
                MISTYPED, FACTORY -> findLoadedClass(name) ?: defineFromParent(name)
                else -> super.loadClass(name, resolve)
            }
        }

    private fun defineFromParent(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val bytes = checkNotNull(parent.getResourceAsStream(path)) { "no class file for $name" }.use { it.readBytes() }
        return defineClass(name, bytes, 0, bytes.size)
    }

    companion object {
        val ANCHOR: String = FatalCrtpProbeAnchor::class.java.name
        val MISTYPED: String = FatalCrtpProbeMistyped::class.java.name
        val FACTORY: String = FatalCrtpProbeFactory::class.java.name
    }
}

class FatalCrtpSelfTypeTest {
    @Test
    fun aFatalErrorDuringTheWalkPropagatesFromTheConstructorAndCachesNoVerdict() {
        val loader = FatalCrtpProbeLoader(FatalCrtpSelfTypeTest::class.java.classLoader)

        @Suppress("UNCHECKED_CAST")
        val construct =
            loader.loadClass(FatalCrtpProbeLoader.FACTORY).getDeclaredConstructor().newInstance() as () -> Store<*>
        val fatal = StackOverflowError()
        loader.refuse = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { construct() })

        // No `Open` verdict was cached for the class: the next construction walks again.
        loader.refuse = null
        val violation = assertFailsWith<IllegalStateException> { construct() }
        assertTrue(
            violation.message.orEmpty().startsWith(
                "class FatalCrtpProbeMistyped extends Store<FatalCrtpProbeAnchor>;",
            ),
            violation.message,
        )
        assertEquals(violation.message, assertFailsWith<IllegalStateException> { construct() }.message)

        // Control: the parent's own copy of the class is checked the same way.
        assertFailsWith<IllegalStateException> { FatalCrtpProbeMistyped() }
    }
}
