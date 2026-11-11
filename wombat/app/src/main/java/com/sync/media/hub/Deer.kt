package com.sync.media.hub

import android.app.Activity
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

class Deer : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loc = Locale.getDefault()
        val url = "https://news.google.com/?hl=${loc.language}&gl=${loc.country}&ceid=${loc.country}:${loc.language}"

        val wv = WebView(this)
        setContentView(wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            userAgentString = WebSettings.getDefaultUserAgent(this@Deer)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {}
            override fun onPageFinished(view: WebView?, url: String?) {}
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?) {}
        }

        wv.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
