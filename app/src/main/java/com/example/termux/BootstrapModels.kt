package com.example.termux

import android.os.Build
import org.json.JSONObject
import java.io.File

/** One downloadable bootstrap archive described by bootstrap-manifest.json. */
data class BootstrapArchive(
    val url: String,
    val sha256: String,
    val size: Long,
)

data class BootstrapManifest(
    val tag: String,
    val archives: Map<String, BootstrapArchive>,
) {
    companion object {
        fun parse(json: String): BootstrapManifest {
            val root = JSONObject(json)
            val archivesJson = root.getJSONObject("archives")
            val map = mutableMapOf<String, BootstrapArchive>()
            for (key in archivesJson.keys()) {
                val o = archivesJson.getJSONObject(key)
                map[key] = BootstrapArchive(
                    url = o.getString("url"),
                    sha256 = o.getString("sha256").lowercase(),
                    size = o.getLong("size"),
                )
            }
            return BootstrapManifest(root.getString("tag"), map)
        }
    }
}

/** Maps Android ABIs to the architecture names used by Termux bootstrap archives. */
object BootstrapArch {
    fun forAbis(abis: List<String>): String? {
        for (abi in abis) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a", "armeabi" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        return null
    }

    fun current(): String? = forAbis(Build.SUPPORTED_ABIS.toList())
}

/**
 * Install locations (same layout as Termux, applicationId com.termux).
 *
 * Termux packages hardcode /data/data/com.termux/files, but Context.filesDir usually resolves to
 * /data/user/0/com.termux/files. Both are the same directory; we use the /data/data form.
 */
class TermuxPaths(rawFilesDir: File) {
    val filesDir: File = normalize(rawFilesDir)
    val prefix: File = File(filesDir, "usr")
    val stagingPrefix: File = File(filesDir, "usr-staging")
    val home: File = File(filesDir, "home")
    val downloadTmp: File = File(filesDir, "bootstrap-download.tmp")
    val versionMarker: File = File(filesDir, "bootstrap-version.txt")

    fun isInstalled(): Boolean =
        File(prefix, "bin/sh").exists() &&
            (File(prefix, "bin/login").exists() || File(prefix, "bin/bash").exists())

    companion object {
        private const val USER0 = "/data/user/0/"

        fun normalize(dir: File): File {
            val p = dir.path
            if (!p.startsWith(USER0)) return dir
            val alt = File("/data/data/" + p.removePrefix(USER0))
            return if (alt.exists()) alt else dir
        }

        /** Pure path rewrite (unit tested). */
        fun rewriteUser0(path: String): String =
            if (path.startsWith(USER0)) "/data/data/" + path.removePrefix(USER0) else path
    }
}

sealed class BootstrapProgress {
    data object Idle : BootstrapProgress()
    data class Downloading(val bytes: Long, val total: Long) : BootstrapProgress()
    data object Verifying : BootstrapProgress()
    data class Extracting(val done: Int, val total: Int) : BootstrapProgress()
    data object Finalizing : BootstrapProgress()
    data object Done : BootstrapProgress()
    data class Failed(val message: String) : BootstrapProgress()
}
