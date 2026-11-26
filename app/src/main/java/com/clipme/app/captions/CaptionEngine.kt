package com.clipme.app.captions

import android.content.Context
import com.clipme.app.model.CaptionLine
import com.clipme.app.model.CaptionWord
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile

/**
 * Offline word-by-word transcription using Vosk.
 *
 * Vosk returns per-word timestamps ("result" array with start/end). We collect
 * those and map them onto clip-local time (0 = clip start).
 */
class CaptionEngine(
    private val context: Context,
    private val modelManager: CaptionModelManager
) {

    sealed interface CaptionResult {
        data class Done(val lines: List<CaptionLine>) : CaptionResult
        data class Failed(val message: String) : CaptionResult
    }

    /** Transcribes the given file and returns word-accurate captions for [clipStartMs..clipStartMs+clipDurationMs]. */
    suspend fun transcribeClip(
        clipFile: File,
        clipStartMs: Long,
        clipDurationMs: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit = {}
    ): CaptionResult = withContext(Dispatchers.IO) {
        if (!modelManager.isReady()) return@withContext CaptionResult.Failed("Caption model not downloaded")
        val wav = PcmExtractor.extract(context, clipFile) { p -> onProgress(p * 0.45f) }
        try {
            val model = Model(modelManager.modelDir().absolutePath)
            val recognizer = Recognizer(model, PcmExtractor.SAMPLE_RATE.toFloat())
            recognizer.setWords(true)
            val words = mutableListOf<CaptionWord>()
            val total = wav.length().coerceAtLeast(1L)
            var done = 0L

            try {
                RandomAccessFile(wav, "r").use { raf ->
                    // Skip the 44-byte WAV header.
                    if (raf.length() > 44L) raf.seek(44L)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = raf.read(buffer)
                        if (n < 0) break
                        done += n
                        val finished = recognizer.acceptWaveForm(buffer, n)
                        onProgress((0.45f + (done.toFloat() / total) * 0.55f).coerceIn(0f, 1f))
                        if (finished) {
                            collectWords(recognizer.result, words)
                        }
                    }
                    collectWords(recognizer.finalResult, words)
                }
            } finally {
                runCatching { recognizer.close() }
                runCatching { model.close() }
            }

            val lines = groupWordsIntoLines(words, clipStartMs, clipDurationMs)
            if (lines.isEmpty()) {
                CaptionResult.Failed("No speech was recognized in this clip")
            } else {
                CaptionResult.Done(lines)
            }
        } catch (t: Throwable) {
            CaptionResult.Failed(t.message ?: "Transcription failed")
        } finally {
            FileUtils.deleteQuietly(wav)
        }
    }

    private fun collectWords(json: String?, out: MutableList<CaptionWord>) {
        if (json.isNullOrBlank()) return
        val arr = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: JSONArray()
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            val start = (w.optDouble("start", 0.0) * 1000).toLong()
            val end = (w.optDouble("end", 0.0) * 1000).toLong()
            val text = w.optString("word", "").trim()
            if (text.isNotEmpty()) {
                out.add(CaptionWord(text, start, end))
            }
        }
    }

    /** Groups words into timed subtitle lines within [clipStartMs..clipStartMs+clipDurationMs]. */
    private fun groupWordsIntoLines(
        words: List<CaptionWord>,
        clipStartMs: Long,
        clipDurationMs: Long
    ): List<CaptionLine> {
        val clipEndMs = if (clipDurationMs == Long.MAX_VALUE) Long.MAX_VALUE else clipStartMs + clipDurationMs
        val filtered = words.mapNotNull { w ->
            if (w.endMs <= clipStartMs || w.startMs >= clipEndMs) return@mapNotNull null
            CaptionWord(
                text = w.text,
                startMs = (w.startMs - clipStartMs).coerceAtLeast(0L),
                endMs = (w.endMs - clipStartMs).coerceAtMost(clipDurationMs)
            )
        }

        val lines = mutableListOf<CaptionLine>()
        var current = mutableListOf<CaptionWord>()
        var lineStart = -1L
        var lineEnd = -1L
        var lastWordEnd = -1L

        fun flush() {
            if (current.isNotEmpty()) {
                lines.add(
                    CaptionLine(
                        words = current.toList(),
                        startMs = lineStart.coerceAtLeast(0L),
                        endMs = lineEnd.coerceAtLeast(lineStart + 100L)
                    )
                )
                current.clear()
            }
        }

        for (w in filtered) {
            val gap = if (lastWordEnd >= 0) w.startMs - lastWordEnd else 0L
            val punctuationBreak = w.text.endsWith(".") || w.text.endsWith("?") || w.text.endsWith("!")
            if (current.isNotEmpty() && (gap > 700 || (current.size >= 4 && punctuationBreak) || current.size >= 6)) {
                flush()
            }
            if (current.isEmpty()) {
                lineStart = w.startMs
            }
            current.add(w)
            lineEnd = w.endMs
            lastWordEnd = w.endMs
        }
        flush()
        return lines
    }
}
