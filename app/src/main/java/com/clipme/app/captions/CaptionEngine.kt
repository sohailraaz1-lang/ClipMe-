package com.clipme.app.captions

import android.content.Context
import com.clipme.app.model.CaptionLine
import com.clipme.app.model.CaptionWord
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Offline word-by-word transcription using Vosk.
 *
 * Vosk's partial result includes per-word timestamps for the recognized
 * utterance ("result" array with start/end). We collect those and map them
 * onto clip-local time (0 = clip start).
 */
class CaptionEngine(
    private val context: Context,
    private val modelManager: CaptionModelManager
) {

    sealed interface CaptionResult {
        data class Done(val lines: List<CaptionLine>) : CaptionResult
        data class Failed(val message: String) : CaptionResult
    }

    /** Transcribes the given clip file and returns word-accurate captions. */
    suspend fun transcribeClip(
        clipFile: File,
        clipStartMs: Long,
        clipEndMs: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit = {}
    ): CaptionResult = withContext(Dispatchers.IO) {
        if (!modelManager.isReady()) return@withContext CaptionResult.Failed("Caption model not downloaded")
        runCatching {
            val wav = PcmExtractor.extract(context, clipFile, onProgress)
            try {
                val model = Model(modelManager.modelDir().absolutePath)
                val recognizer = Recognizer(model, PcmExtractor.SAMPLE_RATE.toFloat())
                recognizer.setWords(true)
                val words = mutableListOf<CaptionWord>()
                var pendingText = StringBuilder()
                val total = wav.length()
                var done = 0L

                RandomAccessFile(wav, "r").use { raf ->
                    // Skip the 44-byte WAV header.
                    raf.seek(44)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = raf.read(buffer)
                        if (n < 0) break
                        done += n
                        val finished = recognizer.acceptWaveForm(buffer, n)
                        val json = if (finished) {
                            recognizer.result
                        } else {
                            recognizer.partialResult
                        }
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))

                        if (finished) {
                            val arr = JSONObject(json).optJSONArray("result") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val w = arr.getJSONObject(i)
                                val start = (w.optDouble("start", 0.0) * 1000).toLong()
                                val end = (w.optDouble("end", 0.0) * 1000).toLong()
                                val text = w.optString("word", "").trim()
                                if (text.isNotEmpty()) {
                                    words.add(CaptionWord(text, start, end))
                                }
                            }
                        } else {
                            pendingText = StringBuilder(JSONObject(json).optString("partial", ""))
                        }
                    }
                    // Flush the last words that were still buffered inside the recognizer.
                    val tail = JSONObject(recognizer.finalResult).optJSONArray("result") ?: JSONArray()
                    for (i in 0 until tail.length()) {
                        val w = tail.getJSONObject(i)
                        val text = w.optString("word", "").trim()
                        if (text.isNotEmpty()) {
                            words.add(
                                CaptionWord(
                                    text,
                                    (w.optDouble("start", 0.0) * 1000).toLong(),
                                    (w.optDouble("end", 0.0) * 1000).toLong()
                                )
                            )
                        }
                    }
                }
                recognizer.close()
                model.close()

                // The whole downloaded window is transcribed; keep only words that fall inside this clip.
                val inClip = words.filter { it.endMs > clipStartMs && it.startMs < clipEndMs }
                val lines = groupWordsIntoLines(inClip, clipStartMs)
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
        }.getOrElse { CaptionResult.Failed(it.message ?: "Transcription failed") }
    }

    /** Groups words into timed subtitle lines (roughly sentence chunks). */
    private fun groupWordsIntoLines(words: List<CaptionWord>, clipStartMs: Long): List<CaptionLine> {
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
                        startMs = (lineStart - clipStartMs).coerceAtLeast(0L),
                        endMs = (lineEnd - clipStartMs).coerceAtLeast(0L)
                    )
                )
                current.clear()
            }
        }

        for (w in words) {
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
