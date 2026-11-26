package com.clipme.app.resolve

import com.clipme.app.net.HttpClient
import com.clipme.app.util.FileUtils
import java.io.File
import java.net.URI
import java.util.Locale

/** Parsed HLS master playlist: variant → (bandwidth, resolution, uri). */
data class HlsMaster(
    val variants: List<HlsVariant>
) {
    val lowest: HlsVariant? get() = variants.minByOrNull { it.bandwidth }
}

data class HlsVariant(
    val bandwidth: Long,
    val height: Int?,
    val uri: String,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true
)

data class HlsSegment(
    val uri: String,
    val durationSec: Double
)

data class HlsPlaylist(
    val segments: List<HlsSegment>,
    val isEndless: Boolean,
    val targetDurationSec: Double,
    val mediaSequence: Long
)

object Hls {

    fun parseMaster(body: String, baseUrl: String?): HlsMaster {
        val variants = mutableListOf<HlsVariant>()
        var pending: HlsVariant? = null
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?: 0L
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                pending = HlsVariant(bandwidth, height, "")
            } else if (!line.startsWith("#") && line.isNotEmpty() && pending != null) {
                pending = pending.copy(
                    uri = resolveUrl(line, baseUrl),
                    hasVideo = !line.contains("audio_only") && !line.contains("AUDIO"),
                    hasAudio = !line.contains("video_only") && !line.contains("VIDEO")
                )
                variants.add(pending!!)
                pending = null
            }
        }
        return HlsMaster(variants)
    }

    /** Parses a media playlist. [windowLimitSec] keeps only the last N seconds (live DVR). */
    fun parseMedia(body: String, baseUrl: String?, windowLimitSec: Double? = null): HlsPlaylist {
        val segments = mutableListOf<HlsSegment>()
        var target = 6.0
        var duration = 0.0
        var mediaSeq = 0L
        var endless = false
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    target = line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-ENDLIST") -> endless = false
                line.startsWith("#EXTINF:") -> {
                    duration = line.substringAfter(':').trim().substringBefore(',').toDoubleOrNull() ?: 0.0
                }
                !line.startsWith("#") && line.isNotEmpty() -> {
                    segments.add(HlsSegment(resolveUrl(line, baseUrl), duration))
                    duration = 0.0
                }
            }
        }
        if (body.contains("#EXT-X-ENDLIST").not() && body.contains("LIVE")) endless = true

        var list = segments
        if (windowLimitSec != null) {
            var acc = 0.0
            val kept = mutableListOf<HlsSegment>()
            for (i in segments.indices.reversed()) {
                acc += segments[i].durationSec
                kept.add(0, segments[i])
                if (acc >= windowLimitSec) break
            }
            list = kept
        }
        return HlsPlaylist(list, endless, target, mediaSeq)
    }

    private fun resolveUrl(line: String, baseUrl: String?): String {
        if (line.startsWith("http")) return line
        if (baseUrl == null) return line
        return URI(baseUrl).resolve(line).toString()
    }

    /** Human bytes/s of a variant. */
    fun kbps(bytesPerSec: Long): String = String.format(Locale.US, "%.0f kbps", bytesPerSec / 1000.0)
}
