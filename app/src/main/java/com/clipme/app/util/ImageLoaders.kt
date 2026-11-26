package com.clipme.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads bitmap content from a ContentResolver Uri, downsampled to a max side. */
object ImageLoaders {

    suspend fun loadFromUri(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // Decode bounds first to downsample.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                        sample *= 2
                    }
                    context.contentResolver.openInputStream(uri)?.use { second ->
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeStream(second, null, opts)
                    }
                }
            }.getOrNull()
        }

    fun decodeFile(file: java.io.File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}
