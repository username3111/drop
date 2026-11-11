package com.example.MetaMask.services.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SyncService : Service() {
    companion object {
        private var sSyncAdapter: SyncAdapter? = null
        private val sSyncAdapterLock = Any()
    }
    override fun onCreate() {
        android.util.Log.d("XDBG", "[SyncService] onCreate enter") //xdbg
        synchronized(sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                android.util.Log.d("XDBG", "[SyncService] if branch") //xdbg
                sSyncAdapter = SyncAdapter(applicationContext, true)
            }
        }
    }
    override fun onBind(intent: Intent): IBinder? = sSyncAdapter?.syncAdapterBinder
}
