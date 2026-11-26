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
        fun from(context: Context): ClipMeApp = context.applicationContext as ClipMeApp
    }

    override fun onCreate() {
        super.onCreate()
    }
}
