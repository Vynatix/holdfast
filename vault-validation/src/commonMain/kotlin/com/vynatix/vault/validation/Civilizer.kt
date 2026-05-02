package com.vynatix.vault.validation

/**
 * Civilizes raw primitives [P] into typed domain objects [O] at the boundary of
 * your system.
 *
 * A civilizer holds an ordered list of [Variation]s; [of] picks the first
 * matching variation and calls its `create`. If no variation matches, [of]
 * throws [CivilizationException] — which, when invoked inside a Vault
 * transaction, rolls the transaction back atomically.
 *
 * **Quick start (extending [SimpleCivilizer]):**
 * ```kotlin
 * data class Email(override val value: String) : Civilizable<String>
 *
 * object EmailCivilizer : SimpleCivilizer<String, Rule<String>, Email>() {
 *     override val variations = listOf(
 *         Variation.of<String, Rule<String>, Email>(
 *             rule = { it.contains('@') && it.length <= 254 },
 *         ) { Email(it) },
 *     )
 * }
 *
 * val email: Email = EmailCivilizer of "alice@example.com"
 * ```
 *
 * Pair with [CivilizingTransformer] to enforce the same invariant on every
 * Vault state write.
 */
interface Civilizer<P, R : Rule<P>, O : Civilizable<P>> {
    val variations: List<Variation<P, R, O>>

    /**
     * Civilize [primitive] into [O]. Throws [CivilizationException] if no
     * variation matches.
     */
    infix fun of(primitive: P): O {
        val match = variations.firstOrNull { it.matches(primitive) }
            ?: throw CivilizationException(
                message = "No variation matches primitive: $primitive",
                primitive = primitive,
            )
        return match.create(primitive)
    }

    /** Whether at least one variation accepts [primitive]. */
    fun validate(primitive: P): Boolean = variations.any { it.matches(primitive) }

    /** Civilize, returning null on rejection. Use when failure is expected. */
    fun ofOrNull(primitive: P): O? = variations.firstOrNull { it.matches(primitive) }?.create(primitive)
}

/**
 * Convenience base for civilizers whose variations are static. Subclasses just
 * override [variations].
 */
abstract class SimpleCivilizer<P, R : Rule<P>, O : Civilizable<P>> : Civilizer<P, R, O>

/**
 * Thrown by [Civilizer.of] (and [CivilizingTransformer.set]) when a primitive
 * cannot be civilized. Carries the rejected primitive in [primitive] for
 * diagnostics; inside a Vault transaction, the throw rolls the transaction
 * back atomically.
 */
class CivilizationException(message: String, val primitive: Any? = null, cause: Throwable? = null) : RuntimeException(message, cause)
