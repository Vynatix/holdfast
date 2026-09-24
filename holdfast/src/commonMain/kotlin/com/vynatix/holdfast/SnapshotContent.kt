package com.vynatix.holdfast

import kotlin.reflect.KClass

// What a StoreSnapshot holds (issue #20, R1). A snapshot is one of two kinds:
// CAPTURED by `snapshot()` — raw values in memory, read back through the
// states of the store instance that took it — or DECODED from text by
// `StoreSnapshot.decode` — codec texts by name, read back through any store's
// states of those names, decoding with the reading state's codec.

/** The contents of a [StoreSnapshot]. */
internal sealed class SnapshotContent {
    /** The schema version the snapshot was taken at. */
    abstract val schema: Int

    /** Every state name the snapshot knows, with or without a value. */
    abstract val names: Set<String>

    /** The states the snapshot's store declares but could not encode. */
    abstract val unencodable: Set<String>

    /** This content as a v1 store body: the encodable projection. */
    abstract fun toBody(): StoreBody
}

/** What [Store.snapshot] captures. */
internal class CapturedContent(
    /** Raw values of the declared (and internally registered) states, in declaration order. */
    val rawValues: Map<String, Any>,
    /** Raw values of derived backing states; restored only into the store with [originKey]. */
    val derivedBackingValues: Map<String, Any> = emptyMap(),
    /** [Store.lockOrderKey] of the store captured, or `null` for a snapshot made by hand. */
    val originKey: Long? = null,
    /** The class of the store captured, or `null` for a snapshot made by hand. */
    val originClass: KClass<*>? = null,
    /** The codec of each state in [rawValues] that has one. */
    val codecs: Map<String, StateCodec<*>> = emptyMap(),
) : SnapshotContent() {
    /** Captured stores have no schema versions yet: every capture is version 1. */
    override val schema: Int get() = CAPTURED_SCHEMA_VERSION

    override val names: Set<String> get() = rawValues.keys

    override val unencodable: Set<String> get() = rawValues.keys - codecs.keys

    override fun toBody(): StoreBody {
        val texts = LinkedHashMap<String, String?>()
        for ((name, codec) in codecs) texts[name] = encodeValue(name, codec, rawValues.getValue(name))
        return StoreBody(schema, texts, emptyMap(), unencodable)
    }
}

/** What [StoreSnapshot.decode] read. */
internal class DecodedContent(
    val body: StoreBody,
) : SnapshotContent() {
    override val schema: Int get() = body.schema

    override val names: Set<String> get() = body.states.keys + body.families.keys + body.skipped

    override val unencodable: Set<String> get() = body.skipped

    /** Families a newer writer wrote are kept for reading but never written back (see SnapshotEnvelope.kt). */
    override fun toBody(): StoreBody = body.copy(families = emptyMap())
}

/** The schema version of every captured snapshot, until stores can declare one. */
internal const val CAPTURED_SCHEMA_VERSION = 1

/**
 * [raw] encoded by [codec], for state [name]. A throwing codec is reported
 * without its exception, whose message may quote the value.
 */
private fun encodeValue(
    name: String,
    codec: StateCodec<*>,
    raw: Any,
): String {
    @Suppress("UNCHECKED_CAST")
    val typed = codec as StateCodec<Any>
    return runCatching { typed.encode(raw) }.getOrElse { failure ->
        error(
            "Cannot encode state '$name': its codec threw ${failure.describeClass()}. The exception is not " +
                "attached, because its message may quote the value. Check the state's codec.",
        )
    }
}

/**
 * The snapshot as text for a person: one line per state, sorted by name — a
 * captured state's raw value, a decoded state's text as a JSON string — plus
 * the states it has no value for. See [StoreSnapshot.render].
 */
internal fun SnapshotContent.render(): String =
    buildString {
        append("StoreSnapshot (schema ").append(schema).append(if (this@render is DecodedContent) ", decoded)" else ")")
        for (name in names.sorted()) {
            append("\n  ").append(name)
            when (this@render) {
                is CapturedContent -> append(" = ").append(rawValues.getValue(name))
                is DecodedContent -> append(renderDecoded(name))
            }
        }
    }

private fun DecodedContent.renderDecoded(name: String): String =
    when {
        name in body.families -> " = <keyed state family>"
        name in body.skipped -> " (not encoded)"
        else -> body.states.getValue(name)?.let { " = " + buildString { appendJsonString(it) } } ?: " = <redacted>"
    }
