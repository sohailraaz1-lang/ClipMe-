package com.clipme.app.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.clipme.app.model.ClipItem
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Library of exported clips. Sources:
 *  1. MediaStore (Movies/ClipMe) — the source of truth after a successful publish.
 *  2. Direct scan of Movies/ClipMe for files that MediaStore has not indexed yet.
 */
class ClipStore(private val context: Context) {

    private val _clips = MutableStateFlow<List<ClipItem>>(emptyList())
    val clips: StateFlow<List<ClipItem>> = _clips.asStateFlow()

    fun refresh() {
        _clips.value = scan()
    }

    fun addFromExport(file: File, streamTitle: String?) {
        val published = FileUtils.publishToGallery(context, file)
        refresh()
    }

    fun scan(): List<ClipItem> {
        val items = mutableListOf<ClipItem>()
        val seen = HashSet<String>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DATA
                ),
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("%/ClipMe%"),
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!name.startsWith("clip_") || !name.endsWith(".mp4")) continue
                    val id = c.getLong(idCol)
                    val data = c.getString(dataCol)
                    val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val file = if (data != null) File(data) else null
                    val size = c.getLong(sizeCol)
                    items.add(
                        ClipItem(
                            id = "ms_$id",
                            filePath = file?.absolutePath ?: "",
                            uri = uri,
                            name = name,
                            durationMs = c.getLong(durCol),
                            sizeBytes = if (size > 0) size else (file?.length() ?: 0L),
                            createdAt = c.getLong(addedCol) * 1000L
                        )
                    )
                    file?.absolutePath?.let { seen.add(it) }
                }
            }
        } catch (_: Exception) {
            // MediaStore may not have the file yet — fall through to direct scan.
        }

        // Direct scan fallback for files not yet indexed.
        try {
            FileUtils.exportDir(context).listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("clip_") && f.name.endsWith(".mp4") &&
                    !seen.contains(f.absolutePath)
                ) {
                    items.add(
                        ClipItem(
                            id = "fs_${f.name}_${f.lastModified()}",
                            filePath = f.absolutePath,
                            uri = Uri.fromFile(f),
                            name = f.name,
                            durationMs = 0L,
                            sizeBytes = f.length(),
                            createdAt = f.lastModified()
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return items.sortedByDescending { it.createdAt }
    }

    suspend fun rename(item: ClipItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        var clean = newName.trim()
        if (!clean.endsWith(".mp4")) clean += ".mp4"
        if (clean == item.name || clean.isEmpty()) return@withContext false
        runCatching {
            val file = File(item.filePath)
            if (!file.exists()) return@withContext false
            val renamed = File(file.parentFile, clean)
            if (renamed.exists()) return@withContext false
            val ok = file.renameTo(renamed)
            if (ok) refresh()
            ok
        }.getOrDefault(false)
    }

    suspend fun delete(item: ClipItem): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.delete(item.uri, null, null)
            val file = File(item.filePath)
            if (file.exists()) file.delete()
            refresh()
            true
        }.getOrDefault(false)
    }
}
