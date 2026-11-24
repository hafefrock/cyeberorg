package com.example.ai

import com.example.R
import com.example.data.ApiKeyMetadata
import com.example.data.AppSettings
import com.example.data.GeminiModels
import com.example.data.KeyRoutingStrategy
import com.example.data.SettingsStore
import com.example.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class KeyHealthStatus {
    ACTIVE,
    READY,
    COOLDOWN,
    FORBIDDEN_DELAY,
    MODEL_UNAVAILABLE,
    AUTH_ERROR,
    DISABLED
}

data class ManagedKeyStatus(
    val metadata: ApiKeyMetadata,
    val status: KeyHealthStatus,
    val cooldownRemainingSec: Int = 0,
    val requestCount: Int = 0,
    val errorCount: Int = 0,
    val isRechecking: Boolean = false,
    val lastStatusNote: UiText? = null
)

data class KeyManagerSnapshot(
    val keys: List<ManagedKeyStatus> = emptyList(),
    val activeKeyId: String? = null,
    val activeKeyLabel: String? = null,
    val strategy: KeyRoutingStrategy = KeyRoutingStrategy.ROUND_ROBIN,
    val usableKeyCount: Int = 0
)

sealed class GeminiCallResult<out T> {
    data class Success<T>(
        val data: T,
        val usedKeyId: String,
        val usedKeyLabel: String,
        val usedModel: String = ""
    ) : GeminiCallResult<T>()

    data class QuotaExceeded(val uiMessage: UiText, val httpCode: Int = 429) : GeminiCallResult<Nothing>() {
        constructor(message: String, httpCode: Int = 429) : this(UiText.Dynamic(message), httpCode)
    }

    data class ForbiddenRetryable(val uiMessage: UiText, val httpCode: Int = 403) : GeminiCallResult<Nothing>() {
        constructor(message: String, httpCode: Int = 403) : this(UiText.Dynamic(message), httpCode)
    }

    data class ModelUnavailable(val uiMessage: UiText, val httpCode: Int = 404) : GeminiCallResult<Nothing>() {
        constructor(message: String, httpCode: Int = 404) : this(UiText.Dynamic(message), httpCode)
    }

    data class AuthFailure(val uiMessage: UiText, val httpCode: Int) : GeminiCallResult<Nothing>() {
        constructor(message: String, httpCode: Int) : this(UiText.Dynamic(message), httpCode)
    }

    data class NetworkOrServerError(val uiMessage: UiText, val httpCode: Int? = null) : GeminiCallResult<Nothing>() {
        constructor(message: String, httpCode: Int? = null) : this(UiText.Dynamic(message), httpCode)
    }
}

sealed interface KeyStreamEvent {
    data class Chunk(
        val textDelta: String,
        val keyId: String,
        val keyLabel: String,
        val modelUsed: String = ""
    ) : KeyStreamEvent

    data class Completed(
        val keyId: String,
        val keyLabel: String,
        val finishReason: String? = null,
        val modelUsed: String = ""
    ) : KeyStreamEvent

    data class MidStreamError(
        val error: GeminiCallResult<Nothing>,
        val keyId: String,
        val keyLabel: String,
        val modelUsed: String = ""
    ) : KeyStreamEvent

    data class Failed(val error: GeminiCallResult<Nothing>) : KeyStreamEvent
}

