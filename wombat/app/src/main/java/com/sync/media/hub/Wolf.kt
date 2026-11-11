package com.sync.media.hub

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.net.ServerSocket
import kotlin.math.abs

internal object Wolf {

    fun strandReason(ctx: Context): String? = runChecks(ctx)

    fun isStranded(ctx: Context): Boolean = strandReason(ctx) != null

    private fun runChecks(ctx: Context): String? {
        if (scanWatering())          return blockWith(ctx)
        if (traceTerrain())          return blockWith(ctx)
        if (sniffBurrows())          return blockWith(ctx)
        if (surveyHerd())            return blockWith(ctx)
        if (hasObserver())           return blockWith(ctx)
        if (hasEmulatorHardware())   return blockWith(ctx)
        if (hasBannedFingerprint())  return blockWith(ctx)
        if (hasBotPackage(ctx))      return blockWith(ctx)
        if (freshMeadow())           return blockWith(ctx)
        if (installSpread(ctx))      return blockWith(ctx)
        if (checkLauncherApps(ctx))  return blockWith(ctx)
        return null
    }

    private fun blockWith(ctx: Context): String = localizedBlockMsg(ctx)

    private fun localizedBlockMsg(ctx: Context): String {
        val lang = try {
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                ctx.resources.configuration.locales[0]
            else
                @Suppress("DEPRECATION") ctx.resources.configuration.locale
            locale.language
        } catch (_: Exception) { "en" }
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
        return Lynx.WATERING_HOLES.any { port ->
            try {
                ServerSocket(port).close()
                false
            } catch (_: java.net.BindException) {
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun traceTerrain(): Boolean {
        return try {
            File("/proc/self/maps").useLines { lines ->
                lines.any { l ->
                    l.contains(Lynx.TERRAIN_MARKER_A) ||
                    l.contains(Lynx.TERRAIN_MARKER_B) ||
                    l.contains(Lynx.TERRAIN_MARKER_C)
                }
            }
        } catch (_: Exception) { false }
    }

    private fun sniffBurrows(): Boolean = Lynx.DEN_PATHS.any { File(it).exists() }

    private fun surveyHerd(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("ps")
            p.inputStream.bufferedReader().use { r ->
                r.lineSequence().any { l ->
                    l.contains(Lynx.HERD_MARKER_A) || l.contains(Lynx.HERD_MARKER_B) ||
                    l.contains(Lynx.HERD_MARKER_C) || l.contains(Lynx.HERD_MARKER_D)
                }
            }
        } catch (_: Exception) { false }
    }

    private fun hasObserver(): Boolean = Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    private fun hasEmulatorHardware(): Boolean {
        val hw    = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val fp    = Build.FINGERPRINT.lowercase()
        val emulatorHw    = hw == "goldfish" || hw == "ranchu"
        val emulatorModel = model.contains("sdk") || model.contains("emulator") ||
                            model.contains("android sdk built for x86")
        val testKeys = fp.endsWith(":test-keys")
        return emulatorHw || emulatorModel || (testKeys && emulatorHw)
    }

    private fun hasBannedFingerprint(): Boolean =
        Lynx.BANNED_FINGERPRINTS.any { it == Build.FINGERPRINT }

    private fun hasBotPackage(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return Lynx.BOT_PACKAGES.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
    }

    private fun freshMeadow(): Boolean =
        SystemClock.elapsedRealtime() < Lynx.MEADOW_THRESHOLD_MS

    private fun installSpread(ctx: Context): Boolean {
        return try {
            val pm = ctx.packageManager
            val ts = pm.getPackageInfo("com.android.settings", 0).firstInstallTime
            val tb = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (abs(ts - tb) < 600_000L) return true
            var uc = 0; var cc = 0
            for (p in pm.getInstalledPackages(0)) {
                val ai = p.applicationInfo ?: continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                if ((ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
                if (p.packageName == ctx.packageName) continue
                uc++
                if (p.firstInstallTime - ts > Lynx.SPREAD_WINDOW_MS) return false
                if (abs(p.firstInstallTime - ts) < 300_000L) cc++
            }
            uc > 0 && cc == uc
        } catch (_: Exception) { false }
    }

    private fun checkLauncherApps(ctx: Context): Boolean {
        val pm    = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps  = pm.queryIntentActivities(intent, 0)
        val myPkg = ctx.packageName
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && apps.size < 5) return true
        for (ri in apps) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == myPkg) continue
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { continue }
            if (Lynx.BOT_LABELS.any { it == label }) return true
        }
        return false
    }
}
