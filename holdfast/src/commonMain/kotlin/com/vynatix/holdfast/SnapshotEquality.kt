package com.vynatix.holdfast

// When two snapshots are equal (issue #20, plan D11).
//
// Public equality is value equality of what each snapshot holds: two captured
// snapshots are equal when they hold the same state names with `==` raw
// values, and the same keyed state families with the same keys and `==` raw
// values, at the same schema version — whichever store instances took them, so
// a store after `reset()` and a freshly constructed one have equal snapshots
// (unless a keyed state family has live entries: `reset()` resets them but
// never evicts them, so `evictAll()` in the same action to match a new store).
// Derived backing states take no part: their names and values belong to one
// instance. Nor do codecs (user codecs are often per-instance objects that
// compare by identity), so two equal captured snapshots from stores whose
// states declare different codecs can encode to different text. Two decoded
// snapshots are equal when their encoded bodies are. A captured and a decoded
// snapshot are never equal: one holds values, the other texts, and only a
// codec relates the two.
//
// That relation is the encodable projection: what `encode()` writes. A
// snapshot survives a round trip through text on that projection only — the
// states without a codec keep their names but lose their values — so the
// round-trip guarantee is `decode(s.encode()).equalsEncodable(s)`.

/** [StoreSnapshot.equals] for this content. */
internal fun SnapshotContent.valueEquals(other: SnapshotContent): Boolean =
    when {
        this is CapturedContent && other is CapturedContent ->
            schema == other.schema && rawValues == other.rawValues && familyValues == other.familyValues
        this is DecodedContent && other is DecodedContent -> body == other.body
        else -> false
    }

/** [StoreSnapshot.hashCode] for this content, consistent with [valueEquals]. */
internal fun SnapshotContent.valueHashCode(): Int =
    when (this) {
        is CapturedContent -> rawValues.hashCode() xor schema xor familyValues.hashCode()
        is DecodedContent -> body.hashCode()
    }

/**
 * Whether this snapshot and [other] encode to the same text: equal on the
 * encodable projection. Unlike `==`, it relates a captured snapshot to the
 * decoded text of its [StoreSnapshot.encode].
 *
 * @throws IllegalStateException if a codec fails to encode a captured value.
 */
internal fun StoreSnapshot.equalsEncodable(other: StoreSnapshot): Boolean = content.toBody() == other.content.toBody()

/** Each captured keyed state family's raw values by key: what captured equality compares of families. */
private val CapturedContent.familyValues: Map<String, Map<Any, Any>>
    get() = families.mapValues { it.value.entries }
