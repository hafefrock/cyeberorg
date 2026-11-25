package com.example.termux

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

object PackageUpdateParser {
    /** Counts `apt list --upgradable` entries (lines containing "[upgradable from: ...]"). */
    fun countUpgradable(output: String): Int =
        output.lineSequence().count { it.contains("[upgradable") }
}

/**
 * Periodically checks (at most every 6 hours, not on metered networks) whether installed Termux
 * packages can be upgraded, by running `apt-get update` and `apt list --upgradable`.
 */
object PackageUpdateChecker {

    data class State(val upgradable: Int = 0, val checking: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val PREFS = "pkg_update"
    private const val KEY_LAST = "last_check_ms"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    suspend fun checkIfDue(context: Context, initialDelayMs: Long = 15_000L, force: Boolean = false) {
        val app = context.applicationContext
        delay(initialDelayMs)
        withContext(Dispatchers.IO) {
            val paths = TermuxPaths(app.filesDir)
            if (!paths.isInstalled() || _state.value.checking) return@withContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong(KEY_LAST, 0L) < MIN_INTERVAL_MS) return@withContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null || cm.activeNetworkInfo?.isConnected != true || cm.isActiveNetworkMetered) return@withContext

            _state.value = _state.value.copy(checking = true)
            try {
                val update = run(paths, listOf("apt-get", "update", "-qq"), timeoutMs = 90_000L)
                if (update?.first != 0) return@withContext // lock held or network error: try again later
                val list = run(paths, listOf("apt", "list", "--upgradable"), timeoutMs = 30_000L)
                val count = list?.let { PackageUpdateParser.countUpgradable(it.second) } ?: 0
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                _state.value = State(upgradable = count, checking = false)
            } catch (_: Exception) {
                // Best effort only.
            } finally {
                _state.value = _state.value.copy(checking = false)
            }
        }
    }

    /** Called when the user taps the chip: hide it until the next scheduled check. */
    fun markHandled(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        _state.value = State()
    }

    private fun run(paths: TermuxPaths, args: List<String>, timeoutMs: Long): Pair<Int, String>? {
        val exe = File(paths.prefix, "bin/${args[0]}")
        if (!exe.exists()) return null
        val pb = ProcessBuilder(listOf(exe.path) + args.drop(1))
        pb.directory(paths.home)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        TermuxEnv.build(paths.prefix, paths.home).forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) env[kv.substring(0, i)] = kv.substring(i + 1)
        }
        val process = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            } catch (_: Exception) {
            }
        }.also { it.start() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var exit: Int? = null
        while (exit == null) {
            try {
                exit = process.exitValue()
            } catch (_: IllegalThreadStateException) {
                if (System.currentTimeMillis() > deadline) {
                    process.destroy()
                    return null
                }
                Thread.sleep(200)
            }
        }
        reader.join(2_000)
        return exit to out.toString()
    }
}
