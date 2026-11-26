package com.clipme.app.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object FileUtils {

    private val fileSeq = AtomicLong(0L)

    /** Shared directory for exported clips. */
    fun exportDir(context: Context): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "ClipMe"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Public copy of a clip registered with MediaStore so it survives app uninstall. */
    fun publishToGallery(context: Context, src: File): File {
        val displayName = if (src.name.startsWith("clip_") && src.name.endsWith(".mp4")) {
            src.name
        } else {
            newClipFileName()
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipMe")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                File(exportDir(context), displayName)
            } else {
                val dir = exportDir(context)
                var dest = File(dir, displayName)
                var i = 1
                while (dest.exists()) {
                    dest = File(dir, displayName.removeSuffix(".mp4") + "_$i.mp4")
                    i++
                }
                src.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                dest
            }
        } catch (_: Throwable) {
            src
        }
    }

    fun newClipFileName(): String {
        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        val seq = fileSeq.incrementAndGet()
        return "clip_${stamp}_${seq}.mp4"
    }

    fun uniqueExportPath(context: Context): File {
        val dir = exportDir(context)
        val base = newClipFileName()
        var file = File(dir, base)
        var i = 1
        while (file.exists()) {
            file = File(dir, base.removeSuffix(".mp4") + "_$i.mp4")
            i++
        }
        return file
    }

    fun tempExportPath(context: Context): File {
        val dir = File(context.filesDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tmp_" + System.currentTimeMillis() + "_" + fileSeq.incrementAndGet() + ".mp4")
    }

    fun fileSizeBytes(file: File): Long = if (file.exists()) file.length() else 0L

    /** Human readable file size. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.lastIndex) {
            v /= 1024
            u++
        }
        return String.format(Locale.US, "%.1f %s", v, units[u])
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun formatClock(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    fun formatSignedTime(ms: Long): String {
        val sec = ms / 1000
        return String.format(Locale.US, "-%d:%02d", sec / 60, sec % 60)
    }

    fun relativeTime(context: Context, epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        val min = diff / 60_000
        val hr = diff / 3_600_000
        val day = diff / 86_400_000
        return when {
            diff < 60_000 -> "just now"
            min < 60 -> "${min} m ago"
            hr < 24 -> "${hr} h ago"
            day < 7 -> "${day} d ago"
            else -> SimpleDateFormat("MMM d", Locale.US).format(Date(epochMs))
        }
    }

    /** Guarantees a unique temp file with the given suffix. */
    @Throws(IOException::class)
    fun tempFile(context: Context, suffix: String): File {
        val dir = File(context.cacheDir, "cm_work")
        if (!dir.exists()) dir.mkdirs()
        return File.createTempFile("cm_", suffix, dir)
    }

    fun deleteQuietly(vararg files: File) {
        files.forEach { f -> runCatching { if (f.exists()) f.delete() } }
    }
}
