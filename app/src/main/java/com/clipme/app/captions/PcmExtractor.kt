package com.clipme.app.captions

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.clipme.app.util.FileUtils
import java.io.File
import java.io.RandomAccessFile

/**
 * Decodes the audio track of a local mp4 into 16 kHz mono 16-bit PCM,
 * which Vosk expects. Works on API 26+ (MediaCodec synchronous mode) and
 * falls back to the deprecated async API below API 26 (not needed here —
 * minSdk is 26, so we always use sync mode).
 */
object PcmExtractor {

    const val SAMPLE_RATE = 16000

    /** Extracts the full audio track as 16 kHz PCM WAV file. */
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
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    break
                }
            }
            if (track < 0) throw IllegalStateException("No audio track found")

            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val codec = android.media.MediaCodec.createDecoderByType(mime)
            val csd0 = format.getByteBuffer("csd-0")
            val inputFormat = MediaFormat()
                .apply {
                    setString(MediaFormat.KEY_MIME, mime)
                    setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    setByteBuffer("csd-0", csd0)
                }

            val raf = RandomAccessFile(pcm, "rw")
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val buffers = codec.inputBuffers
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalPcm = 0L
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            var lastProgress = -1f
            val resampler = Resampler(sampleRate, channelCount)

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = buffers[inIndex]
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) resampler.inRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) resampler.channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            out.get(chunk)
                            val converted = resampler.process(chunk)
                            raf.write(converted)
                            totalPcm += converted.size
                            val progress = if (durationUs > 0)
                                (info.presentationTimeUs.toFloat() / durationUs) else 0f
                            if ((progress * 100).toInt() != lastProgress) {
                                lastProgress = (progress * 100).toInt()
                                onProgress(progress)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            codec.stop()
            codec.release()
            raf.close()
        } finally {
            extractor.release()
        }

        // Rewrap into a WAV container.
        val wav = FileUtils.tempFile(context, "_pcm.wav")
        writeWavHeaderAndCopy(pcm, wav)
        pcm.delete()
        return wav
    }

    /** Downmixes 16-bit PCM to mono and resamples it to [SAMPLE_RATE] (linear interpolation). */
    private class Resampler(var inRate: Int, var channels: Int) {
        private var pos = 0.0

        fun process(chunk: ByteArray): ByteArray {
            val ch = channels.coerceAtLeast(1)
            val frames = chunk.size / 2 / ch
            if (frames < 2) return ByteArray(0)
            val bb = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val mono = IntArray(frames)
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until ch) sum += bb.getShort((f * ch + c) * 2).toInt()
                mono[f] = sum / ch
            }
            val step = inRate.toDouble() / SAMPLE_RATE
            val out = java.io.ByteArrayOutputStream()
            while (pos < frames - 1) {
                val i = pos.toInt()
                val frac = pos - i
                val v = (mono[i] * (1.0 - frac) + mono[i + 1] * frac).toInt().coerceIn(-32768, 32767)
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                pos += step
            }
            pos -= frames.toDouble()
            if (pos < 0.0) pos = 0.0
            return out.toByteArray()
        }
    }

    private fun writeWavHeaderAndCopy(pcm: File, wav: File) {
        val dataSize = pcm.length()
        RandomAccessFile(wav, "rw").use { out ->
            out.write("RIFF".toByteArray())
            out.writeInt((36 + dataSize).toInt())
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.writeInt(16)
            out.writeShort(1)            // PCM
            out.writeShort(1)            // mono
            out.writeInt(SAMPLE_RATE)
            out.writeInt(SAMPLE_RATE * 2)
            out.writeShort(2)
            out.writeShort(16)
            out.write("data".toByteArray())
            out.writeInt(dataSize.toInt())
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
