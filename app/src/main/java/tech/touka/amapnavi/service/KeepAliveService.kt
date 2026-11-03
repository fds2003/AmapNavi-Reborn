package tech.touka.amapnavi.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import tech.touka.amapnavi.R
import tech.touka.amapnavi.data.relay.RelayEngine
import tech.touka.amapnavi.ui.main.MainActivity

/**
 * 前台保活服务：将进程优先级提升至 FOREGROUND_APP，防止 Android 4.4 LMK 误杀。
 * 同时作为协同中继引擎的宿主。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "amapnavi_relay"
        private const val CHANNEL_NAME = "AmapNavi 协同中继"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        RelayEngine.get(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        RelayEngine.get(this).start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RelayEngine.get(this).stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AmapNavi 协同中继")
            .setContentText("高德导航与 C3 实时桥接运行中")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationOreoHelper.createChannel(this, CHANNEL_ID, CHANNEL_NAME)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private object NotificationOreoHelper {
        fun createChannel(context: Context, channelId: String, channelName: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }
    }
}
