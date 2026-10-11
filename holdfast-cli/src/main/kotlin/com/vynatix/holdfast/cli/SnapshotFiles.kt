package com.vynatix.holdfast.cli

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.StoreSnapshot
import java.io.IOException
import java.nio.file.Path

/** What the CLI can see of one decoded snapshot, all through the public API. */
internal data class SnapshotFacts(
    val schemaVersion: Int,
    val stateNames: Set<String>,
    val unencodableStateNames: Set<String>,
    /** The redaction-aware rendering (`<redacted>` for secrets, keyed entries by key). */
    val rendered: String,
)

/** What decoding one body produced. */
internal sealed interface DecodeResult {
    data class Decoded(
        val facts: SnapshotFacts,
    ) : DecodeResult

    /** The body [com.vynatix.holdfast.StoreSnapshot.decode] refused. */
    data class Rejected(
        val message: String,
    ) : DecodeResult
}

/** Decode `text` into reportable facts, or say why the library rejected it. */
@OptIn(ExperimentalStoreApi::class)
internal fun decodeSnapshotFacts(text: String): DecodeResult =
    try {
        val snapshot = StoreSnapshot.decode(text)
        DecodeResult.Decoded(
            SnapshotFacts(
                schemaVersion = snapshot.schemaVersion,
                stateNames = snapshot.stateNames,
                unencodableStateNames = snapshot.unencodableStateNames,
                rendered = snapshot.render(),
            ),
        )
    } catch (e: SnapshotFormatException) {
        DecodeResult.Rejected(e.message ?: "not an encoded store snapshot")
    }

/** The canonical encoding of `text`'s body: what the writer would emit for it. */
@OptIn(ExperimentalStoreApi::class)
internal fun canonicalForm(text: String): String = StoreSnapshot.decode(text).encode()

/** True when `text` byte-matches what the writer would emit for the same body. */
internal fun isCanonicalEncoding(text: String): Boolean = canonicalForm(text) == text.trim()

internal fun readSnapshotFile(
    label: String,
    path: Path,
): String =
    try {
        path.toFile().readText()
    } catch (e: IOException) {
        throw SnapshotFileUnreadable(label, path, e.message ?: e::class.simpleName.orEmpty())
    }

/** Thrown for I/O failures; rendered as a plain CLI error, never a stack trace. */
internal class SnapshotFileUnreadable(
    label: String,
    path: Path,
    reason: String,
) : Exception("cannot read $label file $path: $reason")
