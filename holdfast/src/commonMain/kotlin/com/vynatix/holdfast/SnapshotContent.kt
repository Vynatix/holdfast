@file:OptIn(ExperimentalStoreApi::class)

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

    /** Every state (and keyed state family) name the snapshot knows, with or without a value. */
    abstract val names: Set<String>

    /** The states (and keyed state families) the snapshot's store declares but could not encode. */
    abstract val unencodable: Set<String>

    /**
     * This content as a v1 store body: the encodable projection. Remote
     * states are left out unless [includeRemote], and Secret states are
     * written as `null` (captured content only: decoded text carries no tags).
     */
    abstract fun toBody(includeRemote: Boolean = false): StoreBody
}

/** The store instance, and its class, that took a captured snapshot. */
internal class CaptureOrigin(
    /** [Store.lockOrderKey] of the store captured. */
    val key: Long,
    /** The class of the store captured. */
    val storeClass: KClass<*>,
)

/** A capture's [SnapshotScope], and which of its states carry the tags a snapshot enforces. */
internal class CaptureTags(
    val scope: SnapshotScope,
    /**
     * The captured states and keyed state families tagged [StateTag.Secret]:
     * never encoded, rendered or (outside [SnapshotScope.Raw]) read.
     */
    val secret: Set<String>,
    /** The captured states and keyed state families tagged [StateTag.Remote]: encoded only with `includeRemote`. */
    val remote: Set<String>,
) {
    companion object {
        /** A snapshot made by hand: scope [SnapshotScope.All], no tagged state. */
        val None = CaptureTags(SnapshotScope.All, emptySet(), emptySet())
    }
}

/** What [Store.snapshot] captures. */
internal class CapturedContent(
    /** Raw values of the declared (and internally registered) states, in declaration order. */
    val rawValues: Map<String, Any>,
    /** Raw values of derived backing states; restored only into the store with [originKey]. */
    val derivedBackingValues: Map<String, Any> = emptyMap(),
    /** The store instance that took the snapshot, or `null` for a snapshot made by hand. */
    val origin: CaptureOrigin? = null,
    /** The codec of each state in [rawValues] that has one. */
    val codecs: Map<String, StateCodec<*>> = emptyMap(),
    /** The schema version of the store captured ([SchemaVersioned]); 1 for a snapshot made by hand. */
    override val schema: Int = DEFAULT_SCHEMA_VERSION,
    /** The capture's scope and tagged states. */
    val tags: CaptureTags = CaptureTags.None,
    /** The keyed state families captured, by name: each one's live entries (KeyedSnapshot.kt). */
    val families: Map<String, CapturedFamily> = emptyMap(),
) : SnapshotContent() {
    /** [Store.lockOrderKey] of the store captured, or `null` for a snapshot made by hand. */
    val originKey: Long? get() = origin?.key

    /** The class of the store captured, or `null` for a snapshot made by hand. */
    val originClass: KClass<*>? get() = origin?.storeClass

    override val names: Set<String> get() = rawValues.keys + families.keys

    override val unencodable: Set<String>
        get() = rawValues.keys - codecs.keys + families.filterValues { !it.encodable }.keys

    override fun toBody(includeRemote: Boolean): StoreBody {
        val written = { name: String -> includeRemote || name !in tags.remote }
        val texts = LinkedHashMap<String, String?>()
        for ((name, codec) in codecs) {
            if (!written(name)) continue
            // A Secret value never reaches its codec: `null` is the withheld marker.
            texts[name] = if (name in tags.secret) null else encodeValue(name, codec, rawValues.getValue(name))
        }
        val familyTexts = LinkedHashMap<String, Map<String, String?>>()
        for ((name, family) in families) {
            if (written(name) && family.encodable) familyTexts[name] = family.encodeEntries(name, name in tags.secret)
        }
        return StoreBody(schema, texts, familyTexts, unencodable.filterTo(LinkedHashSet(), written))
    }
}

/** What [StoreSnapshot.decode] read. */
internal class DecodedContent(
    val body: StoreBody,
) : SnapshotContent() {
    override val schema: Int get() = body.schema

    override val names: Set<String> get() = body.states.keys + body.families.keys + body.skipped

    override val unencodable: Set<String> get() = body.skipped

    /**
     * The text carries no tags, so it is written back as it was read —
     * keyed state families included — whatever [includeRemote] says.
     */
    override fun toBody(includeRemote: Boolean): StoreBody = body
}

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
 * captured state's raw value (a Secret state's withheld), a decoded state's
 * text as a JSON string — plus the states it has no value for. See
 * [StoreSnapshot.render].
 */
internal fun SnapshotContent.render(): String =
    buildString {
        append("StoreSnapshot (schema ").append(schema).append(renderKind())
        for (name in names.sorted()) {
            append("\n  ").append(name)
            when (this@render) {
                is CapturedContent -> append(renderCaptured(name))
                is DecodedContent -> append(renderDecoded(name))
            }
        }
    }

/** How [render]'s header names the snapshot's kind: decoded, or a capture's scope other than [SnapshotScope.All]. */
private fun SnapshotContent.renderKind(): String =
    when {
        this is DecodedContent -> ", decoded)"
        this is CapturedContent && tags.scope !== SnapshotScope.All -> ", scope ${tags.scope})"
        else -> ")"
    }

private fun CapturedContent.renderCaptured(name: String): String {
    val secret = name in tags.secret
    val family = families[name] ?: return " = ${renderedValue(secret, rawValues.getValue(name))}"
    return renderCapturedFamily(family, secret)
}

private fun DecodedContent.renderDecoded(name: String): String =
    when {
        name in body.families -> renderDecodedFamily(body.families.getValue(name))
        name in body.skipped -> " (not encoded)"
        else -> body.states.getValue(name)?.let { " = " + buildString { appendJsonString(it) } } ?: " = $REDACTED_TEXT"
    }

/**
 * How [StoreSnapshot.render] shows a captured family: its size, then one line
 * per entry sorted by key — the key's `toString()` and the raw value, or
 * `<redacted>` for a [secret] family's.
 */
internal fun renderCapturedFamily(
    family: CapturedFamily,
    secret: Boolean,
): String =
    buildString {
        append(" = keyed state family (").append(family.entries.size).append(" entries)")
        for ((key, raw) in family.entries.entries.sortedBy { it.key.toString() }) {
            append("\n    [").append(key).append("] = ").append(renderedValue(secret, raw))
        }
    }

/**
 * How [StoreSnapshot.render] shows a decoded family: its size, then one line
 * per entry sorted by encoded key — key and text as JSON strings, a withheld
 * value as `<redacted>`.
 */
internal fun renderDecodedFamily(texts: Map<String, String?>): String =
    buildString {
        append(" = keyed state family (").append(texts.size).append(" entries)")
        for (key in texts.keys.sorted()) {
            append("\n    [")
            appendJsonString(key)
            append("] = ")
            texts.getValue(key)?.let { appendJsonString(it) } ?: append(REDACTED_TEXT)
        }
    }
