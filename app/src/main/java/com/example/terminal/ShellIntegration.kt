package com.example.terminal

import java.io.ByteArrayOutputStream

/**
 * Dual-channel shell integration contract between the PTY shell and Cyberorg:
 *
 * 1. **In-band OSC 7777 stream channel** ([OscStreamParser]):
 *    `ESC ] 7777 ; exit=<int> ; cwd=<path> BEL` (or `ST` = `ESC \`).
 * 2. **Out-of-band atomic state file channel** ([parseStateFile]):
 *    Writes `exit=<code>\ncwd=<path>\n` to `<stateFile>.tmp` and atomically replaces
 *    `<stateFile>` via `mv -f` — resilient when real terminal emulators or multiplexers
 *    swallow unknown private OSC escape sequences.
 */
object ShellIntegration {

    const val OSC_CODE = "7777"
    const val MAX_OSC_BUFFER_BYTES = 4096

    /**
     * Bash `PROMPT_COMMAND` snippet reporting via both OSC 7777 and atomic state file.
     */
    fun bashSnippet(stateFilePath: String): String = """
        __hs_precmd() {
          local __hs_ec=${'$'}?
          printf '\033]7777;exit=%d;cwd=%s\007' "${'$'}__hs_ec" "${'$'}PWD"
          printf 'exit=%d\ncwd=%s\n' "${'$'}__hs_ec" "${'$'}PWD" > "${stateFilePath}.tmp" && mv -f "${stateFilePath}.tmp" "$stateFilePath"
        }
        PROMPT_COMMAND="__hs_precmd${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}"
    """.trimIndent()

    /**
     * Zsh `precmd` hook snippet reporting via both OSC 7777 and atomic state file.
     */
    fun zshSnippet(stateFilePath: String): String = """
        __hs_precmd() {
          local __hs_ec=${'$'}?
          printf '\033]7777;exit=%d;cwd=%s\007' "${'$'}__hs_ec" "${'$'}PWD"
          printf 'exit=%d\ncwd=%s\n' "${'$'}__hs_ec" "${'$'}PWD" > "${stateFilePath}.tmp" && mv -f "${stateFilePath}.tmp" "$stateFilePath"
        }
        autoload -Uz add-zsh-hook
        add-zsh-hook precmd __hs_precmd
    """.trimIndent()

    fun formatOscMarker(exitCode: Int, cwd: String, useStTerminator: Boolean = false): String {
        val terminator = if (useStTerminator) "\u001B\\" else "\u0007"
        return "\u001B]$OSC_CODE;exit=$exitCode;cwd=$cwd$terminator"
    }

    fun formatStateFilePayload(exitCode: Int, cwd: String): String {
        return "exit=$exitCode\ncwd=$cwd\n"
    }

    fun parseStateFile(content: String): ShellPromptEvent? {
        var exitCode: Int? = null
        var cwd: String? = null
        content.lineSequence().forEach { line ->
            when {
                line.startsWith("exit=") -> exitCode = line.removePrefix("exit=").trim().toIntOrNull()
                line.startsWith("cwd=") -> cwd = line.removePrefix("cwd=").trimEnd('\r')
            }
        }
        val resolvedExit = exitCode ?: return null
        val resolvedCwd = cwd ?: return null
        return ShellPromptEvent(exitCode = resolvedExit, cwd = resolvedCwd)
    }

    data class ShellPromptEvent(
        val exitCode: Int,
        val cwd: String
    )

    data class ParseResult(
        val cleanText: String,
        val events: List<ShellPromptEvent>
    )

