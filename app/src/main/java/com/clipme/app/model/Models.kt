package com.clipme.app.model

import android.net.Uri

/** Platform of the stream being clipped. */
enum class Platform {
    YOUTUBE, TWITCH;

    val label: String get() = when (this) {
        YOUTUBE -> "YouTube"
        TWITCH -> "Twitch"
    }
}

/** A resolved stream / VOD ready to be clipped. */
data class StreamInfo(
    val platform: Platform,
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val isLive: Boolean,
    val durationMs: Long? = null,
    /** Progressive (non-HLS) direct video URL, when the platform provides one. */
    val progressiveUrl: String? = null,
    /** Ordered list of HLS/video manifests. The first one is the preferred (lowest latency live) one. */
    val manifests: List<String> = emptyList()
) {
    val badge: String get() = if (isLive) "LIVE" else "VOD"
}

/** One chosen time window inside the stream. */
data class ClipRange(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs

    fun overlaps(other: ClipRange): Boolean =
        startMs < other.endMs && endMs > other.startMs

    companion object {
        const val MIN_MS = 5_000L
        const val MAX_MS = 180_000L
    }
}

/** A clip queued for export, in stream time. */
data class QueuedClip(
    val id: String,
    val range: ClipRange,
    val filePath: String
)

/** Placement of a sticker on the 9:16 canvas (0..1 normalized). */
data class StickerPlacement(
    val id: String,
    val label: String,
    val isEmoji: Boolean,
    val cx: Float,
    val cy: Float,
    val scale: Float,
    val rotationDeg: Float
)

/** Word-level caption timing produced by on-device ASR. */
data class CaptionWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** A subtitle line (word or group) with timing. */
data class CaptionLine(
    val words: List<CaptionWord>,
    val startMs: Long,
    val endMs: Long
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/** One export job processed by the foreground service. */
data class ExportJob(
    val jobId: String,
    val outputFile: String,
    val sourceFile: String,
    val startMs: Long,
    val durationMs: Long,
    val settings: ExportSettings,
    val streamTitle: String? = null
)

/** The 9:16 layout preset applied to every exported clip. */
enum class LayoutPreset(val id: String) {
    SPLIT("split"),
    CIRCLE_CAM("circle_cam"),
    GAME_ONLY("game_only"),
    BLUR("blur"),
    BIG_CAM("big_cam"),
    ROUNDED_CAM("rounded_cam"),
    CAM_GAME("cam_game");

    val label: String get() = when (this) {
        SPLIT -> "Split"
        CIRCLE_CAM -> "Circle cam"
        GAME_ONLY -> "Game only"
        BLUR -> "Blur"
        BIG_CAM -> "Big cam"
        ROUNDED_CAM -> "Rounded cam"
        CAM_GAME -> "Cam + game"
    }
}

/** Caption styling baked into exports. */
enum class CaptionStyle(val id: String) {
    BOLD("bold"),
    CLEAN("clean"),
    BOX("box");

    val label: String get() = when (this) {
        BOLD -> "Bold"
        CLEAN -> "Clean"
        BOX -> "Box"
    }
}

/** Everything needed to render one clip's 9:16 export. */
data class ExportSettings(
    val preset: LayoutPreset = LayoutPreset.SPLIT,
    /** Source rect of the game region on the raw stream frame, normalized 0..1. */
    val gameRect: NormRect? = null,
    /** Source rect of the cam region on the raw stream frame, normalized 0..1. */
    val camRect: NormRect? = null,
    /** Whether the cam should be hidden in the export. */
    val hideCam: Boolean = false,
    /** Hook text (defaults to the stream title). */
    val hookText: String? = null,
    val hookVisible: Boolean = true,
    /** Caption toggles. */
    val captionsOn: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle.BOLD,
    /** Word-accurate caption lines produced by the on-device ASR. */
    val captionLines: List<CaptionLine> = emptyList(),
    /** Extra sticker placements on the canvas (normalized 0..1, origin top-left). */
    val stickers: List<StickerPlacement> = emptyList(),
    val quality: ExportQuality = ExportQuality.P1080
)

/** Output resolution of the vertical 9:16 clip. */
enum class ExportQuality(val id: String, val height: Int) {
    P1080("1080p", 1920),
    P720("720p", 1280)
}

/** A rectangle in normalized (0..1) coordinates. */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val Full = NormRect(0f, 0f, 1f, 1f)
        val TopRight = NormRect(0.55f, 0.03f, 0.97f, 0.5f)
        val BottomRight = NormRect(0.55f, 0.53f, 0.97f, 0.97f)
    }
}

/** A finished clip in the library. */
data class ClipItem(
    val id: String,
    val filePath: String,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val streamTitle: String? = null
) {
    val relative: String get() = "…"
}
