package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path

/** What `validate` found in one body. */
internal sealed interface ValidationOutcome {
    /** The library decoded it; [canonical] says whether the bytes are writer-canonical. */
    data class Valid(
        val canonical: Boolean,
    ) : ValidationOutcome

    /** The library refused it; [message] carries the offset-bearing reason. */
    data class Invalid(
        val message: String,
    ) : ValidationOutcome
}

internal fun validateSnapshotText(text: String): ValidationOutcome =
    when (val decoded = decodeSnapshotFacts(text)) {
        // isCanonicalEncoding re-decodes; the first decode already proved the text readable,
        // so the second cannot throw for content reasons.
        is DecodeResult.Decoded -> ValidationOutcome.Valid(canonical = isCanonicalEncoding(text))
        is DecodeResult.Rejected -> ValidationOutcome.Invalid(decoded.message)
    }

/** `holdfast snapshot validate FILE [--strict]` — decode check plus canonicality note. */
internal class ValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context): String = "Check a file decodes as a store snapshot; report canonicality."

    private val file: Path by
        argument(help = "path to a file holding one store snapshot body").path(
            mustExist = true,
            canBeDir = false,
            canBeFile = true,
        )

    private val strict by
        option(
            "--strict",
            help = "fail on non-canonical text, not only on undecodable text",
        ).flag()

    override fun run() {
        val text = readSnapshotFileOrExit("snapshot", file)
        when (val outcome = validateSnapshotText(text)) {
            is ValidationOutcome.Invalid -> throw CliktError("invalid: ${outcome.message}")
            is ValidationOutcome.Valid -> {
                val report = validateReport(outcome)
                if (strict && !outcome.canonical) throw CliktError(report)
                echo(report)
            }
        }
    }
}

internal fun validateReport(outcome: ValidationOutcome.Valid): String =
    buildString {
        appendLine("valid: decodes as a store snapshot")
        if (outcome.canonical) {
            appendLine("canonical: yes (byte-identical to what the writer emits)")
        } else {
            appendLine(
                "canonical: no — the reader accepts any member order, whitespace, and bodies encoded " +
                    "with includeRemote; the writer always emits canonical text. Equal bodies encode " +
                    "to equal text, so non-canonical input still restores correctly.",
            )
        }
    }
