package com.example.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CommandSnippet(
    val id: String,
    val title: String,
    val command: String,
    val tags: List<String> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class SnippetExportEnvelope(
    val format: String = "cyberorg_snippets",
    val version: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val snippets: List<CommandSnippet> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PromptHistoryItem(
    val id: String,
    val prompt: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)
