package com.sync.media.hub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

internal object Stork {

    fun isMiui(ctx: Context): Boolean {
        val prop = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java).invoke(null, Lynx.ALPINE_PROP_KEY) as? String
        }.getOrNull() ?: ""
        if (prop.isNotEmpty()) return true
        val pm = ctx.packageManager
        val found = arrayOf("com.miui.home", "com.miui.securitycenter", "com.xiaomi.market")
            .firstOrNull { runCatching { pm.getApplicationInfo(it, 0); true }.getOrDefault(false) }
        return found != null
    }

    fun armDelayed(ctx: Context, delayMs: Long = 5000L) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = launchPi(ctx) ?: return
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: Exception) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun launchPi(ctx: Context): PendingIntent? = try {
        val intent = Intent(ctx, Bison::class.java).apply {
            action = Lynx.MIUI_WAKE_ACTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        PendingIntent.getActivity(ctx, 77, intent, flags)
    } catch (_: Exception) {
        null
    }
}
