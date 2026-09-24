package com.vynatix.holdfast

// The v1 wire format of one store's snapshot (issue #20, R1):
//
//   {"format":"holdfast.store","v":1,"schema":N,"states":{name: text|null|family},"skipped":[name, …]}
//
// `states` holds each encodable state's codec text by name; `null` stands for
// a value withheld from the encoding (the redaction marker), and an object for
// a keyed state family — reserved: read and kept, never written yet.
// `skipped` names the states the store declares but could not encode (they
// have no codec). The writer is canonical: fields in this order, states and
// skipped names sorted, no whitespace, fixed escaping (SnapshotJsonWriter.kt),
// so equal bodies encode to equal text. The reader accepts any member order
// and whitespace, and skips fields it does not know.
//
// The body is a self-contained JSON object, written and read at the current
// position of a writer or reader, so an enclosing document (a tree of stores)
// can embed store bodies verbatim.

/** The `format` of an encoded store snapshot. */
internal const val STORE_SNAPSHOT_FORMAT = "holdfast.store"

/** The wire-format version this Holdfast writes, and the only one it reads. */
internal const val STORE_SNAPSHOT_VERSION = 1

/** What the v1 body of one store's snapshot holds. */
internal data class StoreBody(
    /** The schema version of the store the body was taken from. */
    val schema: Int,
    /** Codec text by state name; `null` for a value withheld from the encoding. */
    val states: Map<String, String?>,
    /** Keyed state families by name, as the JSON object text a newer writer wrote. Read and kept, never written. */
    val families: Map<String, String>,
    /** The states the store declares but could not encode. */
    val skipped: Set<String>,
)

/** Write [body] as a JSON object at the writer's position. */
internal fun SnapshotJsonWriter.writeStoreBody(body: StoreBody) {
    beginObject()
    name("format")
    value(STORE_SNAPSHOT_FORMAT)
    name("v")
    value(STORE_SNAPSHOT_VERSION)
    name("schema")
    value(body.schema)
    name("states")
    beginObject()
    for (state in body.states.keys.sorted()) {
        name(state)
        value(body.states.getValue(state))
    }
    endObject()
    name("skipped")
    beginArray()
    body.skipped.sorted().forEach { value(it) }
    endArray()
    endObject()
}

/**
 * Read the store body at the reader's position.
 *
 * @throws SnapshotFormatException if it is not a v1 store body.
 */
internal fun SnapshotJsonReader.readStoreBody(): StoreBody {
    val fields = BodyFields(start = position)
    beginObject()
    while (hasNext()) {
        val at = position
        val field = nextName()
        if (field in KNOWN_FIELDS && !fields.seen.add(field)) snapshotFormatError("duplicate field \"$field\"", at)
        when (field) {
            "format" -> fields.format = nextStringOrNull("the format name")
            "v" -> fields.version = nextInt("the format version")
            "schema" -> fields.schema = nextInt("the schema version")
            "states" -> readStates(fields)
            "skipped" -> readSkipped(fields)
            else -> skipValue()
        }
    }
    endContainer()
    return fields.toBody()
}

/** The text of a whole document holding one store body. */
internal fun encodeStoreDocument(body: StoreBody): String {
    val writer = SnapshotJsonWriter()
    writer.writeStoreBody(body)
    return writer.toString()
}

/**
 * The body of [text], a whole document holding one store body and nothing else.
 *
 * @throws SnapshotFormatException if it is not one.
 */
internal fun decodeStoreDocument(text: String): StoreBody {
    val reader = SnapshotJsonReader(text)
    val body = reader.readStoreBody()
    reader.endDocument()
    return body
}

private val KNOWN_FIELDS = setOf("format", "v", "schema", "states", "skipped")

private fun SnapshotJsonReader.readStates(into: BodyFields) {
    beginObject()
    while (hasNext()) {
        val at = position
        val name = nextName()
        if (name in into.states || name in into.families) snapshotFormatError("duplicate state \"$name\"", at)
        if (peek() == JsonKind.Object) {
            into.families[name] = captureValue()
        } else {
            into.states[name] = nextStringOrNull("a state's text, null or a family object")
        }
    }
    endContainer()
}

private fun SnapshotJsonReader.readSkipped(into: BodyFields) {
    beginArray()
    while (hasNext()) {
        val at = position
        val name = nextStringOrNull("a state name") ?: snapshotFormatError("expected a state name", at)
        if (!into.skipped.add(name)) snapshotFormatError("duplicate skipped state \"$name\"", at)
    }
    endContainer()
}

/** The fields of a body being read, validated once it is complete. */
private class BodyFields(
    val start: Int,
) {
    val seen = HashSet<String>()
    var format: String? = null
    var version: Int? = null
    var schema: Int? = null
    val states = LinkedHashMap<String, String?>()
    val families = LinkedHashMap<String, String>()
    val skipped = LinkedHashSet<String>()

    fun toBody(): StoreBody {
        fun fail(problem: String): Nothing = snapshotFormatError(problem, start)
        if (format != STORE_SNAPSHOT_FORMAT) {
            fail("not a Holdfast store snapshot (\"format\" is not \"$STORE_SNAPSHOT_FORMAT\")")
        }
        val v = version ?: fail("the \"v\" field is missing")
        if (v != STORE_SNAPSHOT_VERSION) {
            fail("format version $v is not readable by this Holdfast, which reads version $STORE_SNAPSHOT_VERSION")
        }
        val schemaVersion = schema?.takeIf { it > 0 } ?: fail("the \"schema\" field is missing or not positive")
        if ("states" !in seen) fail("the \"states\" field is missing")
        val both = skipped.firstOrNull { it in states || it in families }
        if (both != null) fail("state \"$both\" is both encoded and skipped")
        return StoreBody(schemaVersion, states.toMap(), families.toMap(), skipped.toSet())
    }
}
