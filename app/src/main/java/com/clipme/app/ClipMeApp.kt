package com.clipme.app

import android.app.Application
import android.content.Context
import com.clipme.app.captions.CaptionModelManager
import com.clipme.app.captions.CaptionEngine
import com.clipme.app.data.ClipStore
import com.clipme.app.export.ClipExportEngine
import com.clipme.app.net.HttpClient
import com.clipme.app.resolve.HlsWindowDownloader
import com.clipme.app.resolve.StreamResolver
import com.clipme.app.resolve.StreamRepository

/** Simple manual service locator — keeps ViewModels and services decoupled. */
class ClipMeApp : Application() {

    val clipStore: ClipStore by lazy { ClipStore(this) }
    val http: HttpClient by lazy { HttpClient(this) }
    val streamRepository: StreamRepository by lazy { StreamRepository(StreamResolver(this, http), this) }
    val captionModelManager: CaptionModelManager by lazy { CaptionModelManager(this) }
    val captionEngine: CaptionEngine by lazy { CaptionEngine(this, captionModelManager) }
    val hlsDownloader: HlsWindowDownloader by lazy { HlsWindowDownloader(this, http) }
    val exportEngine: ClipExportEngine by lazy { ClipExportEngine(this) }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
        fun from(context: Context): ClipMeApp = context.applicationContext as ClipMeApp
    }

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
    }

    /** Saves the stack trace of any crash so the next launch can show it on screen. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = buildString {
                    append("Thread: ").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(throwable))
                }
                java.io.File(filesDir, CRASH_FILE).writeText(text.take(6000))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
