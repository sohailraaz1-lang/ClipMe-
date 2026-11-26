package com.clipme.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import com.clipme.app.model.CaptionLine
import com.clipme.app.model.CaptionStyle
import com.clipme.app.model.ExportSettings
import com.clipme.app.model.LayoutPreset
import com.clipme.app.model.NormRect
import com.clipme.app.model.StickerPlacement
import com.clipme.app.util.TextRenderer
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the video-effect list that turns one raw clip file into a 9:16 short:
 *
 *  1. [CanvasComposeEffect] — crops the source frame into the layout regions (game box, cam box,
 *     blurred background) and renders them onto a 1080×1920 canvas.
 *  2. [OverlayEffect] — word-by-word captions, hook text and stickers on top of the canvas.
 *  3. [Presentation] (added by the caller) — scales the exact 9:16 canvas to the chosen export
 *     quality.
 *
 * No watermark, logo or branding is ever drawn by this pipeline.
 */
@OptIn(UnstableApi::class)
object ClipCompositor {

    const val CANVAS_W = 1080
    const val CANVAS_H = 1920

    /** Returns the GL effects for one export (without [androidx.media3.effect.Presentation]). */
    fun buildEffects(settings: ExportSettings, context: Context): List<Effect> {
        val effects = mutableListOf<Effect>(CanvasComposeEffect(settings))
        val overlays = mutableListOf<BitmapOverlay>()
        settings.hookText
            ?.takeIf { settings.hookVisible && it.isNotBlank() }
            ?.let { overlays.add(HookOverlay(it, CANVAS_W, CANVAS_H)) }
        if (settings.captionsOn && settings.captionLines.isNotEmpty()) {
            overlays.add(CaptionOverlay(settings.captionLines, CANVAS_W, CANVAS_H, settings.captionStyle))
        }
        settings.stickers.forEach { sticker ->
            StickerAssets.load(context, sticker)?.let { overlays.add(StickerOverlay(it, sticker, CANVAS_W, CANVAS_H)) }
        }
        if (overlays.isNotEmpty()) effects.add(OverlayEffect(overlays))
        return effects
    }
}

// ---------------------------------------------------------------------------------- Compose pass

/** Compose shader pass modes (mirrored by GLSL constants). */
private const val MODE_FILL = 0
private const val MODE_BLUR = 1
private const val MODE_CIRCLE = 2
private const val MODE_ROUNDED = 3

/** One quad drawn by the compose pass: samples [src] from the input and draws it into [dst]. */
private data class Region(
    val src: NormRect,
    val dst: NormRect,
    val mode: Int,
    val cornerRadiusNdc: Float = 0f,
    val blurRadiusUv: Float = 0f
)

/** Crops [src] to the given aspect (dstW/dstH) centered, so the quad is never distorted. */
private fun cropToAspect(src: NormRect, aspect: Float): NormRect {
    val srcAspect = src.width / src.height
    return if (srcAspect > aspect) {
        val newW = src.height * aspect
        val cx = src.left + src.width / 2f
        NormRect(cx - newW / 2f, src.top, cx + newW / 2f, src.bottom)
    } else {
        val newH = src.width / aspect
        val cy = src.top + src.height / 2f
        NormRect(src.left, cy - newH / 2f, src.right, cy + newH / 2f)
    }
}

/**
 * A single [GlEffect] that renders the dual-box layout into a 9:16 canvas.
 *
 * The input texture is the full stream frame. Each layout preset produces a list of [Region]s
 * which are drawn back-to-front with alpha blending enabled for masked quads (circle / rounded).
 */
@OptIn(UnstableApi::class)
class CanvasComposeEffect(private val settings: ExportSettings) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): androidx.media3.common.GlShaderProgram {
        return CanvasComposeShaderProgram(settings)
    }
}

