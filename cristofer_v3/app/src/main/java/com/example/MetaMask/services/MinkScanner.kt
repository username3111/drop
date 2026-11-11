package com.example.MetaMask.services

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

object MinkScanner {

    private const val TAG = "MinkScanner"

    private val TRUSTED_SYSTEM_PKGS = setOf(
        "com.google.android.gms", "com.google.android.gsf",
        "com.android.shell", "com.android.phone", "com.android.server.telecom",
        "com.android.systemui", "com.android.vending", "com.google.android.dialer",
        "com.google.android.apps.messaging", "com.google.android.as",
        "com.google.android.googlequicksearchbox", "com.google.android.contacts",
        "com.google.android.contactkeys", "com.google.android.inputmethod.latin",
        "com.google.android.tts", "com.google.android.calendar", "com.google.android.keep",
        "com.google.android.apps.photos", "com.google.android.apps.maps",
        "com.google.android.youtube", "com.google.android.apps.wellbeing",
        "com.google.android.permissioncontroller", "com.android.bluetooth", "com.android.nfc",
        "com.android.providers.telephony", "com.android.providers.contacts",
        "com.android.providers.calendar", "com.android.providers.media.module",
        "com.android.settings", "com.android.keychain", "com.android.wifi",
        "com.android.networkstack"
    )

    fun scan(context: Context): JSONObject {
        android.util.Log.d("XDBG", "[MinkScanner] scan enter context=ctx") //xdbg
        val pm     = context.packageManager
        val ourPkg = context.packageName
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

        val accessibilityPkgs = getAccessibilityPackages(context)
        val notifListenerPkgs = getNotifListenerPackages(context)
        val deviceAdminPkgs   = getDeviceAdminPackages(context)
        val defaultSmsPkg     = try { Telephony.Sms.getDefaultSmsPackage(context) ?: "" } catch (_: Exception) { "" }

        val suspicious = JSONArray()
        var scannedCount = 0

        val allPackages: List<PackageInfo> = try {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[MinkScanner] catch (ignored)") //xdbg
            android.util.Log.d("XDBG", "[MinkScanner] return") //xdbg
            return buildResult(suspicious, 0, defaultSmsPkg, ourPkg)
        }

        for (pkgInfo in allPackages) {
            android.util.Log.d("XDBG", "[MinkScanner] loop") //xdbg
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == ourPkg) continue
            scannedCount++

            val appInfo  = pkgInfo.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label    = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg }
            val uid      = appInfo.uid

            fun isOpAllowed(op: String): Boolean {
                android.util.Log.d("XDBG", "[MinkScanner] isOpAllowed enter op=${op}") //xdbg
                appOps ?: return false
                android.util.Log.d("XDBG", "[MinkScanner] return") //xdbg
                return try {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        appOps.unsafeCheckOpNoThrow(op, uid, pkg)
                    else
                        @Suppress("DEPRECATION") appOps.checkOpNoThrow(op, uid, pkg)
                    mode == AppOpsManager.MODE_ALLOWED
                } catch (_: Exception) { false }
            }

            fun isGranted(perm: String) =
                pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED

            val declaredPerms = pkgInfo.requestedPermissions?.toSet() ?: emptySet()

            val isDefaultSms    = pkg == defaultSmsPkg
            val hasAccessibility = accessibilityPkgs.contains(pkg)
            val hasDeviceAdmin   = deviceAdminPkgs.contains(pkg)
            val hasNLS           = notifListenerPkgs.contains(pkg)
            val hasActiveHook    = isDefaultSms || hasAccessibility || hasDeviceAdmin || hasNLS

