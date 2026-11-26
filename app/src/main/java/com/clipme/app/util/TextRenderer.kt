package com.clipme.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Layout configuration of every text overlay baked into the export. */
data class TextOverlaySpec(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val cx: Float,          // 0..1 of canvas width
    val cy: Float,          // 0..1 of canvas height
    val sizeFactor: Float,  // relative font size (base = 0.055 * width)
    val bgColor: Int = Color.TRANSPARENT,
    val textColor: Int = Color.WHITE,
    val bold: Boolean = true,
    val boxed: Boolean = false,
    val font: String = "Bold"
)

object TextRenderer {

    fun fontFor(style: String, boxed: Boolean): Typeface {
        val tf = when (style.lowercase()) {
            "narrow" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            "grotesk" -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            "mono" -> Typeface.create("monospace", Typeface.BOLD)
            else -> Typeface.DEFAULT_BOLD
        }
        return if (boxed) tf else tf
    }

    fun fontFor(style: String): Typeface = fontFor(style, false)

    fun renderText(text: String, w: Int, h: Int, spec: TextOverlaySpec): Bitmap {
        if (text.isBlank()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val size = (w * 0.055f * spec.sizeFactor).toInt().coerceAtLeast(8)
        val typeface = fontFor(spec.font, spec.boxed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size.toFloat()
            color = spec.textColor
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        val textW = paint.measureText(text)
        val textH = metrics.bottom - metrics.top
        val padX = (size * 0.75f).toInt()
        val padY = (size * 0.45f).toInt()
        val bmpW = (textW + padX * 2).toInt()
        val bmpH = (textH + padY * 2).toInt()
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat())
        if (spec.boxed) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.bgColor }
            canvas.drawRoundRect(rect, size * 0.4f, size * 0.4f, box)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.08f
                color = spec.bgColor
            }
            canvas.drawRoundRect(rect, size * 0.4f, size * 0.4f, stroke)
        } else {
            // Stroke the text so it stays readable on any frame.
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.18f
                color = Color.rgb(10, 12, 11)
            }
            canvas.drawText(text, bmpW / 2f, bmpH / 2f - (metrics.top + metrics.bottom) / 2f, stroke)
        }
        canvas.drawText(text, bmpW / 2f, bmpH / 2f - (metrics.top + metrics.bottom) / 2f, paint)
        return bmp
    }
}
