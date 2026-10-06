package ch.madtreasures.fluency.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ch.madtreasures.fluency.FluencyApp
import ch.madtreasures.fluency.MainActivity
import ch.madtreasures.fluency.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while models are downloading and shows the progress. The download
 * logic itself lives in [ModelManager]; this service only observes it.
 */
class DownloadService : LifecycleService() {

    private var observing = false
    private var lastNotify = 0L
    private var lastPercent = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val manager = (application as FluencyApp).container.modelManager
        startForeground(NOTIFICATION_ID, build(this, "Modelle werden geladen …", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (observing) return START_NOT_STICKY
        observing = true
        lifecycleScope.launch {
            manager.states.collectLatest { states ->
                val busy = states.filterValues { it.isBusy }
                if (busy.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                var done = 0L
                var total = 0L
                busy.values.forEach { s ->
                    when (s) {
                        is ModelState.Downloading -> { done += s.bytesDone; total += s.bytesTotal }
                        is ModelState.Importing -> { done += s.bytesDone; total += s.bytesTotal }
                        else -> {}
                    }
                }
                val names = busy.keys.mapNotNull { manager.model(it)?.name }.joinToString(", ")
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                val now = System.currentTimeMillis()
                // the system throttles frequent updates anyway; once per second is enough
                if (percent != lastPercent && now - lastNotify >= 1_000) {
                    lastNotify = now
                    lastPercent = percent
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, build(this@DownloadService, names, percent, total <= 0))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42

        /** Called by [ModelManager] whenever the number of active downloads changes. */
        fun update(context: Context, active: Boolean) {
            if (!active) return // the service stops itself when nothing is busy any more
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        private fun build(context: Context, text: String, percent: Int, indeterminate: Boolean): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Modell-Downloads", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Fluency lädt Modelle")
                .setContentText(text)
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