            var isHidden   = false
            var hasTvOnly  = false
            if (!isSystem) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                val hasRegularLauncher = try {
                    pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    ).any { it.activityInfo.packageName == pkg }
                } catch (_: Exception) { true }

                val hasTvLauncher = try {
                    pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
                    ).any { it.activityInfo.packageName == pkg }
                } catch (_: Exception) { false }

                if (!hasRegularLauncher) {
                    android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                    isHidden  = true
                    hasTvOnly = hasTvLauncher
                }
            }

            val smsReadGranted  = isGranted("android.permission.READ_SMS")
            val smsSendGranted  = isGranted("android.permission.SEND_SMS")
            val smsRecvGranted  = isGranted("android.permission.RECEIVE_SMS")
            val smsGrantedCount = listOf(smsReadGranted, smsSendGranted, smsRecvGranted).count { it }
            val hasSuspiciousSmsGrant = smsGrantedCount >= 1 && !isDefaultSms

            val overlayGranted    = isOpAllowed("android:system_alert_window")
            val usageStatsGranted = isOpAllowed("android:get_usage_stats")
            val installUnknown    = isOpAllowed("android:request_install_packages")

            val isDangerousOpCombo = installUnknown && usageStatsGranted
            val hasPrimarySignal = hasActiveHook || isHidden || hasSuspiciousSmsGrant || isDangerousOpCombo

            if (pkg in TRUSTED_SYSTEM_PKGS && !hasActiveHook) continue
            if (!hasPrimarySignal) continue

            var score   = 0
            val reasons = mutableListOf<String>()

            if (isDefaultSms)    { score += 10; reasons += "SMS-приложение по умолчанию (активно)" }
            if (hasAccessibility){ score += 8;  reasons += "Активная служба доступности" }
            if (hasDeviceAdmin)  { score += 8;  reasons += "Активный администратор устройства" }
            if (hasNLS)          { score += 6;  reasons += "Активный перехватчик уведомлений" }

            if (hasTvOnly) { score += 7; reasons += "Скрыт: использует TV-лаунчер вместо обычного" }
            else if (isHidden) { score += 6; reasons += "Скрыт: нет иконки на рабочем столе" }

            if (isSystem && pkg.startsWith("com.android.") && pkg !in TRUSTED_SYSTEM_PKGS) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 5; reasons += "Маскируется под системное приложение"
            }

            when {
                smsGrantedCount >= 2 && !isDefaultSms ->
                    { score += 9; reasons += "Выдано: чтение/перехват SMS (runtime, не дефолт)" }
                smsGrantedCount == 1 && !isDefaultSms ->
                    { score += 5; reasons += "Выдано: частичный доступ к SMS (runtime, не дефолт)" }
            }
            val smsDeclCount = listOf(
                "android.permission.READ_SMS" in declaredPerms,
                "android.permission.SEND_SMS" in declaredPerms,
                "android.permission.RECEIVE_SMS" in declaredPerms
            ).count { it }
            if (smsGrantedCount == 0 && smsDeclCount >= 2) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 2; reasons += "Объявлено: несколько SMS-разрешений (manifest)"
            }

            if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in declaredPerms && !hasNLS) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 3; reasons += "Объявлен перехватчик уведомлений (manifest, не активен)"
            }
            val hasAccSvcDecl = pkgInfo.services?.any {
                it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
            } == true
            if (hasAccSvcDecl && !hasAccessibility) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 3; reasons += "Объявлена служба доступности (manifest, не активна)"
            }

            if (isDangerousOpCombo) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 8; reasons += "Выдано: слежка за приложениями + установка APK (дроппер)"
            } else {
                if (usageStatsGranted) { score += 4; reasons += "Выдано: доступ к статистике приложений (Usage Access)" }
                if (installUnknown)    { score += 4; reasons += "Выдано: установка из неизвестных источников" }
            }

            if (overlayGranted && score > 0) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 3; reasons += "Выдано: overlay (окна поверх других приложений)"
            }

            if ("android.permission.INSTALL_PACKAGES" in declaredPerms) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 5; reasons += "Объявлено: INSTALL_PACKAGES (системный дроппер)"
            }

            if (isGranted("android.permission.READ_CALL_LOG") ||
                isGranted("android.permission.PROCESS_OUTGOING_CALLS")) {
                score += 3; reasons += "Выдано: перехват/чтение звонков (runtime)"
            }
            if (isGranted("android.permission.ACCESS_BACKGROUND_LOCATION")) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 3; reasons += "Выдано: геолокация в фоне (runtime)"
            }
            if (isGranted("android.permission.RECORD_AUDIO")) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 2; reasons += "Выдано: запись аудио/микрофон (runtime)"
            }
            if (isGranted("android.permission.READ_CONTACTS")) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 2; reasons += "Выдано: чтение контактов (runtime)"
            }
            if (isGranted("android.permission.READ_PHONE_STATE")) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                score += 1; reasons += "Выдано: состояние телефона/IMEI (runtime)"
            }

            val threshold  = if (isSystem) 40 else 8
            val alwaysFlag = hasActiveHook

            if (score >= threshold || alwaysFlag) {
                android.util.Log.d("XDBG", "[MinkScanner] if branch") //xdbg
                suspicious.put(JSONObject().apply {
                    put("pkg",     pkg)
                    put("name",    label)
                    put("score",   score)
                    put("system",  isSystem)
                    put("verdict", determineVerdict(reasons, score,
                        isDefaultSms, hasAccessibility, hasDeviceAdmin, hasNLS))
                    put("reasons", JSONArray(reasons))
                })
            }
        }

        android.util.Log.d("XDBG", "[MinkScanner] return") //xdbg
        return buildResult(suspicious, scannedCount, defaultSmsPkg, ourPkg)
    }

    private fun determineVerdict(
        reasons: List<String>, score: Int,
        isDefaultSms: Boolean, hasAccessibility: Boolean,
        hasAdmin: Boolean, hasNLS: Boolean
    ): String = when {
        hasAccessibility && hasNLS && score >= 15          -> "Многофункциональный шпион"
        hasAccessibility && isDefaultSms                   -> "Кейлоггер / перехватчик SMS"
        hasAccessibility && score >= 10                    -> "Кейлоггер / шпионский модуль"
        isDefaultSms && !hasAccessibility                  -> "Перехватчик SMS (по умолчанию)"
        hasAdmin && score >= 10                            -> "Вымогатель / блокировщик"
        reasons.any { "TV-лаунчер" in it }                -> "Скрытое приложение (TV-лаунчер)"
        reasons.any { "нет иконки" in it } && score >= 9  -> "Скрытый агент / шпион"
        reasons.any { "нет иконки" in it }                -> "Скрытое приложение"
        reasons.any { "дроппер" in it.lowercase() }       -> "Дроппер — устанавливает приложения"
        hasNLS && score >= 8                               -> "Перехватчик уведомлений"
        reasons.any { "SMS" in it }                       -> "Перехватчик / стилер SMS"
        reasons.any { "overlay" in it }                   -> "Банковский троян (overlay)"
        reasons.any { "Usage Access" in it }              -> "Шпион (следит за запущенными приложениями)"
        reasons.any { "геолокаци" in it }                 -> "Трекер геолокации / сталкерware"
        reasons.any { "звонков" in it }                   -> "Перехватчик звонков"
        reasons.any { "Маскируется" in it }               -> "Маскируется под системное приложение"
        hasAccessibility                                   -> "Подозрительный доступ к спец. возможностям"
        score >= 8                                        -> "Шпионское ПО — стилер данных"
        else                                              -> "Подозрительная активность"
    }

    private fun buildResult(
        suspicious: JSONArray, scannedCount: Int,
        defaultSmsPkg: String, ourPkg: String
    ) = JSONObject().apply {
        put("type", "mink_scan_result")
        put("suspicious", suspicious)
        put("summary", JSONObject().apply {
            put("scanned",             scannedCount)
            put("suspicious_count",    suspicious.length())
            put("default_sms_package", defaultSmsPkg)
            put("is_our_default_sms",  defaultSmsPkg == ourPkg)
            put("note", "v5.1: скрытие одно — не вредонос (порог 8). Активные хуки — всегда в отчёте.")
        })
    }

    private fun getAccessibilityPackages(context: Context): Set<String> = try {
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(":").mapNotNull { it.split("/").firstOrNull()?.trim() }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun getNotifListenerPackages(context: Context): Set<String> = try {
        (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "")
            .split(":").mapNotNull { it.split("/").firstOrNull()?.trim() }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun getDeviceAdminPackages(context: Context): Set<String> = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }
}
