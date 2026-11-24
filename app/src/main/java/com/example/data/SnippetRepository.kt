package com.example.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

private val Context.snippetsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cyberorg_snippets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class SnippetImportException(
    @StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
    fallbackMessage: String
) : IllegalArgumentException(fallbackMessage)

interface SnippetStore {
    val snippetsFlow: Flow<List<CommandSnippet>>
    suspend fun saveSnippet(
        title: String,
        command: String,
        tags: List<String>,
        existingId: String? = null
    ): CommandSnippet
    suspend fun deleteSnippet(id: String)
    fun exportToJson(snippets: List<CommandSnippet>): String
    suspend fun importFromJson(rawJson: String): Result<Int>
}

object SnippetJsonCodec {
    const val MAX_IMPORT_FILE_BYTES = 1 * 1024 * 1024 // 1 MB
    const val MAX_IMPORT_SNIPPETS = 500
    const val MAX_TITLE_LENGTH = 120
    const val MAX_COMMAND_LENGTH = 4_000
    const val MAX_TAGS_PER_SNIPPET = 20

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val envelopeAdapter = moshi.adapter(SnippetExportEnvelope::class.java).indent("  ")
    private val listAdapter = moshi.adapter<List<CommandSnippet>>(
        Types.newParameterizedType(List::class.java, CommandSnippet::class.java)
    )

    fun encodeInternal(snippets: List<CommandSnippet>): String = listAdapter.toJson(snippets)

    fun decodeInternal(raw: String?): List<CommandSnippet> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { listAdapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    fun exportToJson(snippets: List<CommandSnippet>, exportedAtMillis: Long = System.currentTimeMillis()): String {
        val envelope = SnippetExportEnvelope(
            exportedAtMillis = exportedAtMillis,
            snippets = snippets
        )
        return envelopeAdapter.toJson(envelope)
    }

    /**
     * Reads UTF-8 text from [input] up to [maxBytes], rejecting oversized streams before
     * allocating large strings in memory.
     */
    fun readUtf8Bounded(
        input: InputStream,
        maxBytes: Int = MAX_IMPORT_FILE_BYTES
    ): Result<String> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                return Result.failure(
                    SnippetImportException(
                        messageRes = R.string.snippets_import_err_file_size,
                        fallbackMessage = "Import rejected: JSON file exceeds 1 MB limit"
                    )
                )
            }
            out.write(buffer, 0, read)
        }
        return Result.success(out.toByteArray().decodeToString())
    }

    /**
     * Strictly validates the entire JSON import payload against size, snippet count,
     * title length, command length, and tag count limits. Any violation rejects the
     * entire import atomically.
     */
    fun parseImportJson(rawJson: String): Result<List<CommandSnippet>> {
        if (rawJson.length > MAX_IMPORT_FILE_BYTES ||
            rawJson.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_FILE_BYTES
        ) {
            return Result.failure(
                SnippetImportException(
                    messageRes = R.string.snippets_import_err_file_size,
                    fallbackMessage = "Import rejected: JSON file exceeds 1 MB limit"
                )
            )
        }

        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty JSON input"))
        }

        // Accept either SnippetExportEnvelope object or a raw JSON array of snippets
        val parsed = if (trimmed.startsWith("[")) {
            runCatching { listAdapter.fromJson(trimmed) }.getOrNull()
        } else {
            runCatching { envelopeAdapter.fromJson(trimmed) }.getOrNull()?.snippets
        } ?: return Result.failure(IllegalArgumentException("Invalid snippets JSON format"))

        if (parsed.size > MAX_IMPORT_SNIPPETS) {
            return Result.failure(
                SnippetImportException(
                    messageRes = R.string.snippets_import_err_count,
                    formatArgs = listOf(MAX_IMPORT_SNIPPETS),
                    fallbackMessage = "Import rejected: exceeds maximum of $MAX_IMPORT_SNIPPETS snippets"
                )
            )
        }

        val cleaned = mutableListOf<CommandSnippet>()
        parsed.forEachIndexed { index, item ->
            val rawTitle = item.title.trim()
            if (rawTitle.length > MAX_TITLE_LENGTH) {
                return Result.failure(
                    SnippetImportException(
                        messageRes = R.string.snippets_import_err_title_length,
                        formatArgs = listOf(index + 1, MAX_TITLE_LENGTH),
                        fallbackMessage = "Import rejected: snippet #${index + 1} title exceeds $MAX_TITLE_LENGTH characters"
                    )
                )
            }

            val cmd = item.command.trim()
            if (cmd.length > MAX_COMMAND_LENGTH) {
                return Result.failure(
                    SnippetImportException(
                        messageRes = R.string.snippets_import_err_command_length,
                        formatArgs = listOf(index + 1, MAX_COMMAND_LENGTH),
                        fallbackMessage = "Import rejected: snippet #${index + 1} command exceeds $MAX_COMMAND_LENGTH characters"
                    )
                )
            }
            if (cmd.isEmpty()) return@forEachIndexed

            val nonBlankTags = item.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (nonBlankTags.size > MAX_TAGS_PER_SNIPPET) {
                return Result.failure(
                    SnippetImportException(
                        messageRes = R.string.snippets_import_err_tags_count,
                        formatArgs = listOf(index + 1, MAX_TAGS_PER_SNIPPET),
                        fallbackMessage = "Import rejected: snippet #${index + 1} exceeds $MAX_TAGS_PER_SNIPPET tags"
                    )
                )
            }

            val title = rawTitle.ifEmpty { cmd.lines().first().take(40) }
            cleaned.add(
                CommandSnippet(
                    id = item.id.trim().ifEmpty { "snip_${UUID.randomUUID().toString().take(8)}" },
                    title = title,
                    command = cmd,
                    tags = nonBlankTags.distinct(),
                    updatedAtMillis = item.updatedAtMillis
                )
            )
        }

        if (cleaned.size > MAX_IMPORT_SNIPPETS) {
            return Result.failure(
                SnippetImportException(
                    messageRes = R.string.snippets_import_err_count,
                    formatArgs = listOf(MAX_IMPORT_SNIPPETS),
                    fallbackMessage = "Import rejected: exceeds maximum of $MAX_IMPORT_SNIPPETS snippets"
                )
            )
        }

        return Result.success(cleaned)
    }
}

