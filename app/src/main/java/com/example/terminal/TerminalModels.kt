package com.example.terminal

import androidx.compose.runtime.Immutable

/**
 * Single character cell in a VT/ANSI terminal screen grid.
 */
@Immutable
data class TerminalCell(
    val char: Char = ' ',
    val fgColorCode: Int = DEFAULT_FG,
    val bgColorCode: Int = DEFAULT_BG,
    val bold: Boolean = false
) {
    companion object {
        const val DEFAULT_FG = -1
        const val DEFAULT_BG = -1
        val EMPTY = TerminalCell()
    }
}

/**
 * Immutable snapshot of the visible terminal grid + scrollback lines for rendering.
 */
@Immutable
data class TerminalScreenBuffer(
    val cols: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val visibleRows: List<List<TerminalCell>>,
    val scrollbackRows: List<List<TerminalCell>> = emptyList()
) {
    val totalRowCount: Int
        get() = scrollbackRows.size + visibleRows.size

    fun getRow(absIndex: Int): List<TerminalCell> {
        val sbSize = scrollbackRows.size
        return if (absIndex < sbSize) {
            scrollbackRows[absIndex]
        } else {
            visibleRows[absIndex - sbSize]
        }
    }
}

@Immutable
data class TerminalErrorContext(
    val sessionId: String,
    val command: String,
    val exitCode: Int,
    val errorOutput: String,
    val cwd: String
)

/**
 * Translates extra-key taps and sticky modifiers into raw PTY byte sequences.
 */
enum class ExtraKeyCode(
    val label: String,
    val isModifier: Boolean = false
) {
    ESC("ESC"),
    TAB("TAB"),
    CTRL("CTRL", isModifier = true),
    ALT("ALT", isModifier = true),
    ARROW_UP("↑"),
    ARROW_DOWN("↓"),
    ARROW_LEFT("←"),
    ARROW_RIGHT("→"),
    PIPE("|"),
    TILDE("~"),
    SLASH("/"),
    MINUS("-"),
    LBRACE("{"),
    RBRACE("}"),
    LBRACKET("["),
    RBRACKET("]"),
    DOLLAR("$"),
    AMPERSAND("&"),
    GREATER(">");

    fun toByteSequence(): ByteArray? = when (this) {
        ESC -> byteArrayOf(0x1B)
        TAB -> byteArrayOf(0x09)
        CTRL, ALT -> null
        ARROW_UP -> byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
        ARROW_DOWN -> byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
        ARROW_RIGHT -> byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())
        ARROW_LEFT -> byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())
        PIPE -> "|".encodeToByteArray()
        TILDE -> "~".encodeToByteArray()
        SLASH -> "/".encodeToByteArray()
        MINUS -> "-".encodeToByteArray()
        LBRACE -> "{".encodeToByteArray()
        RBRACE -> "}".encodeToByteArray()
        LBRACKET -> "[".encodeToByteArray()
        RBRACKET -> "]".encodeToByteArray()
        DOLLAR -> "$".encodeToByteArray()
        AMPERSAND -> "&".encodeToByteArray()
        GREATER -> ">".encodeToByteArray()
    }
}

object TerminalInputEncoder {
    /**
     * Encodes typed text with optional sticky CTRL/ALT modifiers into raw PTY bytes.
     * For example: CTRL + 'c' -> 0x03 (ETX), CTRL + 'l' -> 0x0C, ALT + 'b' -> 0x1B 'b'.
     */
    fun encodeInput(text: String, ctrlArmed: Boolean, altArmed: Boolean): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (ctrlArmed && text.length == 1) {
            val ch = text[0].lowercaseChar()
            if (ch in 'a'..'z') {
                return byteArrayOf((ch.code - 'a'.code + 1).toByte())
            }
        }
        val raw = text.encodeToByteArray()
        return if (altArmed) {
            byteArrayOf(0x1B) + raw
        } else {
            raw
        }
    }
}

@Immutable
data class TerminalGridPoint(
    val row: Int,
    val col: Int
) : Comparable<TerminalGridPoint> {
    override fun compareTo(other: TerminalGridPoint): Int {
        return if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
    }
}

@Immutable
data class TerminalGridSelection(
    val anchor: TerminalGridPoint,
    val focus: TerminalGridPoint
) {
    val start: TerminalGridPoint get() = if (anchor <= focus) anchor else focus
    val end: TerminalGridPoint get() = if (anchor <= focus) focus else anchor

    fun contains(row: Int, col: Int): Boolean {
        val p = TerminalGridPoint(row, col)
        return p >= start && p <= end
    }

    fun extractText(buffer: TerminalScreenBuffer): String {
        val total = buffer.totalRowCount
        if (total == 0) return ""
        val minRow = start.row.coerceIn(0, total - 1)
        val maxRow = end.row.coerceIn(0, total - 1)
        if (minRow > maxRow) return ""

        val out = StringBuilder()
        val lineBuf = StringBuilder()
        for (r in minRow..maxRow) {
            if (r > minRow) out.append('\n')
            val rowCells = buffer.getRow(r)
            if (rowCells.isEmpty()) continue
            val cStart = if (r == minRow) start.col.coerceIn(0, rowCells.lastIndex) else 0
            val cEnd = if (r == maxRow) end.col.coerceIn(0, rowCells.lastIndex) else rowCells.lastIndex
            if (cStart <= cEnd) {
                lineBuf.setLength(0)
                for (c in cStart..cEnd) {
                    lineBuf.append(rowCells[c].char)
                }
                var endTrim = lineBuf.length
                while (endTrim > 0 && lineBuf[endTrim - 1].isWhitespace()) {
                    endTrim--
                }
                if (endTrim > 0) {
                    out.append(lineBuf, 0, endTrim)
                }
            }
        }
        return out.toString().trim()
    }

    fun extractText(rows: List<List<TerminalCell>>): String {
        if (rows.isEmpty()) return ""
        val minRow = start.row.coerceIn(0, rows.lastIndex)
        val maxRow = end.row.coerceIn(0, rows.lastIndex)
        if (minRow > maxRow) return ""

        val lines = mutableListOf<String>()
        for (r in minRow..maxRow) {
            val rowCells = rows[r]
            if (rowCells.isEmpty()) {
                lines.add("")
                continue
            }
            val cStart = if (r == minRow) start.col.coerceIn(0, rowCells.lastIndex) else 0
            val cEnd = if (r == maxRow) end.col.coerceIn(0, rowCells.lastIndex) else rowCells.lastIndex
            if (cStart <= cEnd) {
                val slice = rowCells.subList(cStart, cEnd + 1)
                    .joinToString("") { it.char.toString() }
                    .trimEnd()
                lines.add(slice)
            }
        }
        return lines.joinToString("\n").trim()
    }
}

