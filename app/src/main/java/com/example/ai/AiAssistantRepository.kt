package com.example.ai

import com.example.R
import com.example.data.AppLanguage
import com.example.data.GeminiModels
import com.example.data.SettingsStore
import com.example.terminal.TerminalErrorContext
import com.example.ui.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AiMessageRole {
    USER,
    ASSISTANT,
    ERROR
}

enum class AiPromptKind {
    CHAT,
    EXPLAIN_ERROR,
    FIX_ERROR,
    SUGGEST_COMMAND,
    WRITE_SCRIPT,
    EXPLAIN_SELECTION,
    WRITE_COMMAND,
    EXPLAIN_COMMAND,
    SUMMARIZE_OUTPUT,
    FIND_ROOT_CAUSE
}

data class AiPromptContext(
    val cwd: String? = null,
    val lastError: TerminalErrorContext? = null,
    val selectedText: String? = null,
    val recentOutput: String? = null,
    val shell: String? = null
)

data class AiPromptRequest(
    val kind: AiPromptKind,
    val userPrompt: String,
    val context: AiPromptContext = AiPromptContext()
)

data class CommandSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val title: UiText,
    val command: String,
    val language: String = "bash",
    val risk: CommandRiskAssessment = CommandRiskClassifier.classify(command)
)

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AiMessageRole,
    val text: UiText,
    val suggestions: List<CommandSuggestion> = emptyList(),
    val keyLabel: String? = null,
    val modelUsed: String? = null,
    val isStreaming: Boolean = false,
    val isTruncated: Boolean = false,
    val streamInterruptedError: UiText? = null,
    val canRetry: Boolean = false,
    val retryRequest: AiPromptRequest? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

sealed interface AiStreamUpdate {
    data class Delta(
        val accumulatedRawText: String,
        val keyLabel: String,
        val modelUsed: String
    ) : AiStreamUpdate

    data class Completed(
        val text: UiText,
        val suggestions: List<CommandSuggestion>,
        val keyLabel: String,
        val modelUsed: String,
        val isTruncated: Boolean = false
    ) : AiStreamUpdate

    data class Interrupted(
        val partialText: UiText,
        val suggestions: List<CommandSuggestion>,
        val errorUiText: UiText,
        val keyLabel: String,
        val modelUsed: String
    ) : AiStreamUpdate

    data class Failed(
        val errorUiText: UiText
    ) : AiStreamUpdate
}

