package tech.touka.amapnavi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import tech.touka.amapnavi.service.KeepAliveService
/**
 * 开机自启接收器：车机通电后自动拉起前台保活服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent == null || intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val serviceIntent = Intent(context, KeepAliveService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
