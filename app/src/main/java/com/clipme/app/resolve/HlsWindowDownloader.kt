package com.clipme.app.resolve

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.clipme.app.model.Platform
import com.clipme.app.net.HttpClient
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer

/**
 * Downloads a wall-clock window of a live stream or a range of a VOD into a
 * single local MP4 (H.264 + AAC — always media3-exoplayer compatible).
 *
 * Live: picks the LOWEST-BANDWIDTH combined (audio+video) HLS variant and
 * downloads the last [windowSec] seconds of the current media playlist.
 *
 * VOD: uses the progressive/video URL when available; otherwise reads the
 * media playlist and downloads only the segments.
 *
 * Segment muxing re-wraps the raw H.264/AAC access units with [MediaMuxer], so
 * the result always has a valid moov atom for instant scrubbing.
 */
class HlsWindowDownloader(
    private val context: Context,
    private val http: HttpClient
) {

    class WindowException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun download(
        url: String,
        platform: Platform,
        isLive: Boolean,
        windowSec: Int,
        onProgress: (downloadedSegments: Int, totalSegments: Int) -> Unit
    ): File {
        val target = FileUtils.tempFile(context, "_window.mp4")
        try {
            if (isLive) {
                downloadLive(url, windowSec, target, onProgress)
            } else {
                downloadVod(url, target, onProgress)
            }
            return target
        } catch (t: Throwable) {
            FileUtils.deleteQuietly(target)
            throw t
        }
    }

    // ---------------------------------------------------------------- Live

    private suspend fun downloadLive(
        manifestUrl: String,
        windowSec: Int,
        target: File,
        onProgress: (Int, Int) -> Unit
    ) {
        val master = Hls.parseMaster(http.getString(manifestUrl, 60), baseOf(manifestUrl))
        if (master.variants.isEmpty()) throw WindowException("No video variant in live manifest")

        val combined = master.variants
            .filter { it.hasVideo && it.hasAudio }
            .minByOrNull { it.bandwidth }
        val videoOnly = master.variants.filter { it.hasVideo && !it.hasAudio }.minByOrNull { it.bandwidth }
        val audioOnly = master.variants.filter { it.hasAudio && !it.hasVideo }.minByOrNull { it.bandwidth }

        val videoUrl = combined?.uri ?: videoOnly?.uri
            ?: throw WindowException("Live manifest has no video variant")
        val audioUrl = combined?.uri ?: audioOnly?.uri

        val playlist = Hls.parseMedia(
            http.getString(videoUrl, 60),
            baseOf(videoUrl),
            windowLimitSec = windowSec.toDouble()
        )
        val segments = playlist.segments
        if (segments.isEmpty()) throw WindowException("Live stream has no recent segments yet — try again in a few seconds")

        val work = newWorkDir()
        try {
            val videoFiles = mutableListOf<File>()
            segments.forEachIndexed { index, seg ->
                val f = File(work, "v_${"%03d".format(index)}.ts")
                http.download(seg.uri, f)
                videoFiles.add(f)
                onProgress(videoFiles.size, segments.size)
            }
            val audioFiles = mutableListOf<File>()
            if (audioUrl != null && audioUrl != videoUrl) {
                val audioPlaylist = Hls.parseMedia(
                    http.getString(audioUrl, 60),
                    baseOf(audioUrl),
                    windowLimitSec = windowSec.toDouble()
                )
                audioPlaylist.segments.forEachIndexed { index, seg ->
                    val f = File(work, "a_${"%03d".format(index)}.m4a")
                    http.download(seg.uri, f)
                    audioFiles.add(f)
                }
            }
            merge(files = videoFiles, audioFiles = audioFiles, target = target)
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- VOD

    private suspend fun downloadVod(
        manifestUrl: String,
        target: File,
        onProgress: (Int, Int) -> Unit
    ) {
        // Progressive (direct MP4) — just download it.
        if (manifestUrl.endsWith(".mp4") || manifestUrl.contains(".mp4?")) {
            http.download(manifestUrl, target)
            onProgress(1, 1)
            return
        }

        val body = http.getString(manifestUrl, 60)
        val playlist = Hls.parseMedia(body, baseOf(manifestUrl))
        val segments = playlist.segments
        if (segments.isEmpty()) throw WindowException("VOD playlist has no segments")

        val work = newWorkDir()
        try {
            val files = mutableListOf<File>()
            segments.forEachIndexed { index, seg ->
                val f = File(work, "seg_${"%03d".format(index)}.ts")
                http.download(seg.uri, f)
                files.add(f)
                onProgress(files.size, segments.size)
            }
            merge(files = files, audioFiles = emptyList(), target = target)
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- merge

    /**
     * Re-muxes downloaded transport segments into one playable MP4, keeping
     * both video and audio tracks. Codec config is skipped (it is carried by
     * the track format passed to [MediaMuxer.addTrack]).
     */
    private fun merge(files: List<File>, audioFiles: List<File>, target: File) {
        if (files.isEmpty()) throw WindowException("No video segments were downloaded")
        if (files.size == 1 && audioFiles.isEmpty()) {
            files[0].copyTo(target, overwrite = true)
            return
        }

        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var started = false
        try {
            val allVideo = files.map { SampleSource.open(it) }
            val allAudio = audioFiles.map { SampleSource.open(it) }
            try {
                // Add tracks from the first present source.
                for (src in allVideo) {
                    if (videoTrack < 0) {
                        val f = src.mediaFormat
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            videoTrack = muxer.addTrack(f)
                            break
                        }
                    }
                }
                for (src in allAudio) {
                    if (audioTrack < 0) {
                        val f = src.mediaFormat
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            audioTrack = muxer.addTrack(f)
                            break
                        }
                    }
                }
                if (videoTrack < 0) throw WindowException("No video track found in segments")
                muxer.start()
                started = true

                var videoOffsetUs = 0L
                for (src in allVideo) {
                    videoOffsetUs = writeSource(muxer, videoTrack, src, videoOffsetUs, 33_333L)
                }
                var audioOffsetUs = 0L
                for (src in allAudio) {
                    audioOffsetUs = writeSource(muxer, audioTrack, src, audioOffsetUs, 21_333L)
                }
            } finally {
                (allVideo + allAudio).forEach { it.close() }
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun writeSource(
        muxer: MediaMuxer,
        track: Int,
        src: SampleSource,
        offsetUs: Long,
        defaultStepUs: Long
    ): Long {
        val buffer = ByteBuffer.allocate(src.sampleBufferSize.coerceAtLeast(1 shl 16))
        val info = MediaCodec.BufferInfo()
        var baseTs = -1L
        var maxTs = offsetUs
        while (true) {
            buffer.clear()
            val n = src.extractor.readSampleData(buffer, 0)
            if (n < 0) break
            info.size = n
            info.offset = 0
            val rawTs = src.extractor.sampleTime
            info.flags = src.extractor.sampleFlags
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                src.extractor.advance()
                continue
            }
            if (baseTs < 0) baseTs = rawTs
            val pts = (offsetUs + (rawTs - baseTs)).coerceAtLeast(0L)
            info.presentationTimeUs = pts
            if (pts > maxTs) maxTs = pts
            muxer.writeSampleData(track, buffer, info)
            src.extractor.advance()
        }
        return maxTs + defaultStepUs
    }

    private fun newWorkDir(): File {
        val d = File(context.cacheDir, "cm_work/" + System.currentTimeMillis())
        d.mkdirs()
        return d
    }

    private fun baseOf(url: String): String? =
        runCatching { url.substringBeforeLast('/') + "/" }.getOrNull()
}

/** Lightweight wrapper around a MediaExtractor for one downloaded segment. */
private class SampleSource private constructor(val extractor: MediaExtractor, val mediaFormat: MediaFormat, val sampleBufferSize: Int) {
    companion object {
        fun open(file: File): SampleSource {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    return SampleSource(ex, f, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20))
                }
            }
            ex.release()
            throw IllegalArgumentException("Segment has no media track: ${file.name}")
        }
    }

    fun close() {
        runCatching { extractor.release() }
    }
}
