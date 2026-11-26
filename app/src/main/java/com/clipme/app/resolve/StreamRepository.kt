package com.clipme.app.resolve

import android.content.Context
import android.graphics.Bitmap
import com.clipme.app.model.ClipRange
import com.clipme.app.model.Platform
import com.clipme.app.model.StreamInfo
import com.clipme.app.net.HttpClient
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LoadState {
    data object Idle : LoadState
    data object CheckingLink : LoadState
    data object GettingFrame : LoadState
    data class Done(val info: StreamInfo, val frame: Bitmap) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * Orchestrates stream loading:
 *  - task 1 "Checking the link…" → resolves [StreamInfo]
 *  - task 2 "Getting a frame…" → grabs a frame from the resolved HLS via ExoPlayer
 */
class StreamRepository(
    private val resolver: StreamResolver,
    private val context: Context
) {

    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private var job: Job? = null
    private val cancelled = AtomicBoolean(false)

    fun load(url: String) {
        cancel()
        cancelled.set(false)
        _state.value = LoadState.CheckingLink
        job = scope.launch {
            try {
                val info = resolver.resolve(url)
                checkCancelled()
                _state.value = LoadState.GettingFrame
                val frame = FrameGrabber.grabFrame(
                    context = context,
                    info = info,
                    http = http,
                    onProgress = { checkCancelled() }
                )
                checkCancelled()
                _state.value = LoadState.Done(info, frame)
            } catch (e: CancellationException) {
                _state.value = LoadState.Idle
            } catch (t: Throwable) {
                if (cancelled.get()) {
                    _state.value = LoadState.Idle
                } else {
                    _state.value = LoadState.Failed(t.message ?: "Could not load the stream")
                }
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        job?.cancel()
        job = null
    }

    fun reset() {
        cancel()
        _state.value = LoadState.Idle
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("cancelled")
    }

    private val http: HttpClient by lazy { HttpClient(context) }
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    companion object {
        fun isSupportedUrl(url: String): Boolean {
            val u = url.trim().lowercase()
            return u.contains("youtube.com") || u.contains("youtu.be") || u.contains("twitch.tv")
        }
    }
}
