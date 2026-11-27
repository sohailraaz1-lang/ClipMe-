package com.clipme.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clipme.app.ClipMeApp
import com.clipme.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the batch export alive while the user leaves
 * the app, and shows a persistent progress notification with an expandable
 * log console.
 *
 * The lifecycle is driven by [ClipExportEngine.exportState]: the service starts
 * when a batch is queued ([ACTION_START]), mirrors every state change into the
 * notification, and stops itself once the batch finishes or is cancelled.
 */
class ClipForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "clipme_export"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.clipme.app.export.START"
        const val ACTION_CANCEL = "com.clipme.app.export.CANCEL"

        /** Queues a batch on the engine, then asks the system to start this service. */
        fun start(context: Context) {
            val intent = Intent(context, ClipForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ClipForegroundService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val engine: ClipExportEngine get() = ClipMeApp.from(this).exportEngine
    private var wasRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground(idleNotification())
        scope.launch {
            engine.exportState.collectLatest { state ->
                updateNotification(state)
                if (wasRunning && !state.running) {
                    postFinishedNotification(state)
                    stopSelf()
                }
                wasRunning = state.running
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> engine.cancel()
            ACTION_START -> {
                // startBatch() consumes the queued jobs on the main thread.
                if (!engine.isRunning) engine.startBatch()
            }
            else -> if (!engine.isRunning) engine.startBatch()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Notification shown before the engine reports a running batch. */
    private fun idleNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_preparing))
            .setContentText(getString(R.string.notif_preparing_detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: ExportUiState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (state.running) {
            val label = if (state.total > 0) {
                getString(R.string.notif_batch, state.total)
            } else {
                getString(R.string.notif_exporting)
            }
            val progressText = when {
                state.total > 0 -> getString(
                    R.string.notif_progress,
                    state.current + 1,
                    state.total,
                    state.percent
                )
                else -> "${state.percent}%"
            }
            builder.setContentTitle(label)
                .setContentText(progressText)
                .setProgress(100, state.overallPercent.coerceIn(0, 100), false)
                .setContentInfo("${state.percent}%")
                .addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification,
                        getString(R.string.notif_cancel),
                        cancelPendingIntent()
                    ).build()
                )
        } else {
            builder.setContentTitle(getString(R.string.notif_preparing))
                .setContentText(getString(R.string.notif_preparing_detail))
        }

        if (state.log.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(state.log.joinToString("\n"))
                    .setBigContentTitle(labelOf(state))
            )
        }

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** One-shot completion summary after the batch ends. */
    private fun postFinishedNotification(state: ExportUiState) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = if (state.failed == 0) {
            getString(R.string.notif_all_done, state.completed)
        } else {
            getString(R.string.notif_done_errors, state.completed, state.failed)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun labelOf(state: ExportUiState): String = when {
        state.total > 0 -> getString(R.string.notif_batch, state.total)
        else -> getString(R.string.notif_exporting)
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, ClipForegroundService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
