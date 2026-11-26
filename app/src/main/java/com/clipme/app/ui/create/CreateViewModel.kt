package com.clipme.app.ui.create

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipme.app.ClipMeApp
import com.clipme.app.captions.FaceDetectorBox
import com.clipme.app.export.ClipForegroundService
import com.clipme.app.export.ExportUiState
import com.clipme.app.model.CaptionLine
import com.clipme.app.model.CaptionStyle
import com.clipme.app.model.ClipRange
import com.clipme.app.model.ExportJob
import com.clipme.app.model.ExportQuality
import com.clipme.app.model.ExportSettings
import com.clipme.app.model.LayoutPreset
import com.clipme.app.model.NormRect
import com.clipme.app.model.QueuedClip
import com.clipme.app.model.StickerPlacement
import com.clipme.app.model.StreamInfo
import com.clipme.app.resolve.LoadState
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The three steps of the create flow. */
enum class Step { STREAM, CLIPS, LAYOUT }

/** Everything the create flow screens need. */
data class CreateUiState(
    val step: Step = Step.STREAM,
    val url: String = "",
    val loadState: LoadState = LoadState.Idle,
    val stream: StreamInfo? = null,
    val frame: Bitmap? = null,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val sourceFile: File? = null,
    val windowDurationMs: Long = 0L,
    /** Auto-detected (or fallback) facecam box. */
    val camRect: NormRect? = null,
    val gameRect: NormRect = NormRect.Full,
    val ranges: List<ClipRange> = emptyList(),
    val selectedRange: Int = -1,
    val makingClips: Boolean = false,
    /** Per-clip caption lines indexed by clip position. */
    val captions: Map<Int, List<CaptionLine>> = emptyMap(),
    val transcribing: Boolean = false,
    val captionProgress: Float = 0f,
    val settings: ExportSettings = ExportSettings(),
    val exporting: Boolean = false,
    val error: String? = null
) {
    val clips: List<QueuedClip>
        get() = ranges.mapIndexed { i, r ->
            QueuedClip(id = "clip_$i", range = r, filePath = sourceFile?.absolutePath ?: "")
        }
}

class CreateViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ClipMeApp.from(app).streamRepository
    private val downloader = ClipMeApp.from(app).hlsDownloader
    private val captionEngine = ClipMeApp.from(app).captionEngine
    private val exportEngine = ClipMeApp.from(app).exportEngine

    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    /** Live export queue state surfaced straight from the engine. */
    val exportState: StateFlow<ExportUiState> = exportEngine.exportState

    init {
        viewModelScope.launch {
            repo.state.collect { load ->
                val s = _state.value
                _state.value = s.copy(loadState = load, error = null)
                if (load is LoadState.Done) {
                    _state.value = _state.value.copy(stream = load.info, frame = load.frame)
                    autoFacecam(load.frame)
                }
            }
        }
        viewModelScope.launch {
            exportEngine.exportState.collect { _state.value = _state.value.copy(exporting = it.running) }
        }
    }

    // ------------------------------------------------------------ Step 1 — Stream

    fun onUrlChange(text: String) {
        _state.value = _state.value.copy(url = text, error = null)
    }

    fun prefillUrl(url: String) {
        if (url.isNotBlank()) _state.value = _state.value.copy(url = url)
    }

    fun checkLink() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.value = _state.value.copy(error = "Paste a YouTube or Twitch link first")
            return
        }
        repo.load(url)
    }

    private fun autoFacecam(frame: Bitmap) {
        viewModelScope.launch {
            val rect = FaceDetectorBox.detect(frame)
            val norm = rect?.let {
                NormRect(
                    left = (it.left / frame.width).coerceIn(0f, 1f),
                    top = (it.top / frame.height).coerceIn(0f, 1f),
                    right = (it.right / frame.width).coerceIn(0f, 1f),
                    bottom = (it.bottom / frame.height).coerceIn(0f, 1f)
                )
            } ?: NormRect.TopRight
            _state.value = _state.value.copy(camRect = norm)
        }
    }

    fun setGameRect(rect: NormRect) {
        _state.value = _state.value.copy(gameRect = rect)
    }

    fun setCamRect(rect: NormRect) {
        _state.value = _state.value.copy(camRect = rect)
    }

    /** Downloads the stream window (last [windowSec] seconds for live). */
    fun downloadWindow() {
        val s = _state.value
        val info = s.stream ?: return
        val source = s.sourceFile
        if (source != null) {
            // Already downloaded — go straight to step 2.
            _state.value = s.copy(step = Step.CLIPS, error = null)
            return
        }
        val url = info.progressiveUrl ?: info.manifests.firstOrNull()
        if (url == null) {
            _state.value = s.copy(error = "No playable stream found for this link")
            return
        }
        _state.value = s.copy(downloading = true, downloadProgress = 0f, error = null)
        viewModelScope.launch {
            runCatching {
                val file = downloader.download(
                    url = url,
                    platform = info.platform,
                    isLive = info.isLive,
                    windowSec = 120,
                    onProgress = { done, total ->
                        _state.value = _state.value.copy(
                            downloadProgress = if (total > 0) done.toFloat() / total else 0f
                        )
                    }
                )
                val duration = fileDurationMs(file)
                val range = ClipRange(0L, minOf(30_000L, duration))
                _state.value = _state.value.copy(
                    sourceFile = file,
                    windowDurationMs = duration,
                    ranges = listOf(range),
                    selectedRange = 0,
                    downloading = false,
                    step = Step.CLIPS
                )
            }.onFailure { t ->
                _state.value = _state.value.copy(
                    downloading = false,
                    error = t.message ?: "Download failed"
                )
            }
        }
    }

    // ------------------------------------------------------------ Step 2 — Clips

    fun addRange(startMs: Long, endMs: Long) {
        val s = _state.value
        val start = startMs.coerceIn(0L, s.windowDurationMs.coerceAtLeast(0L))
        val end = endMs.coerceIn(start + ClipRange.MIN_MS, s.windowDurationMs)
        val range = ClipRange(start, end)
        if (s.ranges.any { it.overlaps(range) }) {
            _state.value = s.copy(error = "Clips cannot overlap — drag an existing clip edge instead")
            return
        }
        val ranges = s.ranges + range
        _state.value = s.copy(ranges = ranges, selectedRange = ranges.lastIndex, error = null)
    }

    fun updateRange(index: Int, startMs: Long, endMs: Long) {
        if (index !in _state.value.ranges.indices) return
        val s = _state.value
        val duration = s.windowDurationMs
        var start = startMs.coerceIn(0L, duration)
        var end = endMs.coerceIn(start + ClipRange.MIN_MS, duration)
        if (end - start > ClipRange.MAX_MS) end = start + ClipRange.MAX_MS
        if (start + ClipRange.MAX_MS > duration) {
            start = (duration - ClipRange.MAX_MS).coerceAtLeast(0L)
            end = duration
        }
        val ranges = s.ranges.toMutableList()
        ranges[index] = ClipRange(start, end)
        _state.value = s.copy(ranges = ranges, selectedRange = index, error = null)
    }

    fun removeRange(index: Int) {
        if (index !in _state.value.ranges.indices) return
        val ranges = _state.value.ranges.toMutableList()
        ranges.removeAt(index)
        _state.value = _state.value.copy(
            ranges = ranges,
            selectedRange = -1,
            captions = _state.value.captions.filterKeys { it != index }
        )
    }

    fun selectRange(index: Int) {
        _state.value = _state.value.copy(selectedRange = index)
    }

    /** Step 2 → 3: pins the clip list (no re-encoding — clips are cut at export time). */
    fun makeClips() {
        val s = _state.value
        if (s.ranges.isEmpty()) {
            _state.value = s.copy(error = "Add at least one clip first")
            return
        }
        _state.value = s.copy(makingClips = true)
        viewModelScope.launch {
            // Brief prep pass so the user sees the "MAKING CLIPS" state.
            for (i in 0..20) {
                _state.value = _state.value.copy(makingClips = true)
                delay(50)
            }
            _state.value = _state.value.copy(
                makingClips = false,
                step = Step.LAYOUT,
                settings = _state.value.settings.copy(
                    hookText = _state.value.stream?.title ?: _state.value.settings.hookText
                )
            )
        }
    }

    // ------------------------------------------------------------ Step 3 — Layout

    fun setPreset(preset: LayoutPreset) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(preset = preset))
    }

    fun setHideCam(hide: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(hideCam = hide))
    }

    fun setHookText(text: String) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(hookText = text))
    }

    fun setHookVisible(visible: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(hookVisible = visible))
    }

    fun setCaptionsOn(on: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(captionsOn = on))
    }

    fun setCaptionStyle(style: CaptionStyle) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(captionStyle = style))
    }

    fun setQuality(quality: ExportQuality) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(quality = quality))
    }

    fun setStickers(stickers: List<StickerPlacement>) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(stickers = stickers))
    }

    /** Runs offline ASR for every clip and stores word-accurate caption lines. */
    fun transcribeCaptions() {
        val s = _state.value
        val source = s.sourceFile ?: return
        if (s.ranges.isEmpty() || s.transcribing) return
        _state.value = s.copy(transcribing = true, captionProgress = 0f, error = null)
        viewModelScope.launch {
            val done = mutableMapOf<Int, List<CaptionLine>>()
            var overall = 0f
            for (i in s.ranges.indices) {
                val range = s.ranges[i]
                val result = captionEngine.transcribeClip(
                    clipFile = source,
                    clipStartMs = range.startMs,
                    clipEndMs = range.endMs,
                    onProgress = { p -> overall = (i + p) / s.ranges.size }
                )
                when (result) {
                    is com.clipme.app.captions.CaptionEngine.CaptionResult.Done -> done[i] = result.lines
                    is com.clipme.app.captions.CaptionEngine.CaptionResult.Failed -> {
                        done[i] = emptyList()
                    }
                }
                _state.value = _state.value.copy(
                    captionProgress = (i + 1f) / s.ranges.size
                )
            }
            _state.value = _state.value.copy(
                transcribing = false,
                captions = done,
                settings = _state.value.settings.copy(captionsOn = true)
            )
        }
    }

    /** Queues every clip on the export engine and starts the foreground service. */
    fun export() {
        val s = _state.value
        val source = s.sourceFile
        if (source == null || s.ranges.isEmpty()) {
            _state.value = s.copy(error = "Nothing to export yet")
            return
        }
        val app = getApplication<Application>()
        val jobs = s.ranges.mapIndexed { i, r ->
            ExportJob(
                jobId = "job_${System.currentTimeMillis()}_$i",
                outputFile = FileUtils.uniqueExportPath(app).absolutePath,
                sourceFile = source.absolutePath,
                startMs = r.startMs,
                durationMs = r.durationMs,
                settings = s.settings.copy(captionLines = s.captions[i] ?: emptyList()),
                streamTitle = s.stream?.title
            )
        }
        exportEngine.enqueue(jobs)
        ClipForegroundService.start(app)
    }

    fun cancelExport() {
        exportEngine.cancel()
    }

    fun backToStream() {
        _state.value = _state.value.copy(step = Step.STREAM)
    }

    fun backToClips() {
        _state.value = _state.value.copy(step = Step.CLIPS)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------ helpers

    private fun fileDurationMs(file: File): Long {
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            return f.getLong(MediaFormat.KEY_DURATION) / 1000L
                        }
                    }
                }
                0L
            } finally {
                extractor.release()
            }
        }.getOrElse { 0L }
    }
}