class DataStoreSnippetRepository(
    context: Context
) : SnippetStore {

    private val dataStore = context.applicationContext.snippetsDataStore

    override val snippetsFlow: Flow<List<CommandSnippet>> = dataStore.data.map { prefs ->
        val seeded = prefs[KEY_SEEDED] ?: false
        val stored = SnippetJsonCodec.decodeInternal(prefs[KEY_SNIPPETS_JSON])
        if (!seeded && stored.isEmpty()) {
            DEFAULT_SNIPPETS
        } else {
            stored
        }
    }

    override suspend fun saveSnippet(
        title: String,
        command: String,
        tags: List<String>,
        existingId: String?
    ): CommandSnippet {
        val cleanCmd = command.trim()
        val cleanTitle = title.trim().ifEmpty { cleanCmd.lines().firstOrNull()?.take(42) ?: "Snippet" }
        val cleanTags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val now = System.currentTimeMillis()
        val saved = CommandSnippet(
            id = existingId ?: "snip_${UUID.randomUUID().toString().take(8)}",
            title = cleanTitle,
            command = cleanCmd,
            tags = cleanTags,
            updatedAtMillis = now
        )

        dataStore.edit { prefs ->
            val base = currentOrSeeded(prefs)
            val idx = base.indexOfFirst { it.id == saved.id }
            val updated = if (idx >= 0) {
                base.toMutableList().apply { set(idx, saved) }
            } else {
                listOf(saved) + base
            }
            prefs[KEY_SEEDED] = true
            prefs[KEY_SNIPPETS_JSON] = SnippetJsonCodec.encodeInternal(updated)
        }
        return saved
    }

    override suspend fun deleteSnippet(id: String) {
        dataStore.edit { prefs ->
            val base = currentOrSeeded(prefs)
            val updated = base.filterNot { it.id == id }
            prefs[KEY_SEEDED] = true
            prefs[KEY_SNIPPETS_JSON] = SnippetJsonCodec.encodeInternal(updated)
        }
    }

    override fun exportToJson(snippets: List<CommandSnippet>): String =
        SnippetJsonCodec.exportToJson(snippets)

    override suspend fun importFromJson(rawJson: String): Result<Int> {
        val parsed = SnippetJsonCodec.parseImportJson(rawJson)
        val incoming = parsed.getOrElse { return Result.failure(it) }
        if (incoming.isEmpty()) {
            return Result.success(0)
        }

        var importedCount = 0
        var rejectedLimitError: SnippetImportException? = null
        dataStore.edit { prefs ->
            val existing = currentOrSeeded(prefs).toMutableList()
            for (item in incoming) {
                val sameIdIdx = existing.indexOfFirst { it.id == item.id }
                val sameContent = existing.any { it.command == item.command && it.title == item.title }
                when {
                    sameIdIdx >= 0 -> {
                        existing[sameIdIdx] = item
                        importedCount++
                    }
                    !sameContent -> {
                        existing.add(0, item)
                        importedCount++
                    }
                }
            }
            if (existing.size > SnippetJsonCodec.MAX_IMPORT_SNIPPETS) {
                rejectedLimitError = SnippetImportException(
                    messageRes = R.string.snippets_import_err_count,
                    formatArgs = listOf(SnippetJsonCodec.MAX_IMPORT_SNIPPETS),
                    fallbackMessage = "Import rejected: total snippets would exceed ${SnippetJsonCodec.MAX_IMPORT_SNIPPETS}"
                )
                return@edit
            }
            prefs[KEY_SEEDED] = true
            prefs[KEY_SNIPPETS_JSON] = SnippetJsonCodec.encodeInternal(existing)
        }
        rejectedLimitError?.let { return Result.failure(it) }
        return Result.success(importedCount)
    }

    private fun currentOrSeeded(prefs: Preferences): List<CommandSnippet> {
        val seeded = prefs[KEY_SEEDED] ?: false
        val stored = SnippetJsonCodec.decodeInternal(prefs[KEY_SNIPPETS_JSON])
        return if (!seeded && stored.isEmpty()) DEFAULT_SNIPPETS else stored
    }

    companion object {
        private val KEY_SEEDED = booleanPreferencesKey("snippets_seeded")
        private val KEY_SNIPPETS_JSON = stringPreferencesKey("snippets_json")

        val DEFAULT_SNIPPETS = listOf(
            CommandSnippet(
                id = "builtin_disk",
                title = "Find Largest Files (Top 15)",
                command = "du -ah . | sort -rh | head -n 15",
                tags = listOf("disk", "diagnostics")
            ),
            CommandSnippet(
                id = "builtin_git",
                title = "Git Status & Recent Commits",
                command = "git status -sb && git log -n 5 --oneline",
                tags = listOf("git")
            ),
            CommandSnippet(
                id = "builtin_deploy",
                title = "Test Failing Deploy Script",
                command = "sh scripts/deploy.sh",
                tags = listOf("demo", "error")
            ),
            CommandSnippet(
                id = "builtin_pkg",
                title = "List Installed Termux Packages",
                command = "pkg list-installed",
                tags = listOf("termux", "pkg")
            ),
            CommandSnippet(
                id = "builtin_python",
                title = "Run CLI Diagnostics (Python)",
                command = "python3 ~/projects/cyberorg-cli/main.py",
                tags = listOf("python", "cli")
            )
        )
    }
}
