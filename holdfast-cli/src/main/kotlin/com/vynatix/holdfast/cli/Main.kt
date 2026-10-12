package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

/**
 * Entry point of the `holdfast` debug CLI. Everything here analyzes artifacts
 * files already contain — the CLI never connects to a running process and
 * never decodes a state value itself: [com.vynatix.holdfast.StoreSnapshot]
 * does all parsing, so what you see is exactly what `restore` would see.
 */
public fun main(args: Array<String>) {
    HoldfastRootCommand()
        .subcommands(
            SnapshotCommand().subcommands(InspectCommand(), ValidateCommand(), DiffCommand()),
            TraceCommand().subcommands(RenderTraceCommand(), TraceStatsCommand()),
            RunCommand(),
            DoctorCommand(),
        ).main(args)
}

private class HoldfastRootCommand : CliktCommand(name = "holdfast") {
    override fun help(context: Context): String = "Offline debugging of holdfast artifacts (snapshots and traces)."

    override fun run() {
        // Invoked without a subcommand: clikt prints help and exits non-zero.
    }
}
