package com.example.terminal

enum class TerminalTokenKind {
    URL,
    IPV4,
    FILE_PATH
}

data class DetectedTerminalToken(
    val text: String,
    val kind: TerminalTokenKind,
    val startCol: Int,
    val endColExclusive: Int
)

/**
 * Pure, linear-time, bounded token detector for URLs, IPv4 addresses, and file paths.
 * Avoids regex backtracking (ReDoS-safe) and skips lines longer than [MAX_ROW_LENGTH] cleanly.
 */
object TerminalTokenDetector {
    const val MAX_ROW_LENGTH: Int = 2_000
    const val MAX_TOKEN_LENGTH: Int = 512

    private val URL_SCHEMES = listOf("https://", "http://", "ftp://", "ssh://", "git://")
    private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
    private val PATH_BOUNDARY_PREFIXES = setOf(' ', '\t', '"', '\'', '(', '[', '{', '<', ',', ';', '=', ':')

    fun rowToPlainText(rowCells: List<TerminalCell>): String {
        if (rowCells.isEmpty()) return ""
        val limit = minOf(rowCells.size, MAX_ROW_LENGTH)
        val sb = StringBuilder(limit)
        for (i in 0 until limit) {
            sb.append(rowCells[i].char)
        }
        return sb.toString()
    }

    fun detectTokens(rowText: String): List<DetectedTerminalToken> {
        if (rowText.isEmpty()) return emptyList()
        val limit = minOf(rowText.length, MAX_ROW_LENGTH)
        val results = mutableListOf<DetectedTerminalToken>()
        var i = 0

        while (i < limit) {
            val ch = rowText[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }

            // 1. Check URL schemes cleanly via exact prefix match
            val matchedScheme = matchSchemeAt(rowText, i, limit)
            if (matchedScheme != null && isUrlBoundaryBefore(rowText, i)) {
                val rawEnd = scanNonDelimiterEnd(rowText, i, limit)
                val tokenEnd = trimTokenEnd(rowText, i, rawEnd)
                if (tokenEnd > i + matchedScheme.length) {
                    val cappedEnd = minOf(tokenEnd, i + MAX_TOKEN_LENGTH)
                    results += DetectedTerminalToken(
                        text = rowText.substring(i, cappedEnd),
                        kind = TerminalTokenKind.URL,
                        startCol = i,
                        endColExclusive = cappedEnd
                    )
                    i = rawEnd
                    continue
                }
            }

            // 2. Check File Paths starting with ~/, ./, ../, or /
            val pathPrefixLen = matchFilePathPrefix(rowText, i, limit)
            if (pathPrefixLen > 0) {
                val rawEnd = scanPathEnd(rowText, i, limit)
                val tokenEnd = trimPathEnd(rowText, i, rawEnd, pathPrefixLen)
                if (tokenEnd > i) {
                    val cappedEnd = minOf(tokenEnd, i + MAX_TOKEN_LENGTH)
                    val candidate = rowText.substring(i, cappedEnd)
                    if (isValidFilePathCandidate(candidate)) {
                        results += DetectedTerminalToken(
                            text = candidate,
                            kind = TerminalTokenKind.FILE_PATH,
                            startCol = i,
                            endColExclusive = cappedEnd
                        )
                        i = rawEnd
                        continue
                    }
                }
            }

            // 3. Check IPv4 addresses (digits at boundary)
            if (ch in '0'..'9' && isIpv4BoundaryBefore(rowText, i)) {
                val ipv4End = tryParseIpv4(rowText, i, limit)
                if (ipv4End > i) {
                    val cappedEnd = minOf(ipv4End, i + MAX_TOKEN_LENGTH)
                    results += DetectedTerminalToken(
                        text = rowText.substring(i, cappedEnd),
                        kind = TerminalTokenKind.IPV4,
                        startCol = i,
                        endColExclusive = cappedEnd
                    )
                    i = ipv4End
                    continue
                }
            }

            i++
        }
        return results
    }

    fun findTokenAt(rowText: String, col: Int): DetectedTerminalToken? {
        if (col < 0 || rowText.isEmpty()) return null
        val tokens = detectTokens(rowText)
        return tokens.firstOrNull { col in it.startCol until it.endColExclusive }
    }

