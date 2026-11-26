package com.clipme.app.captions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads the Vosk English small model (~42 MB) to internal storage.
 * The spec mentions a ~142 MB model; the compact English "small" model keeps
 * the download reasonable while remaining fully offline. Vosk's
 * RecognizerLoader looks for the model at filesDir/<modelName>.
 */
class CaptionModelManager(private val context: Context) {

    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private val _state = MutableStateFlow<ModelState>(
        if (modelDir().exists() && modelDir().listFiles()?.isNotEmpty() == true) ModelState.Ready
        else ModelState.NotDownloaded
    )
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun modelDir(): File = File(context.filesDir, MODEL_NAME)

    fun isReady(): Boolean = _state.value is ModelState.Ready

    suspend fun download() = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext
        _state.value = ModelState.Downloading(0f)
        val zip = File(context.cacheDir, "$MODEL_NAME.zip")
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "ClipMe/1.0")
            conn.connect()
            if (conn.responseCode != 200) {
                _state.value = ModelState.Failed("Download failed (HTTP ${conn.responseCode})")
                return@withContext
            }
            val total = conn.contentLengthLong
            val input = conn.inputStream
            FileOutputStream(zip).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    done += read
                    if (total > 0) _state.value = ModelState.Downloading(done.toFloat() / total)
                }
            }
            input.close()

            unzip(zip, modelDir())
            zip.delete()

            _state.value = if (modelDir().listFiles()?.isNotEmpty() == true) {
                ModelState.Ready
            } else {
                ModelState.Failed("Model files missing after unzip")
            }
        } catch (t: Throwable) {
            zip.delete()
            _state.value = ModelState.Failed(t.message ?: "Download failed")
        }
    }

    private fun unzip(zip: File, destDir: File) {
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // Strip the top-level folder name.
                val clean = name.substringAfter('/')
                if (clean.isNotEmpty()) {
                    val target = File(destDir, clean)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            zis.copyTo(out)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun deleteModel() {
        modelDir().deleteRecursively()
        _state.value = ModelState.NotDownloaded
    }
}
