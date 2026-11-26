package com.clipme.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Helpers for cropping / scaling bitmap frames. */
object BitmapUtils {

    /** Extracts the sub-rect of [src] as a new bitmap, clamping out-of-bounds edges. */
    fun crop(src: Bitmap, sx: Int, sy: Int, sw: Int, sh: Int): Bitmap {
        val x = sx.coerceIn(0, max(0, src.width - 1))
        val y = sy.coerceIn(0, max(0, src.height - 1))
        val w = min(sw, src.width - x)
        val h = min(sh, src.height - y)
        if (w <= 0 || h <= 0) return src
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** Letterbox-fits [src] into a [w]x[h] bitmap (no stretching). */
    fun fitCenter(src: Bitmap, w: Int, h: Int): Bitmap {
        if (w <= 0 || h <= 0) return src
        val scale = min(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(scaled, ((w - sw) / 2f), ((h - sh) / 2f), null)
        if (scaled != src) scaled.recycle()
        return out
    }

    /**
     * Blurs [src] and scales it so both dimensions cover [w]x[h] (blurred background fill).
     * Uses a cheap, dependency-free blur: shrink to a tiny bitmap and scale back up with
     * bilinear filtering (runs on every API level, no RenderScript needed).
     */
    @Suppress("UNUSED_PARAMETER")
    fun blurFill(context: Context, src: Bitmap, w: Int, h: Int): Bitmap {
        val coverScale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * coverScale).toInt().coerceAtLeast(w)
        val sh = (src.height * coverScale).toInt().coerceAtLeast(h)
        val x = ((sw - w) / 2).coerceAtLeast(0)
        val y = ((sh - h) / 2).coerceAtLeast(0)
        return try {
            val tinyW = (w / 24).coerceAtLeast(8)
            val tinyH = (h / 24).coerceAtLeast(8)
            val tiny = Bitmap.createScaledBitmap(src, tinyW, tinyH, true)
            val blurred = Bitmap.createScaledBitmap(tiny, sw, sh, true)
            if (tiny != src) tiny.recycle()
            Bitmap.createBitmap(blurred, x, y, min(w, sw - x), min(h, sh - y))
        } catch (t: Throwable) {
            val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
            Bitmap.createBitmap(scaled, x, y, min(w, sw - x), min(h, sh - y))
        }
    }

    /** Scales [src] to exactly [w]x[h] (stretching). */
    fun stretch(src: Bitmap, w: Int, h: Int): Bitmap {
        if (w == src.width && h == src.height) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
