package com.tdeletto.mp3bulk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the process alive while a batch runs and shows its progress. Not exported. */
class CompressService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Compression progress", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, PROGRESS_ID, progress(0, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (!observing) {
            observing = true
            scope.launch {
                // Redrawing a progress notification is costly for System UI; once a second is plenty.
                var lastNotify = 0L
                BatchRunner.state
                    .map { s -> s?.let { Triple((it.fraction * 100).toInt(), it.completed, if (it.finished) -1 else it.total) } }
                    .distinctUntilChanged()
                    .collect { t ->
                        val s = BatchRunner.state.value
                        if (s == null || s.finished) {
                            ServiceCompat.stopForeground(this@CompressService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            if (s != null) nm.notify(DONE_ID, finished(s))
                            stopSelf()
                        } else if (t != null && SystemClock.elapsedRealtime() - lastNotify >= 1_000) {
                            lastNotify = SystemClock.elapsedRealtime()
                            nm.notify(PROGRESS_ID, progress(t.first, t.second, t.third))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(pct: Int, done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compressing MP3s")
            .setContentText(if (total > 0) "$done of $total files · $pct%" else "Starting…")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun finished(s: RunState): Notification {
        val pending = s.pendingTrash.size
        val text = if (pending > 0) "Tap to approve moving $pending original(s) to Trash"
        else "${s.done.size} compressed, ${s.skipped} skipped, ${s.failed} failed"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (s.cancelled) "Compression stopped" else "Compression finished")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val CHANNEL = "progress"
        private const val PROGRESS_ID = 1
        private const val DONE_ID = 2
    }
}
