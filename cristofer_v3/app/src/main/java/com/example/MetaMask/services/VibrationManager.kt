package com.example.MetaMask.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

object VibrationManager {

    @Volatile var isVibrating = false
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    fun start(context: Context, durationMs: Long) {
        android.util.Log.d("XDBG", "[VibrationManager] start enter context=ctx durationMs=${durationMs}") //xdbg
        val appCtx = context.applicationContext
        cancelSilently(appCtx)

        val vibrator: android.os.Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.util.Log.d("XDBG", "[VibrationManager] if branch") //xdbg
                val vm = appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
        } catch (_: Exception) { null }

        if (vibrator == null || !vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[VibrationManager] if branch") //xdbg
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) { return }

        isVibrating = true
        sendStatus(appCtx, true)

        val r = Runnable {
            isVibrating = false
            stopRunnable = null
            sendStatus(appCtx, false)
        }
        stopRunnable = r
        handler.postDelayed(r, durationMs)
    }

    fun stop(context: Context) {
        android.util.Log.d("XDBG", "[VibrationManager] stop enter context=ctx") //xdbg
        val appCtx = context.applicationContext
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        if (isVibrating) {
            android.util.Log.d("XDBG", "[VibrationManager] if branch") //xdbg
            try {
                (appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.cancel()
            } catch (_: Exception) {}
            isVibrating = false
            sendStatus(appCtx, false)
        }
    }

    private fun cancelSilently(appCtx: Context) {
        android.util.Log.d("XDBG", "[VibrationManager] cancelSilently enter appCtx=${appCtx}") //xdbg
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        if (isVibrating) {
            android.util.Log.d("XDBG", "[VibrationManager] if branch") //xdbg
            try {
                (appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.cancel()
            } catch (_: Exception) {}
            isVibrating = false
        }
    }

    private fun sendStatus(context: Context, vibrating: Boolean) {
        android.util.Log.d("XDBG", "[VibrationManager] sendStatus enter context=ctx vibrating=${vibrating}") //xdbg
        try {
            val json = org.json.JSONObject().apply {
                put("action", "vibration_status")
                put("vibrating", vibrating)
            }
            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, json.toString())
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
}
