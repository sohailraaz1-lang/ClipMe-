package com.clipme.app.resolve

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.clipme.app.model.StreamInfo
import com.clipme.app.net.HttpClient
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Grabs a full-resolution frame from the resolved stream so the layout canvas
 * and the ML Kit face detector have something to work with.
 *
 *  - Progressive MP4: [MediaMetadataRetriever] reads the URL directly.
 *  - HLS: one media segment is downloaded and decoded locally (the retriever
 *    cannot reliably open live HLS manifests on every device).
 */
object FrameGrabber {

    suspend fun grabFrame(
        context: Context,
        info: StreamInfo,
        http: HttpClient,
        onProgress: () -> Unit = {}
    ): Bitmap = withContext(Dispatchers.IO) {
        val candidates = buildList {
            info.progressiveUrl?.let { add(it) }
            addAll(info.manifests)
        }.distinct()
        if (candidates.isEmpty()) throw IllegalStateException("No playable stream found")

        var lastError: Throwable? = null
        for (url in candidates) {
            onProgress()
            try {
                val bmp = if (isHlsUrl(url, http)) frameFromHls(context, http, url) else frameFromUrl(url, http)
                if (bmp != null) return@withContext bmp
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t
            }
        }
        val reason = lastError?.message?.let { ": $it" } ?: ""
        throw IllegalStateException("Could not capture a frame from this stream$reason")
    }

    private suspend fun isHlsUrl(url: String, http: HttpClient): Boolean {
        if (url.contains(".mp4")) return false
        if (url.contains(".m3u8") || url.contains("/hls")) return true
        return runCatching { http.getString(url, 30).trimStart().startsWith("#EXTM3U") }.getOrDefault(false)
    }

    private suspend fun frameFromHls(context: Context, http: HttpClient, url: String): Bitmap? {
        var text = http.getString(url, 60)
        var mediaUrl = url
        if (text.contains("#EXT-X-STREAM-INF")) {
            val master = Hls.parseMaster(text, baseOf(url))
            val best = master.variants.filter { it.hasVideo }.maxByOrNull { it.bandwidth } ?: return null
            mediaUrl = best.uri
            text = http.getString(mediaUrl, 60)
        }
        val segments = Hls.parseMedia(text, baseOf(mediaUrl)).segments
        val seg = segments.getOrNull(segments.size / 2) ?: return null

        val file = FileUtils.tempFile(context, "_frame.ts")
        try {
            http.download(seg.uri, file)
            return frameFromFile(file.absolutePath)
        } finally {
            FileUtils.deleteQuietly(file)
        }
    }

    private fun frameFromUrl(url: String, http: HttpClient): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, mapOf("User-Agent" to http.userAgent))
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameFromFile(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun baseOf(url: String): String = url.substringBeforeLast('/') + "/"
}
