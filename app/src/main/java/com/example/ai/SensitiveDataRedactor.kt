package com.example.ai

/**
 * Pure, linear-time, ReDoS-safe secret redactor applied before any prompt or
 * terminal context is serialized for Gemini API requests.
 *
 * Input bounds & strategy:
 * - [redact] enforces a hard upper bound of [MAX_INPUT_CHARS] (32,000 characters) by truncating
 *   oversized inputs prior to regex evaluation. Truncation is chosen over sliding-window chunking
 *   because a PEM private key block spanning more than a chunk overlap (> 256 chars) would lose its
 *   `-----BEGIN ... PRIVATE KEY-----` header in subsequent chunks and risk leaking key body lines;
 *   capping at 32,000 characters keeps regex evaluation strictly linear (`O(N)` with bounded prefixes
 *   and value lengths) and ensures any trailing content beyond the cap cannot leave the device.
 * - [redactRequest] truncates user prompt and context fields to compact limits before calling
 *   [redact], avoiding wasted CPU on oversized terminal history buffers.
 */
object SensitiveDataRedactor {
    const val REDACTED = "[REDACTED]"
    const val MAX_INPUT_CHARS = 32_000
    const val MAX_USER_PROMPT_CHARS = 8_000
    const val MAX_CWD_CHARS = 512
    const val MAX_COMMAND_CHARS = 2_000
    const val MAX_ERROR_OUTPUT_CHARS = 4_000
    const val MAX_SELECTED_TEXT_CHARS = 4_000
    const val MAX_RECENT_OUTPUT_CHARS = 4_000

    private val pemPrivateKeyPattern = Regex(
        """-----BEGIN [A-Z0-9 ]{0,48}PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9 ]{0,48}PRIVATE KEY-----|$)"""
    )

    private val authHeaderPattern = Regex(
        """(?<![A-Za-z0-9_-])((?i:Authorization)[ \t]{0,8}:[ \t]{0,8}(?i:Bearer|Basic|Token)[ \t]{1,8})([^\s"'`,;]{1,1024})"""
    )

    private val standaloneBearerPattern = Regex(
        """(?<![A-Za-z0-9_-])(Bearer[ \t]{1,8})([A-Za-z0-9._~+/=-]{12,1024})"""
    )

    private val apiKeyHeaderPattern = Regex(
        """(?<![A-Za-z0-9_-])((?i:x-(?:goog-)?api-key)[ \t]{0,8}:[ \t]{0,8})([^\s"'`,;]{1,512})"""
    )

    private val googleApiKeyPattern = Regex(
        """(?<![A-Za-z0-9_-])AIza[0-9A-Za-z_-]{16,300}"""
    )

    private val knownProviderTokenPattern = Regex(
        """(?<![A-Za-z0-9_-])(?:sk-(?:proj-|ant-|live-|test-)?[0-9A-Za-z_-]{16,256}|gh[pousr]_[0-9A-Za-z_]{16,256}|github_pat_[0-9A-Za-z_]{20,256}|xox[baprs]-[0-9A-Za-z-]{10,256}|glpat-[0-9A-Za-z_-]{16,256})"""
    )

    private val awsAccessKeyPattern = Regex(
        """(?<![A-Za-z0-9_])(?:AKIA|ASIA)[0-9A-Z]{16}(?![A-Za-z0-9_])"""
    )

    private val jwtPattern = Regex(
        """(?<![A-Za-z0-9_.-])eyJ[0-9A-Za-z_-]{6,512}\.eyJ[0-9A-Za-z_-]{6,512}\.[0-9A-Za-z_-]{6,512}"""
    )

    private val urlUserInfoPattern = Regex(
        """(?<![A-Za-z0-9+.-])([a-zA-Z][a-zA-Z0-9+.-]{0,63}://[^\s:/@]{1,64}:)([^\s/@]{1,256})(@)"""
    )

