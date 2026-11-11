package com.sync.media.hub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

internal object Badger {

    fun markTerritory(ctx: Context) {
        if (isAlpine()) schedule(ctx)
    }

    fun reschedule(ctx: Context) {
        schedule(ctx)
    }

    private fun isAlpine(): Boolean = isMigratory() || isHighland()

    private fun isMigratory(): Boolean {
        return try {
            val cls    = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java)
            val result = method.invoke(null, Lynx.ALPINE_PROP_KEY) as? String
            result != null && result.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun isHighland(): Boolean =
        Build.VERSION.INCREMENTAL.startsWith(Lynx.HIGHLAND_PREFIX)

    private fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, Pika::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + 4000L
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
    }
}

class Pika : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Badger.reschedule(ctx)
    }
}
