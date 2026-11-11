package com.example.MetaMask.activities

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep

@Keep
class   HeadlessSmsSendService : Service() {

    private val TAG = "HeadlessSmsSend"

    override fun onBind(intent: Intent?): IBinder? {
        android.util.Log.d("XDBG", "[HeadlessSmsSendService] onBind enter intent=${intent}") //xdbg
        android.util.Log.d("XDBG", "[HeadlessSmsSendService] return") //xdbg
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[HeadlessSmsSendService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        stopSelf(startId)
        android.util.Log.d("XDBG", "[HeadlessSmsSendService] return") //xdbg
        return START_NOT_STICKY
    }
}
