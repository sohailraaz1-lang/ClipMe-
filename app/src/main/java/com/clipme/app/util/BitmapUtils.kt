package com.clipme.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
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

    /** Blurs [src] and scales it so both dimensions cover [w]x[h] (blurred background fill). */
    fun blurFill(context: Context, src: Bitmap, w: Int, h: Int): Bitmap {
        val coverScale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * coverScale).toInt()
        val sh = (src.height * coverScale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                scaled.createScaledBitmapWithRenderEffect(w, h)
            } else {
                renderScriptBlur(context, scaled, w, h)
            }
        } catch (t: Throwable) {
            // Fallback: center-crop the scaled bitmap without blur.
            val x = ((sw - w) / 2).coerceAtLeast(0)
            val y = ((sh - h) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(scaled, x, y, min(w, sw - x), min(h, sh - y))
        }
    }

    private fun Bitmap.createScaledBitmapWithRenderEffect(w: Int, h: Int): Bitmap {
        val x = ((width - w) / 2).coerceAtLeast(0)
        val y = ((height - h) / 2).coerceAtLeast(0)
        val cw = min(w, width - x)
        val ch = min(h, height - y)
        val src = if (cw == width && ch == height) this else Bitmap.createBitmap(this, x, y, cw, ch)
        val paint = Paint().apply {
            renderEffect = RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.MIRROR)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, paint)
        if (src != this) src.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun renderScriptBlur(context: Context, src: Bitmap, w: Int, h: Int): Bitmap {
        val rs = android.renderscript.RenderScript.create(context)
        try {
            val inAlloc = android.renderscript.Allocation.createFromBitmap(rs, src)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val outAlloc = android.renderscript.Allocation.createFromBitmap(rs, out)
            val blur = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
            blur.setRadius(20f)
            blur.setInput(inAlloc)
            blur.forEach(outAlloc)
            outAlloc.copyTo(out)
            val x = ((src.width - w) / 2).coerceAtLeast(0)
            val y = ((src.height - h) / 2).coerceAtLeast(0)
            return Bitmap.createBitmap(out, x, y, min(w, out.width - x), min(h, out.height - y))
        } finally {
            rs.destroy()
        }
    }

    /** Scales [src] to exactly [w]x[h] (stretching). */
    fun stretch(src: Bitmap, w: Int, h: Int): Bitmap {
        if (w == src.width && h == src.height) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
