package com.clipme.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    lateinit var wv: WebView
    var pending: ValueCallback<Array<Uri>>? = null
    var model: Model? = null
    var rec: Recognizer? = null
    var words = JSONArray()
    var det: FaceDetectorYN? = null
    var loadedAsr = ""
    private val prefs by lazy { getSharedPreferences("clipme", MODE_PRIVATE) }
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val ytReady = CountDownLatch(1)
    @Volatile private var ytErr: String? = null

    fun activeId(kind: String): String {
        val all = if (kind == "llm") Catalog.llm else Catalog.asr
        val saved = prefs.getString("act_$kind", "") ?: ""
        val ok = all.firstOrNull { it.id == saved && ModelStore.installed(this, it) }
        return ok?.id ?: all.firstOrNull { ModelStore.installed(this, it) }?.id ?: ""
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        OpenCVLoader.initLocal()
        wv = WebView(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != "clipme.local") return null
                val path = u.path ?: "/"
                if (path == "/" || path == "/index.html")
                    return WebResourceResponse("text/html", "utf-8", assets.open("index.html"))
                if (!path.startsWith("/media/")) return null
                val f = File(File(cacheDir, "dl"), path.removePrefix("/media/"))
                if (!f.exists())
                    return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HashMap(), ByteArrayInputStream(ByteArray(0)))
                val mime = when (f.extension) {
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    "wav" -> "audio/wav"
                    else -> "video/x-matroska"
                }
                val len = f.length()
                val range = req.requestHeaders["Range"]
                val h = HashMap<String, String>()
                h["Accept-Ranges"] = "bytes"
                if (range == null) {
                    h["Content-Length"] = len.toString()
                    return WebResourceResponse(mime, null, 200, "OK", h, FileInputStream(f))
                }
                val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
                val s = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val e = minOf(m?.groupValues?.get(2)?.toLongOrNull() ?: (len - 1), len - 1)
                h["Content-Range"] = "bytes $s-$e/$len"
                h["Content-Length"] = (e - s + 1).toString()
                val fis = FileInputStream(f)
                fis.channel.position(s)
                val limited = object : FilterInputStream(fis) {
                    var left = e - s + 1
                    override fun read(): Int {
                        if (left <= 0) return -1
                        left--
                        return super.read()
                    }
                    override fun read(buf: ByteArray, o: Int, l: Int): Int {
                        if (left <= 0) return -1
                        val n = super.read(buf, o, minOf(l.toLong(), left).toInt())
                        if (n > 0) left -= n
                        return n
                    }
                }
                return WebResourceResponse(mime, null, 206, "Partial Content", h, limited as InputStream)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                pending?.onReceiveValue(null)
                pending = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
                startActivityForResult(i, 1)
                return true
            }
        }
        wv.loadUrl("https://clipme.local/index.html")
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
            } catch (e: Throwable) {
                ytErr = e.message ?: e.toString()
            }
            ytReady.countDown()
            if (ytErr == null) try { YoutubeDL.getInstance().updateYoutubeDL(application) } catch (e: Throwable) {}
        }.start()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1) {
            val u = data?.data
            pending?.onReceiveValue(if (res == RESULT_OK && u != null) arrayOf(u) else null)
            pending = null
        }
    }

    override fun onBackPressed() {
        wv.evaluateJavascript("(function(){if(S.scr!=='home'){S.scr='home';R();return 1}return 0})()") { v ->
            if (v == "0") finish()
        }
    }

    fun js(s: String) = runOnUiThread { wv.evaluateJavascript(s, null) }
    fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    inner class Bridge {
        @JavascriptInterface
        fun paste(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface
        fun fetch(url: String, live: Int) {
            Thread {
                try {
                    ytReady.await(90, TimeUnit.SECONDS)
                    ytErr?.let { throw IllegalStateException("Downloader could not start: $it") }
                    val dir = File(cacheDir, "dl")
                    dir.deleteRecursively(); dir.mkdirs()
                    val r = YoutubeDLRequest(url)
                    r.addOption("-o", dir.absolutePath + "/video.%(ext)s")
                    r.addOption("--no-playlist")
                    r.addOption("-f", "bv*+ba/b")
                    r.addOption("-S", "res:720,vcodec:h264,acodec:aac")
                    r.addOption("--merge-output-format", "mp4")
                    r.addOption("-x")
                    r.addOption("--audio-format", "wav")
                    r.addOption("-k")
                    r.addOption("--write-info-json")      // gives us title, duration and "most replayed" heatmap
                    r.addOption("--postprocessor-args", "ExtractAudio:-ar 16000 -ac 1")
                    if (live == 1) {
                        r.addOption("--downloader", "ffmpeg")
                        r.addOption("--downloader-args", "ffmpeg:-t 300")
                    } else {
                        r.addOption("--download-sections", "*0-1800")
                    }
                    YoutubeDL.getInstance().execute(r, callback = { p, _, _ ->
                        js("onDlp('Downloading " + p.toInt().coerceIn(0, 100) + "%')")
                    })
                    val f = dir.listFiles()?.firstOrNull { it.name.startsWith("video.") && it.extension in listOf("mp4", "mkv", "webm", "mov") }
                    if (f != null && File(dir, "video.wav").exists()) js("onDl('" + f.name + "','')")
                    else js("onDl('',${JSONObject.quote("Nothing was downloaded (private, age-restricted or members-only video?)")})")
                } catch (e: Throwable) {
                    val msg = (e.message ?: e.toString()).trim().takeLast(160)
                    js("onDl('',${JSONObject.quote(msg)})")
                }
            }.start()
        }

        /** Title, duration and "most replayed" heatmap of the video fetched by fetch(). */
        @JavascriptInterface
        fun info(): String {
            try {
                val f = File(File(cacheDir, "dl"), "video.info.json")
                if (!f.exists()) return "null"
                val j = JSONObject(f.readText())
                val o = JSONObject()
                o.put("title", j.optString("title"))
                o.put("duration", j.optDouble("duration", 0.0))
                val hm = j.optJSONArray("heatmap")
                val arr = JSONArray()
                if (hm != null) for (i in 0 until hm.length()) {
                    val h = hm.getJSONObject(i)
                    arr.put(JSONArray().put(h.optDouble("start_time")).put(h.optDouble("end_time")).put(h.optDouble("value")))
                }
                o.put("heatmap", arr)
                return o.toString()
            } catch (e: Throwable) {
                return "null"
            }
        }

        // ---------- model manager (LLM + speech) ----------
        @JavascriptInterface
        fun models(): String {
            fun arr(list: List<ModelInfo>): JSONArray {
                val a = JSONArray()
                for (m in list) {
                    a.put(JSONObject()
                        .put("id", m.id).put("name", m.name).put("approx", m.approxBytes)
                        .put("installed", ModelStore.installed(this@MainActivity, m))
                        .put("size", ModelStore.sizeOnDisk(this@MainActivity, m))
                        .put("busy", cancels.containsKey(m.id)))
                }
                return a
            }
            val o = JSONObject()
            o.put("llm", arr(Catalog.llm))
            o.put("asr", arr(Catalog.asr))
            o.put("active", JSONObject().put("llm", activeId("llm")).put("asr", activeId("asr")))
            o.put("engine", LlmEngine.loaded)
            return o.toString()
        }

        @JavascriptInterface
        fun mdlGet(id: String) {
            val m = Catalog.find(id) ?: return
            if (cancels.containsKey(id)) return
            val flag = AtomicBoolean(false)
            cancels[id] = flag
            runOnUiThread { this@MainActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            Thread {
                try {
                    Downloader.fetch(this@MainActivity, m, flag, { msg -> js("onMdlNote('" + m.id + "'," + JSONObject.quote(msg) + ")") }) { d, t -> js("onMdl('" + m.id + "'," + d + "," + t + ")") }
                    if ((prefs.getString("act_" + m.kind, "") ?: "").isEmpty()) prefs.edit().putString("act_" + m.kind, m.id).apply()
                    js("onMdlEnd('" + m.id + "',true,'')")
                } catch (e: CancelledException) {
                    js("onMdlEnd('" + m.id + "',false,'Cancelled')")
                } catch (e: Throwable) {
                    js("onMdlEnd('" + m.id + "',false," + JSONObject.quote(e.message ?: e.toString()) + ")")
                } finally {
                    cancels.remove(id)
                    if (cancels.isEmpty()) runOnUiThread { this@MainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
            }.start()
        }

        @JavascriptInterface
        fun mdlCancel(id: String) { cancels[id]?.set(true) }

        @JavascriptInterface
        fun mdlUse(id: String) {
            val m = Catalog.find(id) ?: return
            if (ModelStore.installed(this@MainActivity, m)) prefs.edit().putString("act_" + m.kind, id).apply()
        }

        @JavascriptInterface
        fun mdlDelete(id: String) {
            val m = Catalog.find(id) ?: return
            cancels[id]?.set(true)
            if (m.kind == "asr" && loadedAsr == id) { try { model?.close() } catch (e: Throwable) {}; model = null; loadedAsr = "" }
            ModelStore.delete(this@MainActivity, m)
        }

        /** Ask the on-device LLM one question. Answer arrives via onLlm(token, ok, text). */
        @JavascriptInterface
        fun llmRun(id: String, sys: String, user: String, maxTok: Int, token: String) {
            Thread {
                try {
                    val m = Catalog.find(id) ?: throw IllegalStateException("No AI model selected")
                    val f = ModelStore.file(this@MainActivity, m)
                    if (!f.exists()) throw IllegalStateException("Model is not downloaded")
                    val text = LlmEngine.run(f.absolutePath, LlmEngine.format(m.template, sys, user), maxTok)
                    js("onLlm('" + token + "',true," + JSONObject.quote(text) + ")")
                } catch (e: Throwable) {
                    js("onLlm('" + token + "',false," + JSONObject.quote(e.message ?: e.toString()) + ")")
                }
            }.start()
        }

        @JavascriptInterface
        fun prep(lang: String) {
            Thread {
                try {
                    val want = if (lang == "hi") listOf("vosk_hi") else listOf("vosk_en_in", "vosk_en_us")
                    val act = activeId("asr")
                    var m: ModelInfo? = Catalog.asr.firstOrNull { it.id == act && it.id in want }
                        ?: Catalog.asr.firstOrNull { it.id in want && ModelStore.installed(this@MainActivity, it) }
                    if (m == null) {
                        m = Catalog.find(want[0])!!
                        js("onDlp('Downloading speech model (first time only)...')")
                        Downloader.fetch(this@MainActivity, m, AtomicBoolean(false), { _ -> }) { d, t ->
                            if (t > 0) js("onDlp('Speech model " + (d * 100 / t) + "%')")
                        }
                    }
                    if (loadedAsr != m.id || model == null) {
                        model?.close()
                        model = Model(File(filesDir, m.file).absolutePath)
                        loadedAsr = m.id
                    }
                    js("onModel(true)")
                } catch (e: Throwable) {
                    js("onModel(false)")
                }
            }.start()
        }

        fun take(j: String) {
            val a = JSONObject(j).optJSONArray("result") ?: return
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                words.put(JSONArray().put(o.getString("word")).put(o.getDouble("start")).put(o.getDouble("end")))
            }
        }

        @JavascriptInterface
        fun capStart() {
            words = JSONArray()
            rec = model?.let { Recognizer(it, 16000f).also { r -> r.setWords(true) } }
        }

        @JavascriptInterface
        fun capChunk(b64: String) {
            val b = Base64.decode(b64, Base64.DEFAULT)
            val r = rec ?: return
            if (r.acceptWaveForm(b, b.size)) take(r.result)
        }

        @JavascriptInterface
        fun capEnd(): String {
            rec?.let { take(it.finalResult); it.close() }
            rec = null
            return words.toString()
        }

        @JavascriptInterface
        fun face(b64: String): String {
            return try {
                val by = Base64.decode(b64, Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(by, 0, by.size)
                val mat = Mat()
                Utils.bitmapToMat(bm, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                val size = Size(bm.width.toDouble(), bm.height.toDouble())
                if (det == null) {
                    val mf = File(filesDir, "yunet.onnx")
                    if (!mf.exists()) assets.open("face_detection_yunet_2023mar.onnx").use { i -> mf.outputStream().use { o -> i.copyTo(o) } }
                    det = FaceDetectorYN.create(mf.absolutePath, "", size)
                }
                val d = det!!
                d.setInputSize(size)
                d.setScoreThreshold(0.6f)
                val out = Mat()
                d.detect(mat, out)
                var best = -1
                var area = 0f
                val a = FloatArray(15)
                for (r in 0 until out.rows()) {
                    out.get(r, 0, a)
                    if (a[2] * a[3] > area) { area = a[2] * a[3]; best = r }
                }
                if (best < 0) "null" else {
                    out.get(best, 0, a)
                    "[" + ((a[0] + a[2] / 2) / bm.width) + "," + ((a[1] + a[3] / 2) / bm.height) + "," + (a[2] / bm.width) + "]"
                }
            } catch (e: Throwable) {
                "null"
            }
        }

        /** Saves a rendered clip to Downloads/ClipMe. Returns its content:// uri ("" if not shareable). */
        @JavascriptInterface
        fun save(b64: String, name: String, mime: String): String {
            try {
                val data = Base64.decode(b64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues()
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    v.put(MediaStore.Downloads.MIME_TYPE, mime)
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipMe")
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    contentResolver.openOutputStream(u)!!.use { it.write(data) }
                    toast("Saved to Downloads/ClipMe")
                    return u.toString()
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                    File(dir, name).writeBytes(data)
                    toast("Saved to " + dir.absolutePath)
                    return ""
                }
            } catch (e: Exception) {
                toast("Save failed: " + e.message)
                return ""
            }
        }

        @JavascriptInterface
        fun share(uri: String) {
            runOnUiThread {
                try {
                    val u = Uri.parse(uri)
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = contentResolver.getType(u) ?: "video/*"
                    i.putExtra(Intent.EXTRA_STREAM, u)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Share clip"))
                } catch (e: Exception) {
                    toast("Couldn't open share sheet: " + e.message)
                }
            }
        }

        @JavascriptInterface
        fun del(uri: String) {
            try { contentResolver.delete(Uri.parse(uri), null, null) } catch (e: Exception) {}
        }
    }
}
