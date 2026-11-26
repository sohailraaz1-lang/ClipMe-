package com.clipme.app.captions

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.clipme.app.util.FileUtils
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the audio track of a local mp4 into 16 kHz mono 16-bit PCM WAV,
 * which Vosk expects.
 */
object PcmExtractor {

    const val SAMPLE_RATE = 16000

    /** Extracts the full audio track as a 16 kHz mono 16-bit PCM WAV file. */
    fun extract(context: Context, videoFile: File, onProgress: (Float) -> Unit = {}): File {
        val pcm = FileUtils.tempFile(context, "_pcm.pcm")
        val extractor = MediaExtractor()
        var track = -1
        var sampleRate = 44100
        var channelCount = 2
        try {
            extractor.setDataSource(videoFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    channelCount = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    } else 2
                    break
                }
            }
            if (track < 0) throw IllegalStateException("No audio track found")

            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val codec = MediaCodec.createDecoderByType(mime)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastProgress = -1
                var resamplePhase = 0.0

                BufferedOutputStream(FileOutputStream(pcm), 64 * 1024).use { pcmOut ->
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val buf = codec.getInputBuffer(inIndex)
                                val n = if (buf != null) extractor.readSampleData(buf, 0) else -1
                                if (n < 0) {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = codec.outputFormat
                                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }
                                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                                }
                            }
                            outIndex >= 0 -> {
                                val out = codec.getOutputBuffer(outIndex)
                                if (out != null && info.size > 0) {
                                    out.position(info.offset)
                                    out.limit(info.offset + info.size)
                                    val chunk = ByteArray(info.size)
                                    out.get(chunk)
                                    resamplePhase = writeResampledMono(
                                        chunk, sampleRate, channelCount, resamplePhase, pcmOut
                                    )
                                    val progress = if (durationUs > 0) {
                                        (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                    } else 0f
                                    val pct = (progress * 100).toInt()
                                    if (pct != lastProgress) {
                                        lastProgress = pct
                                        onProgress(progress)
                                    }
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            extractor.release()
        }

        val wav = FileUtils.tempFile(context, "_pcm.wav")
        writeWavHeaderAndCopy(pcm, wav)
        pcm.delete()
        return wav
    }

    /** Downmixes 16-bit LE PCM to mono and resamples to [SAMPLE_RATE], returning updated phase. */
    private fun writeResampledMono(
        chunk: ByteArray,
        inRate: Int,
        channels: Int,
        startPhase: Double,
        out: BufferedOutputStream
    ): Double {
        val frameBytes = channels * 2
        val frameCount = chunk.size / frameBytes
        if (frameCount <= 0) return startPhase

        val shorts = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += shorts.get(i * channels + c)
            }
            mono[i] = sum / channels
        }

        if (inRate == SAMPLE_RATE) {
            val outBytes = ByteArray(frameCount * 2)
            val bb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                bb.putShort(mono[i].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            out.write(outBytes)
            return 0.0
        }

        val step = inRate.toDouble() / SAMPLE_RATE
        var pos = startPhase
        val estimated = ((frameCount - pos) / step).toInt().coerceAtLeast(1) + 2
        val bb = ByteBuffer.allocate(estimated * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (pos < frameCount - 1) {
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val s = mono[idx] + (mono[idx + 1] - mono[idx]) * frac
            bb.putShort(s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            pos += step
        }
        val written = bb.position()
        if (written > 0) {
            out.write(bb.array(), 0, written)
        }
        return (pos - (frameCount - 1)).coerceAtLeast(0.0)
    }

    private fun writeWavHeaderAndCopy(pcm: File, wav: File) {
        val dataSize = pcm.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                      // PCM subchunk size
            putShort(1)                     // AudioFormat = PCM
            putShort(1)                     // NumChannels = Mono
            putInt(SAMPLE_RATE)             // SampleRate
            putInt(SAMPLE_RATE * 2)         // ByteRate
            putShort(2)                     // BlockAlign
            putShort(16)                    // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        RandomAccessFile(wav, "rw").use { out ->
            out.write(header)
            RandomAccessFile(pcm, "r").use { input ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
        }
    }
}
