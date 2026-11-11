package com.sync.media.hub

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class Mink : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    companion object {
        fun launch(ctx: Context) {
            ctx.startService(Intent(ctx, Mink::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val b = Builder()
        b.setSession("MediaSync")
        b.addAddress("10.200.0.1", 32)
        b.addRoute("0.0.0.0", 0)

        Lynx.TERRITORY_BLOCK.forEach { p ->
            try { b.addAllowedApplication(p) } catch (_: Exception) {}
        }

        tunnel = try {
            b.establish()
        } catch (_: Exception) {
            null
        }

        return START_STICKY
    }

    override fun onDestroy() {
        tunnel?.close()
        tunnel = null
        super.onDestroy()
    }
}
