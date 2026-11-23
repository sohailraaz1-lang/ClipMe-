package com.clipme.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** kind = "llm" (GGUF file, run by llama.cpp) or "asr" (Vosk zip, unpacked to a folder). */
data class ModelInfo(
    val id: String,
    val kind: String,
    val name: String,
    val file: String,      // llm: file name in files/models ; asr: folder name in files/
    val url: String,
    val approxBytes: Long,
    val template: String = ""
)

object Catalog {
    // All links are public (no Hugging Face login needed).
    val llm = listOf(
        ModelInfo("qwen25_05b", "llm", "Qwen 2.5 0.5B (Fast)", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491_000_000L, "chatml"),
        ModelInfo("gemma2_2b", "llm", "Gemma 2 2B (Recommended)", "gemma-2-2b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            1_710_000_000L, "gemma"),
        ModelInfo("llama32_3b", "llm", "Llama 3.2 3B (High Quality)", "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2_020_000_000L, "llama3"),
        ModelInfo("phi3_mini", "llm", "Phi-3 Mini 4K", "Phi-3-mini-4k-instruct-q4.gguf",
            "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            2_390_000_000L, "phi3")
    )
    val asr = listOf(
        ModelInfo("vosk_en_in", "asr", "Vosk Indian English", "vosk-model-small-en-in-0.4",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip", 36_000_000L),
        ModelInfo("vosk_hi", "asr", "Vosk Hindi & Regional", "vosk-model-small-hi-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42_000_000L),
        ModelInfo("vosk_en_us", "asr", "Vosk Global English", "vosk-model-small-en-us-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40_000_000L)
    )

    fun find(id: String): ModelInfo? = (llm + asr).firstOrNull { it.id == id }
}

class CancelledException : RuntimeException("Cancelled")
class HttpFail(val code: Int) : RuntimeException(
    when (code) {
        401, 403 -> "HTTP $code - this model needs a login on Hugging Face"
        404 -> "HTTP 404 - model link has moved"
        else -> "HTTP $code"
    }
)

object ModelStore {
    fun llmDir(ctx: Context) = File(ctx.filesDir, "models").also { it.mkdirs() }

    fun file(ctx: Context, m: ModelInfo): File =
        if (m.kind == "llm") File(llmDir(ctx), m.file) else File(ctx.filesDir, m.file)

    fun installed(ctx: Context, m: ModelInfo): Boolean {
        val f = file(ctx, m)
        return if (m.kind == "llm") f.isFile && f.length() > 50_000_000L else File(f, "am").exists()
    }

    fun sizeOnDisk(ctx: Context, m: ModelInfo): Long {
        val f = file(ctx, m)
        return if (m.kind == "llm") (if (f.isFile) f.length() else 0L) else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(ctx: Context, m: ModelInfo) {
        val f = file(ctx, m)
        if (m.kind == "llm") { f.delete(); File(f.path + ".part").delete() } else f.deleteRecursively()
    }
}

object Downloader {
    /** Downloads (resumable, with retries) and installs a model. Throws on failure / cancel. */
    fun fetch(ctx: Context, m: ModelInfo, cancel: AtomicBoolean, note: (String) -> Unit, prog: (Long, Long) -> Unit) {
        val isZip = m.kind == "asr"
        val dir = if (isZip) ctx.cacheDir else ModelStore.llmDir(ctx)
        val dest = File(dir, if (isZip) m.id + ".zip" else m.file)
        val part = File(dir, dest.name + ".part")

        val have = if (part.exists()) part.length() else 0L
        if (ctx.filesDir.usableSpace < (m.approxBytes * 11 / 10 - have)) {
            throw IllegalStateException("Not enough free storage (need about " + (m.approxBytes / 1_000_000) + " MB)")
        }

        var attempt = 0
        while (true) {
            if (cancel.get()) throw CancelledException()
            note(if (attempt == 0) "Connecting..." else "Retrying (" + attempt + "/5)...")
            try {
                download(m.url, part, cancel, prog)
                break
            } catch (e: IOException) {
                attempt++
                val why = e.javaClass.simpleName + ": " + (e.message ?: "connection lost")
                note("Problem: " + why + " - retrying")
                if (attempt >= 6) throw IOException("Network problem: " + why)
                Thread.sleep(minOf(5000L, 1000L shl attempt))   // then resume from the .part file
            }
        }

        if (!isZip) {
            FileInputStream(part).use { i ->
                val h = ByteArray(4)
                if (i.read(h) != 4 || String(h, Charsets.ISO_8859_1) != "GGUF") {
                    part.delete()
                    throw IOException("Downloaded file is not a valid GGUF model")
                }
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save the model file")
        } else {
            val tmp = File(ctx.cacheDir, "x_" + m.id)
            tmp.deleteRecursively(); tmp.mkdirs()
            unzip(part, tmp)
            val src = File(tmp, m.file)
            if (!File(src, "am").exists()) { tmp.deleteRecursively(); part.delete(); throw IOException("Speech model archive looks damaged") }
            val target = File(ctx.filesDir, m.file)
            target.deleteRecursively()
            if (!src.renameTo(target)) { src.copyRecursively(target, true); src.deleteRecursively() }
            tmp.deleteRecursively()
            part.delete()
        }
    }

    private fun download(url: String, part: File, cancel: AtomicBoolean, prog: (Long, Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "ClipMe/2.2 (Android)")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) {
                val tot = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (tot != null && tot == have) { prog(have, have); return }
                part.delete()
                throw IOException("Restarting download")
            }
            if (code >= 500) throw IOException("Server busy (HTTP $code)")
            if (code != 200 && code != 206) throw HttpFail(code)

            val append = code == 206
            if (!append) have = 0L
            val len = c.contentLengthLong
            val total = if (len > 0) have + len else -1L

            c.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    var last = 0L
                    while (true) {
                        if (cancel.get()) throw CancelledException()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { last = now; prog(done, total) }
                    }
                    out.flush()
                    prog(done, total)
                    if (total > 0 && done < total) throw IOException("Connection dropped at $done/$total")
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun unzip(zip: File, dest: File) {
        val root = dest.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip)).use { zi ->
            var e = zi.nextEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(root)) throw IOException("Unsafe zip entry")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zi.copyTo(it) }
                }
                zi.closeEntry()
                e = zi.nextEntry
            }
        }
    }
}
