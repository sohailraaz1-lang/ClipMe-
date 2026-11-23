package com.clipme.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    lateinit var wv: WebView
    var pending: ValueCallback<Array<Uri>>? = null
    var model: Model? = null
    var rec: Recognizer? = null
    var words = JSONArray()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wv = WebView(this)
        setContentView(wv)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
        }
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                pending?.onReceiveValue(null)
                pending = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
                startActivityForResult(i, 1)
                return true
            }
        }
        wv.loadUrl("file:///android_asset/index.html")
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
            } catch (e: Exception) {}
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
        fun fetch(url: String, live: Int) {
            Thread {
                try {
                    val dir = File(cacheDir, "dl")
                    dir.deleteRecursively(); dir.mkdirs()
                    val r = YoutubeDLRequest(url)
                    r.addOption("-o", dir.absolutePath + "/video.%(ext)s")
                    r.addOption("--no-playlist")
                    r.addOption("-S", "res:720,vcodec:h264,acodec:aac")
                    r.addOption("--merge-output-format", "mp4")
                    if (live == 1) {
                        r.addOption("--downloader", "ffmpeg")
                        r.addOption("--downloader-args", "ffmpeg:-t 300")
                    }
                    YoutubeDL.getInstance().execute(r, callback = { p, _, _ ->
                        js("onDlp('Downloading " + p.toInt() + "%')")
                    })
                    val f = dir.listFiles()?.firstOrNull { it.name.startsWith("video.") && !it.name.endsWith(".part") }
                    js("onDl('" + (f?.absolutePath ?: "") + "')")
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
                var bm = BitmapFactory.decodeByteArray(by, 0, by.size)
                bm = Bitmap.createBitmap(bm, 0, 0, bm.width and 1.inv(), bm.height)
                val b5 = bm.copy(Bitmap.Config.RGB_565, false)
                val fs = arrayOfNulls<FaceDetector.Face>(1)
                val n = FaceDetector(b5.width, b5.height, 1).findFaces(b5, fs)
                if (n > 0) {
                    val f = fs[0]!!
                    val p = PointF()
                    f.getMidPoint(p)
                    "[" + p.x / b5.width + "," + p.y / b5.height + "," + f.eyesDistance() * 2.6f / b5.width + "]"
                } else "null"
            } catch (e: Exception) {
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
