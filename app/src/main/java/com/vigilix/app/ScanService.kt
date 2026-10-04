package com.vigilix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Mantiene vivo el escaneo con una notificación mientras Android podría cerrar la app. */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        // Android exige llamar a startForeground() tras cada startForegroundService(), aunque ya haya un escaneo en curso.
        createChannel()
        startForegroundCompat(notification(getString(R.string.scan_notif_starting), indeterminate = true))
        if (job?.isActive == true) return START_NOT_STICKY

        val full = intent?.getBooleanExtra(EXTRA_FULL, false) ?: false
        val useVt = intent?.getBooleanExtra(EXTRA_VT, false) ?: false
        val launched = scope.launch {
            val watcher = ScanEngine.state
                .onEach { state ->
                    if (state.running) notify(notification(progressText(state), indeterminate = true))
                }
                .launchIn(this)
            try {
                ScanEngine.run(applicationContext, full, useVt)
            } finally {
                watcher.cancel()
            }
        }
        job = launched
        launched.invokeOnCompletion {
            Handler(Looper.getMainLooper()).post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifyFinished()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressText(state: ScanState): String = when (state.phase) {
        ScanPhase.VIRUSTOTAL -> getString(R.string.scan_notif_vt, state.vtDone, state.vtTotal)
        ScanPhase.APPS -> getString(R.string.scan_notif_apps)
        else -> getString(R.string.scan_notif_files, state.scannedFiles)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.scan_channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, indeterminate: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, indeterminate)
            .addAction(0, getString(R.string.scan_cancel), stop)
            .build()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun notifyFinished() {
        val state = ScanEngine.state.value
        val text = when (state.phase) {
            ScanPhase.DONE -> if (state.findings.isEmpty()) {
                getString(R.string.scan_notif_done_clean, state.scannedFiles)
            } else {
                getString(R.string.scan_notif_done_findings, state.findings.size)
            }
            ScanPhase.CANCELLED -> getString(R.string.scan_notif_cancelled)
            else -> getString(R.string.scan_note_failed)
        }
        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, done)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "vigilix_scan"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "com.vigilix.app.action.STOP_SCAN"
        private const val EXTRA_FULL = "full"
        private const val EXTRA_VT = "vt"

        fun start(context: Context, full: Boolean, useVirusTotal: Boolean) {
            val intent = Intent(context, ScanService::class.java)
                .putExtra(EXTRA_FULL, full)
                .putExtra(EXTRA_VT, useVirusTotal)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScanService::class.java).setAction(ACTION_STOP))
        }
    }
}
