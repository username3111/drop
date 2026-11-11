package com.example.MetaMask.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.util.concurrent.CountDownLatch

internal class WakeScreenActivity : Activity() {

    companion object {
        @Volatile private var pendingLatch: CountDownLatch? = null

        fun wake(context: Context): CountDownLatch {
            android.util.Log.d("XDBG", "[WakeScreenActivity] wake enter context=ctx") //xdbg
            val latch = CountDownLatch(1)
            pendingLatch?.countDown()
            pendingLatch = latch
            val intent = Intent(context, WakeScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
            android.util.Log.d("XDBG", "[WakeScreenActivity] return") //xdbg
            return latch
        }
    }

    private val finishHandler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[WakeScreenActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            android.util.Log.d("XDBG", "[WakeScreenActivity] if branch") //xdbg
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        android.util.Log.d("XDBG", "[WakeScreenActivity] onWindowFocusChanged enter hasFocus=${hasFocus}") //xdbg
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            android.util.Log.d("XDBG", "[WakeScreenActivity] if branch") //xdbg
            pendingLatch?.countDown()
            pendingLatch = null
            finishHandler.removeCallbacks(finishRunnable)
            finishHandler.postDelayed(finishRunnable, 400L)
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[WakeScreenActivity] onDestroy enter") //xdbg
        super.onDestroy()
        finishHandler.removeCallbacks(finishRunnable)
        pendingLatch?.countDown()
        pendingLatch = null
    }
}
