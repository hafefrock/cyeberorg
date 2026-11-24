package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.promptHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cyberorg_prompt_history",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

interface PromptHistoryStore {
    val historyFlow: Flow<List<PromptHistoryItem>>
    suspend fun recordPrompt(prompt: String)
    suspend fun clearAll()
}

object PromptHistoryReducer {
    const val MAX_HISTORY_ITEMS = 30

    fun appendPrompt(
        current: List<PromptHistoryItem>,
        rawPrompt: String,
        nowMillis: Long = System.currentTimeMillis(),
        idFactory: () -> String = { "hist_${UUID.randomUUID().toString().take(8)}" }
    ): List<PromptHistoryItem> {
        val clean = rawPrompt.trim()
        if (clean.isEmpty()) return current
        val deduped = current.filterNot { it.prompt == clean }
        val newItem = PromptHistoryItem(
            id = idFactory(),
            prompt = clean,
            createdAtMillis = nowMillis
        )
        return (listOf(newItem) + deduped).take(MAX_HISTORY_ITEMS)
    }
}

class DataStorePromptHistoryRepository(
    context: Context
) : PromptHistoryStore {

    private val dataStore = context.applicationContext.promptHistoryDataStore

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val listAdapter = moshi.adapter<List<PromptHistoryItem>>(
        Types.newParameterizedType(List::class.java, PromptHistoryItem::class.java)
    )

    override val historyFlow: Flow<List<PromptHistoryItem>> = dataStore.data.map { prefs ->
        decode(prefs[KEY_HISTORY_JSON])
    }

    override suspend fun recordPrompt(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_HISTORY_JSON])
            val updated = PromptHistoryReducer.appendPrompt(current, clean)
            prefs[KEY_HISTORY_JSON] = listAdapter.toJson(updated)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs[KEY_HISTORY_JSON] = "[]"
        }
    }

    private fun decode(raw: String?): List<PromptHistoryItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { listAdapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    companion object {
        private val KEY_HISTORY_JSON = stringPreferencesKey("prompt_history_json")
    }
}