    // Matches secret assignments only when:
    // 1. An ALL-CAPS env var contains KEY/TOKEN/SECRET/PASSWORD/PASSWD at underscore/hyphen word boundaries, OR
    // 2. A lowercase/mixed-case identifier ends with key/token/secret/password/passwd at a separator or camelCase boundary.
    // All prefixes and values are strictly bounded so worst-case scanning stays O(N).
    private val secretAssignmentPattern = Regex(
        """(?<![A-Za-z0-9_.-])(["']?(?:(?:[A-Z0-9_-]{0,32}[_-])?(?:KEY|TOKEN|SECRET|PASSWORD|PASSWD)(?:[_-][A-Z0-9_-]{0,32})?|(?i:(?:[a-z0-9._-]{0,48}[._-])?(?:key|token|secret|password|passwd))|[a-z0-9]{1,32}(?:Key|Token|Secret|Password|Passwd))["']?[ \t]{0,8}[:=][ \t]{0,8})("[^"\r\n]{1,512}"|'[^'\r\n]{1,512}'|[^\s"'`;,&|)\r\n]{1,512})"""
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var out = if (input.length > MAX_INPUT_CHARS) input.substring(0, MAX_INPUT_CHARS) else input
        if (out.contains("-----BEGIN ")) {
            out = pemPrivateKeyPattern.replace(out, REDACTED)
        }
        val hasColon = out.indexOf(':') >= 0
        val hasEquals = out.indexOf('=') >= 0
        if (hasColon) {
            out = authHeaderPattern.replace(out) { "${it.groupValues[1]}$REDACTED" }
        }
        if (out.contains("Bearer")) {
            out = standaloneBearerPattern.replace(out) { "${it.groupValues[1]}$REDACTED" }
        }
        if (hasColon) {
            out = apiKeyHeaderPattern.replace(out) { "${it.groupValues[1]}$REDACTED" }
        }
        if (out.contains("://") && out.indexOf('@') >= 0) {
            out = urlUserInfoPattern.replace(out) { "${it.groupValues[1]}$REDACTED${it.groupValues[3]}" }
        }
        if (hasEquals || hasColon) {
            out = secretAssignmentPattern.replace(out) { "${it.groupValues[1]}$REDACTED" }
        }
        if (out.contains("AIza")) {
            out = googleApiKeyPattern.replace(out, REDACTED)
        }
        if (out.contains("AQ.")) {
            out = redactAqKeys(out)
        }
        if (out.contains("sk-") || out.contains("gh") || out.contains("github_pat_") ||
            out.contains("xox") || out.contains("glpat-")
        ) {
            out = knownProviderTokenPattern.replace(out, REDACTED)
        }
        if (out.contains("AKIA") || out.contains("ASIA")) {
            out = awsAccessKeyPattern.replace(out, REDACTED)
        }
        if (out.contains("eyJ")) {
            out = jwtPattern.replace(out, REDACTED)
        }
        return out
    }

    fun redactContext(context: AiPromptContext): AiPromptContext {
        return AiPromptContext(
            cwd = context.cwd?.let { redact(it.take(MAX_CWD_CHARS)) },
            lastError = context.lastError?.let { err ->
                err.copy(
                    command = redact(err.command.take(MAX_COMMAND_CHARS)),
                    cwd = redact(err.cwd.take(MAX_CWD_CHARS)),
                    errorOutput = redact(err.errorOutput.takeLast(MAX_ERROR_OUTPUT_CHARS))
                )
            },
            selectedText = context.selectedText?.let { redact(it.take(MAX_SELECTED_TEXT_CHARS)) },
            recentOutput = context.recentOutput?.let { redact(it.takeLast(MAX_RECENT_OUTPUT_CHARS)) }
        )
    }

    fun redactRequest(request: AiPromptRequest): AiPromptRequest {
        return AiPromptRequest(
            kind = request.kind,
            userPrompt = redact(request.userPrompt.take(MAX_USER_PROMPT_CHARS)),
            context = redactContext(request.context)
        )
    }

    private fun redactAqKeys(input: String): String {
        val len = input.length
        var idx = input.indexOf("AQ.")
        if (idx < 0) return input

        var sb: StringBuilder? = null
        var lastCopy = 0
        while (idx >= 0) {
            val precededByTokenChar = idx > 0 && isAqTokenChar(input[idx - 1])
            if (precededByTokenChar) {
                idx = input.indexOf("AQ.", idx + 3)
                continue
            }
            val bodyStart = idx + 3
            val limit = minOf(len, bodyStart + 300)
            var end = bodyStart
            while (end < limit && isAqTokenChar(input[end])) {
                end++
            }
            if (end - bodyStart >= 16) {
                val out = sb ?: StringBuilder(len).also { sb = it }
                out.append(input, lastCopy, idx)
                out.append(REDACTED)
                lastCopy = end
                idx = input.indexOf("AQ.", end)
            } else {
                idx = input.indexOf("AQ.", bodyStart)
            }
        }
        val out = sb ?: return input
        if (lastCopy < len) {
            out.append(input, lastCopy, len)
        }
        return out.toString()
    }

    private fun isAqTokenChar(c: Char): Boolean {
        return (c in 'A'..'Z') ||
            (c in 'a'..'z') ||
            (c in '0'..'9') ||
            c == '.' ||
            c == '_' ||
            c == '-'
    }
}
