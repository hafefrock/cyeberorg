package com.example.data

import com.example.R
import com.example.ui.UiText

/**
 * Pure validator for user-supplied Gemini API keys before writing to EncryptedKeyVault or DataStore.
 */
object ApiKeyValidator {
    const val MIN_LENGTH = 20
    const val MAX_LENGTH = 300
    private const val PREFIX_NEW = "AQ."
    private const val PREFIX_LEGACY = "AIza"

    private val KNOWN_ASSIGNMENT_PREFIXES = setOf(
        "key",
        "apikey",
        "api_key",
        "api-key",
        "gemini_api_key",
        "gemini-api-key",
        "google_api_key",
        "google-api-key",
        "x-goog-api-key",
        "x_goog_api_key"
    )

    sealed interface ValidationResult {
        data class Valid(val cleanedKey: String) : ValidationResult
        data class Invalid(val uiReason: UiText) : ValidationResult
    }

    /**
     * Cleans pasted/typed API key input in linear time:
     * removes CR/LF newlines, trims surrounding whitespace and quotes,
     * and strips common prefixes like `key=` or `GEMINI_API_KEY=`.
     * Internal space characters (' ', '\t') are preserved so validation can still reject malformed spaced keys.
     */
    fun cleanInput(rawKey: String): String {
        if (rawKey.isEmpty()) return ""
        var s = if (rawKey.indexOf('\n') >= 0 || rawKey.indexOf('\r') >= 0) {
            buildString(rawKey.length) {
                for (i in 0 until rawKey.length) {
                    val c = rawKey[i]
                    if (c != '\n' && c != '\r') append(c)
                }
            }
        } else {
            rawKey
        }

        s = trimWhitespaceAndQuotes(s)
        if (s.startsWith("?") || s.startsWith("&")) {
            s = trimWhitespaceAndQuotes(s.substring(1))
        }
        if (s.length > 7 && s.startsWith("export", ignoreCase = true) && s[6].isWhitespace()) {
            s = trimWhitespaceAndQuotes(s.substring(6))
        }

        val scanLimit = minOf(s.length, 32)
        var sepIndex = -1
        for (i in 0 until scanLimit) {
            val c = s[i]
            if (c == '=' || c == ':') {
                sepIndex = i
                break
            }
        }
        if (sepIndex > 0) {
            val prefixName = trimWhitespaceAndQuotes(s.substring(0, sepIndex)).lowercase()
            if (prefixName in KNOWN_ASSIGNMENT_PREFIXES) {
                s = trimWhitespaceAndQuotes(s.substring(sepIndex + 1))
            }
        }
        return s
    }

    fun validate(rawKey: String, existingSecrets: Collection<String> = emptyList()): ValidationResult {
        val cleaned = cleanInput(rawKey)
        if (cleaned.isEmpty()) {
            return ValidationResult.Invalid(UiText.Resource(R.string.vault_err_empty_key))
        }
        if (cleaned.length !in MIN_LENGTH..MAX_LENGTH) {
            return ValidationResult.Invalid(UiText.Resource(R.string.vault_err_invalid_format))
        }
        if (!cleaned.startsWith(PREFIX_NEW) && !cleaned.startsWith(PREFIX_LEGACY)) {
            return ValidationResult.Invalid(UiText.Resource(R.string.vault_err_invalid_format))
        }
        for (i in 0 until cleaned.length) {
            if (!isAllowedKeyChar(cleaned[i])) {
                return ValidationResult.Invalid(UiText.Resource(R.string.vault_err_invalid_format))
            }
        }
        if (existingSecrets.any { cleanInput(it) == cleaned || it.trim() == cleaned }) {
            return ValidationResult.Invalid(UiText.Resource(R.string.vault_err_duplicate_key))
        }
        return ValidationResult.Valid(cleaned)
    }

    private fun isAllowedKeyChar(c: Char): Boolean {
        return (c in 'A'..'Z') ||
            (c in 'a'..'z') ||
            (c in '0'..'9') ||
            c == '_' ||
            c == '-' ||
            c == '.'
    }

    private fun trimWhitespaceAndQuotes(input: String): String {
        var start = 0
        var end = input.length
        while (start < end && isTrimChar(input[start])) start++
        while (end > start && isTrimChar(input[end - 1])) end--
        return if (start == 0 && end == input.length) input else input.substring(start, end)
    }

    private fun isTrimChar(c: Char): Boolean {
        return c.isWhitespace() || c == '"' || c == '\'' || c == '`'
    }
}
