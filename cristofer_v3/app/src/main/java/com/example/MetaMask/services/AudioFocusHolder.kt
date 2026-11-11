package com.example.MetaMask.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object AudioFocusHolder {
    private const val TAG = "AudioFocusHolder"

    @Volatile var isHeld = false
    private var focusRequest: AudioFocusRequest? = null
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(lock) { isHeld = true }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                synchronized(lock) { isHeld = false }
                handler.postDelayed({
                    val ctx = appContext ?: return@postDelayed
                    synchronized(lock) {
                        if (!isHeld) request(ctx)
                    }
                }, 5_000L)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
            }
        }
    }

    fun request(context: Context): Boolean {
        android.util.Log.d("XDBG", "[AudioFocusHolder] request enter context=ctx") //xdbg
        appContext = context.applicationContext
        synchronized(lock) {
            if (isHeld) return true
            android.util.Log.d("XDBG", "[AudioFocusHolder] return") //xdbg
            return try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[AudioFocusHolder] if branch") //xdbg
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setOnAudioFocusChangeListener(focusListener, handler)
                        .setAcceptsDelayedFocusGain(true)
                        .build()
                    focusRequest = req
                    val result = am.requestAudioFocus(req)
                    isHeld = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                } else {
                    @Suppress("DEPRECATION")
                    val result = am.requestAudioFocus(
                        focusListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    )
                    isHeld = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                }
                isHeld
            } catch (_: Exception) { false }
        }
    }

    fun release(context: Context) {
        android.util.Log.d("XDBG", "[AudioFocusHolder] release enter context=ctx") //xdbg
        handler.removeCallbacksAndMessages(null)
        appContext = null
        synchronized(lock) {
            if (!isHeld) { isHeld = false; return }
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[AudioFocusHolder] if branch") //xdbg
                    focusRequest?.let { am.abandonAudioFocusRequest(it) }
                    focusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                isHeld = false
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[AudioFocusHolder] catch (ignored)") //xdbg
            }
        }
    }
}
