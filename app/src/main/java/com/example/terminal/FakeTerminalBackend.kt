package com.example.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * In-memory VT/ANSI cell-grid PTY simulator implementing [TerminalBackend]
 * and [TerminalSessionHandle], including basic ANSI SGR colors, cursor control,
 * scrollback buffer, streaming [ShellIntegration.OscStreamParser], and atomic
 * state-file reporting via [stateFilePath].
 */
class FakeTerminalBackend(
    private val stateDirectory: File = File(
        System.getProperty("java.io.tmpdir") ?: "/tmp",
        "hackerstudio_pty_state"
    ),
    private val clockMillis: () -> Long = System::currentTimeMillis
) : TerminalBackend {

    private data class VirtualNode(
        val isDirectory: Boolean,
        var content: String = "",
        var permissions: String = if (isDirectory) "drwxr-xr-x" else "-rw-r--r--"
    )

    private val vfs = linkedMapOf<String, VirtualNode>()
    private val sessionHandles = linkedMapOf<String, FakeSessionHandle>()
    private var sessionCounter = 1

    private val _sessions = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    override val sessions: StateFlow<List<TerminalSessionHandle>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    override val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    init {
        stateDirectory.mkdirs()
        seedVirtualFilesystem()
        createSession("bash-1")
    }

    private fun seedVirtualFilesystem() {
        vfs[HOME_DIR] = VirtualNode(isDirectory = true)
        vfs["$HOME_DIR/projects"] = VirtualNode(isDirectory = true)
        vfs["$HOME_DIR/projects/hackerstudio-cli"] = VirtualNode(isDirectory = true)
        vfs["$HOME_DIR/projects/hackerstudio-cli/.git"] = VirtualNode(isDirectory = true)
        vfs["$HOME_DIR/projects/hackerstudio-cli/main.py"] = VirtualNode(
            isDirectory = false,
            content = """
                #!/usr/bin/env python3
                def main():
                    print("HackerStudio CLI diagnostics online")

                if __name__ == "__main__":
                    main()
            """.trimIndent()
        )
        vfs["$HOME_DIR/scripts"] = VirtualNode(isDirectory = true)
        vfs["$HOME_DIR/scripts/deploy.sh"] = VirtualNode(
            isDirectory = false,
            permissions = "-rwxr-xr-x",
            content = """
                #!/data/data/com.termux/files/usr/bin/bash
                set -euo pipefail
                echo "Building release artifact..."
                gradle :app:assembleRelease
            """.trimIndent()
        )
        vfs["$HOME_DIR/notes.md"] = VirtualNode(
            isDirectory = false,
            content = """
                # HackerStudio Notes
                - Plug Termux TerminalSession into TerminalSessionHandle
                - Configure Gemini API keys in Settings -> API Vault
            """.trimIndent()
        )
        vfs["$HOME_DIR/.bashrc"] = VirtualNode(
            isDirectory = false,
            content = ShellIntegration.bashSnippet("/data/data/com.termux/files/usr/tmp/hs_state")
        )
    }

    override fun createSession(title: String?): TerminalSessionHandle {
        val id = "sess-${UUID.randomUUID().toString().take(8)}"
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: "bash-${sessionCounter++}"
        if (title != null) sessionCounter++

        val handle = FakeSessionHandle(
            id = id,
            initialTitle = resolvedTitle,
            initialCwd = HOME_DIR,
            stateFile = File(stateDirectory, "state_$id"),
            showWelcome = sessionHandles.isEmpty()
        )
        sessionHandles[id] = handle
        _activeSessionId.value = id
        emitSessions()
        return handle
    }

    override fun closeSession(sessionId: String) {
        val keys = sessionHandles.keys.toList()
        val removedIndex = keys.indexOf(sessionId)
        val removed = sessionHandles.remove(sessionId) ?: return
        removed.internalClose()
        if (_activeSessionId.value == sessionId) {
            val remaining = sessionHandles.keys.toList()
            _activeSessionId.value = if (remaining.isEmpty()) {
                ""
            } else {
                val nextIdx = (removedIndex - 1).coerceIn(0, remaining.lastIndex)
                remaining[nextIdx]
            }
        }
        emitSessions()
    }

    override fun switchSession(sessionId: String) {
        if (sessionHandles.containsKey(sessionId)) {
            _activeSessionId.value = sessionId
        }
    }

    override fun stateFilePath(sessionId: String): String {
        return sessionHandles[sessionId]?.stateFilePath()
            ?: File(stateDirectory, "state_$sessionId").absolutePath
    }

    private fun emitSessions() {
        _sessions.value = sessionHandles.values.toList()
    }

    private inner class FakeSessionHandle(
        override val id: String,
        initialTitle: String,
        initialCwd: String,
        private val stateFile: File,
        showWelcome: Boolean
    ) : TerminalSessionHandle {

        private val _title = MutableStateFlow(initialTitle)
        override val title: StateFlow<String> = _title.asStateFlow()

        private val _cwd = MutableStateFlow(initialCwd)
        override val cwd: StateFlow<String> = _cwd.asStateFlow()

        private val _lastExitCode = MutableStateFlow<Int?>(0)
        override val lastExitCode: StateFlow<Int?> = _lastExitCode.asStateFlow()

        private val _screenVersion = MutableStateFlow(0L)
        override val screenVersion: StateFlow<Long> = _screenVersion.asStateFlow()

        private val _lastErrorContext = MutableStateFlow<TerminalErrorContext?>(null)
        override val lastErrorContext: StateFlow<TerminalErrorContext?> = _lastErrorContext.asStateFlow()

        private val _bracketedPasteEnabled = MutableStateFlow(false)
        override val bracketedPasteEnabled: StateFlow<Boolean> = _bracketedPasteEnabled.asStateFlow()

        private val _lastPasteTruncated = MutableStateFlow(false)
        override val lastPasteTruncated: StateFlow<Boolean> = _lastPasteTruncated.asStateFlow()

        private val _commandEvents = MutableSharedFlow<TerminalCommandEvent>(extraBufferCapacity = 32)
        override val commandEvents: Flow<TerminalCommandEvent> = _commandEvents.asSharedFlow()

        private val oscParser = ShellIntegration.OscStreamParser()

        private var cols = 80
        private var rows = 24
        private var cursorRow = 0
        private var cursorCol = 0

        private var grid = MutableList(rows) { MutableList(cols) { TerminalCell.EMPTY } }
        private val scrollback = ArrayDeque<List<TerminalCell>>()
        private var visibleRowSnapshots: Array<List<TerminalCell>?> = arrayOfNulls(rows)
        private var cachedVisibleRowsList: List<List<TerminalCell>>? = null
        private var cachedScrollbackSnapshot: List<List<TerminalCell>>? = null
        private var cachedScreenBuffer: TerminalScreenBuffer? = null

        private fun invalidateGridRow(rowIndex: Int) {
            if (rowIndex in visibleRowSnapshots.indices) {
                visibleRowSnapshots[rowIndex] = null
            }
            cachedVisibleRowsList = null
            cachedScreenBuffer = null
        }

        private fun invalidateCursorOnly() {
            cachedScreenBuffer = null
        }

        private fun invalidateScrollback() {
            cachedScrollbackSnapshot = null
            cachedScreenBuffer = null
        }

        private fun setGridCell(rowIndex: Int, colIndex: Int, cell: TerminalCell) {
            val row = grid[rowIndex]
            if (row[colIndex] != cell) {
                row[colIndex] = cell
                invalidateGridRow(rowIndex)
            }
        }

        private fun clearGridRow(rowIndex: Int, fromCol: Int = 0) {
            val row = grid[rowIndex]
            var dirty = false
            for (c in fromCol until cols) {
                if (row[c] != TerminalCell.EMPTY) {
                    row[c] = TerminalCell.EMPTY
                    dirty = true
                }
            }
            if (dirty) {
                invalidateGridRow(rowIndex)
            }
        }

        private fun snapshotVisibleRow(rowIndex: Int): List<TerminalCell> {
            return visibleRowSnapshots[rowIndex]
                ?: grid[rowIndex].toList().also { visibleRowSnapshots[rowIndex] = it }
        }

        private var activeFg = TerminalCell.DEFAULT_FG
        private var activeBg = TerminalCell.DEFAULT_BG
        private var activeBold = false

        private val lineBuffer = StringBuilder()
        private var lineCursorPos = 0
        private var promptStartRow = 0
        private var promptStartCol = 0

        private val history = mutableListOf<String>()
        private var historyCursor = -1
        private var lastSubmittedCommand = ""
        private val lastCommandOutput = StringBuilder()
        private var closed = false

        private val env = linkedMapOf(
            "HOME" to HOME_DIR,
            "USER" to "u0_a137",
            "SHELL" to "/data/data/com.termux/files/usr/bin/bash",
            "TERM" to "xterm-256color",
            "PREFIX" to "/data/data/com.termux/files/usr",
            "PATH" to "/data/data/com.termux/files/usr/bin:/system/bin"
        )

        private val escBuffer = StringBuilder()
        private var inEscape = false
        private var inBracketedPaste = false

        init {
            if (showWelcome) {
                emitToTerminal(
                    "\u001B[1;36mHackerStudio PTY Shell v2.0\u001B[0m\r\n" +
                        "\u001B[90m[VT Cell Grid • OSC 7777 Ready]\u001B[0m\r\n" +
                        "\u001B[36mType 'help' for commands, or run\r\n" +
                        "'sh scripts/deploy.sh' for AI fix.\u001B[0m\r\n"
                )
            } else {
                emitToTerminal("\u001B[36mSession '$initialTitle' (~)\u001B[0m\r\n")
            }
            // Interactive bash/readline 5.2+ enables DECSET 2004 (bracketed paste) at prompt startup.
            emitToTerminal("\u001B[?2004h")
            writeStateFileAtomic(0, initialCwd)
            renderPrompt()
            bumpScreenVersion()
        }

        override fun stateFilePath(): String = stateFile.absolutePath

        override fun pasteText(text: String) {
            pasteTextWithOutcome(text)
        }

        override fun pasteTextWithOutcome(text: String): TerminalPasteOutcome {
            if (closed) {
                return TerminalPasteOutcome(inserted = false, truncated = false, copiedFallbackRequired = false)
            }
            val sanitized = TerminalPasteSanitizer.sanitize(text, TerminalPasteSanitizer.MAX_PASTE_BYTES)
            _lastPasteTruncated.value = sanitized.wasTruncated
            if (sanitized.text.isEmpty()) {
                return TerminalPasteOutcome(
                    inserted = false,
                    truncated = sanitized.wasTruncated,
                    copiedFallbackRequired = false
                )
            }
            return if (_bracketedPasteEnabled.value) {
                write("\u001B[200~${sanitized.text}\u001B[201~".encodeToByteArray())
                TerminalPasteOutcome(
                    inserted = true,
                    truncated = sanitized.wasTruncated,
                    copiedFallbackRequired = false
                )
            } else if (!sanitized.hadNewlines) {
                write(sanitized.text.encodeToByteArray())
                TerminalPasteOutcome(
                    inserted = true,
                    truncated = sanitized.wasTruncated,
                    copiedFallbackRequired = false
                )
            } else {
                TerminalPasteOutcome(
                    inserted = false,
                    truncated = sanitized.wasTruncated,
                    copiedFallbackRequired = true
                )
            }
        }

        override fun write(bytes: ByteArray) {
            if (closed || bytes.isEmpty()) return
            val text = bytes.decodeToString()
            var lineBufferDirty = false
            fun appendLineChar(c: Char) {
                if (lineCursorPos == lineBuffer.length) {
                    lineBuffer.append(c)
                } else {
                    lineBuffer.insert(lineCursorPos, c)
                }
                lineCursorPos++
                lineBufferDirty = true
            }
            fun flushLineBufferIfDirty() {
                if (lineBufferDirty) {
                    redrawCurrentLineBuffer()
                    lineBufferDirty = false
                }
            }

            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (inEscape) {
                    escBuffer.append(ch)
                    val seq = escBuffer.toString()
                    if (seq.length == 1 && ch != '[') {
                        inEscape = false
                        escBuffer.clear()
                    } else if (seq.length >= 2 && ch.code in 0x40..0x7E) {
                        when (seq) {
                            "[A", "[B", "[C", "[D" -> if (!inBracketedPaste) {
                                flushLineBufferIfDirty()
                                handleArrowKey(seq[1])
                            }
                            "[200~" -> inBracketedPaste = true
                            "[201~" -> inBracketedPaste = false
                        }
                        inEscape = false
                        escBuffer.clear()
                    } else if (seq.length > 16) {
                        inEscape = false
                        escBuffer.clear()
                    }
                    i++
                    continue
                }

                if (inBracketedPaste) {
                    when (ch.code) {
                        0x1B -> {
                            inEscape = true
                            escBuffer.clear()
                        }
                        0x0D -> {
                            // Ignore CR when LF follows, or normalize CR to LF inside paste buffer
                            if (i + 1 >= text.length || text[i + 1] != '\n') {
                                appendLineChar('\n')
                            }
                        }
                        0x0A -> appendLineChar('\n')
                        0x09 -> appendLineChar(' ')
                        else -> if (ch.code >= 0x20 && ch.code != 0x7F) {
                            appendLineChar(ch)
                        }
                    }
                    i++
                    continue
                }

                when (ch.code) {
                    0x1B -> {
                        inEscape = true
                        escBuffer.clear()
                    }
                    0x03 -> {
                        flushLineBufferIfDirty()
                        emitToTerminal("^C\r\n")
                        lineBuffer.clear()
                        lineCursorPos = 0
                        historyCursor = -1
                        emitShellPromptWithStatus(130)
                    }
                    0x0C -> {
                        lineBufferDirty = false
                        clearGridOnly()
                        renderPrompt()
                        redrawCurrentLineBuffer()
                    }
                    0x15 -> {
                        lineBuffer.clear()
                        lineCursorPos = 0
                        lineBufferDirty = true
                    }
                    0x09 -> {
                        flushLineBufferIfDirty()
                        handleTabComplete()
                    }
                    0x0D, 0x0A -> {
                        flushLineBufferIfDirty()
                        val cmd = lineBuffer.toString()
                        lineBuffer.clear()
                        lineCursorPos = 0
                        historyCursor = -1
                        emitToTerminal("\r\n")
                        executeCommandLine(cmd)
                    }
                    0x7F, 0x08 -> {
                        if (lineCursorPos > 0) {
                            lineBuffer.deleteCharAt(lineCursorPos - 1)
                            lineCursorPos--
                            lineBufferDirty = true
                        }
                    }
                    else -> {
                        if (ch.code >= 0x20 && ch.code != 0x7F) {
                            appendLineChar(ch)
                        }
                    }
                }
                i++
            }
            flushLineBufferIfDirty()
            bumpScreenVersion()
        }

        override fun resize(cols: Int, rows: Int) {
            val newCols = cols.coerceIn(20, 240)
            val newRows = rows.coerceIn(6, 120)
            if (newCols == this.cols && newRows == this.rows) return

            val oldRows = this.rows
            val oldCols = this.cols
            val oldSnapshots = this.visibleRowSnapshots

            var shiftUp = 0
            var pullFromScrollback = 0
            if (newRows < oldRows && this.cursorRow >= newRows) {
                shiftUp = (this.cursorRow - (newRows - 1)).coerceIn(0, oldRows - 1)
                for (r in 0 until shiftUp) {
                    scrollback.addLast(snapshotVisibleRow(r))
                }
                while (scrollback.size > MAX_SCROLLBACK) {
                    scrollback.removeFirst()
                }
                invalidateScrollback()
            } else if (newRows > oldRows && scrollback.isNotEmpty()) {
                pullFromScrollback = minOf(newRows - oldRows, scrollback.size)
            }

            val pulledRows = if (pullFromScrollback > 0) {
                val list = ArrayList<List<TerminalCell>>(pullFromScrollback)
                repeat(pullFromScrollback) {
                    list.add(0, scrollback.removeLast())
                }
                invalidateScrollback()
                list
            } else {
                emptyList()
            }

            val newGrid = MutableList(newRows) { r ->
                if (r < pulledRows.size) {
                    val srcRow = pulledRows[r]
                    MutableList(newCols) { c ->
                        if (c < srcRow.size) srcRow[c] else TerminalCell.EMPTY
                    }
                } else {
                    val srcRowIdx = (r - pulledRows.size) + shiftUp
                    MutableList(newCols) { c ->
                        if (srcRowIdx < oldRows && c < oldCols) grid[srcRowIdx][c] else TerminalCell.EMPTY
                    }
                }
            }
            val newSnapshots = arrayOfNulls<List<TerminalCell>>(newRows)
            if (newCols == oldCols) {
                for (r in 0 until pulledRows.size.coerceAtMost(newRows)) {
                    if (pulledRows[r].size == newCols) {
                        newSnapshots[r] = pulledRows[r]
                    }
                }
                for (r in pulledRows.size until newRows) {
                    val srcRowIdx = (r - pulledRows.size) + shiftUp
                    if (srcRowIdx < oldRows) {
                        newSnapshots[r] = oldSnapshots[srcRowIdx]
                    }
                }
            }
            this.cols = newCols
            this.rows = newRows
            this.grid = newGrid
            this.visibleRowSnapshots = newSnapshots
            this.cachedVisibleRowsList = null
            this.cachedScreenBuffer = null
            this.cursorRow = (this.cursorRow - shiftUp + pulledRows.size).coerceIn(0, newRows - 1)
            this.cursorCol = this.cursorCol.coerceIn(0, newCols - 1)
            this.promptStartRow = (this.promptStartRow - shiftUp + pulledRows.size).coerceIn(0, newRows - 1)
            this.promptStartCol = this.promptStartCol.coerceIn(0, newCols - 1)
            bumpScreenVersion()
        }

        override fun close() {
            closeSession(id)
        }

        fun internalClose() {
            closed = true
            runCatching { stateFile.delete() }
        }

        override fun rename(newTitle: String) {
            val clean = newTitle.trim()
            if (clean.isNotEmpty()) {
                _title.value = clean.take(20)
            }
        }

        override fun clearScreen() {
            clearGridOnly()
            if (scrollback.isNotEmpty()) {
                scrollback.clear()
                invalidateScrollback()
            }
            renderPrompt()
            redrawCurrentLineBuffer()
            bumpScreenVersion()
        }

        override fun readScreen(): TerminalScreenBuffer {
            cachedScreenBuffer?.let { existing ->
                if (existing.cursorRow == cursorRow && existing.cursorCol == cursorCol) {
                    return existing
                }
            }
            val visibleSnap = cachedVisibleRowsList ?: List(rows) { r -> snapshotVisibleRow(r) }
                .also { cachedVisibleRowsList = it }
            val scrollbackSnap = cachedScrollbackSnapshot ?: scrollback.toList()
                .also { cachedScrollbackSnapshot = it }
            return TerminalScreenBuffer(
                cols = cols,
                rows = rows,
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                visibleRows = visibleSnap,
                scrollbackRows = scrollbackSnap
            ).also { cachedScreenBuffer = it }
        }

        override fun readRecentText(maxLines: Int): String {
            if (maxLines <= 0) return ""
            val collected = ArrayList<String>(minOf(maxLines, rows + scrollback.size))
            var foundNonEmpty = false

            fun rowToTrimmedString(row: List<TerminalCell>): String {
                var lastNonSpace = -1
                for (i in row.indices) {
                    if (!row[i].char.isWhitespace()) {
                        lastNonSpace = i
                    }
                }
                if (lastNonSpace < 0) return ""
                val sb = StringBuilder(lastNonSpace + 1)
                for (i in 0..lastNonSpace) {
                    sb.append(row[i].char)
                }
                return sb.toString()
            }

            for (r in (rows - 1) downTo 0) {
                val line = rowToTrimmedString(grid[r])
                if (!foundNonEmpty && line.isEmpty()) continue
                foundNonEmpty = true
                collected.add(line)
                if (collected.size >= maxLines) break
            }
            if (collected.size < maxLines) {
                for (i in (scrollback.size - 1) downTo 0) {
                    val line = rowToTrimmedString(scrollback[i])
                    if (!foundNonEmpty && line.isEmpty()) continue
                    foundNonEmpty = true
                    collected.add(line)
                    if (collected.size >= maxLines) break
                }
            }
            collected.reverse()
            return collected.joinToString("\n")
        }

        private fun handleArrowKey(code: Char) {
            when (code) {
                'A' -> {
                    if (history.isEmpty()) return
                    historyCursor = when {
                        historyCursor == -1 -> history.lastIndex
                        historyCursor > 0 -> historyCursor - 1
                        else -> 0
                    }
                    lineBuffer.clear().append(history[historyCursor])
                    lineCursorPos = lineBuffer.length
                    redrawCurrentLineBuffer()
                }
                'B' -> {
                    if (historyCursor == -1) return
                    lineBuffer.clear()
                    if (historyCursor < history.lastIndex) {
                        historyCursor++
                        lineBuffer.append(history[historyCursor])
                    } else {
                        historyCursor = -1
                    }
                    lineCursorPos = lineBuffer.length
                    redrawCurrentLineBuffer()
                }
                'C' -> if (lineCursorPos < lineBuffer.length) {
                    lineCursorPos++
                    syncCursorToLinePos()
                }
                'D' -> if (lineCursorPos > 0) {
                    lineCursorPos--
                    syncCursorToLinePos()
                }
            }
        }

        private fun handleTabComplete() {
            val draft = lineBuffer.toString()
            val parts = draft.split(" ")
            if (parts.size <= 1) {
                val prefix = parts.firstOrNull().orEmpty()
                val matches = BUILTIN_COMMANDS.filter { it.startsWith(prefix) }
                if (matches.size == 1) {
                    lineBuffer.clear().append("${matches.first()} ")
                    lineCursorPos = lineBuffer.length
                    redrawCurrentLineBuffer()
                } else if (matches.size > 1) {
                    emitToTerminal("\r\n\u001B[36m${matches.joinToString("  ")}\u001B[0m\r\n")
                    renderPrompt()
                    redrawCurrentLineBuffer()
                }
            } else {
                val token = parts.last()
                val matches = listChildren(_cwd.value).filter { it.startsWith(token) }
                if (matches.size == 1) {
                    val completed = matches.first()
                    val suffix = if (vfs[resolvePath(_cwd.value, completed)]?.isDirectory == true) "/" else ""
                    val updated = parts.dropLast(1).joinToString(" ") + " $completed$suffix"
                    lineBuffer.clear().append(updated)
                    lineCursorPos = lineBuffer.length
                    redrawCurrentLineBuffer()
                } else if (matches.size > 1) {
                    emitToTerminal("\r\n\u001B[36m${matches.joinToString("  ")}\u001B[0m\r\n")
                    renderPrompt()
                    redrawCurrentLineBuffer()
                }
            }
        }

        private fun redrawCurrentLineBuffer() {
            cursorRow = promptStartRow
            cursorCol = promptStartCol
            clearGridRow(promptStartRow, promptStartCol)
            for (r in (promptStartRow + 1) until rows) {
                clearGridRow(r, 0)
            }
            activeFg = TerminalCell.DEFAULT_FG
            activeBg = TerminalCell.DEFAULT_BG
            activeBold = false
            writePlainChars(lineBuffer.toString())
            syncCursorToLinePos()
        }

        private fun syncCursorToLinePos() {
            var row = promptStartRow
            var col = promptStartCol
            val limit = lineCursorPos.coerceAtMost(lineBuffer.length)
            for (idx in 0 until limit) {
                val ch = lineBuffer[idx]
                if (ch == '\n') {
                    row = (row + 1).coerceAtMost(rows - 1)
                    col = 0
                } else {
                    col++
                    if (col >= cols) {
                        row = (row + 1).coerceAtMost(rows - 1)
                        col = 0
                    }
                }
            }
            cursorRow = row.coerceIn(0, rows - 1)
            cursorCol = col.coerceIn(0, cols - 1)
            invalidateCursorOnly()
        }

        private fun renderPrompt() {
            val currentCwd = _cwd.value
            val shortPath = when {
                currentCwd == HOME_DIR -> "~"
                currentCwd.startsWith("$HOME_DIR/") -> "~/${currentCwd.removePrefix("$HOME_DIR/")}"
                else -> currentCwd
            }
            val ec = _lastExitCode.value ?: 0
            val ecBadge = if (ec != 0) "\u001B[1;31m[$ec] \u001B[0m" else ""
            val user = env["USER"] ?: "u0_a137"
            emitToTerminal("$ecBadge\u001B[1;32m$user@termux\u001B[0m:\u001B[1;36m$shortPath\u001B[0m$ ")
            promptStartRow = cursorRow
            promptStartCol = cursorCol
        }

        private fun emitShellPromptWithStatus(exitCode: Int) {
            val currentCwd = _cwd.value
            writeStateFileAtomic(exitCode, currentCwd)
            val marker = ShellIntegration.formatOscMarker(exitCode, currentCwd)
            emitToTerminal(marker)
            renderPrompt()
        }

        private fun writeStateFileAtomic(exitCode: Int, cwdPath: String) {
            runCatching {
                stateFile.parentFile?.mkdirs()
                val tmp = File(stateFile.parentFile, "${stateFile.name}.tmp")
                tmp.writeText(ShellIntegration.formatStateFilePayload(exitCode, cwdPath))
                if (!tmp.renameTo(stateFile)) {
                    stateFile.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }

        private fun executeCommandLine(rawCommand: String) {
            val cmd = rawCommand.trim()
            if (cmd.isEmpty()) {
                emitShellPromptWithStatus(_lastExitCode.value ?: 0)
                return
            }
            if (history.lastOrNull() != cmd) {
                history.add(cmd)
            }
            lastSubmittedCommand = cmd
            lastCommandOutput.clear()

            val startedAt = clockMillis()
            _commandEvents.tryEmit(
                TerminalCommandEvent.Started(
                    sessionId = id,
                    command = cmd,
                    startedAtMillis = startedAt
                )
            )

            var exitCode = 0
            val lines = cmd.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            for (line in lines) {
                if (line.contains(" && ")) {
                    val segments = line.split(" && ").map { it.trim() }.filter { it.isNotEmpty() }
                    for (segment in segments) {
                        exitCode = executeSingle(segment)
                        if (exitCode != 0) break
                    }
                } else {
                    exitCode = executeSingle(line)
                }
                if (exitCode != 0) break
            }

            val finishedAt = clockMillis()
            _commandEvents.tryEmit(
                TerminalCommandEvent.Finished(
                    sessionId = id,
                    sessionTitle = _title.value,
                    command = cmd,
                    exitCode = exitCode,
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt
                )
            )

            emitShellPromptWithStatus(exitCode)
        }

        private fun executeSingle(command: String): Int {
            val tokens = tokenize(command)
            if (tokens.isEmpty()) return 0
            val cmd = tokens.first()
            val args = tokens.drop(1)

            fun out(text: String, ansiColor: String? = null) {
                lastCommandOutput.appendLine(text)
                val formatted = if (ansiColor != null) "$ansiColor$text\u001B[0m\r\n" else "$text\r\n"
                emitToTerminal(formatted.replace("\n", "\r\n").replace("\r\r\n", "\r\n"))
            }

            return when (cmd) {
                "help" -> {
                    out(
                        """
                        Built-in PTY commands:
                          ls [-la] [dir]      List directory contents
                          cd [dir]            Change directory
                          pwd / whoami / id   Session & identity info
                          cat <file>          Display file contents
                          echo <text>         Print text (${'$'}VAR supported)
                          mkdir / touch / rm  Modify virtual filesystem
                          grep <pat> <file>   Search pattern in file
                          git <subcmd>        Simulated git status / branch / log
                          pkg <subcmd>        Simulated Termux pkg manager
                          sh <script>         Execute script (try 'sh scripts/deploy.sh')
                          python3 <script>    Run Python script
                          uname [-a] / env    System & environment
                          clear               Clear screen grid
                        """.trimIndent(),
                        "\u001B[36m"
                    )
                    0
                }
                "clear" -> {
                    clearGridOnly()
                    0
                }
                "pwd" -> { out(_cwd.value); 0 }
                "whoami" -> { out(env["USER"] ?: "u0_a137"); 0 }
                "id" -> { out("uid=10137(u0_a137) gid=10137(u0_a137) groups=10137(u0_a137),3003(inet)"); 0 }
                "uname" -> {
                    out(if (args.contains("-a")) "Linux localhost 6.1.75-android14-11 #1 SMP PREEMPT aarch64 Android" else "Linux")
                    0
                }
                "date" -> {
                    out(SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).format(Date()))
                    0
                }
                "env" -> { out(env.entries.joinToString("\n") { "${it.key}=${it.value}" }); 0 }
                "export" -> {
                    val assignment = args.firstOrNull()
                    if (assignment == null || !assignment.contains("=")) {
                        out("bash: export: usage: export KEY=VALUE", "\u001B[31m")
                        1
                    } else {
                        val idx = assignment.indexOf('=')
                        val key = assignment.substring(0, idx).trim()
                        val value = assignment.substring(idx + 1).trim('"', '\'')
                        if (key.isEmpty()) {
                            out("bash: export: not a valid identifier", "\u001B[31m")
                            1
                        } else {
                            env[key] = value
                            0
                        }
                    }
                }
                "echo" -> {
                    val enableEscapes = args.firstOrNull() == "-e"
                    val effectiveArgs = if (enableEscapes) args.drop(1) else args
                    val joined = effectiveArgs.joinToString(" ") {
                        if (it.startsWith("$")) env[it.removePrefix("$")] ?: "" else it
                    }
                    if (enableEscapes) {
                        emitToTerminal(decodeEscapeSequences(joined) + "\r\n")
                    } else {
                        out(joined)
                    }
                    0
                }
                "printf" -> {
                    val rawFormat = args.firstOrNull().orEmpty()
                    emitToTerminal(decodeEscapeSequences(rawFormat))
                    0
                }
                "history" -> {
                    out(history.mapIndexed { idx, s -> String.format(Locale.US, "%4d  %s", idx + 1, s) }.joinToString("\n"))
                    0
                }
                "ps" -> {
                    out("  PID TTY      TIME CMD\n 4102 pts/0    00:00:00 bash\n 4188 pts/0    00:00:01 hackerstudio-pty")
                    0
                }
                "ls" -> {
                    val showHidden = args.any { it.startsWith("-") && it.contains("a") }
                    val longFormat = args.any { it.startsWith("-") && it.contains("l") }
                    val targetArg = args.firstOrNull { !it.startsWith("-") }
                    val targetPath = if (targetArg == null) _cwd.value else resolvePath(_cwd.value, targetArg)
                    val node = vfs[targetPath]
                    when {
                        node == null -> { out("ls: cannot access '$targetArg': No such file or directory", "\u001B[31m"); 2 }
                        !node.isDirectory -> {
                            val name = targetPath.substringAfterLast('/')
                            out(if (longFormat) "${node.permissions} 1 u0_a137 ${node.content.length} $name" else name)
                            0
                        }
                        else -> {
                            val entries = listChildren(targetPath).filter { showHidden || !it.startsWith(".") }
                            if (entries.isNotEmpty()) {
                                if (longFormat) {
                                    entries.forEach { name ->
                                        val child = vfs["$targetPath/$name"]
                                        val perm = child?.permissions ?: "-rw-r--r--"
                                        val size = child?.content?.length ?: 4096
                                        val colored = if (child?.isDirectory == true) "\u001B[1;34m$name/\u001B[0m" else name
                                        emitToTerminal("$perm 1 u0_a137 ${size.toString().padStart(5)} $colored\r\n")
                                        lastCommandOutput.appendLine("$perm $name")
                                    }
                                } else {
                                    val rendered = entries.joinToString("  ") { name ->
                                        if (vfs["$targetPath/$name"]?.isDirectory == true) "\u001B[1;34m$name/\u001B[0m" else name
                                    }
                                    emitToTerminal("$rendered\r\n")
                                    lastCommandOutput.appendLine(entries.joinToString("  "))
                                }
                            }
                            0
                        }
                    }
                }
                "cd" -> {
                    val targetArg = args.firstOrNull() ?: HOME_DIR
                    val resolved = resolvePath(_cwd.value, targetArg)
                    val node = vfs[resolved]
                    when {
                        node == null -> { out("bash: cd: $targetArg: No such file or directory", "\u001B[31m"); 1 }
                        !node.isDirectory -> { out("bash: cd: $targetArg: Not a directory", "\u001B[31m"); 1 }
                        else -> { _cwd.value = resolved; 0 }
                    }
                }
                "cat" -> {
                    val targetArg = args.firstOrNull()
                    if (targetArg == null) { out("cat: missing file operand", "\u001B[31m"); 1 }
                    else {
                        val node = vfs[resolvePath(_cwd.value, targetArg)]
                        when {
                            node == null -> { out("cat: $targetArg: No such file or directory", "\u001B[31m"); 1 }
                            node.isDirectory -> { out("cat: $targetArg: Is a directory", "\u001B[31m"); 1 }
                            else -> { out(node.content); 0 }
                        }
                    }
                }
                "mkdir" -> {
                    val targetArg = args.firstOrNull { !it.startsWith("-") }
                    if (targetArg == null) { out("mkdir: missing operand", "\u001B[31m"); 1 }
                    else {
                        val resolved = resolvePath(_cwd.value, targetArg)
                        if (vfs.containsKey(resolved)) { out("mkdir: cannot create directory '$targetArg': File exists", "\u001B[31m"); 1 }
                        else { vfs[resolved] = VirtualNode(isDirectory = true); 0 }
                    }
                }
                "touch" -> {
                    val targetArg = args.firstOrNull()
                    if (targetArg == null) { out("touch: missing file operand", "\u001B[31m"); 1 }
                    else {
                        val resolved = resolvePath(_cwd.value, targetArg)
                        if (!vfs.containsKey(resolved)) vfs[resolved] = VirtualNode(isDirectory = false)
                        0
                    }
                }
                "rm" -> {
                    val recursive = args.any { it == "-r" || it == "-rf" || it == "-fr" }
                    val targetArg = args.firstOrNull { !it.startsWith("-") }
                    if (targetArg == null) { out("rm: missing operand", "\u001B[31m"); 1 }
                    else {
                        val resolved = resolvePath(_cwd.value, targetArg)
                        val node = vfs[resolved]
                        when {
                            node == null -> { out("rm: cannot remove '$targetArg': No such file or directory", "\u001B[31m"); 1 }
                            node.isDirectory && !recursive -> { out("rm: cannot remove '$targetArg': Is a directory", "\u001B[31m"); 1 }
                            resolved == HOME_DIR -> { out("rm: refusing to remove HOME directory", "\u001B[31m"); 1 }
                            else -> {
                                vfs.keys.filter { it == resolved || it.startsWith("$resolved/") }.forEach { vfs.remove(it) }
                                0
                            }
                        }
                    }
                }
                "grep" -> {
                    if (args.size < 2) { out("Usage: grep <pattern> <file>", "\u001B[31m"); 2 }
                    else {
                        val node = vfs[resolvePath(_cwd.value, args[1])]
                        when {
                            node == null -> { out("grep: ${args[1]}: No such file or directory", "\u001B[31m"); 2 }
                            node.isDirectory -> { out("grep: ${args[1]}: Is a directory", "\u001B[31m"); 2 }
                            else -> {
                                val matches = node.content.lines().filter { it.contains(args[0]) }
                                if (matches.isEmpty()) 1 else { out(matches.joinToString("\n"), "\u001B[32m"); 0 }
                            }
                        }
                    }
                }
                "git" -> {
                    if (!_cwd.value.contains("hackerstudio-cli")) {
                        out("fatal: not a git repository (or any parent up to mount point /data)", "\u001B[31m")
                        128
                    } else {
                        when (args.firstOrNull()) {
                            "status" -> { out("On branch main\nnothing to commit, working tree clean", "\u001B[32m"); 0 }
                            "branch" -> { out("* main", "\u001B[32m"); 0 }
                            "log" -> { out("commit 9a4f21c (HEAD -> main)\n    feat: PTY-ready TerminalSessionHandle", "\u001B[33m"); 0 }
                            else -> { out("git: unsupported subcommand. Try status, branch, or log.", "\u001B[31m"); 1 }
                        }
                    }
                }
                "pkg" -> when (args.firstOrNull()) {
                    "list-installed" -> { out("bash/stable 5.2.32 aarch64 [installed]\ngit/stable 2.47.0 aarch64 [installed]\npython/stable 3.12.7 aarch64 [installed]"); 0 }
                    "update" -> { out("All packages are up to date (simulated pkg index).", "\u001B[32m"); 0 }
                    else -> { out("pkg: simulated mode supports 'pkg list-installed' and 'pkg update'.", "\u001B[31m"); 1 }
                }
                "python3" -> {
                    val script = args.firstOrNull()
                    val node = script?.let { vfs[resolvePath(_cwd.value, it)] }
                    if (script == null || node == null || node.isDirectory) {
                        out("python3: can't open file '${script ?: ""}': [Errno 2] No such file or directory", "\u001B[31m")
                        2
                    } else {
                        out("HackerStudio CLI diagnostics online", "\u001B[32m")
                        0
                    }
                }
                "sh", "bash" -> {
                    val script = args.firstOrNull()
                    if (script == null) { out("Already inside HackerStudio PTY bash session.", "\u001B[36m"); 0 }
                    else {
                        val node = vfs[resolvePath(_cwd.value, script)]
                        when {
                            node == null || node.isDirectory -> { out("sh: 0: Can't open $script", "\u001B[31m"); 127 }
                            script.endsWith("deploy.sh") -> {
                                out("Building release artifact...")
                                out("FAILURE: Build failed for task ':app:validateSigningRelease'.\n> Keystore password not set. Export STORE_PASSWORD and KEY_PASSWORD.", "\u001B[31m")
                                1
                            }
                            else -> { out("Executed $script (exit 0)", "\u001B[32m"); 0 }
                        }
                    }
                }
                else -> { out("bash: $cmd: command not found", "\u001B[31m"); 127 }
            }
        }

        private fun emitToTerminal(raw: String) {
            val parsed = oscParser.feed(raw)
            parsed.events.forEach { ev ->
                _lastExitCode.value = ev.exitCode
                if (ev.cwd.isNotBlank()) {
                    _cwd.value = ev.cwd
                }
                if (ev.exitCode != 0 && lastSubmittedCommand.isNotBlank()) {
                    _lastErrorContext.value = TerminalErrorContext(
                        sessionId = id,
                        command = lastSubmittedCommand,
                        exitCode = ev.exitCode,
                        errorOutput = lastCommandOutput.toString().trim().ifEmpty { "Command exited with ${ev.exitCode}" },
                        cwd = _cwd.value
                    )
                }
            }

            val text = parsed.cleanText
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\u001B' && i + 1 < text.length && text[i + 1] == '[') {
                    val endIdx = findCsiEnd(text, i + 2)
                    if (endIdx != -1) {
                        val params = text.substring(i + 2, endIdx)
                        val cmdChar = text[endIdx]
                        applyCsi(params, cmdChar)
                        i = endIdx + 1
                        continue
                    }
                }
                when (ch) {
                    '\r' -> cursorCol = 0
                    '\n' -> newLine()
                    '\b' -> if (cursorCol > 0) cursorCol--
                    else -> putChar(ch)
                }
                i++
            }
        }

        private fun findCsiEnd(text: String, start: Int): Int {
            var idx = start
            while (idx < text.length) {
                val c = text[idx]
                if (c.code in 0x40..0x7E) return idx
                idx++
            }
            return -1
        }

        private fun applyCsi(params: String, cmd: Char) {
            when (cmd) {
                'm' -> {
                    val codes = if (params.isEmpty()) listOf(0) else params.split(';').mapNotNull { it.toIntOrNull() }
                    for (code in codes) {
                        when (code) {
                            0 -> { activeFg = TerminalCell.DEFAULT_FG; activeBg = TerminalCell.DEFAULT_BG; activeBold = false }
                            1 -> activeBold = true
                            in 30..37 -> activeFg = code
                            39 -> activeFg = TerminalCell.DEFAULT_FG
                            in 40..47 -> activeBg = code
                            49 -> activeBg = TerminalCell.DEFAULT_BG
                            in 90..97 -> activeFg = code
                        }
                    }
                }
                'J' -> if (params == "2") clearGridOnly()
                'H' -> { cursorRow = 0; cursorCol = 0; invalidateCursorOnly() }
                'K' -> clearGridRow(cursorRow, cursorCol)
                'h', 'l' -> {
                    if (params.startsWith("?")) {
                        val enable = cmd == 'h'
                        params.removePrefix("?")
                            .split(';')
                            .mapNotNull { it.toIntOrNull() }
                            .forEach { mode ->
                                if (mode == 2004) {
                                    _bracketedPasteEnabled.value = enable
                                }
                            }
                    }
                }
            }
        }

        private fun writePlainChars(text: String) {
            for (ch in text) {
                if (ch == '\n') {
                    cursorCol = 0
                    invalidateCursorOnly()
                    newLine()
                } else {
                    putChar(ch)
                }
            }
        }

        private fun putChar(ch: Char) {
            if (cursorCol >= cols) {
                cursorCol = 0
                invalidateCursorOnly()
                newLine()
            }
            setGridCell(
                rowIndex = cursorRow,
                colIndex = cursorCol,
                cell = TerminalCell(
                    char = ch,
                    fgColorCode = activeFg,
                    bgColorCode = activeBg,
                    bold = activeBold
                )
            )
            cursorCol++
            invalidateCursorOnly()
        }

        private fun newLine() {
            if (cursorRow < rows - 1) {
                cursorRow++
                invalidateCursorOnly()
            } else {
                val evictedSnapshot = snapshotVisibleRow(0)
                grid.removeAt(0)
                scrollback.addLast(evictedSnapshot)
                if (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
                grid.add(MutableList(cols) { TerminalCell.EMPTY })
                for (r in 0 until rows - 1) {
                    visibleRowSnapshots[r] = visibleRowSnapshots[r + 1]
                }
                visibleRowSnapshots[rows - 1] = null
                cachedVisibleRowsList = null
                invalidateScrollback()
                if (promptStartRow > 0) promptStartRow--
            }
        }

        private fun clearGridOnly() {
            for (r in 0 until rows) {
                clearGridRow(r, 0)
            }
            cursorRow = 0; cursorCol = 0; promptStartRow = 0; promptStartCol = 0
            invalidateCursorOnly()
        }

        private fun bumpScreenVersion() {
            _screenVersion.value++
        }

        private fun resolvePath(cwd: String, input: String): String {
            val raw = when {
                input == "~" -> HOME_DIR
                input.startsWith("~/") -> "$HOME_DIR/${input.removePrefix("~/")}"
                input.startsWith("/") -> input
                else -> "$cwd/$input"
            }
            val stack = ArrayDeque<String>()
            for (segment in raw.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (stack.isNotEmpty()) stack.removeLast()
                    else -> stack.addLast(segment)
                }
            }
            return if (stack.isEmpty()) "/" else "/" + stack.joinToString("/")
        }

        private fun listChildren(dirPath: String): List<String> {
            val prefix = if (dirPath == "/") "/" else "$dirPath/"
            return vfs.keys
                .filter { it.startsWith(prefix) && it != dirPath }
                .map { it.removePrefix(prefix) }
                .filter { !it.contains('/') }
                .sorted()
        }

        private fun tokenize(command: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            for (ch in command) {
                when {
                    quote != null -> if (ch == quote) quote = null else current.append(ch)
                    ch == '"' || ch == '\'' -> quote = ch
                    ch.isWhitespace() -> if (current.isNotEmpty()) { result.add(current.toString()); current.clear() }
                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
            return result
        }

        private fun decodeEscapeSequences(raw: String): String {
            return raw
                .replace("\\033", "\u001B")
                .replace("\\x1b", "\u001B", ignoreCase = true)
                .replace("\\e", "\u001B")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
    }

    companion object {
        const val HOME_DIR = "/data/data/com.termux/files/home"
        private const val MAX_SCROLLBACK = 400
        private val BUILTIN_COMMANDS = listOf(
            "bash", "cat", "cd", "clear", "date", "echo", "env", "export",
            "git", "grep", "help", "history", "id", "ls", "mkdir", "pkg",
            "ps", "pwd", "python3", "rm", "sh", "touch", "uname", "whoami"
        )
    }
}
