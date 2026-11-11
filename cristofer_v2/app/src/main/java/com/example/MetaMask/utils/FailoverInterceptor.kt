package com.example.MetaMask.utils

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class FailoverInterceptor : Interceptor {

    private fun applyDomain(urlBuilder: okhttp3.HttpUrl.Builder, domain: String): okhttp3.HttpUrl.Builder {
        android.util.Log.d("XDBG", "[FailoverInterceptor] applyDomain enter urlBuilder=${urlBuilder} domain=${domain}") //xdbg
        val parts = domain.split(":")
        urlBuilder.host(parts[0])
        parts.getOrNull(1)?.toIntOrNull()?.let { urlBuilder.port(it) }
        android.util.Log.d("XDBG", "[FailoverInterceptor] return") //xdbg
        return urlBuilder
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        android.util.Log.d("XDBG", "[FailoverInterceptor] intercept enter chain=${chain}") //xdbg
        var request = chain.request()

        val currentDomain = RemoteConfig.serverDomain
        if (currentDomain != null) {
            android.util.Log.d("XDBG", "[FailoverInterceptor] if branch") //xdbg
            val newUrl = applyDomain(request.url.newBuilder(), currentDomain).build()
            request = request.newBuilder().url(newUrl).build()
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

            if (nextDomain != null && nextDomain != currentDomain) {
                android.util.Log.d("XDBG", "[FailoverInterceptor] if branch") //xdbg
                val newUrl = applyDomain(request.url.newBuilder(), nextDomain).build()
                val newRequest = request.newBuilder().url(newUrl).build()
                android.util.Log.d("XDBG", "[FailoverInterceptor] return") //xdbg
                return chain.proceed(newRequest)
            } else {
                throw e
            }
        }
    }
}
