package com.aiphoneagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors

class LottoMindService : Service() {
    companion object {
        const val CHANNEL_MONITOR = "lottomind_monitor"
        const val CHANNEL_ALERTS = "lottomind_alerts"
        const val FOREGROUND_ID = 6201
        const val ACTION_REFRESH = "com.aiphoneagent.LOTTOMIND_REFRESH"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (LottoMindConfig.enabled(this@LottoMindService)) pollNow()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(FOREGROUND_ID, monitorNotification("LottoMind conectado · esperando revisión"))
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) pollNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun pollNow() {
        executor.execute {
            val base = LottoMindConfig.baseUrl(this)
            var status = "LottoMind revisado"
            for (game in listOf("powerball", "mega-millions")) {
                try {
                    val s = LottoMindClient.snapshot(base, game)
                    val previous = LottoMindConfig.lastDrawKey(this, game)
                    if (previous == null) {
                        LottoMindConfig.setLastDrawKey(this, game, s.drawKey)
                    } else if (previous != s.drawKey) {
                        LottoMindConfig.setLastDrawKey(this, game, s.drawKey)
                        showDrawAlert(s)
                    }
                    status = "${s.gameName}: ${s.drawDate} · próximo ${s.nextDrawDate ?: "—"}"
                } catch (e: Exception) {
                    status = "Sin conexión a LottoMind: ${e.message ?: "error"}"
                }
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(FOREGROUND_ID, monitorNotification(status))
        }
    }

    private fun showDrawAlert(s: LottoSnapshot) {
        val winning = s.numbers.joinToString(" - ") + " + ${s.special}"
        val details = buildString {
            append("Ganadores: $winning")
            if (!s.jackpot.isNullOrBlank()) append("\nJackpot: ${s.jackpot}")
            if (!s.cashValue.isNullOrBlank()) append(" · Cash: ${s.cashValue}")
            if (!s.nextDrawDate.isNullOrBlank()) append("\nPróximo: ${s.nextDrawDate} ${s.nextDrawTime ?: ""}")
            if (!s.prediction.isNullOrBlank()) append("\nNueva predicción: ${s.prediction}")
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${s.gameName}: resultado oficial")
            .setContentText(winning)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(appPendingIntent())
            .build()
        NotificationManagerCompat.from(this).notify((s.game.hashCode() and 0x7fffffff) % 10000 + 7000, notification)
    }

    private fun monitorNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_MONITOR)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("AI Phone Agent · LottoMind")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(appPendingIntent())
        .build()

    private fun appPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_MONITOR, "LottoMind monitor", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "LottoMind alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