class AiAssistantRepository(
    private val keyManager: KeyManager,
    private val geminiApiClient: GeminiTransport,
    private val settingsStore: SettingsStore,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    fun streamPrompt(request: AiPromptRequest): Flow<AiStreamUpdate> = flow {
        val settings = settingsStore.settingsFlow.first()
        if (!settings.aiEnabled) {
            emit(AiStreamUpdate.Failed(UiText.Resource(R.string.ai_err_disabled)))
            return@flow
        }
        if (!settings.aiConsentAccepted) {
            emit(AiStreamUpdate.Failed(UiText.Resource(R.string.ai_err_consent_required)))
            return@flow
        }

        val primaryModel = GeminiModels.normalizeSelectedModel(settings.selectedModel)
        if (primaryModel == null) {
            emit(AiStreamUpdate.Failed(UiText.Resource(R.string.ai_err_select_model_first)))
            return@flow
        }

        val fallbackOrder = settings.fallbackModels
            .filter { it.enabled && GeminiModels.byId(it.modelId) != null }
            .map { it.modelId }
            .filterNot { it == primaryModel }

        val (systemInstruction, promptText) = withContext(defaultDispatcher) {
            buildSystemInstruction(settings.appLanguage) to buildPromptText(request)
        }
        val contents = listOf(
            GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = promptText))
            )
        )

        val accumulated = StringBuilder()
        var latestKeyLabel = ""
        var latestModelUsed: String = primaryModel

        keyManager.executeStreamWithModelFailover(
            primaryModel = primaryModel,
            fallbackModels = fallbackOrder
        ) { model, apiKey, _ ->
            geminiApiClient.streamGenerate(
                model = model,
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                contents = contents
            )
        }.collect { event ->
            when (event) {
                is KeyStreamEvent.Chunk -> {
                    latestKeyLabel = event.keyLabel
                    if (event.modelUsed.isNotBlank()) latestModelUsed = event.modelUsed
                    accumulated.append(event.textDelta)
                    emit(
                        AiStreamUpdate.Delta(
                            accumulatedRawText = accumulated.toString(),
                            keyLabel = event.keyLabel,
                            modelUsed = latestModelUsed
                        )
                    )
                }

                is KeyStreamEvent.Completed -> {
                    if (event.modelUsed.isNotBlank()) latestModelUsed = event.modelUsed
                    val raw = accumulated.toString()
                    val isBlocked = isBlockedFinishReason(event.finishReason)
                    val isTruncated = isMaxTokensFinishReason(event.finishReason)

                    if (isBlocked || (raw.isBlank() && !isTruncated)) {
                        emit(
                            AiStreamUpdate.Completed(
                                text = UiText.Resource(R.string.ai_response_blocked_or_empty),
                                suggestions = emptyList(),
                                keyLabel = event.keyLabel.ifBlank { latestKeyLabel },
                                modelUsed = latestModelUsed,
                                isTruncated = false
                            )
                        )
                    } else if (raw.isBlank() && isTruncated) {
                        emit(
                            AiStreamUpdate.Completed(
                                text = UiText.Resource(R.string.ai_response_truncated),
                                suggestions = emptyList(),
                                keyLabel = event.keyLabel.ifBlank { latestKeyLabel },
                                modelUsed = latestModelUsed,
                                isTruncated = true
                            )
                        )
                    } else {
                        val maxSuggestions = maxSuggestionsForKind(request.kind)
                        val (cleanText, suggestions) = parseResponse(raw, maxSuggestions = maxSuggestions)
                        val resolvedText = if (cleanText is UiText.Dynamic && cleanText.value.isBlank() && suggestions.isEmpty()) {
                            UiText.Resource(
                                if (isTruncated) R.string.ai_response_truncated
                                else R.string.ai_response_blocked_or_empty
                            )
                        } else {
                            cleanText
                        }
                        emit(
                            AiStreamUpdate.Completed(
                                text = resolvedText,
                                suggestions = suggestions,
                                keyLabel = event.keyLabel.ifBlank { latestKeyLabel },
                                modelUsed = latestModelUsed,
                                isTruncated = isTruncated
                            )
                        )
                    }
                }

                is KeyStreamEvent.MidStreamError -> {
                    if (event.modelUsed.isNotBlank()) latestModelUsed = event.modelUsed
                    val rawPartial = accumulated.toString()
                    val (cleanText, suggestions) = parseResponse(
                        rawPartial,
                        maxSuggestions = maxSuggestionsForKind(request.kind)
                    )
                    emit(
                        AiStreamUpdate.Interrupted(
                            partialText = cleanText,
                            suggestions = suggestions,
                            errorUiText = mapErrorToUiText(event.error),
                            keyLabel = event.keyLabel.ifBlank { latestKeyLabel },
                            modelUsed = latestModelUsed
                        )
                    )
                }

                is KeyStreamEvent.Failed -> {
                    emit(AiStreamUpdate.Failed(mapErrorToUiText(event.error)))
                }
            }
        }
    }

    internal fun isBlockedFinishReason(finishReason: String?): Boolean {
        if (finishReason.isNullOrBlank()) return false
        val upper = finishReason.trim().uppercase()
        return upper == "SAFETY" ||
            upper.contains("BLOCK") ||
            upper == "RECITATION" ||
            upper == "PROHIBITED_CONTENT" ||
            upper == "SPII"
    }

    internal fun isMaxTokensFinishReason(finishReason: String?): Boolean {
        return finishReason?.trim()?.equals("MAX_TOKENS", ignoreCase = true) == true
    }

    internal fun buildPromptText(request: AiPromptRequest): String {
        val redacted = SensitiveDataRedactor.redactRequest(request)
        val safeShell = request.context.shell
            ?.trim()
            ?.take(MAX_SHELL_NAME_CHARS)
            ?.let { SensitiveDataRedactor.redact(it) }
            ?.ifBlank { null }

        return buildString {
            when (redacted.kind) {
                AiPromptKind.EXPLAIN_ERROR -> {
                    appendLine("Explain why this shell command failed and provide safe command(s) to diagnose or fix it.")
                }
                AiPromptKind.FIX_ERROR -> {
                    appendLine("Provide a concise fix for the failing shell command below, plus safe runnable command(s) in fenced ```bash blocks.")
                }
                AiPromptKind.SUGGEST_COMMAND -> {
                    appendLine("Suggest the cleanest POSIX/Termux command(s) for this task:")
                    appendLine(redacted.userPrompt.trim())
                }
                AiPromptKind.WRITE_SCRIPT -> {
                    appendLine("Write a clean, safe, well-commented bash shell script for:")
                    appendLine(redacted.userPrompt.trim())
                    appendLine("Include a command block showing how to save or run it safely.")
                }
                AiPromptKind.EXPLAIN_SELECTION -> {
                    appendLine("Explain the selected terminal output/snippet clearly and highlight any issues or useful follow-up commands:")
                    if (redacted.userPrompt.isNotBlank()) {
                        appendLine(redacted.userPrompt.trim())
                    }
                }
                AiPromptKind.WRITE_COMMAND -> {
                    val targetShell = safeShell ?: DEFAULT_SHELL
                    appendLine("Translate the user's goal into 1 or 2 safe, runnable POSIX/$targetShell commands.")
                    appendLine("Respond with ONE concise explanation sentence followed by 1 or 2 fenced ```bash code blocks (max 2 commands). Never assume automatic execution.")
                    appendLine("Goal: ${redacted.userPrompt.trim()}")
                }
                AiPromptKind.EXPLAIN_COMMAND -> {
                    appendLine("Break down every part of this shell command (program, flags, arguments) and explain its side effects or risks.")
                    appendLine("Be concise: use at most 8 short bullet points. Do NOT output runnable command code blocks.")
                    appendLine("Command: `${redacted.userPrompt.trim()}`")
                }
                AiPromptKind.SUMMARIZE_OUTPUT -> {
                    appendLine("Summarize the following terminal output concisely using brief bullet points (key findings, counts, warnings, and errors). Do NOT propose command cards.")
                    if (redacted.userPrompt.isNotBlank()) {
                        appendLine(redacted.userPrompt.trim())
                    }
                }
                AiPromptKind.FIND_ROOT_CAUSE -> {
                    appendLine("Analyze this terminal output or log to find the root cause:")
                    appendLine("1. List ordered probable root causes (most likely first).")
                    appendLine("2. List concrete next inspection steps.")
                    appendLine("3. Provide AT MOST ONE safe diagnostic or inspection command inside a fenced ```bash code block.")
                    if (redacted.userPrompt.isNotBlank()) {
                        appendLine(redacted.userPrompt.trim())
                    }
                }
                AiPromptKind.CHAT -> {
                    appendLine(redacted.userPrompt.trim())
                }
            }

            val ctx = redacted.context
            if (!ctx.cwd.isNullOrBlank()) {
                appendLine()
                appendLine("Current working directory: ${ctx.cwd.take(MAX_POST_REDACT_CWD_CHARS)}")
            }
            if (safeShell != null || redacted.kind == AiPromptKind.WRITE_COMMAND) {
                appendLine("Shell: ${safeShell ?: DEFAULT_SHELL}")
            }
            if (ctx.lastError != null) {
                appendLine()
                appendLine("Last failed command: `${ctx.lastError.command.take(MAX_POST_REDACT_COMMAND_CHARS)}`")
                appendLine("Exit code: ${ctx.lastError.exitCode}")
                if (ctx.lastError.errorOutput.isNotBlank()) {
                    appendLine("Error output:")
                    appendLine("```")
                    appendLine(ctx.lastError.errorOutput.takeLast(MAX_POST_REDACT_OUTPUT_CHARS))
                    appendLine("```")
                }
            }
            if (!ctx.selectedText.isNullOrBlank()) {
                appendLine()
                appendLine("Selected terminal text:")
                appendLine("```")
                appendLine(ctx.selectedText.take(MAX_POST_REDACT_OUTPUT_CHARS))
                appendLine("```")
            }
            if (!ctx.recentOutput.isNullOrBlank()) {
                appendLine()
                appendLine("Recent terminal output:")
                appendLine("```")
                appendLine(ctx.recentOutput.takeLast(MAX_POST_REDACT_OUTPUT_CHARS))
                appendLine("```")
            }
        }.let { SensitiveDataRedactor.redact(it.trim()) }
    }

    suspend fun explainLastError(error: TerminalErrorContext): AiChatMessage {
        return runPrompt(
            AiPromptRequest(
                kind = AiPromptKind.EXPLAIN_ERROR,
                userPrompt = "Explain error",
                context = AiPromptContext(cwd = error.cwd, lastError = error)
            )
        )
    }

    suspend fun suggestCommand(goal: String, cwd: String, recentContext: String): AiChatMessage {
        return runPrompt(
            AiPromptRequest(
                kind = AiPromptKind.SUGGEST_COMMAND,
                userPrompt = goal,
                context = AiPromptContext(cwd = cwd, recentOutput = recentContext.takeIf { it.isNotBlank() })
            )
        )
    }

    suspend fun writeShellScript(purpose: String, cwd: String): AiChatMessage {
        return runPrompt(
            AiPromptRequest(
                kind = AiPromptKind.WRITE_SCRIPT,
                userPrompt = purpose,
                context = AiPromptContext(cwd = cwd)
            )
        )
    }

    suspend fun sendChatPrompt(
        userText: String,
        cwd: String,
        recentContext: String?
    ): AiChatMessage {
        return runPrompt(
            AiPromptRequest(
                kind = AiPromptKind.CHAT,
                userPrompt = userText,
                context = AiPromptContext(cwd = cwd, recentOutput = recentContext?.takeIf { it.isNotBlank() })
            )
        )
    }

    private suspend fun runPrompt(request: AiPromptRequest): AiChatMessage {
        val settings = settingsStore.settingsFlow.first()
        if (!settings.aiEnabled) {
            return AiChatMessage(
                role = AiMessageRole.ERROR,
                text = UiText.Resource(R.string.ai_err_disabled)
            )
        }
        if (!settings.aiConsentAccepted) {
            return AiChatMessage(
                role = AiMessageRole.ERROR,
                text = UiText.Resource(R.string.ai_err_consent_required)
            )
        }

        val primaryModel = GeminiModels.normalizeSelectedModel(settings.selectedModel)
            ?: return AiChatMessage(
                role = AiMessageRole.ERROR,
                text = UiText.Resource(R.string.ai_err_select_model_first)
            )

        val fallbackOrder = settings.fallbackModels
            .filter { it.enabled && GeminiModels.byId(it.modelId) != null }
            .map { it.modelId }
            .filterNot { it == primaryModel }

        val (systemInstruction, promptText) = withContext(defaultDispatcher) {
            buildSystemInstruction(settings.appLanguage) to buildPromptText(request)
        }

        val result = keyManager.executeWithModelFailover(
            primaryModel = primaryModel,
            fallbackModels = fallbackOrder
        ) { model, apiKey, keyMeta ->
            when (
                val apiResult = geminiApiClient.generate(
                    model = model,
                    apiKey = apiKey,
                    systemInstruction = systemInstruction,
                    contents = listOf(
                        GeminiContent(
                            role = "user",
                            parts = listOf(GeminiPart(text = promptText))
                        )
                    )
                )
            ) {
                is GeminiCallResult.Success -> GeminiCallResult.Success(
                    data = apiResult.data,
                    usedKeyId = keyMeta.id,
                    usedKeyLabel = keyMeta.label,
                    usedModel = model
                )
                is GeminiCallResult.QuotaExceeded -> apiResult
                is GeminiCallResult.ForbiddenRetryable -> apiResult
                is GeminiCallResult.ModelUnavailable -> apiResult
                is GeminiCallResult.AuthFailure -> apiResult
                is GeminiCallResult.NetworkOrServerError -> apiResult
            }
        }

        return when (result) {
            is GeminiCallResult.Success -> {
                val (cleanText, suggestions) = parseResponse(
                    result.data,
                    maxSuggestions = maxSuggestionsForKind(request.kind)
                )
                AiChatMessage(
                    role = AiMessageRole.ASSISTANT,
                    text = cleanText,
                    suggestions = suggestions,
                    keyLabel = result.usedKeyLabel,
                    modelUsed = result.usedModel.ifBlank { primaryModel }
                )
            }
            else -> AiChatMessage(
                role = AiMessageRole.ERROR,
                text = mapErrorToUiText(result),
                canRetry = true,
                retryRequest = request
            )
        }
    }

    internal fun mapErrorToUiText(result: GeminiCallResult<*>): UiText = when (result) {
        is GeminiCallResult.QuotaExceeded ->
            UiText.Resource(R.string.ai_err_quota, result.httpCode, result.uiMessage)
        is GeminiCallResult.ForbiddenRetryable ->
            UiText.Resource(R.string.ai_err_key_rejected_google, result.httpCode)
        is GeminiCallResult.ModelUnavailable ->
            UiText.Resource(R.string.ai_err_model_unavailable)
        is GeminiCallResult.AuthFailure ->
            if (result.httpCode == 401 || result.httpCode == 403) {
                UiText.Resource(R.string.ai_err_key_rejected_google, result.httpCode)
            } else {
                UiText.Resource(R.string.ai_err_auth, result.httpCode, result.uiMessage)
            }
        is GeminiCallResult.NetworkOrServerError ->
            UiText.Resource(R.string.ai_err_network, result.uiMessage)
        is GeminiCallResult.Success -> UiText.Dynamic("")
    }

    internal fun buildSystemInstruction(language: AppLanguage): String {
        val replyDirective = if (language.isIndonesian()) {
            "ALWAYS reply in Bahasa Indonesia (keep shell commands and code blocks in standard POSIX syntax)."
        } else {
            "ALWAYS reply in English."
        }
        return """
            You are HackerStudio AI, a concise, expert Linux/Termux shell engineering assistant.
            - $replyDirective
            - Keep explanations crisp, technical, and terminal-friendly.
            - Whenever you recommend shell commands or scripts, ALWAYS put each runnable command or script inside a fenced ```bash code block so the UI can turn it into an Insert/Copy/Save card with safety classification.
            - Never invent destructive commands without a clear warning.
            - Never assume commands will auto-execute; the user reviews and inserts them manually.
        """.trimIndent()
    }

    internal fun maxSuggestionsForKind(kind: AiPromptKind): Int = when (kind) {
        AiPromptKind.WRITE_COMMAND -> MAX_WRITE_COMMAND_SUGGESTIONS
        AiPromptKind.FIND_ROOT_CAUSE -> MAX_ROOT_CAUSE_SUGGESTIONS
        AiPromptKind.SUMMARIZE_OUTPUT,
        AiPromptKind.EXPLAIN_COMMAND -> 0
        AiPromptKind.CHAT,
        AiPromptKind.EXPLAIN_ERROR,
        AiPromptKind.FIX_ERROR,
        AiPromptKind.SUGGEST_COMMAND,
        AiPromptKind.WRITE_SCRIPT,
        AiPromptKind.EXPLAIN_SELECTION -> Int.MAX_VALUE
    }

    internal fun parseResponse(
        raw: String,
        maxSuggestions: Int = Int.MAX_VALUE
    ): Pair<UiText, List<CommandSuggestion>> {
        val normalizedRaw = raw.replace("\r\n", "\n")
        if (maxSuggestions <= 0) {
            val cleanOnly = normalizedRaw.replace(Regex("\n{3,}"), "\n\n").trim()
            val resolved = if (cleanOnly.isEmpty()) UiText.Dynamic("") else UiText.Dynamic(cleanOnly)
            return resolved to emptyList()
        }

        val codeBlockRegex = Regex("```([a-zA-Z0-9_-]*)[ \\t]*\\n([\\s\\S]*?)```")
        val suggestions = mutableListOf<CommandSuggestion>()
        var blockIndex = 1

        val stripped = codeBlockRegex.replace(normalizedRaw) { match ->
            val lang = match.groupValues[1].trim().ifBlank { "sh" }.lowercase()
            val code = match.groupValues[2].trim()
            val hasExecutableLine = code.lines().any { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#")
            }
            when {
                code.isEmpty() -> ""
                !hasExecutableLine -> "\n$code\n"
                suggestions.size >= maxSuggestions -> "\n$code\n"
                else -> {
                    val title = when {
                        lang in listOf("bash", "sh", "zsh", "shell") && code.lines().size > 2 ->
                            UiText.Resource(R.string.ai_block_shell_script, blockIndex)
                        lang in listOf("bash", "sh", "zsh", "shell", "") ->
                            UiText.Resource(R.string.ai_block_command, blockIndex)
                        else -> UiText.Resource(R.string.ai_block_snippet, lang.uppercase(), blockIndex)
                    }
                    suggestions.add(
                        CommandSuggestion(
                            title = title,
                            command = code,
                            language = lang,
                            risk = CommandRiskClassifier.classify(code)
                        )
                    )
                    blockIndex++
                    "\n\u21B3\n"
                }
            }
        }.replace(Regex("\n{3,}"), "\n\n").trim()

        val text = if (stripped.isEmpty()) {
            if (suggestions.isNotEmpty()) UiText.Resource(R.string.ai_suggested_fallback)
            else UiText.Dynamic("")
        } else {
            UiText.Dynamic(stripped)
        }
        return text to suggestions
    }

    companion object {
        const val DEFAULT_SHELL = "bash"
        const val MAX_SHELL_NAME_CHARS = 64
        const val MAX_POST_REDACT_CWD_CHARS = 512
        const val MAX_POST_REDACT_COMMAND_CHARS = 2_000
        const val MAX_POST_REDACT_OUTPUT_CHARS = 3_500
        const val MAX_WRITE_COMMAND_SUGGESTIONS = 2
        const val MAX_ROOT_CAUSE_SUGGESTIONS = 1
    }
}