class KeyManager(
    private val settingsStore: SettingsStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val keyProbe: (suspend (apiKey: String, model: String) -> GeminiCallResult<Unit>)? = null
) {
    private data class KeyModelKey(
        val keyId: String,
        val modelId: String
    )

    private data class GlobalKeyMeta(
        var authErrorNote: UiText? = null,
        var transientNote: UiText? = null,
        var requestCount: Int = 0,
        var errorCount: Int = 0,
        var isRechecking: Boolean = false
    )

    private data class ModelKeyMeta(
        var cooldownUntilMillis: Long = 0L,
        var delayReasonIsForbidden: Boolean = false,
        var unavailableForModel: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val globalByKeyId = ConcurrentHashMap<String, GlobalKeyMeta>()
    private val perKeyModelMeta = ConcurrentHashMap<KeyModelKey, ModelKeyMeta>()

    private var latestSettings = AppSettings()
    private var roundRobinCursor = 0
    private var currentActiveKeyId: String? = null

    private val _snapshot = MutableStateFlow(KeyManagerSnapshot())
    val snapshot: StateFlow<KeyManagerSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            settingsStore.settingsFlow.collectLatest { settings ->
                mutex.withLock {
                    latestSettings = settings
                    val validIds = settings.apiKeys.map { it.id }.toSet()
                    globalByKeyId.keys.retainAll(validIds)
                    perKeyModelMeta.keys.removeAll { it.keyId !in validIds }
                    if (currentActiveKeyId !in validIds) {
                        currentActiveKeyId = null
                    }
                    publishSnapshotLocked()
                }
            }
        }

        scope.launch {
            while (true) {
                delay(1_000L)
                val now = clockMillis()
                val hasActiveCooldown = perKeyModelMeta.values.any { it.cooldownUntilMillis > now }
                if (hasActiveCooldown || _snapshot.value.keys.any {
                        it.status == KeyHealthStatus.COOLDOWN || it.status == KeyHealthStatus.FORBIDDEN_DELAY
                    }
                ) {
                    mutex.withLock {
                        publishSnapshotLocked()
                    }
                }
            }
        }
    }

    suspend fun <T> executeWithFailover(
        block: suspend (apiKey: String, keyMeta: ApiKeyMetadata) -> GeminiCallResult<T>
    ): GeminiCallResult<T> {
        val currentSettings = settingsStore.settingsFlow.first()
        val primary = GeminiModels.normalizeSelectedModel(currentSettings.selectedModel) ?: DEFAULT_PROBE_MODEL_BUCKET
        return executeWithModelFailover(
            primaryModel = primary,
            fallbackModels = emptyList()
        ) { _, apiKey, meta ->
            block(apiKey, meta)
        }
    }

    suspend fun <T> executeWithModelFailover(
        primaryModel: String,
        fallbackModels: List<String> = emptyList(),
        block: suspend (model: String, apiKey: String, keyMeta: ApiKeyMetadata) -> GeminiCallResult<T>
    ): GeminiCallResult<T> {
        val currentSettings = settingsStore.settingsFlow.first()
        mutex.withLock {
            latestSettings = currentSettings
        }

        val modelChain = buildModelChain(primaryModel, fallbackModels)
        if (currentSettings.apiKeys.isEmpty() || currentSettings.apiKeys.none { it.enabled }) {
            return mutex.withLock { unavailableResultLocked(primaryModel) }
        }

        var lastError: GeminiCallResult<T>? = null

        for (modelId in modelChain) {
            val candidates = mutex.withLock {
                selectCandidateOrderLocked(modelId)
            }
            if (candidates.isEmpty()) {
                val allBlockedByAuth = mutex.withLock { allEnabledKeysHaveAuthErrorLocked() }
                if (allBlockedByAuth) {
                    break
                }
                continue
            }

            for (candidate in candidates) {
                val secret = settingsStore.getSecretKey(candidate.id)
                if (secret.isNullOrBlank()) {
                    recordMissingSecret(candidate.id)
                    continue
                }

                mutex.withLock {
                    currentActiveKeyId = candidate.id
                    publishSnapshotLocked()
                }

                val result = try {
                    block(modelId, secret, candidate)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.key_err_network_generic)
                    GeminiCallResult.NetworkOrServerError(msg)
                }

                when (result) {
                    is GeminiCallResult.Success -> {
                        recordSuccess(candidate.id, modelId)
                        return result.copy(usedModel = modelId)
                    }
                    is GeminiCallResult.QuotaExceeded -> {
                        recordCooldown(candidate.id, modelId, isForbidden = false)
                        lastError = result
                    }
                    is GeminiCallResult.ForbiddenRetryable -> {
                        recordCooldown(candidate.id, modelId, isForbidden = true)
                        lastError = result
                    }
                    is GeminiCallResult.ModelUnavailable -> {
                        recordModelUnavailable(candidate.id, modelId)
                        lastError = result
                    }
                    is GeminiCallResult.AuthFailure -> {
                        recordAuthFailure(candidate.id, result.uiMessage)
                        lastError = result
                    }
                    is GeminiCallResult.NetworkOrServerError -> {
                        recordNetworkError(candidate.id)
                        return result
                    }
                }
            }
        }

        return lastError ?: mutex.withLock { unavailableResultLocked(primaryModel) }
    }

    fun executeStreamWithFailover(
        streamBlock: (apiKey: String, keyMeta: ApiKeyMetadata) -> Flow<GeminiStreamEvent>
    ): Flow<KeyStreamEvent> = flow {
        val currentSettings = settingsStore.settingsFlow.first()
        val primary = GeminiModels.normalizeSelectedModel(currentSettings.selectedModel) ?: DEFAULT_PROBE_MODEL_BUCKET
        executeStreamWithModelFailover(
            primaryModel = primary,
            fallbackModels = emptyList()
        ) { _, apiKey, meta ->
            streamBlock(apiKey, meta)
        }.collect { emit(it) }
    }

    fun executeStreamWithModelFailover(
        primaryModel: String,
        fallbackModels: List<String> = emptyList(),
        streamBlock: (model: String, apiKey: String, keyMeta: ApiKeyMetadata) -> Flow<GeminiStreamEvent>
    ): Flow<KeyStreamEvent> = flow {
        val currentSettings = settingsStore.settingsFlow.first()
        mutex.withLock {
            latestSettings = currentSettings
        }

        val modelChain = buildModelChain(primaryModel, fallbackModels)
        if (currentSettings.apiKeys.isEmpty() || currentSettings.apiKeys.none { it.enabled }) {
            emit(KeyStreamEvent.Failed(mutex.withLock { unavailableResultLocked(primaryModel) }))
            return@flow
        }

        var lastPreChunkError: GeminiCallResult<Nothing>? = null

        for (modelId in modelChain) {
            val candidates = mutex.withLock {
                selectCandidateOrderLocked(modelId)
            }
            if (candidates.isEmpty()) {
                val allBlockedByAuth = mutex.withLock { allEnabledKeysHaveAuthErrorLocked() }
                if (allBlockedByAuth) {
                    break
                }
                continue
            }

            for (candidate in candidates) {
                val secret = settingsStore.getSecretKey(candidate.id)
                if (secret.isNullOrBlank()) {
                    recordMissingSecret(candidate.id)
                    continue
                }

                mutex.withLock {
                    currentActiveKeyId = candidate.id
                    publishSnapshotLocked()
                }

                var receivedFirstChunk = false
                var failedEvent: GeminiCallResult<Nothing>? = null
                var completionFinishReason: String? = null

                try {
                    streamBlock(modelId, secret, candidate).collect { ev ->
                        when (ev) {
                            is GeminiStreamEvent.Chunk -> {
                                if (ev.textDelta.isNotEmpty()) {
                                    receivedFirstChunk = true
                                    emit(
                                        KeyStreamEvent.Chunk(
                                            textDelta = ev.textDelta,
                                            keyId = candidate.id,
                                            keyLabel = candidate.label,
                                            modelUsed = modelId
                                        )
                                    )
                                }
                            }
                            is GeminiStreamEvent.Completed -> {
                                completionFinishReason = ev.finishReason
                            }
                            is GeminiStreamEvent.Failure -> {
                                failedEvent = ev.error
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.key_err_network_generic)
                    failedEvent = GeminiCallResult.NetworkOrServerError(msg)
                }

                val err = failedEvent
                if (err != null) {
                    applyErrorOutcome(candidate.id, modelId, err)
                    if (receivedFirstChunk) {
                        emit(
                            KeyStreamEvent.MidStreamError(
                                error = err,
                                keyId = candidate.id,
                                keyLabel = candidate.label,
                                modelUsed = modelId
                            )
                        )
                        return@flow
                    } else {
                        lastPreChunkError = err
                        if (err is GeminiCallResult.NetworkOrServerError) {
                            emit(KeyStreamEvent.Failed(err))
                            return@flow
                        }
                        // 429 / 403 / 404 / 401 / 400 API_KEY_INVALID before first chunk -> try next key on this model, then next model
                        continue
                    }
                }

                // Stream finished without key/transport error (even if blocked by safety or empty):
                // do NOT increment errorCount or put the key on cooldown.
                recordSuccess(candidate.id, modelId)
                emit(
                    KeyStreamEvent.Completed(
                        keyId = candidate.id,
                        keyLabel = candidate.label,
                        finishReason = completionFinishReason,
                        modelUsed = modelId
                    )
                )
                return@flow
            }
        }

        val finalError = lastPreChunkError ?: mutex.withLock { unavailableResultLocked(primaryModel) }
        emit(KeyStreamEvent.Failed(finalError))
    }

    private fun buildModelChain(primaryModel: String, fallbackModels: List<String>): List<String> {
        return buildList {
            add(primaryModel)
            fallbackModels.forEach { fb ->
                if (fb != primaryModel && !contains(fb)) {
                    add(fb)
                }
            }
        }
    }

    private fun allEnabledKeysHaveAuthErrorLocked(): Boolean {
        val enabledKeys = latestSettings.apiKeys.filter { it.enabled }
        if (enabledKeys.isEmpty()) return false
        return enabledKeys.all { globalByKeyId[it.id]?.authErrorNote != null }
    }

    private fun unavailableResultLocked(focusModel: String): GeminiCallResult<Nothing> {
        val allKeys = latestSettings.apiKeys
        if (allKeys.isEmpty()) {
            return GeminiCallResult.QuotaExceeded(UiText.Resource(R.string.key_err_no_keys))
        }
        val enabledKeys = allKeys.filter { it.enabled }
        if (enabledKeys.isEmpty()) {
            return GeminiCallResult.QuotaExceeded(UiText.Resource(R.string.key_err_all_disabled))
        }
        val anyAuthErr = enabledKeys.firstNotNullOfOrNull { globalByKeyId[it.id]?.authErrorNote }
        if (enabledKeys.all { globalByKeyId[it.id]?.authErrorNote != null } && anyAuthErr != null) {
            return GeminiCallResult.AuthFailure(anyAuthErr, 400)
        }
        if (enabledKeys.all { perKeyModelMeta[KeyModelKey(it.id, focusModel)]?.unavailableForModel == true }) {
            return GeminiCallResult.ModelUnavailable(UiText.Resource(R.string.ai_err_model_unavailable), 404)
        }
        return GeminiCallResult.QuotaExceeded(UiText.Resource(R.string.key_err_all_unavailable))
    }

    private suspend fun recordMissingSecret(keyId: String) {
        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            global.authErrorNote = UiText.Resource(R.string.key_err_missing_secret)
            global.transientNote = null
            global.errorCount++
            publishSnapshotLocked()
        }
    }

    private suspend fun recordSuccess(keyId: String, modelId: String) {
        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            global.requestCount++
            global.authErrorNote = null
            global.transientNote = null

            val km = perKeyModelMeta.getOrPut(KeyModelKey(keyId, modelId)) { ModelKeyMeta() }
            km.cooldownUntilMillis = 0L
            km.delayReasonIsForbidden = false
            km.unavailableForModel = false

            currentActiveKeyId = keyId
            publishSnapshotLocked()
        }
    }

    private suspend fun recordCooldown(keyId: String, modelId: String, isForbidden: Boolean) {
        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            global.errorCount++
            global.transientNote = null

            val km = perKeyModelMeta.getOrPut(KeyModelKey(keyId, modelId)) { ModelKeyMeta() }
            km.delayReasonIsForbidden = isForbidden
            km.cooldownUntilMillis = clockMillis() + latestSettings.cooldownSeconds * 1000L

            if (currentActiveKeyId == keyId) currentActiveKeyId = null
            publishSnapshotLocked()
        }
    }

    private suspend fun recordModelUnavailable(keyId: String, modelId: String) {
        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            global.errorCount++
            global.transientNote = null

            val km = perKeyModelMeta.getOrPut(KeyModelKey(keyId, modelId)) { ModelKeyMeta() }
            km.unavailableForModel = true
            km.cooldownUntilMillis = 0L
            km.delayReasonIsForbidden = false

            if (currentActiveKeyId == keyId) currentActiveKeyId = null
            publishSnapshotLocked()
        }
    }

    private suspend fun recordAuthFailure(keyId: String, message: UiText) {
        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            global.errorCount++
            global.transientNote = null
            global.authErrorNote = message
            if (currentActiveKeyId == keyId) currentActiveKeyId = null
            publishSnapshotLocked()
        }
    }

    private suspend fun recordNetworkError(keyId: String) {
        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            global.errorCount++
            publishSnapshotLocked()
        }
    }

    private suspend fun applyErrorOutcome(keyId: String, modelId: String, err: GeminiCallResult<Nothing>) {
        when (err) {
            is GeminiCallResult.QuotaExceeded -> recordCooldown(keyId, modelId, isForbidden = false)
            is GeminiCallResult.ForbiddenRetryable -> recordCooldown(keyId, modelId, isForbidden = true)
            is GeminiCallResult.ModelUnavailable -> recordModelUnavailable(keyId, modelId)
            is GeminiCallResult.AuthFailure -> recordAuthFailure(keyId, err.uiMessage)
            is GeminiCallResult.NetworkOrServerError -> recordNetworkError(keyId)
            is GeminiCallResult.Success -> Unit
        }
    }

    suspend fun recheckKey(keyId: String) {
        val currentSettings = settingsStore.settingsFlow.first()
        val probeModel = GeminiModels.normalizeSelectedModel(currentSettings.selectedModel)
            ?: GeminiModels.available.first().id
        val focusBucket = focusModelIdLocked(currentSettings)

        val savedState = mutex.withLock {
            latestSettings = currentSettings
            val meta = latestSettings.apiKeys.firstOrNull { it.id == keyId } ?: return
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            val km = perKeyModelMeta.getOrPut(KeyModelKey(keyId, focusBucket)) { ModelKeyMeta() }
            val savedCooldown = km.cooldownUntilMillis
            val savedForbidden = km.delayReasonIsForbidden
            val savedUnavailable = km.unavailableForModel
            val savedAuthErr = global.authErrorNote
            global.transientNote = null
            global.isRechecking = true
            publishSnapshotLocked()
            SavedRecheckState(meta, savedCooldown, savedForbidden, savedUnavailable, savedAuthErr)
        }

        val secret = settingsStore.getSecretKey(keyId)
        val probe = keyProbe
        if (secret.isNullOrBlank()) {
            mutex.withLock {
                val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
                global.isRechecking = false
                global.authErrorNote = UiText.Resource(R.string.key_err_missing_secret)
                publishSnapshotLocked()
            }
            return
        }

        if (probe == null) {
            mutex.withLock {
                val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
                global.isRechecking = false
                global.authErrorNote = null
                global.transientNote = null
                perKeyModelMeta.entries
                    .filter { it.key.keyId == keyId }
                    .forEach {
                        it.value.cooldownUntilMillis = 0L
                        it.value.delayReasonIsForbidden = false
                        it.value.unavailableForModel = false
                    }
                currentActiveKeyId = savedState.meta.id
                publishSnapshotLocked()
            }
            return
        }

        val probeResult = try {
            probe(secret, probeModel)
        } catch (e: Exception) {
            GeminiCallResult.NetworkOrServerError(
                e.message?.let { UiText.Dynamic(it) } ?: UiText.Resource(R.string.key_err_network_generic)
            )
        }

        mutex.withLock {
            val global = globalByKeyId.getOrPut(keyId) { GlobalKeyMeta() }
            val kmProbe = perKeyModelMeta.getOrPut(KeyModelKey(keyId, probeModel)) { ModelKeyMeta() }
            val kmFocus = perKeyModelMeta.getOrPut(KeyModelKey(keyId, focusBucket)) { ModelKeyMeta() }
            global.isRechecking = false
            when (probeResult) {
                is GeminiCallResult.Success -> {
                    global.authErrorNote = null
                    global.transientNote = null
                    kmProbe.cooldownUntilMillis = 0L
                    kmProbe.delayReasonIsForbidden = false
                    kmProbe.unavailableForModel = false
                    kmFocus.cooldownUntilMillis = 0L
                    kmFocus.delayReasonIsForbidden = false
                    kmFocus.unavailableForModel = false
                    currentActiveKeyId = keyId
                }
                is GeminiCallResult.QuotaExceeded -> {
                    global.authErrorNote = null
                    global.transientNote = null
                    val until = clockMillis() + latestSettings.cooldownSeconds * 1000L
                    kmProbe.delayReasonIsForbidden = false
                    kmProbe.unavailableForModel = false
                    kmProbe.cooldownUntilMillis = until
                    kmFocus.delayReasonIsForbidden = false
                    kmFocus.unavailableForModel = false
                    kmFocus.cooldownUntilMillis = until
                }
                is GeminiCallResult.ForbiddenRetryable -> {
                    global.authErrorNote = null
                    global.transientNote = null
                    val until = clockMillis() + latestSettings.cooldownSeconds * 1000L
                    kmProbe.delayReasonIsForbidden = true
                    kmProbe.unavailableForModel = false
                    kmProbe.cooldownUntilMillis = until
                    kmFocus.delayReasonIsForbidden = true
                    kmFocus.unavailableForModel = false
                    kmFocus.cooldownUntilMillis = until
                }
                is GeminiCallResult.ModelUnavailable -> {
                    global.authErrorNote = null
                    global.transientNote = null
                    kmProbe.unavailableForModel = true
                    kmProbe.cooldownUntilMillis = 0L
                    kmFocus.unavailableForModel = true
                    kmFocus.cooldownUntilMillis = 0L
                }
                is GeminiCallResult.AuthFailure -> {
                    global.transientNote = null
                    global.authErrorNote = probeResult.uiMessage
                    kmProbe.cooldownUntilMillis = 0L
                    kmProbe.delayReasonIsForbidden = false
                    kmFocus.cooldownUntilMillis = 0L
                    kmFocus.delayReasonIsForbidden = false
                }
                is GeminiCallResult.NetworkOrServerError -> {
                    global.authErrorNote = savedState.savedAuthErr
                    global.transientNote = UiText.Resource(R.string.key_note_network_retry)
                    kmFocus.cooldownUntilMillis = savedState.savedCooldown
                    kmFocus.delayReasonIsForbidden = savedState.savedForbidden
                    kmFocus.unavailableForModel = savedState.savedUnavailable
                }
            }
            publishSnapshotLocked()
        }
    }

    suspend fun activateKeyIfNone(keyId: String) {
        val currentSettings = settingsStore.settingsFlow.first()
        mutex.withLock {
            latestSettings = currentSettings
            val target = latestSettings.apiKeys.firstOrNull { it.id == keyId && it.enabled } ?: return
            val hasActiveKey = currentActiveKeyId != null && latestSettings.apiKeys.any { meta ->
                meta.id == currentActiveKeyId && meta.enabled && globalByKeyId[meta.id]?.authErrorNote == null
            }
            if (!hasActiveKey) {
                currentActiveKeyId = target.id
                publishSnapshotLocked()
            }
        }
    }

    suspend fun clearAllCooldownsAndErrors() {
        mutex.withLock {
            globalByKeyId.values.forEach {
                it.authErrorNote = null
                it.transientNote = null
            }
            perKeyModelMeta.values.forEach {
                it.cooldownUntilMillis = 0L
                it.delayReasonIsForbidden = false
                it.unavailableForModel = false
            }
            publishSnapshotLocked()
        }
    }

    private fun focusModelIdLocked(settings: AppSettings = latestSettings): String {
        return GeminiModels.normalizeSelectedModel(settings.selectedModel) ?: DEFAULT_PROBE_MODEL_BUCKET
    }

    private fun selectCandidateOrderLocked(modelId: String): List<ApiKeyMetadata> {
        val now = clockMillis()
        val eligible = latestSettings.apiKeys.filter { meta ->
            if (!meta.enabled) return@filter false
            val global = globalByKeyId[meta.id]
            val hasAuthErr = global?.authErrorNote != null
            val km = perKeyModelMeta[KeyModelKey(meta.id, modelId)]
            val onCooldown = km != null && km.cooldownUntilMillis > now
            val unavailable = km?.unavailableForModel == true
            !hasAuthErr && !onCooldown && !unavailable
        }
        if (eligible.isEmpty()) return emptyList()

        return when (latestSettings.routingStrategy) {
            KeyRoutingStrategy.FAILOVER -> eligible
            KeyRoutingStrategy.ROUND_ROBIN -> {
                val startIdx = roundRobinCursor % eligible.size
                roundRobinCursor = (startIdx + 1) % eligible.size
                eligible.drop(startIdx) + eligible.take(startIdx)
            }
        }
    }

    private fun publishSnapshotLocked() {
        val now = clockMillis()
        val focusModel = focusModelIdLocked()
        val statuses = latestSettings.apiKeys.map { meta ->
            val global = globalByKeyId.getOrPut(meta.id) { GlobalKeyMeta() }
            val km = perKeyModelMeta[KeyModelKey(meta.id, focusModel)]
            val cooldownRem = if (km != null && km.cooldownUntilMillis > now) {
                ((km.cooldownUntilMillis - now + 999L) / 1000L).toInt()
            } else {
                0
            }
            if (cooldownRem == 0 && km != null && km.cooldownUntilMillis > 0L) {
                km.cooldownUntilMillis = 0L
                km.delayReasonIsForbidden = false
            }
            val status = when {
                !meta.enabled -> KeyHealthStatus.DISABLED
                global.authErrorNote != null -> KeyHealthStatus.AUTH_ERROR
                km?.unavailableForModel == true -> KeyHealthStatus.MODEL_UNAVAILABLE
                cooldownRem > 0 && km?.delayReasonIsForbidden == true -> KeyHealthStatus.FORBIDDEN_DELAY
                cooldownRem > 0 -> KeyHealthStatus.COOLDOWN
                meta.id == currentActiveKeyId -> KeyHealthStatus.ACTIVE
                else -> KeyHealthStatus.READY
            }
            val note: UiText = when {
                global.isRechecking -> UiText.Resource(R.string.key_note_rechecking)
                global.transientNote != null -> global.transientNote!!
                status == KeyHealthStatus.MODEL_UNAVAILABLE -> UiText.Resource(R.string.key_note_model_unavailable)
                status == KeyHealthStatus.FORBIDDEN_DELAY -> UiText.Resource(R.string.key_note_forbidden_delay, cooldownRem)
                status == KeyHealthStatus.COOLDOWN -> UiText.Resource(R.string.key_note_cooldown, cooldownRem)
                status == KeyHealthStatus.AUTH_ERROR -> global.authErrorNote ?: UiText.Resource(R.string.key_note_auth_rejected)
                status == KeyHealthStatus.DISABLED -> UiText.Resource(R.string.key_note_disabled)
                status == KeyHealthStatus.ACTIVE -> UiText.Resource(R.string.key_note_active)
                else -> UiText.Resource(R.string.key_note_ready)
            }
            ManagedKeyStatus(
                metadata = meta,
                status = status,
                cooldownRemainingSec = cooldownRem,
                requestCount = global.requestCount,
                errorCount = global.errorCount,
                isRechecking = global.isRechecking,
                lastStatusNote = note
            )
        }

        val activeEntry = statuses.firstOrNull { it.status == KeyHealthStatus.ACTIVE }
            ?: statuses.firstOrNull { it.status == KeyHealthStatus.READY }

        _snapshot.value = KeyManagerSnapshot(
            keys = statuses,
            activeKeyId = activeEntry?.metadata?.id,
            activeKeyLabel = activeEntry?.metadata?.label,
            strategy = latestSettings.routingStrategy,
            usableKeyCount = statuses.count {
                it.status == KeyHealthStatus.ACTIVE || it.status == KeyHealthStatus.READY
            }
        )
    }

    private data class SavedRecheckState(
        val meta: ApiKeyMetadata,
        val savedCooldown: Long,
        val savedForbidden: Boolean,
        val savedUnavailable: Boolean,
        val savedAuthErr: UiText?
    )

    companion object {
        private const val DEFAULT_PROBE_MODEL_BUCKET = "__default__"
    }
}
