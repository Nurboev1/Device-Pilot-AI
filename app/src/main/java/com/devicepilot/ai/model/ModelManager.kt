package com.devicepilot.ai.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val modelId: String, val downloaded: Long, val total: Long) : DownloadState() {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Failed(val modelId: String, val error: String) : DownloadState()
    data class Completed(val modelId: String) : DownloadState()
}

/**
 * Owns model files on disk, the user's selected model, and resumable downloads.
 */
class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("devicepilot_models", Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(ModelCatalog.byId(prefs.getString(KEY_SELECTED, null)))
    val selected: StateFlow<ModelSpec> = _selected.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun fileFor(spec: ModelSpec): File = File(modelsDir, spec.fileName)

    private fun partFileFor(spec: ModelSpec): File = File(modelsDir, spec.fileName + ".part")

    fun isInstalled(spec: ModelSpec): Boolean {
        val f = fileFor(spec)
        // Guard against truncated files: must be at least 80% of expected size.
        return f.exists() && f.length() > spec.approxBytes * 0.8
    }

    fun select(spec: ModelSpec) {
        prefs.edit().putString(KEY_SELECTED, spec.id).apply()
        _selected.value = spec
    }

    fun delete(spec: ModelSpec) {
        fileFor(spec).delete()
        partFileFor(spec).delete()
        refreshInstalled()
    }

    fun refreshInstalled() {
        _installed.value = scanInstalled()
    }

    private fun scanInstalled(): Set<String> =
        ModelCatalog.all.filter { isInstalled(it) }.map { it.id }.toSet()

    /**
     * Locates a model file that the user may have side-loaded manually (Downloads folder etc.)
     * and imports it, so people who already have the GGUF don't need to re-download.
     */
    private fun importSideloaded(spec: ModelSpec): Boolean {
        val candidates = listOf(
            File("/sdcard/Download/${spec.fileName}"),
            File("/storage/emulated/0/Download/${spec.fileName}"),
            File(context.getExternalFilesDir(null), spec.fileName),
            File(context.filesDir, spec.fileName)
        )
        for (c in candidates) {
            if (c.exists() && c.canRead() && c.length() > spec.approxBytes * 0.8) {
                return try {
                    c.copyTo(fileFor(spec), overwrite = true)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "Import failed from ${c.path}: ${t.message}")
                    false
                }
            }
        }
        return false
    }

    /**
     * Ensures the model file exists locally, downloading it (resumable) if needed.
     * Returns the file, or throws.
     */
    suspend fun ensureDownloaded(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        if (isInstalled(spec)) return@withContext fileFor(spec)
        if (importSideloaded(spec)) {
            refreshInstalled()
            return@withContext fileFor(spec)
        }

        val part = partFileFor(spec)
        val target = fileFor(spec)
        var downloaded = if (part.exists()) part.length() else 0L
        _download.value = DownloadState.Downloading(spec.id, downloaded, spec.approxBytes)

        try {
            var url = URL(spec.downloadUrl)
            var conn: HttpURLConnection? = null
            var redirects = 0
            while (conn == null) {
                val c = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "DevicePilotAI/1.0")
                    if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
                }
                val code = c.responseCode
                if (code in 300..399 && redirects < 6) {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without Location")
                    url = URL(url, loc)
                    c.disconnect()
                    redirects++
                    continue
                }
                if (code == 416 && downloaded > 0) {
                    // Range not satisfiable — .part is corrupt or already complete. Start over.
                    part.delete(); downloaded = 0
                    c.disconnect()
                    continue
                }
                if (code != 200 && code != 206) throw IllegalStateException("HTTP $code from ${url.host}")
                conn = c
            }

            val http: HttpURLConnection = conn!!
            val resuming = http.responseCode == 206
            if (!resuming && downloaded > 0) {
                part.delete(); downloaded = 0
            }
            val remaining = http.contentLengthLong
            val total = if (remaining > 0) downloaded + remaining else spec.approxBytes

            val raf = RandomAccessFile(part, "rw")
            raf.seek(downloaded)
            http.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var lastEmit = System.currentTimeMillis()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 150) {
                        _download.value = DownloadState.Downloading(spec.id, downloaded, total)
                        lastEmit = now
                    }
                }
            }
            raf.close()
            http.disconnect()

            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true); part.delete()
            }
            _download.value = DownloadState.Completed(spec.id)
            refreshInstalled()
            target
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed: ${t.message}", t)
            _download.value = DownloadState.Failed(spec.id, t.message ?: "Unknown error")
            throw t
        }
    }

    fun clearDownloadState() {
        _download.value = DownloadState.Idle
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val KEY_SELECTED = "selected_model"
    }
}
