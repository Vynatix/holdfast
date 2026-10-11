package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/** The `snapshot` command group: inspect, validate, diff. */
internal class SnapshotCommand : CliktCommand(name = "snapshot") {
    override fun help(context: Context): String = "Inspect, validate, and diff encoded store snapshots."

    override fun run() {
        // Bare invocation: clikt prints help and exits non-zero.
    }
}
