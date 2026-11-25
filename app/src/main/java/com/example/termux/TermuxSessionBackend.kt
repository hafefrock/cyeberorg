package com.example.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalCell
import com.example.terminal.TerminalCommandEvent
import com.example.terminal.TerminalErrorContext
import com.example.terminal.TerminalScreenBuffer
import com.example.terminal.TerminalSessionHandle
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [TerminalBackend] backed by real Termux [TerminalSession]s (PTY + /data/data/com.termux/files/usr).
 * Must be created and used on the main thread (TerminalSession requirement).
 */
class TermuxSessionBackend(private val context: Context) : TerminalBackend {

    private val paths = TermuxPaths(context.filesDir)
    private val stateDir = File(context.filesDir, "hs_state").apply { mkdirs() }
    private val handles = linkedMapOf<String, TermuxSessionHandle>()
    private var counter = 1

    private val _sessions = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    override val sessions: StateFlow<List<TerminalSessionHandle>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    override val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    init {
        TermuxIntegration.install(context, paths.home)
        createSession("bash-1")
    }

    override fun createSession(title: String?): TerminalSessionHandle {
        val id = "sess-" + java.util.UUID.randomUUID().toString().take(8)
        val resolved = title?.takeIf { it.isNotBlank() } ?: "bash-${counter++}"
        if (title != null) counter++
        val handle = TermuxSessionHandle(
            context = context,
            paths = paths,
            id = id,
            initialTitle = resolved,
            stateFile = File(TermuxPaths.normalize(stateDir), "state_$id"),
        )
        handles[id] = handle
        _activeSessionId.value = id
        emit()
        return handle
    }

    override fun closeSession(sessionId: String) {
        val keys = handles.keys.toList()
        val idx = keys.indexOf(sessionId)
        val removed = handles.remove(sessionId) ?: return
        removed.close()
        if (_activeSessionId.value == sessionId) {
            val remaining = handles.keys.toList()
            _activeSessionId.value = if (remaining.isEmpty()) "" else remaining[(idx - 1).coerceIn(0, remaining.lastIndex)]
        }
        emit()
    }

    override fun switchSession(sessionId: String) {
        if (handles.containsKey(sessionId)) _activeSessionId.value = sessionId
    }

    override fun stateFilePath(sessionId: String): String =
        handles[sessionId]?.stateFilePath() ?: File(stateDir, "state_$sessionId").absolutePath

    private fun emit() {
        _sessions.value = handles.values.toList()
    }
}

