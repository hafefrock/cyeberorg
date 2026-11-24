package com.example.terminal

/**
 * Pure Kotlin sanitizer for terminal paste and snippet insertion payloads.
 *
 * Strips unsafe C0/C1 control characters, ESC, and bracketed-paste escape sequences
 * iteratively until the string stabilizes, ensuring split or doubled `200~`/`201~`
 * markers cannot escape bracketed paste mode.
 */
object TerminalPasteSanitizer {

    const val MAX_PASTE_BYTES = 64 * 1024 // 64 KB UTF-8 cap

    data class SanitizedPastePayload(
        val text: String,
        val wasTruncated: Boolean,
        val hadControlOrMarkersStripped: Boolean,
        val hadNewlines: Boolean
    )

    private val FORBIDDEN_MARKERS = arrayOf("[200~", "[201~", "200~", "201~")

    /**
     * Returns true if [raw] contains any newline (`\n`, `\r`) or any control character
     * (C0 `0x00..0x1F`, DEL `0x7F`, or C1 `0x80..0x9F`).
     */
    fun containsControlOrNewline(raw: String): Boolean {
        for (i in raw.indices) {
            val code = raw[i].code
            if (code < 0x20 || code == 0x7F || code in 0x80..0x9F) {
                return true
            }
        }
        return false
    }

    /**
     * Normalizes line endings, iteratively strips unsafe control bytes (preserving `\n` and `\t`),
     * `ESC`, and `200~`/`201~` sequences until stable, trims trailing newlines, and caps payload
     * at [maxBytes] UTF-8 bytes without splitting UTF-8 code points or UTF-16 surrogate pairs.
     */
    fun sanitize(raw: String, maxBytes: Int = MAX_PASTE_BYTES): SanitizedPastePayload {
        if (raw.isEmpty()) {
            return SanitizedPastePayload(
                text = "",
                wasTruncated = false,
                hadControlOrMarkersStripped = false,
                hadNewlines = false
            )
        }

        val normalizedLines = normalizeLineEndings(raw)
        var current = normalizedLines
        var strippedSomething = false

        // Repeat strip + marker removal in a bounded convergence loop until completely stable.
        // Because every removal strictly shortens the string, this converges in O(passes * N).
        while (true) {
            val afterControls = stripUnsafeControlChars(current)
            val afterMarkers = stripBracketedMarkers(afterControls)
            if (afterMarkers.length != current.length) {
                strippedSomething = true
                current = afterMarkers
            } else {
                break
            }
        }

        val trimmed = current.trimEnd('\n')
        val (bounded, truncated) = truncateUtf8Bytes(trimmed, maxBytes)
        val finalText = if (truncated) bounded.trimEnd('\n') else bounded

        return SanitizedPastePayload(
            text = finalText,
            wasTruncated = truncated,
            hadControlOrMarkersStripped = strippedSomething,
            hadNewlines = finalText.contains('\n')
        )
    }

    private fun normalizeLineEndings(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\r') {
                sb.append('\n')
                if (i + 1 < input.length && input[i + 1] == '\n') {
                    i += 2
                    continue
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun stripUnsafeControlChars(input: String): String {
        var needsStrip = false
        for (i in input.indices) {
            if (isUnsafeControlCode(input[i].code)) {
                needsStrip = true
                break
            }
        }
        if (!needsStrip) return input

        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            val ch = input[i]
            if (!isUnsafeControlCode(ch.code)) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isUnsafeControlCode(code: Int): Boolean {
        if (code == 0x0A || code == 0x09) return false // preserve \n and \t
        return code in 0x00..0x1F || code == 0x7F || code in 0x80..0x9F
    }

    private fun stripBracketedMarkers(input: String): String {
        // Quick scan for '~' first; if no '~' exists, none of the 200~/201~ markers can match.
        if (input.indexOf('~') == -1) return input

        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            var matchedLen = 0
            for (marker in FORBIDDEN_MARKERS) {
                if (input.startsWith(marker, startIndex = i)) {
                    matchedLen = marker.length
                    break
                }
            }
            if (matchedLen > 0) {
                i += matchedLen
            } else {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun truncateUtf8Bytes(input: String, maxBytes: Int): Pair<String, Boolean> {
        if (maxBytes <= 0) return "" to input.isNotEmpty()
        // Fast path: max Unicode UTF-8 size is 3 bytes per BMP char (4 bytes per surrogate pair = 2 chars).
        if (input.length * 3 <= maxBytes) return input to false

        var byteCount = 0
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (Character.isHighSurrogate(ch) && i + 1 < input.length && Character.isLowSurrogate(input[i + 1])) {
                if (byteCount + 4 > maxBytes) {
                    return input.substring(0, i) to true
                }
                byteCount += 4
                i += 2
                continue
            }
            val charBytes = when {
                ch.code <= 0x7F -> 1
                ch.code <= 0x7FF -> 2
                else -> 3
            }
            if (byteCount + charBytes > maxBytes) {
                return input.substring(0, i) to true
            }
            byteCount += charBytes
            i++
        }
        return input to false
    }
}
