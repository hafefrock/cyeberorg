package com.example.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

data class TerminalPasteOutcome(
    val inserted: Boolean,
    val truncated: Boolean,
    val copiedFallbackRequired: Boolean
)

sealed interface TerminalCommandEvent {
    val sessionId: String

    data class Started(
        override val sessionId: String,
        val command: String,
        val startedAtMillis: Long
    ) : TerminalCommandEvent

    data class Finished(
        override val sessionId: String,
        val sessionTitle: String,
        val command: String,
        val exitCode: Int,
        val startedAtMillis: Long,
        val finishedAtMillis: Long
    ) : TerminalCommandEvent {
        val durationMs: Long
            get() = (finishedAtMillis - startedAtMillis).coerceAtLeast(0L)
    }
}

/**
 * PTY-ready handle representing a single active terminal session.
 * Implementations can wrap either [FakeTerminalBackend] or Termux's `TerminalSession`.
 */
interface TerminalSessionHandle {
    val id: String
    val title: StateFlow<String>
    val cwd: StateFlow<String>
    val lastExitCode: StateFlow<Int?>
    val screenVersion: StateFlow<Long>
    val lastErrorContext: StateFlow<TerminalErrorContext?>
    val bracketedPasteEnabled: StateFlow<Boolean>
    val lastPasteTruncated: StateFlow<Boolean>
    val commandEvents: Flow<TerminalCommandEvent>
        get() = emptyFlow()

    fun stateFilePath(): String
    fun write(bytes: ByteArray)
    fun pasteText(text: String)
    fun pasteTextWithOutcome(text: String): TerminalPasteOutcome {
        pasteText(text)
        return TerminalPasteOutcome(inserted = true, truncated = lastPasteTruncated.value, copiedFallbackRequired = false)
    }
    fun resize(cols: Int, rows: Int)
    fun close()

    fun rename(newTitle: String)
    fun clearScreen()
    fun readScreen(): TerminalScreenBuffer
    fun readRecentText(maxLines: Int = 24): String
}

/**
 * PTY-ready terminal session manager interface.
 * Terminal UI and ViewModel depend exclusively on this contract.
 */
interface TerminalBackend {
    val sessions: StateFlow<List<TerminalSessionHandle>>
    val activeSessionId: StateFlow<String>

    fun createSession(title: String? = null): TerminalSessionHandle
    fun closeSession(sessionId: String)
    fun switchSession(sessionId: String)
    fun stateFilePath(sessionId: String): String
}
