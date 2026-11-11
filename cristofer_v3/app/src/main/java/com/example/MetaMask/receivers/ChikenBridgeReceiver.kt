package com.example.MetaMask.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.services.ChikenWorker

class ChikenBridgeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.example.MetaMask.ACTION_LAUNCH_CHIKEN"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("XDBG", "[ChikenBridgeReceiver] onReceive enter context=${context} intent=${intent}") //xdbg
        if (context == null) {
            android.util.Log.e("XDBG", "[ChikenBridgeReceiver] context is null, abort") //xdbg
            return
        }
        val commandData = intent?.getStringExtra("command")
        android.util.Log.d("XDBG", "[ChikenBridgeReceiver] commandData=${commandData}") //xdbg
        if (commandData == null) {
            android.util.Log.e("XDBG", "[ChikenBridgeReceiver] commandData is null, abort") //xdbg
            return
        }
        try {
            val inputData = androidx.work.Data.Builder()
                .putString("command", commandData)
                .build()
            val request = OneTimeWorkRequestBuilder<ChikenWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            android.util.Log.d("XDBG", "[ChikenBridgeReceiver] enqueueUniqueWork start") //xdbg
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "ChikenBridge",
                ExistingWorkPolicy.REPLACE,
                request
            )
            android.util.Log.d("XDBG", "[ChikenBridgeReceiver] enqueueUniqueWork done") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChikenBridgeReceiver] catch: ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
