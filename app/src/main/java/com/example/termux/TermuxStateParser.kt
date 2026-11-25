package com.example.termux

import android.content.Context
import java.io.File

/** Parsed content of the per-session state file written by cyberorg-integration.sh. */
data class TermuxShellState(
    val exitCode: Int,
    val cwd: String,
    val startSec: Long?,
    val endSec: Long?,
    val command: String,
)

object TermuxStateParser {
    fun parse(content: String): TermuxShellState? {
        var exit: Int? = null
        var cwd: String? = null
        var start: Long? = null
        var end: Long? = null
        var cmd = ""
        content.lineSequence().forEach { line ->
            when {
                line.startsWith("exit=") -> exit = line.removePrefix("exit=").trim().toIntOrNull()
                line.startsWith("cwd=") -> cwd = line.removePrefix("cwd=").trimEnd('\r')
                line.startsWith("start=") -> start = line.removePrefix("start=").trim().toLongOrNull()
                line.startsWith("end=") -> end = line.removePrefix("end=").trim().toLongOrNull()
                line.startsWith("cmd=") -> cmd = line.removePrefix("cmd=").trim()
            }
        }
        val e = exit ?: return null
        val c = cwd ?: return null
        return TermuxShellState(e, c, start, end, cmd)
    }
}

/** Installs the integration script into HOME and makes ~/.bashrc source it. */
object TermuxIntegration {
    const val SCRIPT_NAME = ".cyberorg_integration.sh"
    const val SOURCE_LINE = "[ -f \"\$HOME/$SCRIPT_NAME\" ] && . \"\$HOME/$SCRIPT_NAME\""

    fun install(context: Context, home: File) {
        try {
            home.mkdirs()
            val script = context.assets.open("cyberorg-integration.sh").bufferedReader().use { it.readText() }
            File(home, SCRIPT_NAME).writeText(script)
            val bashrc = File(home, ".bashrc")
            val existing = if (bashrc.exists()) bashrc.readText() else ""
            if (!existing.contains(SCRIPT_NAME)) {
                val prefix = if (existing.isEmpty() || existing.endsWith("\n")) existing else existing + "\n"
                bashrc.writeText(prefix + SOURCE_LINE + "\n")
            }
        } catch (_: Exception) {
            // Integration is optional; the terminal works without it.
        }
    }
}
