package com.clipme.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A simple monotonic stopwatch that updates a [StateFlow] roughly every [tickMs].
 * Used for the "LOADING YOUR STREAM" and "MAKING CLIPS" elapsed timers.
 */
class StopwatchFlow(private val tickMs: Long = 500L) {

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var job: Job? = null
    private val startAt = AtomicBoolean(false)
    private var startedEpoch = 0L

    fun start(scope: CoroutineScope) {
        if (startAt.getAndSet(true)) return
        startedEpoch = System.currentTimeMillis()
        job = scope.launch {
            while (true) {
                _elapsedMs.value = System.currentTimeMillis() - startedEpoch
                delay(tickMs)
            }
        }
    }

    fun reset() {
        startAt.set(false)
        job?.cancel()
        job = null
        _elapsedMs.value = 0L
    }

    val running: Boolean get() = startAt.get()
}