    private fun matchSchemeAt(text: String, pos: Int, limit: Int): String? {
        for (scheme in URL_SCHEMES) {
            if (pos + scheme.length < limit &&
                text.regionMatches(pos, scheme, 0, scheme.length, ignoreCase = false)
            ) {
                return scheme
            }
        }
        return null
    }

    private fun isUrlBoundaryBefore(text: String, pos: Int): Boolean {
        if (pos == 0) return true
        val prev = text[pos - 1]
        return prev in PATH_BOUNDARY_PREFIXES || prev == '\n' || prev == '\r'
    }

    private fun scanNonDelimiterEnd(text: String, start: Int, limit: Int): Int {
        var pos = start
        val maxEnd = minOf(limit, start + MAX_TOKEN_LENGTH)
        while (pos < maxEnd) {
            val c = text[pos]
            if (c.isWhitespace() || c == '<' || c == '>' || c == '"' || c == '\'') {
                break
            }
            pos++
        }
        return pos
    }

    private fun trimTokenEnd(text: String, start: Int, rawEnd: Int): Int {
        var end = rawEnd
        while (end > start && text[end - 1] in TRAILING_PUNCTUATION) {
            val ch = text[end - 1]
            if (ch == ')' && hasBalancedParens(text, start, end)) break
            end--
        }
        return end
    }

    private fun hasBalancedParens(text: String, start: Int, endExclusive: Int): Boolean {
        var openCount = 0
        for (idx in start until endExclusive) {
            when (text[idx]) {
                '(' -> openCount++
                ')' -> openCount--
            }
        }
        return openCount >= 0
    }

    private fun matchFilePathPrefix(text: String, pos: Int, limit: Int): Int {
        if (pos > 0 && text[pos - 1] !in PATH_BOUNDARY_PREFIXES) return 0
        if (pos + 2 < limit && text[pos] == '.' && text[pos + 1] == '.' && text[pos + 2] == '/') {
            return 3
        }
        if (pos + 1 < limit && text[pos] == '.' && text[pos + 1] == '/') {
            return 2
        }
        if (pos + 1 < limit && text[pos] == '~' && text[pos + 1] == '/') {
            return 2
        }
        if (text[pos] == '/') {
            // Avoid matching URL scheme slashes or C-style // comments or bare /
            if (pos + 1 >= limit) return 0
            val next = text[pos + 1]
            if (next.isWhitespace() || next == '/') return 0
            return 1
        }
        return 0
    }

    private fun scanPathEnd(text: String, start: Int, limit: Int): Int {
        var pos = start
        val maxEnd = minOf(limit, start + MAX_TOKEN_LENGTH)
        while (pos < maxEnd) {
            val c = text[pos]
            if (c.isWhitespace() || c == '"' || c == '\'' || c == '<' || c == '>' ||
                c == '|' || c == ';' || c == ',' || c == ')' || c == ']' || c == '}'
            ) {
                break
            }
            pos++
        }
        return pos
    }

    private fun trimPathEnd(text: String, start: Int, rawEnd: Int, prefixLen: Int): Int {
        var end = rawEnd
        while (end > start + prefixLen && (text[end - 1] == '.' || text[end - 1] == ':' || text[end - 1] == '!')) {
            end--
        }
        return end
    }

    private fun isValidFilePathCandidate(candidate: String): Boolean {
        if (candidate.length <= 1) return false
        if (candidate.startsWith("//")) return false
        return candidate != "~/" && candidate != "./" && candidate != "../"
    }

    private fun isIpv4BoundaryBefore(text: String, pos: Int): Boolean {
        if (pos == 0) return true
        val prev = text[pos - 1]
        return !(prev.isLetterOrDigit() || prev == '.' || prev == '_')
    }

    private fun tryParseIpv4(text: String, start: Int, limit: Int): Int {
        var pos = start
        for (octetIdx in 0 until 4) {
            val octetStart = pos
            var value = 0
            var digits = 0
            while (pos < limit && text[pos] in '0'..'9' && digits < 4) {
                value = value * 10 + (text[pos] - '0')
                digits++
                pos++
            }
            if (digits == 0 || digits > 3 || value > 255) return -1
            if (digits > 1 && text[octetStart] == '0') return -1

            if (octetIdx < 3) {
                if (pos >= limit || text[pos] != '.') return -1
                pos++
            }
        }
        if (pos < limit) {
            val after = text[pos]
            if (after.isLetterOrDigit() || after == '.' || after == '_') return -1
        }
        return pos
    }
}
