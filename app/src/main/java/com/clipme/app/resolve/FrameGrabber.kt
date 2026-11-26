package com.clipme.app.resolve

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.clipme.app.model.StreamInfo
import com.clipme.app.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Grabs a high-resolution frame from the resolved stream so the layout canvas
 * and the ML Kit face detector have something to work with.
 */
@OptIn(UnstableApi::class)
object FrameGrabber {

    /**
     * Captures a decoded frame from the resolved stream into a [Bitmap].
     * Prefers a progressive URL (VOD) or the first live manifest.
     */
    suspend fun grabFrame(
        context: Context,
        info: StreamInfo,
        http: HttpClient,
        onProgress: () -> Unit = {}
    ): Bitmap {
        val url = info.progressiveUrl
            ?: info.manifests.firstOrNull()
            ?: throw IllegalStateException("No playable stream found")
        onProgress()
        // For progressive MP4 VODs, MediaMetadataRetriever is fast and avoids surface setup.
        if (info.progressiveUrl != null) {
            val direct = withContext(Dispatchers.IO) { grabWithRetriever(url) }
            if (direct != null) return direct
        }
        return grabWithExoPlayer(Uri.parse(url), context, onProgress)
    }

    private fun grabWithRetriever(url: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, emptyMap())
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun grabWithExoPlayer(
        uri: Uri,
        context: Context,
        onProgress: () -> Unit
    ): Bitmap = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            var reader: ImageReader? = null

            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context))
                .build()

            fun cleanup() {
                handler.removeCallbacksAndMessages(null)
                runCatching { player.setVideoSurface(null) }
                runCatching { player.release() }
                runCatching { reader?.close() }
                reader = null
            }

            fun fail(t: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }

            fun succeed(bmp: Bitmap) {
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                    if (cont.isActive) cont.resume(bmp)
                } else {
                    runCatching { bmp.recycle() }
                }
            }

            val timeoutRunnable = Runnable {
                fail(IllegalStateException("Timed out waiting for stream frame"))
            }
            handler.postDelayed(timeoutRunnable, 15_000L)

            fun attachReader(w: Int, h: Int) {
                if (reader != null || finished.get()) return
                val scale = if (w > 1280) 1280f / w else 1f
                val rw = (w * scale).toInt().coerceAtLeast(2)
                val rh = (h * scale).toInt().coerceAtLeast(2)
                val ir = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 2)
                ir.setOnImageAvailableListener({ r ->
                    if (finished.get()) return@setOnImageAvailableListener
                    val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val bmp = imageToBitmap(img)
                        if (bmp != null) succeed(bmp)
                    } finally {
                        img.close()
                    }
                }, handler)
                reader = ir
                player.setVideoSurface(ir.surface)
            }

            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        attachReader(videoSize.width, videoSize.height)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    onProgress()
                    if (playbackState == Player.STATE_READY && reader == null) {
                        attachReader(1280, 720)
                    } else if (playbackState == Player.STATE_ENDED) {
                        fail(IllegalStateException("Stream ended before a frame was captured"))
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    fail(error)
                }
            })

            cont.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                }
            }

            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == image.width) {
            raw
        } else {
            val cropped = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
            raw.recycle()
            cropped
        }
    }
}
