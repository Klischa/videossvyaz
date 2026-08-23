package com.example.p2pcall.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground-сервис «удержания» звонка.
 *
 * Пока ожидается ответная ссылка или идёт соединение, держит процесс приложения
 * живым (приоритет foreground) — чтобы Android (особенно 13+) не убил процесс
 * и не потерял in-memory сессию WebRTC ([com.example.p2pcall.webrtc.WebRtcController]).
 *
 * Сам WebRTC-движок здесь не живёт — он в CallActivity; задача сервиса — только
 * не дать системе выгрузить процесс.
 */
class CallService : Service() {

    companion object {
        private const val CHANNEL_ID = "p2p_call"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // На случай, если сервис стартует через startForegroundService —
        // уведомление уже показано в onCreate.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Звонок",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Звонок")
            .setContentText("Идёт соединение / активный звонок")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
