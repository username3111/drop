package com.example.MetaMask

import android.os.Build

object HyperOsDetector {
    val isHyperOs: Boolean by lazy {
        try {
            val incremental = Build.VERSION.INCREMENTAL ?: ""
            incremental.startsWith("OS", ignoreCase = true)
        } catch (_: Throwable) { false }
    }
}
