package com.vynatix.holdfast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/** The holdfast version this CLI build was compiled against (generated resource). */
internal object BuildInfo {
    val holdfastVersion: String by lazy { readHoldfastVersion() }

    private fun readHoldfastVersion(): String {
        val path = "/com/vynatix/holdfast/cli/build-info.properties"
        val line =
            BuildInfo::class.java.getResourceAsStream(path)?.bufferedReader()?.useLines { lines ->
                lines.firstOrNull { it.startsWith("holdfastVersion=") }
            }
        return line?.removePrefix("holdfastVersion=") ?: "unknown (built without generated resources)"
    }
}

/** `holdfast doctor` — what this binary is and what it runs on. */
internal class DoctorCommand : CliktCommand(name = "doctor") {
    override fun help(context: Context): String = "Print the CLI's build and runtime environment."

    override fun run() {
        echo(doctorReport(BuildInfo.holdfastVersion))
    }
}

internal fun doctorReport(holdfastVersion: String): String =
    buildString {
        appendLine("holdfast-cli: unreleased (internal tooling)")
        appendLine("holdfast library: $holdfastVersion")
        appendLine("Kotlin: ${KotlinVersion.CURRENT}")
        appendLine("JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    }
