package com.example.termux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Downloads the Termux bootstrap archive for this device, verifies its SHA-256,
 * extracts it to a staging directory (safe against zip-slip), creates symlinks,
 * sets executable bits and atomically renames staging to the real prefix.
 */
class BootstrapInstaller(private val context: Context) {

    private val paths = TermuxPaths(context.filesDir)

    fun isInstalled(): Boolean = paths.isInstalled()

    fun readManifest(): BootstrapManifest =
        BootstrapManifest.parse(
            context.assets.open("bootstrap-manifest.json").bufferedReader().use { it.readText() },
        )

    fun install(): Flow<BootstrapProgress> = flow {
        try {
            val manifest = readManifest()
            val arch = BootstrapArch.current()
                ?: throw IllegalStateException("Unsupported CPU architecture")
            val archive = manifest.archives[arch]
                ?: throw IllegalStateException("No bootstrap for $arch")

            paths.stagingPrefix.deleteRecursively()
            paths.downloadTmp.delete()
            paths.home.mkdirs()

            // 1) download (with running SHA-256)
            emit(BootstrapProgress.Downloading(0, archive.size))
            val digest = MessageDigest.getInstance("SHA-256")
            download(archive.url, paths.downloadTmp, digest) { done ->
                emit(BootstrapProgress.Downloading(done, archive.size))
            }

            // 2) verify
            emit(BootstrapProgress.Verifying)
            val actual = BootstrapUtils.hex(digest.digest())
            if (actual != archive.sha256) {
                paths.downloadTmp.delete()
                throw IllegalStateException("Checksum mismatch")
            }

            // 3) extract
            extract(paths.downloadTmp, paths.stagingPrefix) { done, total ->
                emit(BootstrapProgress.Extracting(done, total))
            }
            paths.downloadTmp.delete()

            // 4) finalize
            emit(BootstrapProgress.Finalizing)
            File(paths.stagingPrefix, "tmp").mkdirs()
            paths.prefix.deleteRecursively()
            if (!paths.stagingPrefix.renameTo(paths.prefix)) {
                throw IllegalStateException("Cannot move staging to prefix")
            }
            paths.versionMarker.writeText(manifest.tag)
            emit(BootstrapProgress.Done)
        } catch (e: Exception) {
            paths.stagingPrefix.deleteRecursively()
            emit(BootstrapProgress.Failed(e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun download(
        url: String,
        dest: File,
        digest: MessageDigest,
        onProgress: suspend (Long) -> Unit,
    ) {
        var current = url
        var conn: HttpURLConnection? = null
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect")
                c.disconnect()
                current = URL(URL(current), loc).toString()
                if (!current.startsWith("https://")) throw IllegalStateException("Insecure redirect")
            } else {
                conn = c
                return@repeat
            }
        }
        val c = conn ?: throw IllegalStateException("Too many redirects")
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        var total = 0L
        var lastEmit = 0L
        c.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    total += n
                    if (total - lastEmit >= 256 * 1024) {
                        lastEmit = total
                        onProgress(total)
                    }
                }
            }
        }
        c.disconnect()
        onProgress(total)
    }

    private suspend fun extract(zip: File, target: File, onProgress: suspend (Int, Int) -> Unit) {
        target.mkdirs()
        var symlinkText: String? = null
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val total = entries.size
            var done = 0
            for (entry in entries) {
                done++
                if (entry.name == "SYMLINKS.txt") {
                    symlinkText = zf.getInputStream(entry).bufferedReader().use { it.readText() }
                    continue
                }
                val out = BootstrapUtils.safeResolve(target, entry.name)
                    ?: throw IllegalStateException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                    if (BootstrapUtils.needsExecutable(entry.name)) {
                        Os.chmod(out.absolutePath, 0b111_000_000) // 0700
                    }
                }
                if (done % 200 == 0) onProgress(done, total)
            }
            onProgress(total, total)
        }
        val links = BootstrapUtils.parseSymlinks(
            symlinkText ?: throw IllegalStateException("SYMLINKS.txt missing"),
        )
        for (l in links) {
            val linkFile = BootstrapUtils.safeResolve(target, l.linkPath)
                ?: throw IllegalStateException("Unsafe symlink path: ${l.linkPath}")
            linkFile.parentFile?.mkdirs()
            Os.symlink(l.target, linkFile.absolutePath)
        }
    }
}
