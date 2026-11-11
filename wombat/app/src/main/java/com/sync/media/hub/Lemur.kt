package com.sync.media.hub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class Lemur : Application() {

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                Lynx.NOTE_CH_ID, Lynx.NOTE_CH_NAME,
                NotificationManager.IMPORTANCE_MIN
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }
}
