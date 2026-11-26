package com.example.ui.files

import java.io.File
import java.util.Locale

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long
)

/** Pure helpers for the Files tab (unit tested). */
object FileBrowserModel {
    /** Folders first, then files, each sorted by name (case-insensitive); null if the folder cannot be read. */
    fun list(dir: File): List<FileEntry>? {
        val files = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return null
        return files
            .map { FileEntry(it.name, it.path, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    fun cdCommand(path: String): String = "cd ${shellQuote(path)}"

    fun viewCommand(path: String): String = "less ${shellQuote(path)}"

    /** The folder above [dir], or null when [dir] is already the top of this location. */
    fun parentWithin(dir: File, root: File): File? =
        if (dir.path == root.path) null else dir.parentFile
}
