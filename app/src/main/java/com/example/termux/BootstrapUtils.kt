package com.example.termux

import java.io.File

/** Pure helpers (unit tested). */
object BootstrapUtils {

    /** Termux SYMLINKS.txt separator: target←linkPath */
    private const val ARROW = '←'

    data class SymlinkEntry(val target: String, val linkPath: String)

    fun parseSymlinks(text: String): List<SymlinkEntry> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf(ARROW)
                if (i <= 0 || i == line.length - 1) null
                else SymlinkEntry(line.substring(0, i), line.substring(i + 1))
            }
            .toList()

    /**
     * Resolves [name] inside [base]; returns null if the result would escape [base]
     * (zip-slip protection) or the name is absolute / empty.
     */
    fun safeResolve(base: File, name: String): File? {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000')) return null
        val baseCanon = base.canonicalFile
        val out = File(baseCanon, name).canonicalFile
        val basePath = baseCanon.path
        return if (out.path == basePath || out.path.startsWith(basePath + File.separator)) out else null
    }

    /** Files Termux marks executable (0700) after extraction. */
    fun needsExecutable(entryName: String): Boolean =
        entryName.startsWith("bin/") ||
            entryName.startsWith("libexec") ||
            entryName.startsWith("lib/apt/apt-helper") ||
            entryName.startsWith("lib/apt/methods")

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
