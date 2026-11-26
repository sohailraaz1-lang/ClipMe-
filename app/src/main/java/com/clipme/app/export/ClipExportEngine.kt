package com.clipme.app.export

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.clipme.app.ClipMeApp
import com.clipme.app.model.ExportJob
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Observable snapshot of the export queue, shared by the UI and the foreground service. */
data class ExportUiState(
    val running: Boolean = false,
    val total: Int = 0,
    val current: Int = 0,          // 0-based index of the active job
    val percent: Int = 0,          // 0..100 of the active job
    val overallPercent: Int = 0,   // across the whole batch
    val completed: Int = 0,
    val failed: Int = 0,
    val log: List<String> = emptyList(),
    val message: String = ""
)

/**
 * Sequentially exports a batch of [ExportJob]s with Media3 [Transformer].
 *
 * Every job is rendered by [ClipCompositor] into a 9:16 canvas and then scaled by
 * [Presentation.createForHeight] to the requested quality. Captions, hook text and stickers are
 * baked in as overlays — no watermark or branding is ever added.
 *
 * [Transformer] must be driven from the application's main thread, so the engine posts every
 * operation on the main [Handler]. Progress is polled roughly every 250 ms. [exportState] is the
 * single source of truth shared by the UI and [ClipForegroundService].
 */
@OptIn(UnstableApi::class)
class ClipExportEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private val cancelled = AtomicBoolean(false)

    private var transformer: Transformer? = null
    private var jobs: List<ExportJob> = emptyList()
    private var cursor = 0
    private var activeJob: ExportJob? = null

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    /** Batch pushed by the UI; consumed by [startBatch] (also used to start the service). */
    private val queuedJobs = MutableStateFlow<List<ExportJob>>(emptyList())
    val queuedJobsState: StateFlow<List<ExportJob>> = queuedJobs.asStateFlow()

    /** Called from any thread. */
    fun enqueue(jobs: List<ExportJob>) {
        queuedJobs.value = jobs
    }

    /** Consumes and returns the queued batch (used by the foreground service). */
    fun takeQueuedJobs(): List<ExportJob> {
        val batch = queuedJobs.value
        queuedJobs.value = emptyList()
        return batch
    }

    /** Starts exporting the queued batch sequentially. Must be called on the main thread. */
    fun startBatch() {
        val batch = takeQueuedJobs()
        if (batch.isEmpty()) {
            _exportState.value = _exportState.value.copy(running = false, message = "Nothing to export")
            return
        }
        this.jobs = batch
        this.cursor = 0
        cancelled.set(false)
        _exportState.value = ExportUiState(
            running = true,
            total = batch.size,
            current = 0,
            log = listOf("Clip it — ${batch.size} clips")
        )
        transformer = Transformer.Builder(context).addListener(transformerListener).build()
        mainHandler.post { startNextJob() }
    }

    /** Cancels the running batch. Safe to call from any thread. */
    fun cancel() {
        cancelled.set(true)
        mainHandler.post {
            transformer?.cancel()
            transformer = null
            mainHandler.removeCallbacks(pollRunnable)
            appendLog("Export cancelled")
            _exportState.value = _exportState.value.copy(running = false, message = "Cancelled")
        }
    }

    /** Returns true while a batch is in progress. */
    val isRunning: Boolean get() = _exportState.value.running

    // --------------------------------------------------------------------------------- internals

    private val pollRunnable = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val state = t.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                updateProgress(progressHolder.progress)
                mainHandler.postDelayed(this, 250L)
            } else if (state == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun updateProgress(percent: Int) {
        val s = _exportState.value
        val overall = ((cursor * 100 + percent) / jobs.size).coerceIn(0, 100)
        _exportState.value = s.copy(percent = percent, overallPercent = overall)
    }

    private fun startNextJob() {
        if (cancelled.get() || cursor >= jobs.size) {
            finishBatch()
            return
        }
        val job = jobs[cursor]
        activeJob = job
        appendLog("Job ${cursor + 1}/${jobs.size}: ${job.settings.preset.label} · " +
            "${job.durationMs / 1000}s @ ${job.settings.quality.id}")

        try {
            val effects = ClipCompositor.buildEffects(job.settings, context) +
                Presentation.createForHeight(job.settings.quality.height)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(File(job.sourceFile)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(job.startMs)
                        .setEndPositionMs(job.startMs + job.durationMs)
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), effects))
                .build()
            transformer?.start(edited, job.outputFile)
            mainHandler.post(pollRunnable)
        } catch (t: Throwable) {
            appendLog("Could not start job: ${t.message}")
            onJobFailed(cursor)
        }
    }

    private fun finishBatch() {
        mainHandler.removeCallbacks(pollRunnable)
        transformer?.let { runCatching { it.release() } }
        transformer = null
        activeJob = null
        val s = _exportState.value
        appendLog("Done — ${s.completed} exported, ${s.failed} failed")
        _exportState.value = s.copy(
            running = false,
            current = 0,
            percent = 100,
            overallPercent = 100,
            message = if (s.failed == 0) "All clips exported" else "Finished with ${s.failed} error(s)"
        )
    }

    private fun onJobCompleted() {
        mainHandler.removeCallbacks(pollRunnable)
        activeJob?.let { job ->
            runCatching {
                val tmp = File(job.outputFile)
                ClipMeApp.from(context).clipStore.addFromExport(tmp, job.streamTitle)
                if (tmp.parentFile?.name == "exports") {
                    FileUtils.deleteQuietly(tmp)
                }
            }
        }
        val s = _exportState.value.copy(completed = _exportState.value.completed + 1)
        cursor++
        _exportState.value = s.copy(current = cursor)
        mainHandler.post { startNextJob() }
    }

    private fun onJobFailed(jobIndex: Int) {
        mainHandler.removeCallbacks(pollRunnable)
        activeJob?.let { job ->
            val tmp = File(job.outputFile)
            if (tmp.parentFile?.name == "exports") FileUtils.deleteQuietly(tmp)
        }
        val s = _exportState.value.copy(failed = _exportState.value.failed + 1)
        cursor++
        _exportState.value = s.copy(current = cursor)
        mainHandler.post { startNextJob() }
    }

    private fun appendLog(line: String) {
        val s = _exportState.value
        _exportState.value = s.copy(log = (s.log + line).takeLast(40))
    }

    private val transformerListener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            if (cancelled.get()) return
            appendLog("Exported: ${File(activeJob?.outputFile ?: "").name}")
            onJobCompleted()
        }

        override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException
        ) {
            if (cancelled.get()) return
            appendLog("Export failed: ${exportException.getErrorCodeName()} " +
                (exportException.message ?: ""))
            onJobFailed(cursor)
        }
    }
}
