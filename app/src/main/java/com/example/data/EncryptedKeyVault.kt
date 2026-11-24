package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.R
import com.example.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore

sealed class VaultStatus {
    data object Ready : VaultStatus()
    data class Corrupted(val reason: UiText) : VaultStatus()
    data class Unavailable(val reason: UiText) : VaultStatus()
}

class EncryptedVaultUnavailableException(
    val uiReason: UiText,
    cause: Throwable? = null
) : IllegalStateException(cause?.message, cause)

interface KeyVault {
    val vaultStatus: StateFlow<VaultStatus>
    fun getKey(id: String): String?
    fun putKey(id: String, rawKey: String)
    fun removeKey(id: String)
    fun maskKey(rawKey: String): String
    fun resetVault(): Boolean = true
}

class AndroidEncryptedKeyVault(
    context: Context,
    private val prefsProvider: (Context, String) -> SharedPreferences = ::defaultCreateEncryptedPrefs,
    private val resetStorageAction: (Context, String) -> Unit = ::defaultResetVaultStorage
) : KeyVault {

    private val appContext = context.applicationContext
    private val _vaultStatus = MutableStateFlow<VaultStatus>(VaultStatus.Ready)
    override val vaultStatus: StateFlow<VaultStatus> = _vaultStatus.asStateFlow()

    private var cachedPrefs: SharedPreferences? = null

    init {
        resolveEncryptedPrefs()
    }

    private fun resolveEncryptedPrefs(): SharedPreferences? {
        cachedPrefs?.let { return it }
        return try {
            val prefs = prefsProvider(appContext, VAULT_FILE_NAME)
            cachedPrefs = prefs
            _vaultStatus.value = VaultStatus.Ready
            prefs
        } catch (t: Throwable) {
            cachedPrefs = null
            _vaultStatus.value = classifyFailure(t)
            null
        }
    }

    override fun getKey(id: String): String? {
        val prefs = resolveEncryptedPrefs() ?: return null
        return try {
            prefs.getString(keyPrefix(id), null)
        } catch (t: Throwable) {
            cachedPrefs = null
            _vaultStatus.value = classifyFailure(t)
            null
        }
    }

    override fun putKey(id: String, rawKey: String) {
        val prefs = resolveEncryptedPrefs()
            ?: throw EncryptedVaultUnavailableException(
                currentErrorUiText()
                    ?: UiText.Resource(R.string.vault_err_unavailable, "Keystore")
            )
        try {
            val committed = prefs.edit().putString(keyPrefix(id), rawKey.trim()).commit()
            if (!committed) {
                throw EncryptedVaultUnavailableException(UiText.Resource(R.string.vault_err_commit))
            }
            _vaultStatus.value = VaultStatus.Ready
        } catch (e: EncryptedVaultUnavailableException) {
            _vaultStatus.value = VaultStatus.Unavailable(e.uiReason)
            throw e
        } catch (t: Throwable) {
            cachedPrefs = null
            val failureStatus = classifyFailure(t)
            _vaultStatus.value = failureStatus
            val uiReason = when (failureStatus) {
                is VaultStatus.Corrupted -> failureStatus.reason
                is VaultStatus.Unavailable -> failureStatus.reason
                VaultStatus.Ready -> UiText.Resource(R.string.vault_err_unavailable, "Keystore")
            }
            throw EncryptedVaultUnavailableException(uiReason, t)
        }
    }

    override fun removeKey(id: String) {
        val prefs = resolveEncryptedPrefs() ?: return
        runCatching {
            prefs.edit().remove(keyPrefix(id)).apply()
        }
    }

    override fun resetVault(): Boolean {
        cachedPrefs = null
        runCatching { resetStorageAction(appContext, VAULT_FILE_NAME) }
        return resolveEncryptedPrefs() != null
    }

    override fun maskKey(rawKey: String): String {
        val clean = rawKey.trim()
        if (clean.length <= 8) return "••••••••"
        return "${clean.take(4)}••••${clean.takeLast(4)}"
    }

    private fun currentErrorUiText(): UiText? = when (val st = _vaultStatus.value) {
        is VaultStatus.Corrupted -> st.reason
        is VaultStatus.Unavailable -> st.reason
        VaultStatus.Ready -> null
    }

    private fun classifyFailure(t: Throwable): VaultStatus {
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        return if (isCorruptionThrowable(t)) {
            VaultStatus.Corrupted(UiText.Resource(R.string.vault_err_corrupted, detail))
        } else {
            VaultStatus.Unavailable(UiText.Resource(R.string.vault_err_unavailable, detail))
        }
    }

    private fun isCorruptionThrowable(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur is GeneralSecurityException) return true
            val className = cur::class.java.name
            if (className.contains("XmlPullParserException") ||
                className.contains("AEADBadTagException")
            ) {
                return true
            }
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("decrypt") ||
                msg.contains("aead") ||
                msg.contains("keyset") ||
                msg.contains("corrupt") ||
                msg.contains("verification failed") ||
                msg.contains("tag mismatch") ||
                msg.contains("mac check")
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun keyPrefix(id: String) = "gemini_key_$id"

    companion object {
        private const val VAULT_FILE_NAME = "hackerstudio_encrypted_key_vault"

        private fun defaultCreateEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun defaultResetVaultStorage(context: Context, fileName: String) {
            runCatching { context.deleteSharedPreferences(fileName) }
            runCatching {
                val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                File(sharedPrefsDir, "$fileName.xml").delete()
                File(sharedPrefsDir, "$fileName.xml.bak").delete()
            }
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }
        }
    }
}