class TermuxSessionHandle(
    private val context: Context,
    paths: TermuxPaths,
    override val id: String,
    initialTitle: String,
    private val stateFile: File,
) : TerminalSessionHandle {

    private val _title = MutableStateFlow(initialTitle)
    override val title: StateFlow<String> = _title.asStateFlow()

    private val _cwd = MutableStateFlow(paths.home.path)
    override val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _lastExitCode = MutableStateFlow<Int?>(null)
    override val lastExitCode: StateFlow<Int?> = _lastExitCode.asStateFlow()

    private val _screenVersion = MutableStateFlow(0L)
    override val screenVersion: StateFlow<Long> = _screenVersion.asStateFlow()

    private val _lastErrorContext = MutableStateFlow<TerminalErrorContext?>(null)
    override val lastErrorContext: StateFlow<TerminalErrorContext?> = _lastErrorContext.asStateFlow()

    private val _bracketed = MutableStateFlow(false)
    override val bracketedPasteEnabled: StateFlow<Boolean> = _bracketed.asStateFlow()

    private val _pasteTruncated = MutableStateFlow(false)
    override val lastPasteTruncated: StateFlow<Boolean> = _pasteTruncated.asStateFlow()

    private val _events = MutableSharedFlow<TerminalCommandEvent>(extraBufferCapacity = 8)
    override val commandEvents: Flow<TerminalCommandEvent> = _events.asSharedFlow()

    private var customTitle = false
    private var lastCwdCheck = 0L
    private var lastStateCheck = 0L
    private var lastStateMtime = stateFile.lastModified()

    private fun pollState() {
        val m = stateFile.lastModified()
        if (m == 0L || m == lastStateMtime) return
        lastStateMtime = m
        val text = try { stateFile.readText() } catch (_: Exception) { return }
        val st = TermuxStateParser.parse(text) ?: return
        val cwdNorm = TermuxPaths.rewriteUser0(st.cwd)
        _lastExitCode.value = st.exitCode
        _cwd.value = cwdNorm
        if (st.exitCode != 0 && st.command.isNotBlank()) {
            _lastErrorContext.value = TerminalErrorContext(
                sessionId = id,
                command = st.command,
                exitCode = st.exitCode,
                errorOutput = readRecentText(30),
                cwd = cwdNorm,
            )
        } else if (st.exitCode == 0) {
            _lastErrorContext.value = null
        }
        val s = st.startSec
        val e = st.endSec
        if (s != null && e != null && st.command.isNotBlank()) {
            _events.tryEmit(
                TerminalCommandEvent.Finished(
                    sessionId = id,
                    sessionTitle = _title.value,
                    command = st.command,
                    exitCode = st.exitCode,
                    startedAtMillis = s * 1000L,
                    finishedAtMillis = e * 1000L,
                ),
            )
        }
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _screenVersion.value = _screenVersion.value + 1
            val now = SystemClock.elapsedRealtime()
            if (now - lastStateCheck > 200) {
                lastStateCheck = now
                pollState()
            }
            if (now - lastCwdCheck > 1000) {
                lastCwdCheck = now
                changedSession.cwd?.let {
                    val n = TermuxPaths.rewriteUser0(it)
                    if (n != _cwd.value) _cwd.value = n
                }
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            if (!customTitle) changedSession.title?.takeIf { it.isNotBlank() }?.let { _title.value = it }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            _lastExitCode.value = finishedSession.exitStatus
            _screenVersion.value = _screenVersion.value + 1
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) pasteText(text)
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val shell = TermuxEnv.shell(paths.prefix)

    private val session = TerminalSession(
        shell.path,
        paths.home.path,
        arrayOf(shell.name),
        TermuxEnv.build(
            paths.prefix,
            paths.home,
            extra = mapOf("CYBERORG_STATE_FILE" to stateFile.absolutePath),
        ),
        SCROLLBACK_ROWS,
        client,
    )

    override fun stateFilePath(): String = stateFile.absolutePath

    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) session.write(bytes, 0, bytes.size)
    }

    override fun pasteText(text: String) {
        val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
        write(normalized.toByteArray(Charsets.UTF_8))
    }

    /** First call creates the emulator and starts the shell process; later calls resize the PTY. */
    override fun resize(cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        session.updateSize(cols, rows)
    }

    override fun close() {
        session.finishIfRunning()
    }

    override fun rename(newTitle: String) {
        if (newTitle.isNotBlank()) {
            customTitle = true
            _title.value = newTitle
        }
    }

    override fun clearScreen() {
        write(byteArrayOf(0x0C))
    }

    override fun readScreen(): TerminalScreenBuffer {
        val em = session.emulator
            ?: return TerminalScreenBuffer(cols = 1, rows = 1, cursorRow = 0, cursorCol = 0, visibleRows = listOf(listOf(TerminalCell.EMPTY)))
        val screen = em.screen
        val cols = em.mColumns
        val rows = em.mRows
        val transcript = screen.activeTranscriptRows.coerceAtMost(MAX_SCROLLBACK_READ)
        val visible = ArrayList<List<TerminalCell>>(rows)
        val scrollback = ArrayList<List<TerminalCell>>(transcript)
        for (y in -transcript until 0) scrollback.add(readRow(screen, y, cols))
        for (y in 0 until rows) visible.add(readRow(screen, y, cols))
        return TerminalScreenBuffer(
            cols = cols,
            rows = rows,
            cursorRow = em.cursorRow,
            cursorCol = em.cursorCol,
            visibleRows = visible,
            scrollbackRows = scrollback,
        )
    }

    private fun readRow(screen: com.termux.terminal.TerminalBuffer, y: Int, cols: Int): List<TerminalCell> {
        val text = screen.getSelectedText(0, y, cols, y) ?: ""
        val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(y))
        val out = ArrayList<TerminalCell>(cols)
        for (x in 0 until cols) {
            val ch = if (x < text.length) text[x] else ' '
            val style = row.getStyle(x)
            val fg = TermuxEnv.approximateAnsiCode(TextStyle.decodeForeColor(style), background = false)
            val bg = TermuxEnv.approximateAnsiCode(TextStyle.decodeBackColor(style), background = true)
            val bold = (TextStyle.decodeEffect(style) and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
            out.add(
                if (ch == ' ' && fg == -1 && bg == -1 && !bold) TerminalCell.EMPTY
                else TerminalCell(char = ch, fgColorCode = fg, bgColorCode = bg, bold = bold),
            )
        }
        return out
    }

    override fun readRecentText(maxLines: Int): String {
        val em = session.emulator ?: return ""
        val all = em.screen.transcriptText ?: return ""
        return all.lines().dropLastWhile { it.isBlank() }.takeLast(maxLines.coerceAtLeast(1)).joinToString("\n")
    }

    private companion object {
        const val SCROLLBACK_ROWS = 2000
        const val MAX_SCROLLBACK_READ = 500
    }
}
