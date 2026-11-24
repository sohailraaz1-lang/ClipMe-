package com.clipme.app

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FetchCancelled : RuntimeException("Cancelled")

/**
 * Video downloader (yt-dlp via youtubedl-android 0.18+, which bundles Python 3.12 and a QuickJS
 * runtime so YouTube's JavaScript challenges can be solved).
 *
 * Flow:  start downloader -> read video info -> download with REAL progress -> check files.
 * Every step has a timeout / stall watchdog, can be cancelled, and always ends with exactly one
 * done(file, error) call so the UI can never hang on "Downloading 0%".
 */
object Fetcher {
    private const val MAX_SECONDS = 1800        // long videos: only the first 30 minutes
    private const val LIVE_SECONDS = 300        // live streams: record 5 minutes
    private const val PROBE_TIMEOUT_MS = 120_000L
    private const val STALL_MS = 180_000L       // no new bytes for 3 minutes = stuck
    private const val UPDATE_EVERY_MS = 12L * 3600_000L

    private val ready = CountDownLatch(1)
    private val started = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var initErr: String? = null
    @Volatile private var cancel = false
    @Volatile private var pid: String? = null
    @Volatile var lastRaw: String = ""          // full technical error of the last failed download

    // ------------------------------------------------------------------ start-up

    /** Call once from Activity.onCreate. Initialises yt-dlp/ffmpeg, then updates yt-dlp (capped). */
    fun boot(app: Application) {
        if (!started.compareAndSet(false, true)) return
        Thread {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Throwable) {
                initErr = e.message ?: e.toString()
            }
            // The update MUST finish before the first download: replacing yt-dlp while it runs breaks it.
            if (initErr == null) updateYtdlp(app, false)
            ready.countDown()
        }.start()
    }

    /** Updates yt-dlp (nightly first, stable as fallback). Returns true if one of them worked. */
    private fun updateYtdlp(app: Application, force: Boolean): Boolean {
        val prefs = app.getSharedPreferences("clipme", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("ytdlp_upd", 0L) < UPDATE_EVERY_MS) return true
        val ok = AtomicBoolean(false)
        val t = Thread {
            for (ch in listOf("NIGHTLY", "STABLE")) {
                try { callUpdate(app, ch); ok.set(true); break } catch (e: Throwable) { /* try next channel */ }
            }
        }
        t.start()
        t.join(45_000)      // GitHub unreachable? don't block the app forever
        if (ok.get()) prefs.edit().putLong("ytdlp_upd", System.currentTimeMillis()).apply()
        return ok.get()
    }

    /** Reflection keeps this compiling whatever the exact updateYoutubeDL signature is. */
    private fun callUpdate(app: Application, channel: String) {
        val yt = YoutubeDL.getInstance()
        val ms = YoutubeDL::class.java.methods.filter { it.name == "updateYoutubeDL" }
        try {
            val two = ms.firstOrNull { it.parameterTypes.size == 2 }
            if (two != null) {
                val cls = Class.forName("com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel\$" + channel)
                two.invoke(yt, app, cls.getField("INSTANCE").get(null))
            } else {
                ms.first { it.parameterTypes.size == 1 }.invoke(yt, app)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun waitReady(app: Application, status: (String) -> Unit) {
        var waited = 0
        while (!ready.await(2, TimeUnit.SECONDS)) {
            checkCancel()
            waited += 2
            status("Starting downloader… " + waited + "s")     // first launch unpacks ~150 MB
            if (waited >= 240) throw IllegalStateException("The downloader is taking too long to start. Close the app, reopen it and try again.")
        }
        if (initErr != null) {                                   // one more try, e.g. after low-storage
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                initErr = null
            } catch (e: Throwable) {
                initErr = e.message ?: e.toString()
            }
        }
        initErr?.let { throw IllegalStateException("Downloader could not start: " + it) }
    }

    // ------------------------------------------------------------------ public API

    /** Downloads [rawUrl] into cacheDir/dl as video.<ext> + video.wav (+ video.info.json). */
    fun fetch(app: Application, rawUrl: String, live: Boolean, status: (String) -> Unit, done: (String, String) -> Unit) {
        if (!busy.compareAndSet(false, true)) { done("", "A download is already running."); return }
        cancel = false
        Thread {
            var file = ""
            var err = ""
            try {
                file = runFlow(app, rawUrl, live, status)
            } catch (e: FetchCancelled) {
                err = "Cancelled"
            } catch (e: Throwable) {
                lastRaw = (e.message ?: e.toString()).takeLast(4000)
                err = friendly(e.message ?: e.toString())
            }
            pid = null
            busy.set(false)          // free BEFORE telling the UI, so a quick retry works
            done(file, err)
        }.start()
    }

    fun cancelFetch() {
        cancel = true
        kill(pid)
    }

    // ------------------------------------------------------------------ main flow

    private fun runFlow(app: Application, rawUrl: String, live: Boolean, status: (String) -> Unit): String {
        status("Starting downloader…")
        waitReady(app, status)

        val url = clean(rawUrl)
        val dir = File(app.cacheDir, "dl")
        dir.deleteRecursively(); dir.mkdirs()

        // Different YouTube "player clients" are tried in turn (same idea as Clipper's "trying another way").
        val ladder: List<String?> =
            if (isYouTube(url)) listOf(null, "tv_samsung,web_safari,mweb", "android_vr,ios,tv") else listOf(null)

        // ---- 1. read video info (fast, and tells us early if the link can't work) ----
        var info: JSONObject? = null
        var idx = 0
        var lastErr = ""
        var updated = false
        while (idx < ladder.size) {
            checkCancel()
            status(if (idx == 0) "Reading video info…" else "Trying another way (" + idx + ")…")
            try {
                info = probe(url, ladder[idx])
                break
            } catch (e: FetchCancelled) {
                throw e
            } catch (e: Throwable) {
                checkCancel()
                lastErr = e.message ?: e.toString()
                if (permanent(lastErr)) throw IllegalStateException(lastErr)
                if (needsUpdate(lastErr) && !updated) {
                    updated = true
                    status("Updating downloader…")
                    updateYtdlp(app, true)
                    continue                      // same client again, with the fresh yt-dlp
                }
                idx++
            }
        }
        val j = info ?: throw IllegalStateException(lastErr.ifBlank { "Could not read the video" })

        val liveStatus = j.optString("live_status")
        if (liveStatus == "is_upcoming") throw IllegalStateException("This live stream has not started yet.")
        val isLive = liveStatus == "is_live" || j.optBoolean("is_live", false)
        var dur = j.optDouble("duration", 0.0)
        if (dur.isNaN()) dur = 0.0
        val pruned = JSONObject().put("title", j.optString("title")).put("duration", dur).put("is_live", isLive)
        j.optJSONArray("heatmap")?.let { pruned.put("heatmap", it) }
        File(dir, "video.info.json").writeText(pruned.toString())

        // ---- 2. download (progress is real; only very long videos / live use the slower ffmpeg path) ----
        val section = if (isLive) LIVE_SECONDS else if (dur > MAX_SECONDS) MAX_SECONDS else 0
        var k = idx
        var dlErr = ""
        while (k < ladder.size) {
            checkCancel()
            clearPartials(dir)
            if (k > idx) status("Trying another way (" + k + ")…")
            try {
                download(dir, url, ladder[k], isLive, section, status)
                return finish(dir)
            } catch (e: FetchCancelled) {
                throw e
            } catch (e: Throwable) {
                checkCancel()
                dlErr = e.message ?: e.toString()
                if (permanent(dlErr)) throw IllegalStateException(dlErr)
                if (needsUpdate(dlErr) && !updated) {
                    updated = true
                    status("Updating downloader…")
                    updateYtdlp(app, true)
                    continue
                }
                k++
            }
        }
        throw IllegalStateException(dlErr.ifBlank { "Nothing was downloaded" })
    }

    private fun baseRequest(url: String, clients: String?): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "20")
        r.addOption("--retries", "10")
        r.addOption("--fragment-retries", "10")
        r.addOption("--extractor-retries", "3")
        if (clients != null) r.addOption("--extractor-args", "youtube:player_client=" + clients)
        return r
    }

    private fun probe(url: String, clients: String?): JSONObject {
        val r = baseRequest(url, clients)
        r.addOption("--dump-single-json")
        r.addOption("--skip-download")
        val id = newPid()
        val finished = AtomicBoolean(false)
        val killed = AtomicBoolean(false)
        val wd = Thread {
            try {
                Thread.sleep(PROBE_TIMEOUT_MS)
                if (!finished.get()) { killed.set(true); kill(id) }
            } catch (e: InterruptedException) { }
        }
        wd.start()
        val out = try {
            YoutubeDL.getInstance().execute(r, processId = id).out
        } catch (e: Throwable) {
            if (cancel) throw FetchCancelled()
            if (killed.get()) throw IOException("Reading the video info timed out. Check your connection and try again.")
            throw e
        } finally {
            finished.set(true)
            wd.interrupt()
        }
        val a = out.indexOf('{')
        val b = out.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("The site returned no video information")
        return JSONObject(out.substring(a, b + 1))
    }

    private fun download(dir: File, url: String, clients: String?, isLive: Boolean, section: Int, status: (String) -> Unit) {
        val r = baseRequest(url, clients)
        r.addOption("-o", File(dir, "video.%(ext)s").absolutePath)
        r.addOption("-f", "bv*+ba/b")
        r.addOption("-S", "res:720,vcodec:h264,acodec:aac")
        r.addOption("--merge-output-format", "mp4")
        r.addOption("-x")
        r.addOption("--audio-format", "wav")
        r.addOption("-k")                                   // keep the video after extracting the audio
        r.addOption("--postprocessor-args", "ExtractAudio:-ar 16000 -ac 1")
        r.addOption("--concurrent-fragments", "4")
        if (isLive) {
            r.addOption("--downloader", "ffmpeg")
            r.addOption("--downloader-args", "ffmpeg:-t " + LIVE_SECONDS)
        } else if (section > 0) {
            r.addOption("--download-sections", "*0-" + section)
        }

        val id = newPid()
        val sawPercent = AtomicBoolean(false)
        val stop = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)

        // Watches the folder: shows MB when yt-dlp prints no percentage, and kills a stalled download.
        val poll = Thread {
            var lastBytes = -1L
            var lastChange = System.currentTimeMillis()
            while (!stop.get()) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                val bytes = dir.walkTopDown().filter { it.isFile && it.name != "video.info.json" }.sumOf { it.length() }
                val now = System.currentTimeMillis()
                if (bytes != lastBytes) { lastBytes = bytes; lastChange = now }
                if (!cancel && now - lastChange > STALL_MS) {
                    stalled.set(true)
                    kill(id)
                    break
                }
                if (!sawPercent.get() && bytes > 0) status("Downloading… " + mb(bytes))
            }
        }
        poll.start()
        status("Preparing download…")
        try {
            YoutubeDL.getInstance().execute(r, processId = id) { pct, _, line ->
                when {
                    line.contains("[Merger]") -> status("Merging video and audio…")
                    line.contains("[ExtractAudio]") -> status("Extracting audio…")
                    pct >= 0f -> {
                        sawPercent.set(true)
                        status("Downloading " + pct.toInt().coerceIn(0, 100) + "%")
                    }
                    else -> {}
                }
            }
        } catch (e: Throwable) {
            if (cancel) throw FetchCancelled()
            if (stalled.get()) throw IOException("The download stalled (no data for 3 minutes). Check your connection and try again.")
            throw e
        } finally {
            stop.set(true)
            poll.interrupt()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun newPid(): String {
        val id = "clipme-" + UUID.randomUUID().toString()
        pid = id
        return id
    }

    private fun kill(id: String?) {
        if (id == null) return
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (e: Throwable) { }
    }

    private fun checkCancel() { if (cancel) throw FetchCancelled() }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

    /** Removes half-finished files of a failed attempt but keeps video.info.json. */
    private fun clearPartials(dir: File) {
        dir.listFiles()?.forEach { if (it.name != "video.info.json") it.deleteRecursively() }
    }

    /** Picks the real merged video (not video.f137.mp4 leftovers), checks the audio, tidies the folder. */
    private fun finish(dir: File): String {
        val v = listOf("mp4", "mkv", "webm", "mov").map { File(dir, "video.$it") }
            .firstOrNull { it.isFile && it.length() > 20_000 }
            ?: throw IOException("Nothing was downloaded (private, age-restricted or members-only video?)")
        val w = File(dir, "video.wav")
        if (!w.isFile || w.length() < 2_000) throw IOException("The audio track could not be extracted from this video")
        dir.listFiles()?.forEach {
            if (it.name != v.name && it.name != "video.wav" && it.name != "video.info.json") it.deleteRecursively()
        }
        return v.name
    }

    private val urlRe = Regex("https?://[^\\s\"'<>]+")

    /** Pulls the link out of pasted text and turns every YouTube form into a plain watch URL. */
    private fun clean(raw: String): String {
        val u = (urlRe.find(raw)?.value ?: raw).trim().trimEnd('.', ',', ')', ']')
        val id = youtubeId(u)
        return if (id != null) "https://www.youtube.com/watch?v=" + id else u
    }

    private fun isYouTube(u: String) = u.contains("youtube.com") || u.contains("youtu.be")

    private fun youtubeId(u: String): String? {
        if (!isYouTube(u) && !u.contains("youtube-nocookie.com")) return null
        val pats = listOf(
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})")
        )
        for (p in pats) { val m = p.find(u); if (m != null) return m.groupValues[1] }
        return null
    }

    private fun permanent(e: String): Boolean {
        val l = e.lowercase()
        return "private video" in l || "members-only" in l || "join this channel" in l ||
            "unsupported url" in l || "is not a valid url" in l || "has been removed" in l ||
            "account associated" in l || "blocked it in your country" in l || "not available in your country" in l
    }

    /** Errors that usually mean "yt-dlp is out of date": update once and retry. */
    private fun needsUpdate(e: String): Boolean {
        val l = e.lowercase()
        return "challenge" in l || "nsig" in l || "signature" in l || "only images are available" in l ||
            "requested format is not available" in l || "unable to extract" in l || "http error 403" in l ||
            "player response" in l || "no module named" in l || "syntaxerror" in l
    }

    fun friendly(raw: String): String {
        val m = raw.trim()
        val l = m.lowercase()
        return when {
            "not a bot" in l || "sign in to confirm" in l ->
                "YouTube wants to verify you are not a bot. Wait a few minutes, or switch between Wi-Fi and mobile data, then try again."
            "private video" in l -> "This video is private."
            "members-only" in l || "join this channel" in l -> "This video is for channel members only."
            "confirm your age" in l || "age-restricted" in l || "inappropriate for some users" in l ->
                "This video is age-restricted and can't be downloaded without signing in."
            "not available in your country" in l || "blocked it in your country" in l -> "This video is blocked in your country."
            "video unavailable" in l || "this video is not available" in l || "has been removed" in l || "account associated" in l ->
                "This video is unavailable."
            "unsupported url" in l -> "That link isn't supported. Paste a public video link."
            "429" in l || "too many requests" in l ->
                "The site is limiting downloads from your network right now. Try again in a few minutes."
            "403" in l && "forbidden" in l ->
                "The site refused the download (HTTP 403). Try again; if it keeps happening switch network."
            "requested format is not available" in l || "only images are available" in l || "challenge" in l || "nsig" in l ->
                "YouTube changed something and the video could not be read. Try again in a minute (the downloader updates itself), or use another link."
            "name resolution" in l || "network is unreachable" in l || "unable to connect" in l || "no address associated" in l ->
                "No internet connection. Check it and try again."
            else -> {
                val line = m.lines().lastOrNull { it.contains("ERROR", true) } ?: m.lines().lastOrNull { it.isNotBlank() } ?: m
                line.replace(Regex("^.*ERROR:\\s*"), "").trim().takeLast(220).ifBlank { "Download failed" }
            }
        }
    }
}
