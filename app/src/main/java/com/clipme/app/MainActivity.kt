package com.clipme.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
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
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    lateinit var wv: WebView
    var pending: ValueCallback<Array<Uri>>? = null
    var model: Model? = null
    var rec: Recognizer? = null
    var words = JSONArray()
    var det: FaceDetectorYN? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        OpenCVLoader.initLocal()
        wv = WebView(this)
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
            } catch (e: Exception) {}
            try { YoutubeDL.getInstance().updateYoutubeDL(application) } catch (e: Exception) {}
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
                    r.addOption("--postprocessor-args", "ExtractAudio:-ar 16000 -ac 1")
                    if (live == 1) {
                        r.addOption("--downloader", "ffmpeg")
                        r.addOption("--downloader-args", "ffmpeg:-t 300")
                    } else {
                        r.addOption("--download-sections", "*0-1800")
                    }
                    YoutubeDL.getInstance().execute(r, callback = { p, _, _ ->
                        js("onDlp('Downloading " + p.toInt() + "%')")
                    })
                    val f = dir.listFiles()?.firstOrNull { it.name.startsWith("video.") && it.extension in listOf("mp4", "mkv", "webm", "mov") }
                    if (f != null && File(dir, "video.wav").exists()) js("onDl('" + f.name + "')") else js("onDl('')")
                } catch (e: Exception) {
                    js("onDl('')")
                }
            }.start()
        }

        @JavascriptInterface
        fun prep(lang: String) {
            Thread {
                try {
                    val name = if (lang == "hi") "vosk-model-small-hi-0.22" else "vosk-model-small-en-us-0.15"
                    val dir = File(filesDir, name)
                    if (!File(dir, "am").exists()) {
                        js("onDlp('Downloading caption model (first time only)...')")
                        val z = File(cacheDir, "m.zip")
                        URL("https://alphacephei.com/vosk/models/$name.zip").openStream().use { i -> z.outputStream().use { o -> i.copyTo(o) } }
                        ZipInputStream(z.inputStream()).use { zi ->
                            var e = zi.nextEntry
                            while (e != null) {
                                val f = File(filesDir, e.name)
                                if (e.isDirectory) f.mkdirs() else {
                                    f.parentFile?.mkdirs()
                                    f.outputStream().use { zi.copyTo(it) }
                                }
                                e = zi.nextEntry
                            }
                        }
                        z.delete()
                    }
                    model?.close()
                    model = Model(dir.absolutePath)
                    js("onModel(true)")
                } catch (e: Exception) {
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

        @JavascriptInterface
        fun save(b64: String, name: String) {
            try {
                val data = Base64.decode(b64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues()
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    v.put(MediaStore.Downloads.MIME_TYPE, "video/webm")
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipMe")
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    contentResolver.openOutputStream(u)!!.use { it.write(data) }
                    toast("Saved to Downloads/ClipMe")
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                    File(dir, name).writeBytes(data)
                    toast("Saved to " + dir.absolutePath)
                }
            } catch (e: Exception) {
                toast("Save failed: " + e.message)
            }
        }
    }
}
