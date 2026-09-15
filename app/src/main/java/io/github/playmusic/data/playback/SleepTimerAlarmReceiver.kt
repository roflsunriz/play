package io.github.playmusic.data.playback

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.playmusic.PlayApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SleepTimerAdminReceiver : DeviceAdminReceiver()

class SleepTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("timerId") ?: return
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Play:sleepTimer")
        // Our PendingIntent is a background broadcast (no FLAG_RECEIVER_FOREGROUND).
        // Its documented 30-second receiver budget covers the maximum 12-second fade.
        wakeLock.acquire(25_000)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { (context.applicationContext as PlayApplication).container.sleepTimer.expire(id) }
            finally { if (wakeLock.isHeld) wakeLock.release(); pending.finish() }
        }
    }
}