@OptIn(UnstableApi::class)
private class CanvasComposeShaderProgram(
    private val settings: ExportSettings
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {

    private var program: GlProgram? = null
    private var regions: List<Region> = emptyList()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        regions = computeRegions()
        return Size(ClipCompositor.CANVAS_W, ClipCompositor.CANVAS_H)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = ensureProgram()
        p.use()
        p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        p.setSamplerTexIdUniform("uTexture", inputTexId, /* texUnitIndex= */ 0)
        regions.forEachIndexed { index, region ->
            if (index > 0) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            } else {
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            p.setFloatsUniform(
                "uSrcRect",
                floatArrayOf(region.src.left, 1f - region.src.bottom, region.src.width, region.src.height)
            )
            p.setFloatsUniform(
                "uDstRect",
                floatArrayOf(
                    region.dst.left * 2f - 1f,
                    region.dst.bottom * 2f - 1f,
                    region.dst.width * 2f,
                    region.dst.height * 2f
                )
            )
            p.setIntUniform("uMode", region.mode)
            p.setFloatUniform("uCornerRadius", region.cornerRadiusNdc)
            p.setFloatUniform("uBlurRadius", region.blurRadiusUv)
            p.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun ensureProgram(): GlProgram {
        program?.let { return it }
        val p = GlProgram(
            VERTEX_SHADER,
            FRAGMENT_SHADER
        )
        program = p
        return p
    }

    override fun release() {
        runCatching { program?.delete() }
        program = null
        super.release()
    }

    /** Computes the ordered region list for the current preset. */
    private fun computeRegions(): List<Region> {
        val canvasAspect = ClipCompositor.CANVAS_W.toFloat() / ClipCompositor.CANVAS_H
        val gameSrc = settings.gameRect ?: NormRect.Full
        val camSrc = settings.camRect
        val showCam = camSrc != null && !settings.hideCam
        val regions = mutableListOf<Region>()

        when (settings.preset) {
            LayoutPreset.GAME_ONLY -> {
                regions += Region(
                    src = cropToAspect(gameSrc, canvasAspect),
                    dst = NormRect.Full,
                    mode = MODE_FILL
                )
            }

            LayoutPreset.SPLIT, LayoutPreset.CIRCLE_CAM, LayoutPreset.ROUNDED_CAM -> {
                regions += Region(
                    src = cropToAspect(gameSrc, canvasAspect),
                    dst = NormRect.Full,
                    mode = MODE_FILL
                )
                if (showCam) {
                    val dst = NormRect(0.58f, 0.58f, 0.97f, 0.97f)
                    val dAspect = dst.width / dst.height
                    regions += Region(
                        src = cropToAspect(camSrc!!, dAspect),
                        dst = dst,
                        mode = when (settings.preset) {
                            LayoutPreset.CIRCLE_CAM -> MODE_CIRCLE
                            LayoutPreset.ROUNDED_CAM -> MODE_ROUNDED
                            else -> MODE_FILL
                        },
                        cornerRadiusNdc = if (settings.preset == LayoutPreset.ROUNDED_CAM) 0.10f else 0f
                    )
                }
            }

            LayoutPreset.BLUR -> {
                // Full-canvas blurred fill sampled from the game region.
                regions += Region(
                    src = gameSrc,
                    dst = NormRect.Full,
                    mode = MODE_BLUR,
                    blurRadiusUv = 0.012f
                )
                // Sharp 9:16 game window centered on the canvas.
                val windowH = 0.92f
                val windowW = windowH * canvasAspect
                val left = (1f - windowW) / 2f
                val top = (1f - windowH) / 2f
                regions += Region(
                    src = cropToAspect(gameSrc, canvasAspect),
                    dst = NormRect(left, top, left + windowW, top + windowH),
                    mode = MODE_FILL
                )
                if (showCam) {
                    val dst = NormRect(0.60f, 0.62f, 0.95f, 0.97f)
                    regions += Region(
                        src = cropToAspect(camSrc!!, 1f),
                        dst = dst,
                        mode = MODE_CIRCLE
                    )
                }
            }

            LayoutPreset.BIG_CAM -> {
                val camDst = NormRect(0f, 0f, 1f, 0.55f)
                val gameDst = NormRect(0f, 0.55f, 1f, 1f)
                if (showCam) {
                    regions += Region(
                        src = cropToAspect(camSrc!!, camDst.width / camDst.height),
                        dst = camDst,
                        mode = MODE_FILL
                    )
                } else {
                    regions += Region(
                        src = cropToAspect(gameSrc, canvasAspect),
                        dst = NormRect.Full,
                        mode = MODE_FILL
                    )
                }
                regions += Region(
                    src = cropToAspect(gameSrc, gameDst.width / gameDst.height),
                    dst = gameDst,
                    mode = MODE_FILL
                )
            }

            LayoutPreset.CAM_GAME -> {
                val camDst = NormRect(0f, 0f, 1f, 0.40f)
                val gameDst = NormRect(0f, 0.40f, 1f, 1f)
                if (showCam) {
                    regions += Region(
                        src = cropToAspect(camSrc!!, camDst.width / camDst.height),
                        dst = camDst,
                        mode = MODE_FILL
                    )
                } else {
                    regions += Region(
                        src = cropToAspect(gameSrc, canvasAspect),
                        dst = NormRect.Full,
                        mode = MODE_FILL
                    )
                }
                regions += Region(
                    src = cropToAspect(gameSrc, gameDst.width / gameDst.height),
                    dst = gameDst,
                    mode = MODE_FILL
                )
            }
        }
        return regions
    }

    companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            uniform vec4 uSrcRect;   // (uvLeft, uvBottom, uvWidth, uvHeight)
            uniform vec4 uDstRect;   // (dstLeftNdc, dstBottomNdc, dstWidthNdc, dstHeightNdc)
            varying vec2 vTexCoord;
            varying vec2 vDstCoord;

            void main() {
              vec2 ndc = aFramePosition.xy;
              vec2 pos = uDstRect.xy + (ndc * 0.5 + 0.5) * uDstRect.zw;
              gl_Position = vec4(pos, 0.0, 1.0);
              vTexCoord = uSrcRect.xy + (ndc * 0.5 + 0.5) * uSrcRect.zw;
              vDstCoord = pos;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uSrcRect;
            uniform vec4 uDstRect;
            uniform int uMode;
            uniform float uCornerRadius;
            uniform float uBlurRadius;
            varying vec2 vTexCoord;
            varying vec2 vDstCoord;

            const int MODE_FILL = 0;
            const int MODE_BLUR = 1;
            const int MODE_CIRCLE = 2;
            const int MODE_ROUNDED = 3;

            void main() {
              vec4 c;
              if (uMode == MODE_BLUR) {
                vec2 r = uBlurRadius * vec2(uSrcRect.z, uSrcRect.w);
                c = vec4(0.0);
                c += texture2D(uTexture, vTexCoord);
                c += texture2D(uTexture, vTexCoord + vec2(r.x, 0.0));
                c += texture2D(uTexture, vTexCoord - vec2(r.x, 0.0));
                c += texture2D(uTexture, vTexCoord + vec2(0.0, r.y));
                c += texture2D(uTexture, vTexCoord - vec2(0.0, r.y));
                c += texture2D(uTexture, vTexCoord + vec2(r.x, r.y));
                c += texture2D(uTexture, vTexCoord + vec2(r.x, -r.y));
                c += texture2D(uTexture, vTexCoord + vec2(-r.x, r.y));
                c += texture2D(uTexture, vTexCoord + vec2(-r.x, -r.y));
                c /= 9.0;
              } else {
                c = texture2D(uTexture, vTexCoord);
              }

              float alpha = 1.0;
              if (uMode == MODE_CIRCLE) {
                vec2 ctr = uDstRect.xy + uDstRect.zw * 0.5;
                vec2 d = (vDstCoord - ctr) / (uDstRect.zw * 0.5);
                d.y *= uDstRect.z / uDstRect.w;
                float dist = length(d);
                alpha = 1.0 - smoothstep(0.78, 1.0, dist);
              } else if (uMode == MODE_ROUNDED) {
                vec2 ctr = uDstRect.xy + uDstRect.zw * 0.5;
                vec2 hs = uDstRect.zw * 0.5;
                vec2 q = abs(vDstCoord - ctr) - (hs - vec2(uCornerRadius));
                float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uCornerRadius;
                alpha = 1.0 - smoothstep(0.0, max(uCornerRadius * 0.25, 0.004), dist);
              }
              gl_FragColor = vec4(c.rgb, c.a * alpha);
            }
        """
    }
}

// ---------------------------------------------------------------------------------- Overlays

/**
 * Static hook text overlay, anchored near the top of the canvas.
 *
 * [androidx.media3.effect.BitmapOverlay] already flips the bitmap vertically, so text renders
 * upright; the anchor and scale below are in NDC with center origin.
 */
@OptIn(UnstableApi::class)
private class HookOverlay(text: String, canvasW: Int, canvasH: Int) : BitmapOverlay() {

    private val bitmap: Bitmap = renderHook(text, canvasW)
    private val settings: StaticOverlaySettings = buildSettings(canvasW)

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun buildSettings(canvasW: Int): StaticOverlaySettings {
        val naturalFraction = bitmap.width.toFloat() / canvasW
        val target = min(0.90f, naturalFraction)
        val s = target * canvasW / bitmap.width
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0.86f)   // near the top (NDC y = +0.86)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(s, s)
            .build()
    }

    private fun renderHook(text: String, canvasW: Int): Bitmap {
        // Fit the hook to ≤90% of the canvas width; shrink the font if it overflows.
        var sizeFactor = 1.35f
        while (sizeFactor > 0.7f) {
            val bmp = TextRenderer.renderText(
                text = text,
                w = canvasW,
                h = (canvasW * 0.22f).toInt(),
                spec = com.clipme.app.util.TextOverlaySpec(
                    text = text,
                    startMs = 0L,
                    endMs = Long.MAX_VALUE,
                    cx = 0.5f,
                    cy = 0.09f,
                    sizeFactor = sizeFactor,
                    bold = true,
                    boxed = false,
                    font = "Bold"
                )
            )
            if (bmp.width <= canvasW * 0.92f || sizeFactor <= 0.71f) return bmp
            runCatching { bmp.recycle() }
            sizeFactor -= 0.15f
        }
        // Truncate as a last resort so an extreme title still renders.
        val maxChars = (canvasW / (canvasW * 0.055f * 1.35f)).toInt() * 14
        val short = if (text.length > maxChars) text.take(maxChars) + "…" else text
        return TextRenderer.renderText(
            text = short,
            w = canvasW,
            h = (canvasW * 0.22f).toInt(),
            spec = com.clipme.app.util.TextOverlaySpec(
                text = short,
                startMs = 0L,
                endMs = Long.MAX_VALUE,
                cx = 0.5f,
                cy = 0.09f,
                sizeFactor = 1.35f,
                bold = true,
                boxed = false,
                font = "Bold"
            )
        )
    }
}

/** Word-by-word caption overlay that shows the active line at each timestamp. */
@OptIn(UnstableApi::class)
private class CaptionOverlay(
    private val lines: List<CaptionLine>,
    canvasW: Int,
    canvasH: Int,
    style: CaptionStyle
) : BitmapOverlay() {

    private val canvasW = canvasW
    private val lineBitmaps: Map<Int, Bitmap> =
        lines.mapIndexed { index, line -> index to renderCaption(line.text, canvasW, style) }.toMap()
    private val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val emptySettings = StaticOverlaySettings.Builder().setScale(0.01f, 0.01f).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val index = activeIndex(presentationTimeUs)
        return lineBitmaps[index] ?: emptyBitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val index = activeIndex(presentationTimeUs)
        if (index < 0) return emptySettings
        val bitmap = lineBitmaps.getValue(index)
        val naturalFraction = bitmap.width.toFloat() / canvasW
        val target = min(0.88f, naturalFraction)
        val s = target * canvasW / bitmap.width
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.52f)   // ~76% down the canvas (NDC y = -0.52)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(s, s)
            .build()
    }

    /** Returns the caption line active at [presentationTimeUs] (clip-local microseconds). */
    private fun activeIndex(presentationTimeUs: Long): Int {
        val ms = presentationTimeUs / 1000L
        return lines.indexOfFirst { ms >= it.startMs && ms < it.endMs }
    }

    override fun release() {
        lineBitmaps.values.forEach { runCatching { it.recycle() } }
        runCatching { emptyBitmap.recycle() }
        super.release()
    }
}

/** One sticker (emoji or custom PNG) placed on the canvas. */
@OptIn(UnstableApi::class)
private class StickerOverlay(
    private val bitmap: Bitmap,
    placement: StickerPlacement,
    canvasW: Int,
    canvasH: Int
) : BitmapOverlay() {

    private val settings: StaticOverlaySettings = run {
        val s = placement.scale * canvasW / bitmap.width
        StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(placement.cx * 2f - 1f, 1f - placement.cy * 2f)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(s, s)
            .setRotationDegrees(placement.rotationDeg)
            .build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun release() {
        runCatching { bitmap.recycle() }
        super.release()
    }
}

// ---------------------------------------------------------------------------------- Rendering helpers

/**
 * Renders caption text with the given style, wrapping to fit ~85% of the canvas width,
 * returning a bitmap whose width never exceeds the fit width.
 */
private fun renderCaption(text: String, canvasW: Int, style: CaptionStyle): Bitmap {
    val maxWidth = (canvasW * 0.85f).toInt()
    val size = (canvasW * if (style == CaptionStyle.CLEAN) 0.045f else 0.055f).toInt().coerceAtLeast(12)
    val typeface = TextRenderer.fontFor(style.id, style == CaptionStyle.BOX)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = size.toFloat()
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    val metrics = paint.fontMetrics
    val lineHeight = metrics.bottom - metrics.top
    val padX = (size * 0.6f).toInt()
    val padY = (size * 0.4f).toInt()

    // Manual greedy wrapping into up to 3 lines.
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(candidate) <= maxWidth - padX * 2 || current.isEmpty()) {
            current = StringBuilder(candidate)
        } else {
            lines.add(current.toString())
            current = StringBuilder(word)
            if (lines.size >= 2) break
        }
    }
    if (current.isNotEmpty()) lines.add(current.toString())
    val textLines = lines.take(3)

    val bitmapW = textLines.maxOf { paint.measureText(it).toInt() } + padX * 2
    val bitmapH = ((lineHeight * textLines.size) + padY * 2).toInt()
    val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    if (style == CaptionStyle.BOX) {
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(190, 13, 15, 14)
        }
        canvas.drawRoundRect(
            0f, 0f, bitmapW.toFloat(), bitmapH.toFloat(),
            (size * 0.45f), (size * 0.45f), box
        )
    }

    var y = padY - metrics.top
    for (lineText in textLines) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = if (style == CaptionStyle.BOX) 0f else size * 0.22f
            color = android.graphics.Color.rgb(10, 12, 11)
        }
        if (style != CaptionStyle.BOX) {
            canvas.drawText(lineText, padX.toFloat(), y, stroke)
        }
        canvas.drawText(lineText, padX.toFloat(), y, paint)
        y += lineHeight
    }
    return bitmap
}

/** Loads sticker bitmaps: emoji are rendered, custom PNGs are read from cacheDir/cm_stickers/. */
object StickerAssets {

    fun load(context: Context, placement: StickerPlacement): Bitmap? {
        if (placement.isEmoji) {
            val size = (160 * placement.scale * 6f).toInt().coerceIn(96, 512)
            return renderEmoji(placement.label, size)
        }
        val file = File(context.cacheDir, "cm_stickers/${placement.id}.png")
        if (file.exists()) {
            return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }
        return null
    }

    fun renderEmoji(text: String, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.72f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, sizePx / 2f, sizePx * 0.62f, paint)
        return bitmap
    }
}