    /**
     * Stateful streaming parser for PTY byte chunks.
     * Handles sequences split across chunk boundaries, BEL (0x07) and ST (ESC \)
     * terminators, and caps the OSC buffer at [MAX_OSC_BUFFER_BYTES] (4 KB),
     * flushing malformed or oversized sequences as plain text.
     */
    class OscStreamParser(
        private val maxBufferBytes: Int = MAX_OSC_BUFFER_BYTES
    ) {
        private enum class State {
            NORMAL,
            AFTER_ESC,
            IN_OSC,
            OSC_AFTER_ESC
        }

        private var state = State.NORMAL
        private val oscBuffer = ByteArrayOutputStream()

        fun feed(text: String): ParseResult = feed(text.encodeToByteArray())

        fun feed(bytes: ByteArray): ParseResult {
            val out = ByteArrayOutputStream()
            val events = mutableListOf<ShellPromptEvent>()

            for (b in bytes) {
                val code = b.toInt() and 0xFF
                when (state) {
                    State.NORMAL -> {
                        if (code == 0x1B) {
                            oscBuffer.reset()
                            oscBuffer.write(code)
                            state = State.AFTER_ESC
                        } else {
                            out.write(code)
                        }
                    }

                    State.AFTER_ESC -> {
                        if (code == 0x5D) { // ']' -> OSC start
                            oscBuffer.write(code)
                            state = State.IN_OSC
                        } else if (code == 0x1B) {
                            // Previous ESC was standalone; new ESC starts another candidate
                            flushBufferTo(out)
                            oscBuffer.write(code)
                            state = State.AFTER_ESC
                        } else {
                            // Not an OSC sequence (e.g. CSI '[' for ANSI colors); flush intact
                            flushBufferTo(out)
                            out.write(code)
                            state = State.NORMAL
                        }
                    }

                    State.IN_OSC -> {
                        if (code == 0x07) { // BEL terminator
                            val payloadBytes = oscBuffer.toByteArray()
                            val parsed = parseOscPayload(payloadBytes)
                            if (parsed != null) {
                                events.add(parsed)
                                oscBuffer.reset()
                            } else {
                                // Malformed OSC 7777 -> flush raw bytes + BEL as plain text
                                flushBufferTo(out)
                                out.write(code)
                            }
                            state = State.NORMAL
                        } else if (code == 0x1B) {
                            // Potential ST terminator (ESC \) — check buffer cap before holding
                            if (oscBuffer.size() + 1 > maxBufferBytes) {
                                flushBufferTo(out)
                                out.write(code)
                                state = State.NORMAL
                            } else {
                                oscBuffer.write(code)
                                state = State.OSC_AFTER_ESC
                            }
                        } else {
                            if (oscBuffer.size() + 1 > maxBufferBytes) {
                                // Oversized (>4 KB): flush buffered bytes + current byte as plain text
                                flushBufferTo(out)
                                out.write(code)
                                state = State.NORMAL
                            } else {
                                oscBuffer.write(code)
                                // If prefix after "\u001B]" is not a prefix of "7777;", flush early
                                if (!isPossibleTargetPrefix()) {
                                    flushBufferTo(out)
                                    state = State.NORMAL
                                }
                            }
                        }
                    }

                    State.OSC_AFTER_ESC -> {
                        if (code == 0x5C) { // '\' -> ST terminator (ESC \)
                            val rawWithTrailingEsc = oscBuffer.toByteArray()
                            // Drop trailing ESC (last byte) before parsing payload
                            val payloadBytes = rawWithTrailingEsc.copyOf(rawWithTrailingEsc.size - 1)
                            val parsed = parseOscPayload(payloadBytes)
                            if (parsed != null) {
                                events.add(parsed)
                                oscBuffer.reset()
                            } else {
                                flushBufferTo(out)
                                out.write(code)
                            }
                            state = State.NORMAL
                        } else if (code == 0x07) {
                            // Unterminated ESC followed by BEL: malformed, flush as plain text
                            flushBufferTo(out)
                            out.write(code)
                            state = State.NORMAL
                        } else {
                            if (oscBuffer.size() + 1 > maxBufferBytes) {
                                flushBufferTo(out)
                                out.write(code)
                                state = State.NORMAL
                            } else {
                                oscBuffer.write(code)
                                state = if (code == 0x1B) State.OSC_AFTER_ESC else State.IN_OSC
                            }
                        }
                    }
                }
            }

            return ParseResult(
                cleanText = out.toByteArray().decodeToString(),
                events = events
            )
        }

        private fun isPossibleTargetPrefix(): Boolean {
            val raw = oscBuffer.toByteArray()
            if (raw.size <= 2) return true // only "\u001B]" so far
            val targetPrefix = "7777;"
            val afterHeader = raw.copyOfRange(2, raw.size).decodeToString()
            return if (afterHeader.length <= targetPrefix.length) {
                targetPrefix.startsWith(afterHeader)
            } else {
                afterHeader.startsWith(targetPrefix)
            }
        }

        private fun parseOscPayload(rawHeaderAndPayload: ByteArray): ShellPromptEvent? {
            if (rawHeaderAndPayload.size <= 2) return null
            val body = rawHeaderAndPayload.copyOfRange(2, rawHeaderAndPayload.size).decodeToString()
            if (!body.startsWith("$OSC_CODE;")) return null
            val fields = body.removePrefix("$OSC_CODE;")
            val exitPrefix = "exit="
            val cwdSep = ";cwd="
            val sepIdx = fields.indexOf(cwdSep)
            if (!fields.startsWith(exitPrefix) || sepIdx <= exitPrefix.length) return null
            val exitStr = fields.substring(exitPrefix.length, sepIdx)
            val exitCode = exitStr.toIntOrNull() ?: return null
            val cwd = fields.substring(sepIdx + cwdSep.length)
            return ShellPromptEvent(exitCode = exitCode, cwd = cwd)
        }

        private fun flushBufferTo(out: ByteArrayOutputStream) {
            val bytes = oscBuffer.toByteArray()
            if (bytes.isNotEmpty()) {
                out.write(bytes)
                oscBuffer.reset()
            }
        }
    }
}
